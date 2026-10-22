package com.discounthunter.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which published file is the installable update.
 *
 * ## The rule this pins down
 *
 * A release can carry several APKs. Picking the first one that ends in `.apk` can hand the user
 * the wrong file — a debug build, a source archive, a per-architecture build — and installing that
 * is not an update. So the selection is by this project's naming convention, and when the choice is
 * not unambiguous the answer is *nothing*, which the caller reports rather than guessing.
 */
class ReleaseAssetSelectorTest {

    private fun asset(name: String, size: Long = 3_000_000) = ReleaseAsset(
        name = name,
        downloadUrl = "https://github.com/JuggyBob/DiscountHunterAndroid/releases/download/v1.1.0/$name",
        sizeBytes = size,
        contentType = "application/vnd.android.package-archive",
    )

    private fun release(vararg assets: ReleaseAsset) = ReleaseInfo(
        name = "Discount Hunter 1.1.0",
        tagName = "v1.1.0",
        version = AppVersion.parse("v1.1.0"),
        body = "notes",
        publishedAt = "2026-10-20T12:00:00Z",
        htmlUrl = "https://github.com/JuggyBob/DiscountHunterAndroid/releases/tag/v1.1.0",
        assets = assets.toList(),
    )

    @Test
    fun `the release apk is selected`() {
        val chosen = release(asset("discount-hunter-1.1.0.apk")).installableAsset()
        assertEquals("discount-hunter-1.1.0.apk", chosen?.name)
    }

    @Test
    fun `a source archive is not selected`() {
        assertNull(release(asset("Source code (zip)")).installableAsset())
    }

    @Test
    fun `a debug apk is not selected`() {
        assertNull(
            release(asset("discount-hunter-1.1.0-debug.apk")).installableAsset(),
        )
    }

    @Test
    fun `an apk that is not this project is not selected`() {
        assertNull(release(asset("some-other-app-2.0.apk")).installableAsset())
    }

    @Test
    fun `no apk at all means no update`() {
        assertNull(release().installableAsset())
    }

    @Test
    fun `two candidate apks mean no unambiguous choice`() {
        // Two release builds and no principled way to choose between them: the caller has to be
        // told, rather than being handed one at random.
        val chosen = release(
            asset("discount-hunter-1.1.0.apk"),
            asset("discount-hunter-1.1.0-alt.apk"),
        ).installableAsset()
        assertNull(chosen)
    }

    @Test
    fun `a debug and a release apk still leave one candidate`() {
        val chosen = release(
            asset("discount-hunter-1.1.0.apk"),
            asset("discount-hunter-1.1.0-debug.apk"),
        ).installableAsset()
        assertEquals("discount-hunter-1.1.0.apk", chosen?.name)
    }

    @Test
    fun `an app bundle is not selected`() {
        assertNull(release(asset("discount-hunter-1.1.0.aab")).installableAsset())
    }

    @Test
    fun `a digest is read from the sha256 prefix`() {
        val withDigest = ReleaseAsset(
            name = "discount-hunter-1.1.0.apk",
            downloadUrl = "https://example.invalid/a.apk",
            sizeBytes = 1,
            digest = "sha256:abc123",
        )
        assertEquals("abc123", withDigest.sha256)
    }

    @Test
    fun `a missing digest is reported as missing`() {
        val without = ReleaseAsset(
            name = "a.apk",
            downloadUrl = "https://example.invalid/a.apk",
            sizeBytes = 1,
        )
        assertNull(without.sha256)
    }
}
