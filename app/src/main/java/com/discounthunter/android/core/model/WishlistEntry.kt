package com.discounthunter.android.core.model

import com.discounthunter.android.core.text.TitleKey
/**
 * One saved game.
 *
 * Two keys are stored, mirroring the desktop app:
 *  * [key] — exact identity when a store id was available (`s1:a1245620`).
 *  * [titleKey] — normalised title, so a game saved on Steam is also recognised on GOG.
 *
 * Matching is deliberately strict (see `WishlistRepository`): a loose title match would light up
 * unrelated cards.
 */
data class WishlistEntry(
    val key: String,
    val titleKey: String,
    val title: String,
    val store: StoreId,
    val appId: Int? = null,
    val gogSlug: String = "",
    val thumbUrl: String = "",
    val url: String = "",
    /** Epoch milliseconds. */
    val addedAt: Long,
) {
    companion object {
        fun fromDeal(deal: Deal, nowMillis: Long): WishlistEntry = WishlistEntry(
            key = deal.wishlistKey,
            titleKey = deal.titleKey,
            title = deal.title.ifBlank { "Unknown title" }.trim(),
            store = deal.store,
            appId = (deal.identity?.takeIf { it.store == StoreId.STEAM }?.id)?.toIntOrNull(),
            gogSlug = deal.identity?.takeIf { it.store == StoreId.GOG }?.id.orEmpty(),
            thumbUrl = deal.thumbUrl,
            url = deal.storeUrl,
            addedAt = nowMillis,
        )

        fun fromSearch(result: SearchResult, nowMillis: Long): WishlistEntry = WishlistEntry(
            key = result.wishlistKey,
            titleKey = result.titleKey,
            title = result.title.ifBlank { "Unknown title" }.trim(),
            store = result.primaryStore,
            appId = result.steamAppId,
            gogSlug = result.gogSlug.orEmpty(),
            thumbUrl = result.thumbUrl,
            url = result.webUrl,
            addedAt = nowMillis,
        )

        fun fromDetail(detail: GameDetail, nowMillis: Long): WishlistEntry = WishlistEntry(
            key = detail.wishlistKey,
            titleKey = detail.titleKey,
            title = detail.title.ifBlank { "Game details" }.trim(),
            store = detail.primaryStore,
            appId = detail.steamAppId,
            gogSlug = detail.gogSlug,
            thumbUrl = detail.heroUrl,
            url = detail.webUrl,
            addedAt = nowMillis,
        )

        /** Builds the entry for a bare reference, used before the detail has loaded. */
        fun fromReference(reference: GameReference, nowMillis: Long): WishlistEntry {
            val identity = reference.identity
            return WishlistEntry(
                key = TitleKey.exact(identity.store, null, identity.id, reference.title),
                titleKey = TitleKey.normalize(reference.title),
                title = reference.title.ifBlank { "Unknown title" }.trim(),
                store = identity.store,
                appId = identity.id.toIntOrNull()?.takeIf { identity.store == StoreId.STEAM },
                gogSlug = if (identity.store == StoreId.GOG) identity.id else "",
                thumbUrl = reference.thumbUrl,
                url = reference.fallbackUrl,
                addedAt = nowMillis,
            )
        }
    }
}
