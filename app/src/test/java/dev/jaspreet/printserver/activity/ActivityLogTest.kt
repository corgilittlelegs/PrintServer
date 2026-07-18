package dev.jaspreet.printserver.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActivityLogTest {

    @Before
    fun resetLog() {
        ActivityLog.clear()
    }

    @Test
    fun `record prepends a new entry and returns its id`() {
        val id1 = ActivityLog.record(tier = 2, name = "a.pdf", status = ActivityStatus.PRINTING)
        val id2 = ActivityLog.record(tier = 2, name = "b.pdf", status = ActivityStatus.PRINTING)

        val entries = ActivityLog.entries.value
        assertEquals(2, entries.size)
        assertEquals(id2, entries[0].id) // newest first
        assertEquals(id1, entries[1].id)
        assertTrue(id2 > id1)
    }

    @Test
    fun `update mutates only the matching entry`() {
        val id1 = ActivityLog.record(tier = 2, name = "a.pdf", status = ActivityStatus.PRINTING)
        val id2 = ActivityLog.record(tier = 2, name = "b.pdf", status = ActivityStatus.PRINTING)

        ActivityLog.update(id1) { it.copy(status = ActivityStatus.PRINTED, completedAt = 999L) }

        val byId = ActivityLog.entries.value.associateBy { it.id }
        assertEquals(ActivityStatus.PRINTED, byId.getValue(id1).status)
        assertEquals(999L, byId.getValue(id1).completedAt)
        assertEquals(ActivityStatus.PRINTING, byId.getValue(id2).status)
    }

    @Test
    fun `update on unknown id is a no-op`() {
        ActivityLog.record(tier = 2, name = "a.pdf", status = ActivityStatus.PRINTING)
        ActivityLog.update(99999) { it.copy(status = ActivityStatus.FAILED) }
        assertEquals(1, ActivityLog.entries.value.size)
    }

    @Test
    fun `caps at MAX_ENTRIES, dropping the oldest`() {
        repeat(205) { i -> ActivityLog.record(tier = 2, name = "job-$i", status = ActivityStatus.PRINTED) }
        val entries = ActivityLog.entries.value
        assertEquals(200, entries.size)
        assertEquals("job-204", entries.first().name) // newest kept
        assertEquals("job-5", entries.last().name)     // 0..4 dropped
    }

    @Test
    fun `clear empties the log`() {
        ActivityLog.record(tier = 2, name = "a.pdf", status = ActivityStatus.PRINTING)
        ActivityLog.clear()
        assertTrue(ActivityLog.entries.value.isEmpty())
    }

    @Test
    fun `entries default optional fields to null`() {
        val id = ActivityLog.record(tier = 1, name = "Print request", status = ActivityStatus.PRINTING)
        val entry = ActivityLog.entries.value.first { it.id == id }
        assertNull(entry.completedAt)
        assertNull(entry.clientAddress)
        assertNull(entry.sizeBytes)
        assertNull(entry.format)
        assertNull(entry.failureReason)
    }

    @Test
    fun `record stores the optional jobId`() {
        val id = ActivityLog.record(tier = 2, name = "a.pdf", status = ActivityStatus.PRINTING, jobId = 42)
        val entry = ActivityLog.entries.value.first { it.id == id }
        assertEquals(42, entry.jobId)
    }

    @Test
    fun `jobId defaults to null`() {
        val id = ActivityLog.record(tier = 1, name = "Print request", status = ActivityStatus.PRINTING)
        val entry = ActivityLog.entries.value.first { it.id == id }
        assertNull(entry.jobId)
    }
}
