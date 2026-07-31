package dev.jaspreet.printserver.activity

import dev.jaspreet.printserver.jobs.JobState
import org.junit.Assert.assertEquals
import org.junit.Test

class JobStateMappingTest {

    @Test
    fun `pending and processing map to printing`() {
        assertEquals(ActivityStatus.PRINTING, JobState.PENDING.toActivityStatus())
        assertEquals(ActivityStatus.PRINTING, JobState.SPOOLING.toActivityStatus())
        assertEquals(ActivityStatus.PRINTING, JobState.PROCESSING.toActivityStatus())
    }

    @Test
    fun `completed maps to printed`() {
        assertEquals(ActivityStatus.PRINTED, JobState.COMPLETED.toActivityStatus())
    }

    @Test
    fun `aborted and canceled map to failed`() {
        assertEquals(ActivityStatus.FAILED, JobState.ABORTED.toActivityStatus())
        assertEquals(ActivityStatus.FAILED, JobState.CANCELED.toActivityStatus())
    }
}
