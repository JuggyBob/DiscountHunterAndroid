package com.discounthunter.android.alerts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import com.discounthunter.android.MainActivity
import com.discounthunter.android.R
import com.discounthunter.android.core.model.FxRates
import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.PriceFormat
import com.discounthunter.android.diagnostics.AppLog
import com.discounthunter.android.ui.navigation.Destinations

/**
 * Posts the notifications.
 *
 * ## Two channels, deliberately
 *
 * A sale on something the user saved and a free game are different kinds of news, and a user who
 * wants one often does not want the other. Separate channels let them silence one and keep the
 * other, which is not possible when everything arrives on one channel.
 *
 * ## The content
 *
 * A notification that says "a deal is on" is useless. Every one of these carries the **name**, the
 * **new price** and the **discount**, because that is what the user has to act on — and it says
 * what the price is in, rather than leaving the reader to work it out.
 *
 * Converted prices are marked with a `~`, exactly as they are in the app. A converted price is an
 * estimate, and a notification that presents it as a quoted price is lying in the one place the
 * user is most likely to act on it.
 */
class AlertNotifier(
    private val context: Context,
    /** Rates for prices a store quoted in another currency. Null until they load. */
    private val fx: FxRates? = null,
) {

    private val manager = NotificationManagerCompat.from(context)

    /** Creates the channels. Safe to call repeatedly; the system ignores a duplicate. */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java) ?: return

        val sales = NotificationChannel(
            CHANNEL_SALES,
            context.getString(R.string.alert_channel_sales),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.alert_channel_sales_description)
            enableVibration(true)
            setShowBadge(true)
        }

        val free = NotificationChannel(
            CHANNEL_FREE,
            context.getString(R.string.alert_channel_free),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.alert_channel_free_description)
            enableVibration(true)
            setShowBadge(true)
        }

        system.createNotificationChannel(sales)
        system.createNotificationChannel(free)
    }

    /** True when the user has allowed notifications. */
    fun canPost(): Boolean = hasPermission() && manager.areNotificationsEnabled()

    /**
     * The runtime permission check.
     *
     * `areNotificationsEnabled()` alone is not enough on Android 13 and above: the permission has
     * to have been granted, and the system will refuse the post with a `SecurityException` if it
     * has not. Checking it explicitly is also what stops lint — correctly — from assuming the
     * call is safe.
     */
    private fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /**
     * Announces everything in [detection].
     *
     * Returns how many notifications were posted, which the log records so a user reporting
     * "I got no notification" can be checked against what the app thought it did.
     */
    fun announce(detection: Detection, currencySymbol: String, currencyCode: String): Int {
        ensureChannels()
        if (!canPost()) {
            AppLog.w("Alerts: notifications are disabled, so nothing was announced")
            return 0
        }

        var posted = 0
        for (sale in detection.sales) {
            post(saleNotification(sale, currencySymbol, currencyCode), sale.identityKey)
            posted++
        }
        for (free in detection.freeGames) {
            post(freeNotification(free, currencySymbol), free.identityKey)
            posted++
        }
        if (posted > 0) AppLog.i("Alerts: announced $posted notification(s)")
        return posted
    }

    private fun post(notification: Notification, tag: String) {
        // Checked here rather than only once at the top of [announce]. The permission can be
        // revoked between the two, and a background job that posts without checking crashes the
        // app rather than skipping a notification.
        if (!canPost()) {
            AppLog.w("Alerts: notifications are not allowed, so a notification was skipped")
            return
        }
        // The explicit catch is not defensive padding. The permission can be revoked between the
        // check above and the call below - the user can do it from system settings while the
        // background job is running - and the system answers that with a SecurityException. In a
        // background job that would be an unhandled exception and a crash report for what is
        // really a revoked permission.
        try {
            manager.notify(tag, NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            AppLog.w("Alerts: the notification permission was revoked: ${error.message}")
        } catch (error: Exception) {
            AppLog.e("Alerts: could not post a notification", error)
        }
    }

    private fun saleNotification(
        sale: SaleAlert,
        currencySymbol: String,
        currencyCode: String,
    ): Notification {
        val percent = sale.discountPercent.toInt().coerceIn(0, 100)
        val price = PriceFormat.priceWith(sale.price, currencyCode, sale.currencyCode, fx)
        val original = PriceFormat.priceWith(sale.originalPrice, currencyCode, sale.currencyCode, fx)

        val text = if (sale.hasOriginalPrice) {
            context.getString(
                R.string.alert_sale_body,
                price,
                original,
                sale.store.displayName,
            )
        } else {
            context.getString(R.string.alert_sale_body_no_original, price, sale.store.displayName)
        }

        return builder(CHANNEL_SALES, sale.title)
            .setContentTitle(context.getString(R.string.alert_sale_title, sale.title, percent))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent(sale.identity, sale.title))
            .build()
    }

    private fun freeNotification(free: FreeGameAlert, currencySymbol: String): Notification {
        val text = context.getString(R.string.alert_free_body, free.store.displayName)
        return builder(CHANNEL_FREE, free.title)
            .setContentTitle(context.getString(R.string.alert_free_title, free.title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent(free.identity, free.title))
            .build()
    }

    private fun builder(channel: String, title: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setGroup(GROUP_KEY)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    /**
     * Opens the game the notification is about.
     *
     * The game's identity travels in **intent extras**, not in a URI. A URI in a notification's
     * intent was measured arriving with `data == null` — the intent that reached the activity was
     * not the one that had been built — and the notification therefore opened the app without
     * going anywhere. Extras are copied verbatim, so they cannot be dropped like that.
     *
     * The **title** comes along too, and it is not decoration: without it the detail page has to
     * look the game up by name, and for GOG that means a catalogue search, which returns
     * unrelated products for anything but a distinctive title — so the page would show the wrong
     * offer, or none. The title is a hint, never an identity: the id still decides which game this
     * is.
     *
     * A game with no usable identity falls back to opening the app itself, which is better than a
     * notification that does nothing when tapped.
     */
    private fun openIntent(identity: GameIdentity?, title: String): PendingIntent {
        val intent = if (identity != null && identity.id.isNotBlank()) {
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(Destinations.EXTRA_STORE, identity.store.name)
                putExtra(Destinations.EXTRA_ID, identity.id)
                if (title.isNotBlank()) putExtra(Destinations.EXTRA_TITLE, title)
            }
        } else {
            Intent(context, MainActivity::class.java)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            (identity?.key ?: title).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_SALES = "discount_hunter_sales"
        const val CHANNEL_FREE = "discount_hunter_free"

        /** All the notifications share one id so the system collapses them into a summary. */
        private const val NOTIFICATION_ID = 4200
        private const val GROUP_KEY = "discount_hunter_alerts"
    }
}


