package com.discounthunter.android.ui.navigation

import android.net.Uri
import com.discounthunter.android.core.model.GameIdentity

/**
 * The navigation graph.
 *
 * The detail route carries the game's **stable identity** (`steam:1245620`), not its title. Two
 * consequences that matter:
 *
 *  * a rotation or a process death restores the same game, because the id round-trips;
 *  * nothing about what the screen displays depends on a user-visible string, so a stale or
 *    mismatched title can never be what the page is keyed on.
 *
 * The title rides along purely as an instant placeholder for the first frame.
 */
object Destinations {
    const val DEALS = "deals"
    const val SEARCH = "search"
    const val WISHLIST = "wishlist"
    const val SETTINGS = "settings"

    const val DETAIL_ROUTE = "detail/{store}/{id}?title={title}&thumb={thumb}&origin={origin}"

    /** Builds a detail route for [identity]. */
    fun detailRoute(
        identity: GameIdentity,
        title: String,
        thumbUrl: String = "",
        originUrl: String = "",
    ): String = "detail/${identity.store.name.lowercase()}/${Uri.encode(identity.id)}" +
        "?title=${Uri.encode(title)}" +
        "&thumb=${Uri.encode(thumbUrl)}" +
        "&origin=${Uri.encode(originUrl)}"

    fun isDetail(route: String?): Boolean = route?.startsWith("detail/") == true

    /**
     * Extras that carry a game into the app.
     *
     * Used by the notification tap. The identity travels in **extras** rather than in a URI
     * because a URI in a notification's intent was measured arriving with `data == null` — the
     * intent the activity received was not the one that had been built. Extras are copied
     * verbatim and cannot be dropped that way.
     */
    const val EXTRA_STORE = "com.discounthunter.android.extra.STORE"
    const val EXTRA_ID = "com.discounthunter.android.extra.ID"
    const val EXTRA_TITLE = "com.discounthunter.android.extra.TITLE"

    /** The four bottom-bar destinations, in order. */
    val topLevel: List<String> = listOf(DEALS, SEARCH, WISHLIST, SETTINGS)
}
