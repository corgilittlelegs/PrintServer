package dev.jaspreet.printserver.activity

import dev.jaspreet.printserver.jobs.JobState

/** Maps a Tier-2 [JobState] transition onto the coarser [ActivityStatus] the feed UI shows. */
fun JobState.toActivityStatus(): ActivityStatus = when (this) {
    JobState.PENDING,
    JobState.SPOOLING,
    JobState.PROCESSING -> ActivityStatus.PRINTING
    JobState.COMPLETED -> ActivityStatus.PRINTED
    JobState.ABORTED,
    JobState.CANCELED -> ActivityStatus.FAILED
}
