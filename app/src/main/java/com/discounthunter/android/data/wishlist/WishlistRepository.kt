package com.discounthunter.android.data.wishlist

import com.discounthunter.android.core.model.Deal
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.core.model.WishlistEntry
import com.discounthunter.android.data.settings.SettingsRepository
import com.discounthunter.android.data.settings.toEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The user's saved games, mirrored from settings into memory for fast, allocation-free matching.
 *
 * ## Matching rules
 *
 * A card is "on the wishlist" when its exact store id key matches. A normalised **title** key is
 * also stored so a game saved on Steam lights up on the GOG row — but only titles at least
 * [DISTINCTIVE_LENGTH] characters long are indexed, and those must match *exactly*.
 *
 * That strictness is deliberate. The desktop build compared title keys by prefix, so saving
 * *Portal 2* also lit up *Portal* and vice versa — the same class of "wrong game" defect as the
 * screenshot bug, in the wishlist. Short titles are also never title-matched, so *Hades* cannot
 * light up *Hades II*.
 */
class WishlistRepository(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<WishlistEntry>>(emptyList())
    private val exactKeys = mutableSetOf<String>()
    private val indexedTitles = mutableSetOf<String>()

    /** Saved games, newest first. */
    val entries: StateFlow<List<WishlistEntry>> = _entries.asStateFlow()

    val count: Int get() = _entries.value.size

    /**
     * Mirrors persisted changes into memory.
     *
     * Mutations update the local list immediately (so the star fills in on the same frame) and then
     * persist; the collector that observes settings simply converges on the same list.
     */
    fun start() {
        scope.launch {
            settings.settings
                .map { it.wishlist.map { record -> record.toEntry() } }
                .distinctUntilChanged()
                .collect { rebuild(it) }
        }
    }

    /** Seeds the in-memory list without keeping a collector alive (used by tests). */
    suspend fun loadOnce() {
        val snapshot = settings.settings.map { it.wishlist }.distinctUntilChanged()
        var first: List<WishlistEntry>? = null
        snapshot.collect { value ->
            first = value.map { it.toEntry() }
        }
        rebuild(first.orEmpty())
    }

    /** True when the game described by [exactKey] / [titleKey] is saved. */
    fun matches(exactKey: String, titleKey: String): Boolean = synchronized(this) {
        if (exactKey.isNotBlank() && exactKey in exactKeys) return true
        if (titleKey.isBlank() || titleKey.length < DISTINCTIVE_LENGTH) return false
        titleKey in indexedTitles
    }

    fun matches(entry: WishlistEntry): Boolean = matches(entry.key, entry.titleKey)

    fun matches(deal: Deal): Boolean = matches(deal.wishlistKey, deal.titleKey)

    /**
     * Flips the saved state for [entry]; returns true when the game is now saved.
     *
     * Removal uses the same rule as [matches], otherwise a row that matched through the title
     * fallback could never be un-starred. The selection of *which* saved entry to drop is by
     * [representsSame] — not by [matches], because `matches(it.key, it.titleKey)` only asks whether
     * the entry's own key is in the index, which is true for every saved entry and therefore always
     * selects the first one to delete.
     */
    suspend fun toggle(entry: WishlistEntry): Boolean = mutex.withLock {
        val (updated, nowSaved) = toggleDecision(_entries.value, entry, clock())
        rebuild(updated)
        settings.setWishlist(updated)
        nowSaved
    }

    suspend fun remove(exactKey: String, titleKey: String): Boolean = mutex.withLock {
        val (updated, removed) = removeDecision(_entries.value, exactKey, titleKey)
        if (!removed) return@withLock false
        rebuild(updated)
        settings.setWishlist(updated)
        true
    }

    /** Clears the wishlist; returns how many entries were removed. */
    suspend fun clear(): Int = mutex.withLock {
        val removed = _entries.value.size
        if (removed == 0) return@withLock 0
        rebuild(emptyList())
        settings.setWishlist(emptyList())
        removed
    }

    /** True when at least one saved game currently has a live discount. */
    fun anyOnSale(deals: List<Deal>): Boolean = deals.any { matches(it.wishlistKey, it.titleKey) }

    /**
     * Picks the deal this row should advertise, from the deals already known to be the same game.
     *
     * Delegates to [bestLiveDealFor]; see there for the ranking rule.
     */
    fun bestLiveDeal(entry: WishlistEntry, deals: List<Deal>): Deal? =
        bestLiveDealFor(entry.store, deals.filter { matches(it.wishlistKey, it.titleKey) })

    private fun rebuild(updated: List<WishlistEntry>) {
        val usable = updated
            .filter { it.key.isNotBlank() || it.titleKey.isNotBlank() }
            .sortedByDescending { it.addedAt }
        // The index and the published list are updated together, under one lock, so `matches` can
        // never see a half-rebuilt pair. They used to be two separate critical sections: a
        // concurrent DataStore emission could clear the sets, refill them with newer entries and
        // then have an older list published over the top, leaving the star disagreeing with the
        // stored wishlist until the next emission.
        synchronized(this) {
            exactKeys.clear()
            indexedTitles.clear()
            for (entry in usable) {
                if (entry.key.isNotBlank()) exactKeys += entry.key
                if (entry.titleKey.length >= DISTINCTIVE_LENGTH) indexedTitles += entry.titleKey
            }
            _entries.value = usable
        }
    }

    companion object {
        /**
         * Normalised titles shorter than this are never title-matched. "Hades" is far too short to
         * identify one product across three storefronts that name things differently.
         */
        const val DISTINCTIVE_LENGTH = 12
    }
}

/**
 * True when the saved [entry] is the same game as [exactKey] / [titleKey].
 *
 * Used to find the one saved entry for a game rather than merely ask whether that game is saved.
 * `matches` cannot serve that purpose: an entry's own key is already present in the index, so
 * `matches(it.key, it.titleKey)` is true for *every* saved entry and always selects the first.
 */
internal fun representsSame(entry: WishlistEntry, exactKey: String, titleKey: String): Boolean {
    if (exactKey.isNotBlank() && entry.key == exactKey) return true
    if (titleKey.isBlank() || entry.titleKey.isBlank()) return false
    if (titleKey.length < WishlistRepository.DISTINCTIVE_LENGTH || entry.titleKey.length < WishlistRepository.DISTINCTIVE_LENGTH) return false
    return entry.titleKey == titleKey
}

/**
 * Pure decision for [WishlistRepository.toggle]: the new entry list plus whether the game is now
 * saved. Kept pure so "adding a second game drops the first" can be unit-tested without a
 * DataStore-backed [SettingsRepository].
 */
internal fun toggleDecision(
    current: List<WishlistEntry>,
    entry: WishlistEntry,
    now: Long,
): Pair<List<WishlistEntry>, Boolean> {
    val existing = current.firstOrNull { representsSame(it, entry.key, entry.titleKey) }
    return if (existing != null) {
        current.filterNot { it === existing } to false
    } else {
        listOf(entry.copy(addedAt = now)) + current to true
    }
}

/**
 * Pure decision for [WishlistRepository.remove]: the new entry list plus whether one was dropped.
 */
internal fun removeDecision(
    current: List<WishlistEntry>,
    exactKey: String,
    titleKey: String,
): Pair<List<WishlistEntry>, Boolean> {
    val index = current.indexOfFirst { representsSame(it, exactKey, titleKey) }
    return if (index < 0) {
        current to false
    } else {
        current.filterIndexed { position, _ -> position != index } to true
    }
}

/**
 * Picks which of [candidates] — deals already known to be the same game — a wishlist row should
 * advertise, when the game was saved from [savedStore].
 *
 * The saved store wins outright, even when another store currently has it cheaper or free. That is
 * what keeps the row self-consistent: the badge shows that store, and the price is that store's
 * price, so the wishlist never disagrees with the Deals tab about what a game costs. Only when the
 * saved store has nothing live do we fall back to the best offer elsewhere — which is the genuinely
 * useful discovery case (saved on GOG, free on Epic).
 *
 * Pure, so the "every row says FREE" regression is testable without a DataStore-backed
 * [SettingsRepository].
 */
internal fun bestLiveDealFor(savedStore: StoreId, candidates: List<Deal>): Deal? {
    if (candidates.isEmpty()) return null
    val sameStore = candidates.filter { it.store == savedStore }
    return pickCheapest(sameStore) ?: pickCheapest(candidates)
}

/** Free first, then deepest discount, then lowest price; the deal id breaks remaining ties. */
private fun pickCheapest(candidates: List<Deal>): Deal? = candidates.minWithOrNull(
    compareBy({ if (it.isFree) 0 else 1 }, { -it.savingsPercent }, { it.salePrice }, { it.id }),
)
