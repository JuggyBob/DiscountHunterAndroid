package com.discounthunter.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version parsing and comparison.
 *
 * ## Why these rules matter more than they look
 *
 * The updater's whole job is to answer one question — "is the published release newer than the one
 * installed?" — and to answer it wrongly *quietly*. Telling a user a newer version exists when it
 * does not is how the feature gets switched off. Comparing version strings as text is the classic
 * way to get this wrong, and `0.0.10` against `0.0.9` is the test case that catches it.
 */
class AppVersionTest {

    @Test
    fun `a plain version parses`() {
        val version = AppVersion.parse("1.2.3")
        assertEquals(1, version?.major)
        assertEquals(2, version?.minor)
        assertEquals(3, version?.patch)
        assertNull(version?.prerelease)
    }

    @Test
    fun `a leading v is ignored`() {
        assertEquals(AppVersion.parse("1.2.3"), AppVersion.parse("v1.2.3"))
        assertEquals(AppVersion.parse("1.2.3"), AppVersion.parse("V1.2.3"))
    }

    @Test
    fun `missing components default to zero`() {
        assertEquals(AppVersion(1, 0, 0), AppVersion.parse("1"))
        assertEquals(AppVersion(1, 2, 0), AppVersion.parse("1.2"))
    }

    @Test
    fun `a multi digit component is compared numerically`() {
        // The case that string comparison gets wrong: "0.0.10" is *newer* than "0.0.9".
        assertTrue(AppVersion.parse("0.0.10")!! > AppVersion.parse("0.0.9")!!)
        assertTrue(AppVersion.parse("1.0.10")!! > AppVersion.parse("1.0.9")!!)
        assertTrue(AppVersion.parse("10.0.0")!! > AppVersion.parse("9.99.99")!!)
    }

    @Test
    fun `equal versions compare equal`() {
        assertEquals(0, AppVersion.parse("1.1.0")!!.compareTo(AppVersion.parse("v1.1.0")!!))
    }

    @Test
    fun `a newer patch is greater`() {
        assertTrue(AppVersion.parse("1.1.1")!! > AppVersion.parse("1.1.0")!!)
    }

    @Test
    fun `a newer minor is greater`() {
        assertTrue(AppVersion.parse("1.2.0")!! > AppVersion.parse("1.1.9")!!)
    }

    @Test
    fun `a newer major is greater`() {
        assertTrue(AppVersion.parse("2.0.0")!! > AppVersion.parse("1.99.99")!!)
    }

    @Test
    fun `an older release is not an update`() {
        assertTrue(AppVersion.parse("1.0.0")!! < AppVersion.parse("1.1.0")!!)
    }

    @Test
    fun `a prerelease is older than the release it precedes`() {
        assertTrue(AppVersion.parse("1.2.0-beta.1")!! < AppVersion.parse("1.2.0")!!)
        assertTrue(AppVersion.parse("1.2.0-alpha")!! < AppVersion.parse("1.2.0-beta")!!)
    }

    @Test
    fun `a prerelease is recognised as one`() {
        assertTrue(AppVersion.parse("1.2.0-beta.1")!!.isPrerelease)
        assertFalse(AppVersion.parse("1.2.0")!!.isPrerelease)
    }

    @Test
    fun `build metadata is ignored for comparison`() {
        assertEquals(AppVersion.parse("1.2.0")!!, AppVersion.parse("1.2.0+build.7")!!)
    }

    @Test
    fun `the project's build suffix is not a prerelease`() {
        // "1.1.0-release" is this project's version name, and "-release" names the build
        // variant. Read as a prerelease it would make the installed build look older than the
        // published "v1.1.0" and the app would offer an update to itself.
        val installed = AppVersion.parseCore("1.1.0-release")
        assertEquals(AppVersion(1, 1, 0), installed)
        assertFalse(installed!!.isPrerelease)
        assertEquals(0, installed.compareTo(AppVersion.parse("v1.1.0")!!))
    }

    @Test
    fun `the debug variant suffix is not a prerelease either`() {
        // A debug build's version name is "1.1.0-release-debug". It is a different *build*, not
        // an earlier version, and it must not look older than the published release.
        val debug = AppVersion.parseCore("1.1.0-release-debug")
        assertEquals(AppVersion(1, 1, 0), debug)
        assertEquals(0, debug!!.compareTo(AppVersion.parse("v1.1.0")!!))
    }

    @Test
    fun `the numeric core of a prerelease is still the version`() {
        assertEquals(AppVersion(1, 2, 0), AppVersion.parseCore("1.2.0-beta.1"))
    }

    @Test
    fun `a malformed version is not a version`() {
        assertNull(AppVersion.parse(null))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("   "))
        assertNull(AppVersion.parse("not-a-version"))
        assertNull(AppVersion.parse("1.x.3"))
        assertNull(AppVersion.parse("1..3"))
        assertNull(AppVersion.parse("1.2.3.4.5"))
        assertNull(AppVersion.parse("v"))
    }

    @Test
    fun `a version prints back in its canonical form`() {
        assertEquals("1.2.3", AppVersion.parse("v1.2.3").toString())
        assertEquals("1.2.3-beta.1", AppVersion.parse("1.2.3-beta.1").toString())
    }
}
