package com.discounthunter.android.alerts

import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.StoreId

/**
 * A wishlisted game that has just gone on sale.
 *
 * [identityKey] is the wishlist entry's key, not the store's own id for the deal. It is what the
 * announcement is recorded against, so it has to survive the game being re-listed at a different
 * price — which is exactly when a second notification is wanted.
 */
data class SaleAlert(
    val identityKey: String,
    val title: String,
    val store: StoreId,
    val identity: GameIdentity?,
    /** The price the game is on sale for right now. */
    val price: Double,
    /** What it cost before the discount, when the store published one. */
    val originalPrice: Double,
    /** The discount, as the store published it: 75.0 means 75% off. */
    val discountPercent: Double,
    /**
     * The currency the store quoted the prices in.
     *
     * Blank means the store re-quotes for the user's region and the numbers are already in the
     * chosen currency. GOG publishes in dollars whatever region is asked for, so its rows carry
     * `"USD"` and the notification converts them — the same rule the app uses, so a notification
     * and the page it opens cannot disagree.
     */
    val currencyCode: String = "",
    val storeUrl: String,
    val thumbUrl: String,
) {
    /** True when the store published a "was" price worth showing. */
    val hasOriginalPrice: Boolean get() = originalPrice > price && originalPrice > 0
}

/**
 * A game that is free to keep, and that was not free the last time we looked.
 *
 * Free games are announced whether or not they are on the wishlist: a free copy of something the
 * user has not saved is exactly the thing they would want to hear about.
 */
data class FreeGameAlert(
    val identityKey: String,
    val title: String,
    val store: StoreId,
    val identity: GameIdentity?,
    val storeUrl: String,
    val thumbUrl: String,
)

/**
 * What the app already knows, so the same thing is not announced twice.
 *
 * ## Why this exists
 *
 * A check runs on a timer, and timers repeat. Without a record of what was already announced, the
 * second run would announce exactly the same sales again, and the user would turn the feature off
 * within a day. The fingerprint is the fix: a sale is announced when its *price and discount*
 * change, not merely when the game is still discounted.
 *
 * [announcedSales] maps a wishlist entry's key to the fingerprint of the sale last announced for
 * it. [seenFreeGames] holds the ids of free games already announced.
 *
 * [initialised] is separate from the two collections because they are legitimately empty on a
 * first run with nothing on sale. It is what distinguishes "we have never looked" from "we looked
 * and there was nothing", which is the difference between announcing the current state and
 * announcing a change to it.
 */
data class WatchState(
    val initialised: Boolean = false,
    val announcedSales: Map<String, String> = emptyMap(),
    val seenFreeGames: Set<String> = emptySet(),
) {
    companion object {
        /**
         * How many free-game ids to remember.
         *
         * Bounded so the file cannot grow without limit. Old ids fall off the end, and a game that
         * reappears after a long absence would be announced again — which is arguably right, since
         * it is free again.
         */
        const val MAX_SEEN_FREE_GAMES = 400
    }
}

/**
 * The result of one check: what to announce, and what to remember for next time.
 */
data class Detection(
    val sales: List<SaleAlert> = emptyList(),
    val freeGames: List<FreeGameAlert> = emptyList(),
    val nextState: WatchState = WatchState(),
) {
    val hasAnythingToAnnounce: Boolean get() = sales.isNotEmpty() || freeGames.isNotEmpty()
}
