package com.discounthunter.android.data.repo

import com.discounthunter.android.core.model.Deal
import com.discounthunter.android.core.model.DealFeed
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.data.cache.SnapshotStore
import com.discounthunter.android.data.net.Outcome
import com.discounthunter.android.data.net.valueOrNull
import com.discounthunter.android.data.source.EpicSource
import com.discounthunter.android.data.source.GogSource
import com.discounthunter.android.data.source.SteamSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import java.time.LocalDate
import java.time.ZoneId

/**
 * The front page's data source: one merged feed across Steam, Epic and GOG, plus the giveaway
 * schedule that drives the calendar.
 *
 * Every store is fetched independently and concurrently. A store that fails contributes a warning
 * instead of failing the refresh, so one outage degrades the feed rather than emptying it.
 */
class DealRepository(
    private val steam: SteamSource,
    private val gog: GogSource,
    private val epic: EpicSource,
    private val snapshots: SnapshotStore,
) {

    /**
     * Refreshes the merged feed.
     *
     * @param country Steam's region, taken from the user's currency choice.
     * @param stores the stores the user has enabled; disabled stores are not queried at all.
     */
    suspend fun refreshFeed(
        country: String,
        limit: Int,
        stores: Set<StoreId>,
    ): DealFeed = supervisorScope {
        val warnings = mutableListOf<String>()

        val epicDeferred = if (StoreId.EPIC in stores) {
            async { epic.fetchFreeGames() }
        } else {
            null
        }
        val steamDeferred = if (StoreId.STEAM in stores) {
            async { steam.fetchDeals(limit, country) }
        } else {
            null
        }
        val gogDeferred = if (StoreId.GOG in stores) {
            async { gog.fetchDeals(limit) }
        } else {
            null
        }

        val giveaways = epicDeferred?.awaitSafely(warnings, "Epic giveaways")
            .reportFailure(warnings, "Epic giveaways")
            ?.valueOrNull()
            .orEmpty()
        val steamDeals = steamDeferred?.awaitSafely(warnings, "Steam deals")
            .reportFailure(warnings, "Steam deals")
            ?.valueOrNull()
            .orEmpty()
        val gogDeals = gogDeferred?.awaitSafely(warnings, "GOG deals")
            .reportFailure(warnings, "GOG deals")
            ?.valueOrNull()
            ?.map { gog.toDeal(it) }
            .orEmpty()

        // Free Epic giveaways lead the list, exactly as on the desktop build.
        val merged = ArrayList<Deal>(giveaways.size + steamDeals.size + gogDeals.size)
        merged += giveaways.mapNotNull { it.toDeal() }
        merged += steamDeals
        merged += gogDeals

        if (merged.isEmpty() && warnings.isEmpty()) {
            warnings += "No deals could be loaded. Check your connection and pull to refresh."
        }

        DealFeed(dedupe(merged), warnings)
    }

    /** Giveaways touching [date] — used by future calendar days. */
    suspend fun giveawaysForDate(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Deal> =
        dedupe(
            epic.fetchFreeGames().valueOrNull().orEmpty()
                .filter { giveaway ->
                    val start = giveaway.startMillis.toLocalDate(zone)
                    val end = giveaway.endMillis.toLocalDate(zone)
                    !date.isBefore(start) && !date.isAfter(end)
                }
                .mapNotNull { it.toDeal() },
        )

    /**
     * The days within [from]..[to] that a giveaway covers, for the calendar markers.
     *
     * Returns the set of days rather than per-day lists so the caller renders the strip without
     * any further bookkeeping.
     */
    suspend fun giveawayDays(
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Set<LocalDate> {
        val days = mutableSetOf<LocalDate>()
        for (giveaway in epic.fetchFreeGames().valueOrNull().orEmpty()) {
            val start = giveaway.startMillis.toLocalDate(zone)
            val end = giveaway.endMillis.toLocalDate(zone)

            // Clip to the requested window. Clipping the *start* alone would run the marker past the
            // giveaway's real end, marking days that have nothing free on them.
            var day = if (start.isBefore(from)) from else start
            val lastDay = if (end.isAfter(to)) to else end

            // A giveaway never spans more than a couple of weeks; the guard keeps this bounded even
            // if a feed entry carries a wildly wrong date.
            var guard = 0
            while (!day.isAfter(lastDay) && guard++ < MAX_MARKED_DAYS_PER_GIVEAWAY) {
                days += day
                day = day.plusDays(1)
            }
        }
        return days
    }

    suspend fun loadSnapshot(date: LocalDate, country: String): List<Deal>? =
        snapshots.load(date, country)

    suspend fun saveSnapshot(date: LocalDate, deals: List<Deal>, country: String) {
        snapshots.save(date, deals, country)
        snapshots.prune()
    }

    /**
     * Removes duplicates without merging distinct products.
     *
     * The desktop build keyed on `(store, lowercased title, rounded price)`, which collapsed two
     * different editions that happen to share a title and price. Here a duplicate is the same
     * store and the same store-native id — the only reliable notion of "the same deal".
     */
    private fun dedupe(deals: List<Deal>): List<Deal> {
        val seen = HashSet<String>(deals.size)
        val unique = deals.filter { seen.add(it.id) }

        // Free games first, then by how deep the discount is.
        return unique.sortedWith(
            compareBy<Deal> { if (it.isFree) 0 else 1 }
                .thenByDescending { it.savingsPercent }
                .thenBy { it.salePrice }
                .thenBy { it.id },
        )
    }

    /**
     * Awaits one store's fetch, turning an unexpected throw into a warning.
     *
     * Cancellation is rethrown: a superseded refresh must unwind rather than be swallowed.
     */
    private suspend fun <T> Deferred<Outcome<T>>.awaitSafely(
        warnings: MutableList<String>,
        label: String,
    ): Outcome<T> = try {
        await()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        val message = error.message ?: "unknown error"
        warnings += "$label unavailable: $message"
        Outcome.Failure(Outcome.Reason.NETWORK, message)
    }

    /**
     * Surfaces a store that answered with a failure.
     *
     * A store returning `Failure` rather than throwing still means the user is seeing an incomplete
     * feed, and the desktop build swallowed that silently — the page just looked sparser with no
     * explanation. Recording it here is what lets the UI say "Steam is unreachable".
     */
    private fun <T> Outcome<T>?.reportFailure(
        warnings: MutableList<String>,
        label: String,
    ): Outcome<T>? {
        if (this is Outcome.Failure) {
            warnings += "$label unavailable: $message"
        }
        return this
    }

    private fun Long.toLocalDate(zone: ZoneId): LocalDate =
        java.time.Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

    private companion object {
        /** Upper bound on days one giveaway may mark, so a bad feed entry cannot run away. */
        const val MAX_MARKED_DAYS_PER_GIVEAWAY = 60
    }
}
