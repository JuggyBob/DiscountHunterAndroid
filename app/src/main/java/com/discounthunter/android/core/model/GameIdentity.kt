package com.discounthunter.android.core.model

/**
 * A game's *stable* identity: the pair (store, id-in-that-store) that the storefront itself
 * publishes.
 *
 * This is the backbone of the app's media-correctness guarantees. Screenshots and trailers are
 * never attached to a game by title alone — every media item carries the [GameIdentity] of the
 * app that published it, and the detail assembler only keeps media whose owner is one of the
 * identities it has positively accepted for the opened game.
 *
 * `id` is the store-native identifier: a Steam app id, a GOG slug, an Epic page slug.
 */
data class GameIdentity(val store: StoreId, val id: String) {

    /** A stable, URL-safe cache/navigation key, e.g. `steam:1245620`. */
    val key: String get() = "${store.name.lowercase()}:${id.lowercase()}"

    val isUsable: Boolean get() = id.isNotBlank()

    companion object {
        /** Used only when a source publishes no id at all; carries no media of its own. */
        val NONE: GameIdentity = GameIdentity(StoreId.STEAM, "")

        fun steam(appId: Int): GameIdentity? =
            if (appId > 0) GameIdentity(StoreId.STEAM, appId.toString()) else null

        fun gog(slug: String): GameIdentity? =
            slug.trim().trim('/').substringAfterLast('/')
                .takeIf { it.isNotBlank() }
                ?.let { GameIdentity(StoreId.GOG, it) }

        fun epic(slug: String): GameIdentity? =
            slug.trim().trim('/').substringAfterLast('/')
                .takeIf { it.isNotBlank() }
                ?.let { GameIdentity(StoreId.EPIC, it) }

        /**
         * Builds the identity a URL points at, e.g.
         * `https://store.steampowered.com/app/1245620/` -> `steam:1245620`.
         */
        fun fromUrl(url: String): GameIdentity? {
            val value = url.trim()
            if (value.isEmpty()) return null

            if ("store.steampowered.com" in value) {
                val marker = value.indexOf("/app/")
                if (marker >= 0) {
                    val appId = value.substring(marker + 5).substringBefore('/').substringBefore('?')
                    if (appId.toIntOrNull()?.let { it > 0 } == true) return steam(appId.toInt())
                }
                return null
            }

            if ("gog.com" in value) {
                val marker = value.indexOf("/game/")
                if (marker >= 0) {
                    val tail = value.substring(marker + 6).substringBefore('?').substringBefore('#')
                    return gog(tail)
                }
                return null
            }

            if ("epicgames.com" in value) {
                val marker = value.indexOf("/p/")
                if (marker >= 0) {
                    val tail = value.substring(marker + 3).substringBefore('?').substringBefore('#')
                    return epic(tail)
                }
                return null
            }

            return null
        }

        /**
         * Parses a route argument of the form `steam:1245620`.
         *
         * The separator is required. Without this check a malformed argument such as a bare
         * `"steam"` parsed as store `STEAM` with the id `"steam"`, which is not an id any store
         * publishes — the detail page then loaded a game that does not exist and rendered an empty
         * shell instead of reporting that the link could not be resolved. A link that cannot be
         * understood must resolve to nothing, not to a plausible-looking fiction.
         */
        fun parse(raw: String?): GameIdentity? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty() || ':' !in value) return null
            val store = runCatching { StoreId.valueOf(value.substringBefore(':').trim().uppercase()) }
                .getOrNull() ?: return null
            return GameIdentity(store, value.substringAfter(':').trim()).takeIf { it.isUsable }
        }
    }
}
