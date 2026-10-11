package com.discounthunter.android.data.store

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.core.model.StoreOffer

/** How a store link was (or was not) handed off. */
sealed interface LinkResult {
    data class OpenedIn(val viaStoreApp: Boolean, val storeName: String) : LinkResult
    data class Failed(val message: String) : LinkResult
}

/**
 * Opens store pages and trailers outside the app.
 *
 * ## What changed from the desktop build
 *
 * The desktop app detected desktop store clients through the Windows registry and launched
 * `steam://` / `com.epicgames.launcher://` protocols. The Android equivalents are the store apps'
 * registered deep-link schemes, probed once and cached.
 *
 * The honest limitation carries over unchanged: **no store offers a public API for claiming a
 * game.** For a free title the app therefore does the most useful thing available — it opens the
 * game's page inside the store app the user is already signed in to, where claiming is one tap.
 */
class StoreLinks(
    private val context: Context,
) {

    private val schemeCache = mutableMapOf<StoreId, String?>()

    /** Opens a store page, preferring the store's own app when one is installed. */
    fun openOffer(offer: StoreOffer, preferStoreApp: Boolean): LinkResult =
        openUrl(offer.url, store = offer.store, preferStoreApp = preferStoreApp)

    /** Opens an arbitrary web page (a trailer, a screenshot, a store link). */
    fun openUrl(url: String, store: StoreId? = null, preferStoreApp: Boolean = false): LinkResult {
        // Probing needs a Context, so it happens here and the rule itself lives in
        // `handOffAttempts`, which is pure and therefore testable.
        val storeAppInstalled = store != null && probeScheme(store) != null

        val attempts = handOffAttempts(url, store, preferStoreApp, storeAppInstalled)
        for (attempt in attempts) {
            if (launch(attempt.url.toUri(), requireNonBrowser = attempt.viaStoreApp)) {
                return LinkResult.OpenedIn(viaStoreApp = attempt.viaStoreApp, storeName = attempt.storeName)
            }
            // The store app refused — disabled, not installed after all, or simply no handler. The
            // page is still worth opening, so fall through to the browser rather than giving up.
        }

        return LinkResult.Failed(
            if (attempts.isEmpty()) {
                "No link is available for this item."
            } else {
                "Couldn't open that — this device has no app that can handle it."
            },
        )
    }

    /** True when a store app that can handle its deep links appears to be installed. */
    fun isStoreAppAvailable(store: StoreId): Boolean = probeScheme(store) != null

    /**
     * Probes whether anything on the device handles [store]'s scheme.
     *
     * A single cheap `resolveActivity` per store, cached for the process; the result is also what
     * the Settings screen shows as "app detected".
     */
    private fun probeScheme(store: StoreId): String? = synchronized(schemeCache) {
        if (schemeCache.containsKey(store)) return schemeCache[store]

        val scheme = when (store) {
            StoreId.STEAM -> "steam"
            StoreId.EPIC -> "com.epicgames.launcher"
            StoreId.GOG -> null
        }
        val resolved = scheme?.takeIf { candidate ->
            val intent = Intent(Intent.ACTION_VIEW, "$candidate://".toUri())
            runCatching {
                context.packageManager.resolveActivity(intent, 0) != null
            }.getOrDefault(false)
        }
        schemeCache[store] = resolved
        resolved
    }

    /**
     * Starts [uri] in whatever app handles it, reporting whether anything did.
     *
     * [requireNonBrowser] exists for one narrow case: a store deep link such as `steam://…`, which a
     * browser will happily try (and fail) to load. Applied to an ordinary `https://` URL the flag
     * does the *opposite* of what is wanted — it excludes browsers from the resolution, so the
     * hand-off fails whenever the browser is the only candidate. That is the normal case, and it is
     * why "Get deal" used to refuse to do anything on a phone with no store app installed.
     */
    private fun launch(uri: Uri, requireNonBrowser: Boolean): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (requireNonBrowser) addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
        }
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    companion object {
        /** Where a hand-off should go, and how the UI should describe it. */
        data class HandOffTarget(val url: String, val viaStoreApp: Boolean, val storeName: String)

        /**
         * The places to try, in order, for a hand-off — the store's own app first when it applies,
         * then always the public web page.
         *
         * Returning a *list* rather than a single choice is what makes the fallback work: a store
         * app that is installed but refuses the link (disabled, crashed, no handler) no longer ends
         * the attempt, it just hands over to the browser.
         *
         * Pure, so the whole decision table is unit-testable without an Android context. One rule is
         * worth calling out: a hand-off with **no store** — a trailer's own page, for instance —
         * still has to open in the browser. It simply cannot be routed into a store app, because
         * there is no store to route it to. Treating the missing store as a programming error, which
         * a `requireNotNull` here once did, crashed the app the moment a user tapped a trailer's
         * hand-off button.
         */
        fun handOffAttempts(
            url: String,
            store: StoreId?,
            preferStoreApp: Boolean,
            storeAppInstalled: Boolean,
        ): List<HandOffTarget> {
            if (url.isBlank()) return emptyList()

            val attempts = mutableListOf<HandOffTarget>()

            if (preferStoreApp && store != null && storeAppInstalled) {
                deepLinkFor(store, url)?.let {
                    attempts += HandOffTarget(it, viaStoreApp = true, storeName = store.displayName)
                }
            }

            val web = if (url.startsWith("http", ignoreCase = true)) {
                url
            } else {
                webUrlFor(store, url)
            }
            if (web.isNotBlank()) {
                attempts += HandOffTarget(
                    url = web,
                    viaStoreApp = false,
                    storeName = store?.displayName ?: "browser",
                )
            }

            return attempts
        }

        /**
         * The store app's deep link for [url], or null when the store has none or the URL belongs to
         * a different store.
         *
         * Pure: no Context, no probing. Whether the app is *installed* is [openUrl]'s concern.
         */
        fun deepLinkFor(store: StoreId, url: String): String? {
            val identity = GameIdentity.fromUrl(url) ?: return null
            return when (store) {
                StoreId.STEAM -> if (identity.store == StoreId.STEAM) {
                    "steam://url/StoreAppPage/${identity.id}"
                } else {
                    null
                }
                // Epic's launcher scheme, e.g. com.epicgames.launcher://store/p/<slug>
                StoreId.EPIC -> if (identity.store == StoreId.EPIC) {
                    "com.epicgames.launcher://store/p/${identity.id}"
                } else {
                    null
                }
                // GOG Galaxy has no stable store deep link, so the browser is always used.
                StoreId.GOG -> null
            }
        }

        /** The public web page for a store-native identifier. */
        fun webUrlFor(store: StoreId?, slugOrId: String): String = when (store) {
            StoreId.STEAM -> "https://store.steampowered.com/app/$slugOrId/"
            StoreId.GOG -> "https://www.gog.com/en/game/$slugOrId"
            StoreId.EPIC -> "https://store.epicgames.com/en-US/p/$slugOrId"
            null -> GameIdentity.fromUrl(slugOrId)?.let { webUrlFor(it.store, it.id) }.orEmpty()
        }
    }
}
