package com.discounthunter.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the GitHub latest-release response.
 *
 * The parser is the boundary between a third party's response and the rest of the application, so
 * it is written to be *lenient about shape* and *strict about meaning*: a body it does not
 * recognise produces nothing, rather than a release with empty fields that the updater would then
 * compare against.
 */
class GitHubReleaseServiceTest {

    private val service = GitHubReleaseService(
        network = com.discounthunter.android.data.net.HttpClient(
            okhttp3.OkHttpClient(),
        ),
        cache = com.discounthunter.android.data.cache.PayloadCache(
            java.io.File(System.getProperty("java.io.tmpdir"), "unused"),
        ),
    )

    private val body = """
        {
          "name": "Discount Hunter 1.1.0",
          "tag_name": "v1.1.0",
          "body": "## Added\n\n- Deal alerts\n",
          "published_at": "2026-10-20T12:00:00Z",
          "html_url": "https://github.com/JuggyBob/DiscountHunterAndroid/releases/tag/v1.1.0",
          "draft": false,
          "prerelease": false,
          "assets": [
            {
              "name": "discount-hunter-1.1.0.apk",
              "browser_download_url": "https://github.com/JuggyBob/DiscountHunterAndroid/releases/download/v1.1.0/discount-hunter-1.1.0.apk",
              "size": 3200000,
              "content_type": "application/vnd.android.package-archive",
              "digest": "sha256:0123456789abcdef"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `a release is parsed`() {
        val release = service.parse(body)
        assertNotNull(release)
        assertEquals("Discount Hunter 1.1.0", release?.name)
        assertEquals("v1.1.0", release?.tagName)
        assertEquals("1.1.0", release?.versionLabel)
        assertEquals("2026-10-20T12:00:00Z", release?.publishedAt)
        assertEquals(
            "https://github.com/JuggyBob/DiscountHunterAndroid/releases/tag/v1.1.0",
            release?.htmlUrl,
        )
        assertFalse(release?.prerelease ?: true)
        assertFalse(release?.draft ?: true)
    }

    @Test
    fun `the assets are parsed`() {
        val release = service.parse(body)
        assertEquals(1, release?.assets?.size)
        val asset = release?.assets?.first()
        assertEquals("discount-hunter-1.1.0.apk", asset?.name)
        assertEquals(3_200_000L, asset?.sizeBytes)
        assertEquals("sha256:0123456789abcdef", asset?.digest)
        assertEquals("0123456789abcdef", asset?.sha256)
    }

    @Test
    fun `the release notes are kept`() {
        val release = service.parse(body)
        assertTrue(release?.body?.contains("Deal alerts") ?: false)
    }

    @Test
    fun `an empty release body is not an error`() {
        val empty = body.replace("\"body\": \"## Added\\n\\n- Deal alerts\\n\"", "\"body\": \"\"")
        val release = service.parse(empty)
        assertNotNull(release)
        assertEquals("", release?.body)
    }

    @Test
    fun `a release with no assets is not an error`() {
        val none = body.replace("\"assets\": [", "\"assets\": [], \"ignored\": [")
        val release = service.parse(none)
        assertNotNull(release)
        assertTrue(release?.assets?.isEmpty() ?: false)
        assertNull(release?.installableAsset())
    }

    @Test
    fun `a malformed body is rejected`() {
        assertNull(service.parse("not json at all"))
        assertNull(service.parse("{}"))
        assertNull(service.parse("[]"))
        assertNull(service.parse("{\"name\":\"no tag\"}"))
    }

    @Test
    fun `an asset without a download url is skipped`() {
        val broken = body.replace(
            "\"browser_download_url\": \"https://github.com/JuggyBob/DiscountHunterAndroid/releases/download/v1.1.0/discount-hunter-1.1.0.apk\",",
            "\"browser_download_url\": \"\",",
        )
        val release = service.parse(broken)
        assertTrue(release?.assets?.isEmpty() ?: false)
    }

    @Test
    fun `a prerelease is marked as one`() {
        val preview = body.replace("\"prerelease\": false", "\"prerelease\": true")
        assertTrue(service.parse(preview)?.prerelease ?: false)
    }

    @Test
    fun `the endpoint points at this project repository`() {
        assertEquals(
            "https://api.github.com/repos/JuggyBob/DiscountHunterAndroid/releases/latest",
            GitHubReleaseService.API_URL,
        )
    }
}
