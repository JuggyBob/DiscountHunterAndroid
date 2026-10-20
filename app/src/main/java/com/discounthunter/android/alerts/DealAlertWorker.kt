package com.discounthunter.android.alerts

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.discounthunter.android.DiscountHunterApplication
import com.discounthunter.android.data.wishlist.representsSame
import com.discounthunter.android.diagnostics.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Checks for new sales and new free games, and announces them.
 *
 * ## Why a worker rather than a timer in the app
 *
 * A `Timer` or a coroutine on the app's own scope dies with the process. This worker does not: it
 * is run by the system, which starts the app if needed, runs it within the background execution
 * limits, and reschedules it after a reboot. That is the whole reason the notification arrives
 * when the app has never been opened since.
 *
 * The work is **idempotent**. It can be triggered by the periodic schedule, by the alarm
 * heartbeat, or by both at once, and the outcome does not depend on which: what gets announced is
 * decided by comparing against [WatchState], and the state is written as part of the same run.
 * Overlapping runs therefore announce nothing twice.
 */
class DealAlertWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Two triggers can fire within seconds of each other - the alarm and the periodic worker -
        // and the work is enqueued rather than made unique, so two runs really can overlap. A
        // process-wide lock means the second one waits for the first instead of fetching the same
        // feed a second time and racing it to write the state.
        //
        // The lock is taken with `withLock`, so a cancelled run releases it.
        mutex.withLock { runCheck() }
    }

    private suspend fun runCheck(): Result {
        val application = applicationContext as? DiscountHunterApplication
        val container = application?.container
        if (container == null) {
            AppLog.w("Alerts: no container available, skipping this check")
            return Result.retry()
        }

        try {
            val settings = container.settings.settings.first()
            if (!settings.saleAlertsEnabled) {
                AppLog.i("Alerts: monitoring is off, skipping this check")
                return Result.success()
            }

            // The wishlist lives in memory and is mirrored from DataStore. A worker may be the very
            // first thing to run in a fresh process, so it has to be loaded rather than assumed.
            container.wishlist.loadOnce()
            val wishlist = container.wishlist.entries.value
            if (wishlist.isEmpty()) {
                AppLog.i("Alerts: the wishlist is empty, nothing to watch for")
                return Result.success()
            }

            val feed = container.deals.refreshFeed(
                country = settings.steamCountry,
                // A larger feed than the app shows: a wishlisted game at a shallow discount would
                // otherwise sit below the cut-off and never be seen.
                limit = maxOf(settings.dealLimit, MINIMUM_DEAL_LIMIT),
                stores = settings.enabledStores,
            )
            if (feed.deals.isEmpty()) {
                AppLog.w("Alerts: the feed came back empty, so nothing could be checked")
                // Not a failure: the stores may simply be down. Retrying immediately would hammer
                // them, so this is a success with no work done.
                return Result.success()
            }

            val store = WatchStateStore(container.watchStateFile)
            val previous = store.load()
            val detection = SaleDetector.detect(
                wishlist = wishlist,
                deals = feed.deals,
                state = previous,
                matches = { entry, deal -> representsSame(entry, deal.wishlistKey, deal.titleKey) },
            )

            val notifier = AlertNotifier(applicationContext, container.fxRates)
            val posted = notifier.announce(
                detection = detection,
                currencySymbol = settings.currencySymbol,
                currencyCode = settings.currency.code,
            )

            // The state is written whether or not anything was announced. Skipping the write when
            // there is nothing to say would lose the record of what has been seen, and the next
            // check would announce everything again.
            store.save(detection.nextState)
            AppLog.i(
                "Alerts: checked ${feed.deals.size} deals, announced $posted, " +
                    "watching ${wishlist.size} saved game(s)",
            )
            return Result.success()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AppLog.e("Alerts: the check failed", error)
            // Retry, but not in a tight loop: WorkManager backs off on its own.
            return Result.retry()
        }
    }

    private companion object {
        /**
         * Serialises overlapping checks within the process.
         *
         * Process-wide rather than per-instance because WorkManager creates a new worker object
         * for every run, so an instance-level lock would not actually exclude anything.
         */
        val mutex = kotlinx.coroutines.sync.Mutex()
        /**
         * The floor on how many deals to fetch.
         *
         * A wishlisted game on sale at a shallow discount can easily fall below the app's own
         * cut-off of 40 per store, and then it would never be noticed at all. Fetching a little
         * more is cheap and closes most of that gap.
         */
        const val MINIMUM_DEAL_LIMIT = 60
    }
}


