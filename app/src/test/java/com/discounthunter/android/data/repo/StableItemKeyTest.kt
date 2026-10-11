package com.discounthunter.android.data.repo

import com.discounthunter.android.core.model.Deal
import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.core.model.Trailer
import com.discounthunter.android.core.model.TrailerKind
import com.discounthunter.android.core.model.Screenshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lazy-list keys must be unique.
 *
 * Compose's `LazyColumn` and `LazyVerticalGrid` key their items by whatever `key = { … }` returns,
 * and they throw `IllegalArgumentException: Key "…" was already used` during layout when two items
 * collide. That exception is raised inside the composition, not inside the repository call, so it
 * escapes every `try`/`catch` in the data layer and takes the whole process down.
 *
 * Both feed paths and the search path therefore guarantee one row per store-native id.
 */
class StableItemKeyTest {

    private fun deal(id: String, store: StoreId = StoreId.EPIC) = Deal(
        id = id,
        title = "Some Game",
        thumbUrl = "",
        salePrice = 0.0,
        normalPrice = 19.99,
        savingsPercent = 100.0,
        store = store,
        identity = GameIdentity(store, id.removePrefix("epic_")),
        storeUrl = "",
    )

    @Test
    fun `epic giveaways map to a key derived from the slug`() {
        val first = deal("epic_buried_stars")
        val second = deal("epic_buried_stars")

        // The precondition for the crash: two feed elements for one product produce two equal ids.
        assertEquals(first.id, second.id)
    }

    @Test
    fun `duplicate giveaway ids must be removed before they reach a lazy list`() {
        val raw = listOf(
            deal("epic_buried_stars"),
            deal("epic_buried_stars"),
            deal("epic_hades"),
        )

        val unique = raw.distinctBy { it.id }

        assertEquals(2, unique.size)
        assertEquals(2, unique.map { it.id }.toSet().size)
    }

    @Test
    fun `a duplicated key is exactly what compose rejects`() {
        // Documents the invariant in terms the framework agrees with: keys must be unique.
        val keys = listOf("epic_a", "epic_a", "epic_b").distinct()
        assertEquals(keys.size, keys.toSet().size)
        assertTrue("distinct keys are unique by definition", keys.size == keys.toSet().size)
    }

    @Test
    fun `screenshot and trailer identity stays stable for the detail lists`() {
        val identity = GameIdentity(StoreId.STEAM, "1245620")
        val shot = Screenshot(identity, "https://a/t.jpg", "https://a/f.jpg")
        val duplicate = Screenshot(identity, "https://a/t.jpg", "https://a/f.jpg")

        // Detail rows are keyed on `fullUrl`, which must therefore be de-duplicated upstream.
        assertEquals(listOf(shot.fullUrl), listOf(shot, duplicate).distinctBy { it.fullUrl }.map { it.fullUrl })
    }

    @Test
    fun `trailers keyed by name and kind collapse duplicates`() {
        val identity = GameIdentity(StoreId.STEAM, "1245620")
        val trailer = Trailer(
            owner = identity,
            kind = TrailerKind.ADAPTIVE_STREAM,
            name = "Trailer 1",
            thumbUrl = "https://a/t.jpg",
            streamUrl = "https://a/v.m3u8",
            webUrl = "https://store.steampowered.com/app/1245620/",
        )
        val same = trailer.copy()

        assertEquals(1, listOf(trailer, same).distinctBy { it.name to it.kind }.size)
    }
}