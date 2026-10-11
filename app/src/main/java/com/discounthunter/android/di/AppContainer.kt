package com.discounthunter.android.di

import android.content.Context
import com.discounthunter.android.core.model.FxRates
import com.discounthunter.android.data.cache.PayloadCache
import com.discounthunter.android.data.cache.SnapshotStore
import com.discounthunter.android.data.epic.EpicService
import com.discounthunter.android.data.gog.GogService
import com.discounthunter.android.data.net.HttpClient
import com.discounthunter.android.data.repo.DealRepository
import com.discounthunter.android.data.repo.GameDetailRepository
import com.discounthunter.android.data.repo.SearchRepository
import com.discounthunter.android.data.settings.SettingsRepository
import com.discounthunter.android.data.source.EpicSourceAdapter
import com.discounthunter.android.data.source.GogSourceAdapter
import com.discounthunter.android.data.source.SteamSourceAdapter
import com.discounthunter.android.data.store.StoreLinks
import com.discounthunter.android.data.steam.SteamService
import com.discounthunter.android.data.wishlist.WishlistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The application's object graph.
 *
 * A hand-written container, deliberately. The graph is small and entirely singleton-scoped, so a
 * DI framework would add an annotation processor and a layer of indirection without removing any
 * real complexity. Every dependency is a constructor parameter, which also makes the repositories
 * trivially testable with fakes.
 *
 * Two scopes exist and the distinction matters:
 *  * [applicationScope] outlives every screen and owns the wishlist mirror and image sharing;
 *  * repositories themselves are stateless, so a cancelled screen cannot leave work running.
 */
class AppContainer(
    context: Context,
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val appContext: Context = context.applicationContext

    /** One HTTP client for store traffic and artwork, so sockets and timeouts stay bounded. */
    val httpClient: OkHttpClient = HttpClient.buildDefault()

    /**
     * Artwork gets its own client, derived from the store one so it reuses the same connection
     * pool, DNS cache and cookie jar, but not its dispatcher or timeouts.
     *
     * Both of those differences were needed, for reasons that only showed up on a device:
     *
     *  * **Timeouts.** Store APIs answer in a few hundred kilobytes and should fail fast. GOG, in
     *    contrast, publishes no small variant of a deal's cover: the catalogue hands out a
     *    1600x740 PNG of 0.5-1.7 MB per row. Under the store client's 20 s read timeout those
     *    transfers were cut off mid-flight and the row fell back to a broken-image tile, even
     *    though the URL was perfectly valid.
     *  * **Dispatcher.** OkHttp's default budget is four concurrent requests per host. Almost every
     *    GOG thumbnail comes from one host, so a single screen of deals filled that budget with
     *    multi-megabyte downloads and the *store* requests queued behind them — long enough that a
     *    search typed straight after opening the app came back empty. Artwork may not be allowed
     *    to starve the calls that produce the content around it.
     *
     * The connection pool is still shared, so this does not double the number of open sockets.
     */
    val imageClient: OkHttpClient = httpClient.newBuilder()
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = 16
                maxRequestsPerHost = 8
            },
        )
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(Duration.ZERO)
        .build()

    val network: HttpClient = HttpClient(httpClient)

    /**
     * Payloads live in `cacheDir`: Android may reclaim them under storage pressure without the user
     * losing anything they created.
     */
    private val payloadCache = PayloadCache(File(appContext.cacheDir, "http_cache"))

    /**
     * Snapshots live in `filesDir`: they are browsable history, not a disposable cache, so they
     * must survive the system clearing `cacheDir`.
     */
    private val snapshotStore = SnapshotStore(File(appContext.filesDir, "snapshots"))

    /** Public: the detail page reads recorded prices back out of the same store. */
    val snapshots: SnapshotStore get() = snapshotStore

    val storeLinks = StoreLinks(appContext)

    val settings: SettingsRepository = SettingsRepository(appContext)

    /**
     * Today's exchange rates, shared by every screen.
     *
     * One instance app-wide, and it is warmed at startup rather than on first use: the whole point
     * of converting is that the number moves when the setting changes, so a price that appears in
     * dollars and then silently becomes pounds a moment later is worse than a short wait on launch.
     * [FxRates.ensure] de-duplicates concurrent callers, so warming from here and from a ViewModel
     * at the same moment costs one request, not two.
     */
    val fxRates = FxRates()

    val wishlist: WishlistRepository = WishlistRepository(settings, applicationScope)

    // The services are wrapped in their source interfaces so the repositories depend on a small,
    // testable surface rather than on the full network stack.
    private val steam = SteamSourceAdapter(SteamService(network, payloadCache))
    private val gog = GogSourceAdapter(GogService(network, payloadCache))
    private val epic = EpicSourceAdapter(EpicService(network, payloadCache))

    val deals = DealRepository(steam, gog, epic, snapshotStore)
    val search = SearchRepository(steam, gog, epic)
    val gameDetail = GameDetailRepository(steam, gog, epic)

    /** Total bytes used by both caches, for the Settings screen. */
    suspend fun cacheSizeBytes(): Long = payloadCache.sizeBytes() + snapshotStore.sizeBytes()

    /** Drops every cached payload and artwork-free cache entry, keeping user settings intact. */
    suspend fun clearCaches() {
        payloadCache.clear()
        snapshotStore.clear()
    }

    /**
     * Removes everything this app has stored, on disk and in preferences.
     *
     * Caches and day snapshots are disposable, so they go through the two stores that own them.
     * Settings, recent searches and the wishlist all live in one DataStore, so `reset()` covers
     * them together. Doing it in this order means a crash between the steps can only ever leave
     * *less* data behind, never leave a dangling wishlist pointing at a deleted snapshot.
     */
    suspend fun deleteAllData() {
        payloadCache.clear()
        snapshotStore.clear()
        settings.reset()
    }

    fun start() {
        wishlist.start()
        // Warm the rates in the background. Deliberately not awaited: launch must not wait on the
        // network, and a failure here leaves prices labelled in their own currency, which is a
        // usable fallback rather than a broken screen.
        applicationScope.launch { fxRates.ensure() }
    }
}
