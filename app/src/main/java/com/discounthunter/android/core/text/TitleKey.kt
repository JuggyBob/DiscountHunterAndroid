package com.discounthunter.android.core.text

import com.discounthunter.android.core.model.StoreId

/**
 * Wishlist identity.
 *
 * A game can appear in several places (a Steam deal row, a GOG deal row, a merged search hit, the
 * detail page), and each knows a different amount about it. Matching therefore uses **two** keys:
 *
 *  * an *exact* key when the store publishes a durable id (`s1:a1245620`, `s7:goldenearena`), and
 *  * a store-independent *normalised title* (`teldenring`).
 *
 * A title saved on Steam is therefore also recognised on GOG, but only when the titles genuinely
 * describe the same product — see [WishlistRepository] for the (deliberately strict) rules.
 */
object TitleKey {

    /** Store-independent identity derived from the title. */
    fun normalize(title: String?): String = TextTools.coreTitle(title)

    /**
     * Exact identity when the store publishes a durable id, otherwise the title key.
     *
     * [appId] and [slug] are alternatives, and a Steam numeric id is recognised as an app id even
     * when it arrives in the slug slot — which is how the generic [GameIdentity] hands it over. Keeping
     * the two id kinds distinct means a Steam entry can never collide with a GOG entry whose slug
     * happens to be the same string.
     */
    fun exact(store: StoreId, appId: Int?, slug: String?, title: String?): String {
        if (appId != null && appId > 0) {
            return "s${store.id}:a$appId"
        }
        val clean = cleanSlug(slug)
        if (clean.isNotEmpty()) {
            val asAppId = clean.toIntOrNull()
            return if (store == StoreId.STEAM && asAppId != null && asAppId > 0) {
                "s${store.id}:a$asAppId"
            } else {
                "s${store.id}:g${clean.lowercase()}"
            }
        }
        val normalized = normalize(title)
        return if (normalized.isEmpty()) "" else "t$normalized"
    }

    /** GOG and Epic URLs both embed a slug as their last meaningful path segment. */
    fun slugFromUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val marker = url.indexOf("/game/")
        if (marker >= 0) {
            val tail = url.substring(marker + "/game/".length)
                .substringBefore('?')
                .substringBefore('#')
                .trimEnd('/')
            if (tail.isNotEmpty()) return tail
        }
        val trimmed = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val last = trimmed.substringAfterLast('/')
        return if (last.contains('.') || last.isEmpty()) "" else last
    }

    /** `"en/themediaresort"` -> `"themediaresort"`. */
    fun cleanSlug(slug: String?): String =
        (slug ?: "").trim().trim('/').substringAfterLast('/')
}
