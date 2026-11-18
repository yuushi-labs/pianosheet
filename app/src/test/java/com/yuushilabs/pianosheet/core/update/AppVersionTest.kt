package com.yuushilabs.pianosheet.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4.5: the release tag has to be compared with `BuildConfig.VERSION_NAME`.
 * A plain string compare would call `0.4.10` older than `0.4.9`, so the name is
 * split into numbers and a stage suffix before ordering.
 */
class AppVersionTest {

    private fun parse(text: String) = AppVersion.parse(text)!!

    @Test
    fun `a tag with a v and a stage suffix is the version name`() {
        assertEquals("0.4.5-phase2", parse("v0.4.5-phase2").display)
        assertEquals("0.4.5-phase2", parse("0.4.5-phase2").display)
    }

    @Test
    fun `a repository prefix before the numbers is skipped`() {
        assertEquals("0.4.5-phase2", parse("pianosheet-v0.4.5-phase2").display)
        assertEquals("0.4.5-phase2", parse("pianosheet-0.4.5-phase2").display)
    }

    @Test
    fun `text without a version is not a version`() {
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse(null))
        // A date or a build counter is not a version name: the release then has
        // to say which versionCode it carries.
        assertNull(AppVersion.parse("build-12"))
        assertNull(AppVersion.parse("build-2026-11-18"))
    }

    @Test
    fun `a patch bump is newer`() {
        assertTrue(parse("0.4.5-phase2") > parse("0.4.4-phase2"))
        assertTrue(parse("0.4.4-phase2") < parse("0.4.5-phase2"))
    }

    @Test
    fun `a minor bump is newer than any patch`() {
        assertTrue(parse("0.5.0-phase2") > parse("0.4.9-phase2"))
    }

    @Test
    fun `two digit segments are numbers, not text`() {
        assertTrue(parse("0.4.10-phase2") > parse("0.4.9-phase2"))
        assertTrue(parse("0.4.10-phase2") > parse("0.4.9-phase2"))
    }

    @Test
    fun `the same version is equal however it is written`() {
        assertEquals(0, parse("v0.4.5-phase2").compareTo(parse("0.4.5-phase2")))
        assertEquals(0, parse("0.4").compareTo(parse("0.4.0")))
    }

    @Test
    fun `a plain release outranks a stage of the same numbers`() {
        assertTrue(parse("0.5.0") > parse("0.5.0-rc1"))
    }

    @Test
    fun `a later stage number is newer`() {
        assertTrue(parse("0.4.5-phase10") > parse("0.4.5-phase2"))
        assertTrue(parse("0.4.5-phase2") >= parse("0.4.5-phase2"))
    }

    @Test
    fun `a different stage name is ordered by name`() {
        assertTrue(parse("0.4.5-alpha1") < parse("0.4.5-beta1"))
        assertTrue(parse("0.4.5-beta1") < parse("0.4.5-phase1"))
    }
}
