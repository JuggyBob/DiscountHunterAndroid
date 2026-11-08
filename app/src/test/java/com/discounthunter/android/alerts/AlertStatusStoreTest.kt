package com.discounthunter.android.alerts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The record of the last background check.
 *
 * This is the thing that turns "notifications are not working" into a specific answer: did the
 * check run, and what did it do? It therefore has to survive a process death, read a missing file
 * as "no check yet" rather than a success, and never crash the Settings screen on a corrupt one.
 */
class AlertStatusStoreTest {

    @Test
    fun `a record round-trips through its stored form`() {
        val status = AlertRunStatus(
            checkedAt = 1_794_000_000_000L,
            outcome = AlertCheckOutcome.ANNOUNCED,
            announced = 2,
            deals = 137,
            wishlist = 4,
            message = "fine",
        )
        assertEquals(status, AlertStatusCodec.parse(AlertStatusCodec.serialize(status)))
    }

    @Test
    fun `every outcome round-trips`() {
        for (outcome in AlertCheckOutcome.entries) {
            val status = AlertRunStatus(
                checkedAt = 1L,
                outcome = outcome,
            )
            assertEquals(outcome, AlertStatusCodec.parse(AlertStatusCodec.serialize(status))?.outcome)
        }
    }

    @Test
    fun `a malformed record is rejected rather than guessed`() {
        assertNull(AlertStatusCodec.parse("not json"))
        assertNull(AlertStatusCodec.parse("{}"))
        assertNull(AlertStatusCodec.parse("""{"checkedAt":1}"""))
        assertNull(AlertStatusCodec.parse("""{"checkedAt":1,"outcome":"NOT_AN_OUTCOME"}"""))
    }

    @Test
    fun `missing counters default to zero`() {
        val status = AlertStatusCodec.parse("""{"checkedAt":123,"outcome":"CHECKED"}""")
        assertEquals(123L, status?.checkedAt)
        assertEquals(0, status?.announced)
        assertEquals(0, status?.deals)
        assertEquals(0, status?.wishlist)
    }

    @Test
    fun `a very long diagnostic message is truncated`() {
        val status = AlertRunStatus(
            checkedAt = 1L,
            outcome = AlertCheckOutcome.FAILED,
            message = "x".repeat(10_000),
        )
        val parsed = AlertStatusCodec.parse(AlertStatusCodec.serialize(status))
        assertEquals(300, parsed?.message?.length)
    }

    @Test
    fun `a missing file reads as no check`() = runBlocking {
        val directory = Files.createTempDirectory("dh-alert-status-missing").toFile()
        try {
            assertNull(AlertStatusStore(File(directory, "alerts/status.json")).load())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `recording a check makes it readable again`() = runBlocking {
        val directory = Files.createTempDirectory("dh-alert-status-write").toFile()
        try {
            val file = File(directory, "alerts/status.json")
            val store = AlertStatusStore(file)
            val status = AlertRunStatus(
                checkedAt = 1_794_000_000_000L,
                outcome = AlertCheckOutcome.CHECKED,
                deals = 60,
                wishlist = 3,
            )
            store.record(status)
            assertEquals(status, store.load())
            // The atomic write's temporary sibling must not survive.
            assertFalse(File(directory, "alerts/status.json.tmp").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a corrupt file reads as no check rather than throwing`() = runBlocking {
        val directory = Files.createTempDirectory("dh-alert-status-corrupt").toFile()
        try {
            val file = File(directory, "alerts/status.json")
            file.parentFile?.mkdirs()
            file.writeText("{ this is not json")
            assertNull(AlertStatusStore(file).load())
        } finally {
            directory.deleteRecursively()
        }
    }
}
