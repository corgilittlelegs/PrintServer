# Queue Visibility + Retry/Cancel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Tier 2 (on-device rendering) print queue a live UI section showing PENDING/PROCESSING jobs, and let the user cancel a queued job or retry a failed one, directly from the app.

**Architecture:** `JobQueue` (already has `cancel()`/`listActive()`) gains `retry()` and `queuePosition()`. A new `QueueState` singleton object mirrors the existing `ActivityLog` pattern (session-scoped `StateFlow`, updated by `ServerService`, read by `MainActivity` without service binding) and also delegates cancel/retry actions into the live `JobQueue`. `PrintServerApp` gets a new `QueueCard` above the existing `ActivityCard`, and `ActivityRow` gets a Retry button for FAILED entries.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.coroutines StateFlow. No new dependencies.

Spec: `docs/superpowers/specs/2026-07-18-queue-visibility-retry-cancel-design.md`

---

### Task 1: `PrintJob` gains `submittedAtMs` and `retryOf`

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/jobs/PrintJob.kt`

- [ ] **Step 1: Add the two fields**

Replace the full file contents:

```kotlin
package dev.jaspreet.printserver.jobs

import java.io.File

enum class JobState { PENDING, PROCESSING, COMPLETED, ABORTED, CANCELED }

class PrintJob(
    val id: Int,
    val name: String,
    val spoolFile: File,
    val format: String = "application/pdf",
    val clientAddress: String? = null,
) {
    @Volatile var state: JobState = JobState.PENDING
    @Volatile var stateReason: String = "none"
    val submittedAtMs: Long = System.currentTimeMillis()
    /** Set when this job was created via [JobQueue.retry] — the original job's id. Tracking only. */
    @Volatile var retryOf: Int? = null
}
```

- [ ] **Step 2: Build to confirm it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/jobs/PrintJob.kt
git commit -m "feat: add submittedAtMs and retryOf to PrintJob"
```

---

### Task 2: Retain an ABORTED job's spool file until eviction

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/jobs/JobQueue.kt`
- Modify: `app/src/test/java/dev/jaspreet/printserver/jobs/JobQueueTest.kt`

- [ ] **Step 1: Update the existing test to expect retention, not deletion**

In `JobQueueTest.kt`, replace the `render failure aborts the job and deletes the spool file` test:

```kotlin
    @Test
    fun `render failure aborts the job but keeps the spool file for retry`() {
        val spool = pdf()
        val done = CountDownLatch(1)
        val q = JobQueue(
            FakeRenderingPipeline(failWith = IOException("bad pdf")),
            { FakePrinterTransport { ByteArray(0) } },
        ) { done.countDown() }
        queue = q
        val id = q.submit(spool, "broken-doc")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(JobState.ABORTED, q.get(id)!!.state)
        assertTrue(spool.exists())
    }
```

- [ ] **Step 2: Run it to confirm it fails against current code**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.jobs.JobQueueTest"`
Expected: FAIL — `render failure aborts the job but keeps the spool file for retry` fails on `assertTrue(spool.exists())` (current code deletes it unconditionally).

- [ ] **Step 3: Stop deleting the spool file for ABORTED jobs**

In `JobQueue.kt`, `process()`'s `finally` block currently reads:

```kotlin
        } finally {
            job.spoolFile.delete()
            rendered.delete()
            onJobStateChanged(job)
            onJobFinished(job)
        }
```

Replace with:

```kotlin
        } finally {
            // ABORTED jobs keep their spool file so JobQueue.retry() can resubmit it;
            // it's cleaned up by evictOldTerminalJobs() or cleanStaleSpool() on restart.
            if (job.state != JobState.ABORTED) job.spoolFile.delete()
            rendered.delete()
            onJobStateChanged(job)
            onJobFinished(job)
        }
```

- [ ] **Step 4: Run the test again to confirm it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.jobs.JobQueueTest"`
Expected: PASS (all tests in the class)

- [ ] **Step 5: Write a failing test for eviction deleting the retained spool file**

Add to `JobQueueTest.kt`:

```kotlin
    @Test
    fun `evicting a retained ABORTED job deletes its spool file`() {
        // MAX_RETAINED_JOBS is 200 — submit 201 jobs that all fail, forcing eviction of the oldest.
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val finishedAll = CountDownLatch(201)
        val q = JobQueue(
            FakeRenderingPipeline(failWith = IOException("bad pdf")),
            { FakePrinterTransport { ByteArray(0) } },
        ) { done.incrementAndGet(); finishedAll.countDown() }
        queue = q
        val firstSpool = pdf()
        val firstId = q.submit(firstSpool, "job-0")
        repeat(200) { i -> q.submit(pdf(), "job-${i + 1}") }
        assertTrue(finishedAll.await(10, TimeUnit.SECONDS))
        assertNull("oldest job should have been evicted", q.get(firstId))
        assertFalse("evicted job's spool file should be deleted", firstSpool.exists())
    }
```

- [ ] **Step 6: Run it to confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.jobs.JobQueueTest"`
Expected: FAIL — `evicting a retained ABORTED job deletes its spool file` fails on `assertFalse(firstSpool.exists())` (eviction currently only removes the map entry, never deletes the file — this was previously safe because every terminal job's file was already gone by the time eviction ran).

- [ ] **Step 7: Delete the spool file on eviction**

In `JobQueue.kt`, `evictOldTerminalJobs()` currently reads:

```kotlin
    private fun evictOldTerminalJobs() {
        val overflow = jobs.size - MAX_RETAINED_JOBS
        if (overflow <= 0) return
        jobs.values
            .filter { it.state == JobState.COMPLETED || it.state == JobState.ABORTED || it.state == JobState.CANCELED }
            .sortedBy { it.id }
            .take(overflow)
            .forEach { jobs.remove(it.id) }
    }
```

Replace with:

```kotlin
    private fun evictOldTerminalJobs() {
        val overflow = jobs.size - MAX_RETAINED_JOBS
        if (overflow <= 0) return
        jobs.values
            .filter { it.state == JobState.COMPLETED || it.state == JobState.ABORTED || it.state == JobState.CANCELED }
            .sortedBy { it.id }
            .take(overflow)
            .forEach {
                it.spoolFile.delete()
                jobs.remove(it.id)
            }
    }
```

- [ ] **Step 8: Run the full test class to confirm everything passes**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.jobs.JobQueueTest"`
Expected: PASS (all tests)

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/jobs/JobQueue.kt app/src/test/java/dev/jaspreet/printserver/jobs/JobQueueTest.kt
git commit -m "fix: retain ABORTED jobs' spool files until eviction, for retry"
```

---

### Task 3: `JobQueue.retry()`

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/jobs/JobQueue.kt`
- Modify: `app/src/test/java/dev/jaspreet/printserver/jobs/JobQueueTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `JobQueueTest.kt`:

```kotlin
    @Test
    fun `retry copies the original spool bytes into a new file and reruns it`() {
        var failNext = true
        val pipeline = object : dev.jaspreet.printserver.render.RenderingPipeline {
            override fun render(document: File, output: File, format: String) {
                if (failNext) throw IOException("bad pdf")
                output.writeBytes(document.readBytes())
            }
        }
        val done = CountDownLatch(1)
        val q = JobQueue(pipeline, { FakePrinterTransport { ByteArray(0) } }) { done.countDown() }
        queue = q
        val spool = pdf()
        spool.writeText("original-bytes")
        val id = q.submit(spool, "broken-doc")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(JobState.ABORTED, q.get(id)!!.state)
        assertTrue(spool.exists())

        failNext = false
        val newId = q.retry(id)
        assertNotNull(newId)
        assertEquals(id, q.get(newId!!)!!.retryOf)
        val deadline = System.currentTimeMillis() + 5000
        while (q.get(newId)!!.state == JobState.PENDING && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(JobState.COMPLETED, q.get(newId)!!.state)
    }

    @Test
    fun `retry on a non-ABORTED job returns null`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val done = CountDownLatch(1)
        val q = JobQueue(FakeRenderingPipeline("PCL!".toByteArray()), { printer }) { done.countDown() }
        queue = q
        val id = q.submit(pdf(), "ok-doc")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(JobState.COMPLETED, q.get(id)!!.state)
        assertNull(q.retry(id))
    }

    @Test
    fun `retry on an unknown job id returns null`() {
        val q = JobQueue(FakeRenderingPipeline(), { FakePrinterTransport { ByteArray(0) } }) {}
        queue = q
        assertNull(q.retry(999))
    }

    @Test
    fun `retry after the spool file was evicted returns null`() {
        val done = java.util.concurrent.CountDownLatch(201)
        val q = JobQueue(
            FakeRenderingPipeline(failWith = IOException("bad pdf")),
            { FakePrinterTransport { ByteArray(0) } },
        ) { done.countDown() }
        queue = q
        val firstId = q.submit(pdf(), "job-0")
        repeat(200) { i -> q.submit(pdf(), "job-${i + 1}") }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertNull(q.get(firstId)) // evicted
        assertNull(q.retry(firstId))
    }
```

- [ ] **Step 2: Run to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.jobs.JobQueueTest"`
Expected: FAIL — `retry` is not a member of `JobQueue` (compile error), confirming the method doesn't exist yet.

- [ ] **Step 3: Implement `retry()`**

In `JobQueue.kt`, add this method after `cancel()` (which ends around line 127):

```kotlin
    /**
     * Resubmits an ABORTED job's original document as a new job, copied into a fresh
     * spool file (never shares the original file with the new job — a second retry of
     * the same original id, or the new job completing and deleting its own file, must
     * not affect the other). Returns the new job's id, or null if [id] isn't an
     * eligible ABORTED job with its spool file still present (e.g. already evicted).
     */
    fun retry(id: Int): Int? {
        val job = jobs[id] ?: return null
        if (job.state != JobState.ABORTED) return null
        if (!job.spoolFile.exists()) return null
        val copy = File.createTempFile("retry", ".${job.spoolFile.extension}", job.spoolFile.parentFile)
        job.spoolFile.copyTo(copy, overwrite = true)
        val newId = submit(copy, job.name, job.format, job.clientAddress)
        jobs[newId]?.retryOf = id
        return newId
    }
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.jobs.JobQueueTest"`
Expected: PASS (all tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/jobs/JobQueue.kt app/src/test/java/dev/jaspreet/printserver/jobs/JobQueueTest.kt
git commit -m "feat: add JobQueue.retry() to resubmit a failed job's spool bytes"
```

---

### Task 4: `JobQueue.queuePosition()`

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/jobs/JobQueue.kt`
- Modify: `app/src/test/java/dev/jaspreet/printserver/jobs/JobQueueTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `JobQueueTest.kt`:

```kotlin
    @Test
    fun `queuePosition ranks PENDING jobs in FIFO order`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingPipeline = object : dev.jaspreet.printserver.render.RenderingPipeline {
            override fun render(document: File, output: File, format: String) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                output.writeBytes("X".toByteArray())
            }
        }
        val q = JobQueue(blockingPipeline, { FakePrinterTransport { ByteArray(0) } }) {}
        queue = q
        val first = q.submit(pdf(), "job-a")   // occupies the worker (PROCESSING)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val second = q.submit(pdf(), "job-b")  // 1st in queue
        val third = q.submit(pdf(), "job-c")   // 2nd in queue

        assertNull("PROCESSING job has no queue position", q.queuePosition(first))
        assertEquals(1, q.queuePosition(second))
        assertEquals(2, q.queuePosition(third))

        release.countDown()
    }

    @Test
    fun `queuePosition on a terminal or unknown job returns null`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val done = CountDownLatch(1)
        val q = JobQueue(FakeRenderingPipeline("PCL!".toByteArray()), { printer }) { done.countDown() }
        queue = q
        val id = q.submit(pdf(), "ok-doc")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNull(q.queuePosition(id)) // COMPLETED
        assertNull(q.queuePosition(999)) // unknown
    }
```

- [ ] **Step 2: Run to confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.jobs.JobQueueTest"`
Expected: FAIL — `queuePosition` is not a member of `JobQueue` (compile error).

- [ ] **Step 3: Implement `queuePosition()`**

In `JobQueue.kt`, add this method right after `retry()`:

```kotlin
    /**
     * 1-based rank of [id] among currently-PENDING jobs, oldest first — nextId is a
     * strictly increasing AtomicInteger, so id order already equals FIFO submission
     * order. Returns null if [id] is unknown, PROCESSING, or already terminal.
     */
    fun queuePosition(id: Int): Int? {
        val job = jobs[id] ?: return null
        if (job.state != JobState.PENDING) return null
        return jobs.values.count { it.state == JobState.PENDING && it.id < id } + 1
    }
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.jobs.JobQueueTest"`
Expected: PASS (all tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/jobs/JobQueue.kt app/src/test/java/dev/jaspreet/printserver/jobs/JobQueueTest.kt
git commit -m "feat: add JobQueue.queuePosition() for FIFO rank of a PENDING job"
```

---

### Task 5: `ActivityEntry` carries the underlying job id

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/activity/ActivityLog.kt`
- Modify: `app/src/test/java/dev/jaspreet/printserver/activity/ActivityLogTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `ActivityLogTest.kt`:

```kotlin
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
```

(Add `import org.junit.Assert.assertEquals` is already present in the file; no new imports needed.)

- [ ] **Step 2: Run to confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.activity.ActivityLogTest"`
Expected: FAIL — `record(...)` has no `jobId` parameter (compile error).

- [ ] **Step 3: Add the field and parameter**

In `ActivityLog.kt`, `ActivityEntry` currently reads:

```kotlin
data class ActivityEntry(
    val id: Int,
    val tier: Int,                     // 1 or 2
    val name: String,                  // Tier 2: client-sent job name; Tier 1: "Print request"
    val status: ActivityStatus,
    val startedAt: Long,
    val completedAt: Long? = null,
    val clientAddress: String? = null,
    val sizeBytes: Long? = null,
    val format: String? = null,        // Tier 2 only
    val failureReason: String? = null,
)
```

Replace with:

```kotlin
data class ActivityEntry(
    val id: Int,
    val tier: Int,                     // 1 or 2
    val name: String,                  // Tier 2: client-sent job name; Tier 1: "Print request"
    val status: ActivityStatus,
    val startedAt: Long,
    val completedAt: Long? = null,
    val clientAddress: String? = null,
    val sizeBytes: Long? = null,
    val format: String? = null,        // Tier 2 only
    val failureReason: String? = null,
    val jobId: Int? = null,            // Tier 2 only — the underlying JobQueue job id, for retry/cancel
)
```

`record()` currently reads:

```kotlin
    fun record(
        tier: Int,
        name: String,
        status: ActivityStatus,
        startedAt: Long = System.currentTimeMillis(),
        clientAddress: String? = null,
        sizeBytes: Long? = null,
        format: String? = null,
    ): Int {
        val id = nextId.getAndIncrement()
        val entry = ActivityEntry(
            id = id, tier = tier, name = name, status = status, startedAt = startedAt,
            clientAddress = clientAddress, sizeBytes = sizeBytes, format = format,
        )
        _entries.update { (listOf(entry) + it).take(MAX_ENTRIES) }
        return id
    }
```

Replace with:

```kotlin
    fun record(
        tier: Int,
        name: String,
        status: ActivityStatus,
        startedAt: Long = System.currentTimeMillis(),
        clientAddress: String? = null,
        sizeBytes: Long? = null,
        format: String? = null,
        jobId: Int? = null,
    ): Int {
        val id = nextId.getAndIncrement()
        val entry = ActivityEntry(
            id = id, tier = tier, name = name, status = status, startedAt = startedAt,
            clientAddress = clientAddress, sizeBytes = sizeBytes, format = format, jobId = jobId,
        )
        _entries.update { (listOf(entry) + it).take(MAX_ENTRIES) }
        return id
    }
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.activity.ActivityLogTest"`
Expected: PASS (all tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/activity/ActivityLog.kt app/src/test/java/dev/jaspreet/printserver/activity/ActivityLogTest.kt
git commit -m "feat: carry the underlying job id on ActivityEntry"
```

---

### Task 6: `QueueEntry` + `QueueState`

**Files:**
- Create: `app/src/main/java/dev/jaspreet/printserver/jobs/QueueState.kt`
- Test: `app/src/test/java/dev/jaspreet/printserver/jobs/QueueStateTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/jaspreet/printserver/jobs/QueueStateTest.kt`:

```kotlin
package dev.jaspreet.printserver.jobs

import dev.jaspreet.printserver.render.FakeRenderingPipeline
import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class QueueStateTest {

    private var queue: JobQueue? = null
    private val tempFiles = mutableListOf<File>()

    private fun pdf(): File = File.createTempFile("job", ".pdf").also {
        it.writeText("%PDF-fake")
        tempFiles += it
    }

    @After
    fun tearDown() {
        QueueState.detach()
        queue?.shutdown()
        tempFiles.forEach { it.delete() }
    }

    @Test
    fun `refresh with nothing attached leaves entries empty`() {
        QueueState.refresh()
        assertTrue(QueueState.entries.value.isEmpty())
    }

    @Test
    fun `refresh reflects the attached queue's active jobs`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingPipeline = object : dev.jaspreet.printserver.render.RenderingPipeline {
            override fun render(document: File, output: File, format: String) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                output.writeBytes("X".toByteArray())
            }
        }
        val q = JobQueue(blockingPipeline, { FakePrinterTransport { ByteArray(0) } }) {}
        queue = q
        QueueState.attach(q)

        val first = q.submit(pdf(), "job-a")
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val second = q.submit(pdf(), "job-b")
        QueueState.refresh()

        val entries = QueueState.entries.value.associateBy { it.id }
        assertEquals(2, entries.size)
        assertEquals(JobState.PROCESSING, entries.getValue(first).state)
        assertNull(entries.getValue(first).position)
        assertEquals(JobState.PENDING, entries.getValue(second).state)
        assertEquals(1, entries.getValue(second).position)

        release.countDown()
    }

    @Test
    fun `detach clears entries and future actions`() {
        val q = JobQueue(FakeRenderingPipeline(), { FakePrinterTransport { ByteArray(0) } }) {}
        queue = q
        QueueState.attach(q)
        QueueState.detach()
        assertTrue(QueueState.entries.value.isEmpty())
        assertEquals(false, QueueState.cancel(1))
        assertNull(QueueState.retry(1))
    }

    @Test
    fun `cancel and retry delegate to the attached queue`() {
        val spool = pdf()
        val done = CountDownLatch(1)
        val q = JobQueue(
            FakeRenderingPipeline(failWith = java.io.IOException("bad pdf")),
            { FakePrinterTransport { ByteArray(0) } },
        ) { done.countDown() }
        queue = q
        QueueState.attach(q)
        val id = q.submit(spool, "broken-doc")
        assertTrue(done.await(5, TimeUnit.SECONDS))

        val newId = QueueState.retry(id)
        assertEquals(true, newId != null)
        assertEquals(false, QueueState.cancel(999)) // unknown id
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.jobs.QueueStateTest"`
Expected: FAIL — `QueueState` doesn't exist (compile error).

- [ ] **Step 3: Implement `QueueEntry` and `QueueState`**

Create `app/src/main/java/dev/jaspreet/printserver/jobs/QueueState.kt`:

```kotlin
package dev.jaspreet.printserver.jobs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class QueueEntry(
    val id: Int,
    val name: String,
    val state: JobState,          // PENDING or PROCESSING only — listActive() never returns others
    val submittedAtMs: Long,
    val sizeBytes: Long,
    val position: Int?,           // 1-based rank among PENDING jobs; null for PROCESSING
)

/**
 * Session-scoped live view of a JobQueue's active (PENDING/PROCESSING) jobs, plus
 * cancel/retry action delegation. Mirrors ActivityLog's singleton-StateFlow pattern —
 * MainActivity isn't bound to ServerService (onBind returns null), so a global object
 * is how UI reaches whichever JobQueue instance is currently running.
 */
object QueueState {
    private val _entries = MutableStateFlow<List<QueueEntry>>(emptyList())
    val entries: StateFlow<List<QueueEntry>> = _entries.asStateFlow()

    @Volatile private var queue: JobQueue? = null

    fun attach(jobQueue: JobQueue) { queue = jobQueue }

    fun detach() {
        queue = null
        _entries.value = emptyList()
    }

    /** Recomputes the visible snapshot from the attached queue's current active jobs. */
    fun refresh() {
        val q = queue ?: return
        _entries.value = q.listActive().sortedBy { it.id }.map { job ->
            QueueEntry(
                id = job.id,
                name = job.name,
                state = job.state,
                submittedAtMs = job.submittedAtMs,
                sizeBytes = job.spoolFile.length(),
                position = q.queuePosition(job.id),
            )
        }
    }

    fun cancel(id: Int): Boolean = queue?.cancel(id) ?: false

    fun retry(id: Int): Int? = queue?.retry(id)
}
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.jobs.QueueStateTest"`
Expected: PASS (all tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/jobs/QueueState.kt app/src/test/java/dev/jaspreet/printserver/jobs/QueueStateTest.kt
git commit -m "feat: add QueueState, a live view of the active print queue"
```

---

### Task 7: Wire `QueueState` and `jobId` into `ServerService`

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/service/ServerService.kt`

No new unit test here — `ServerService` is an Android `Service`, not JVM-testable in this codebase (same as `NativeRenderingPipeline`); this task is verified in Task 10's manual/hardware check.

- [ ] **Step 1: Import `QueueState`**

In `ServerService.kt`, the import block currently has:

```kotlin
import dev.jaspreet.printserver.jobs.JobQueue
import dev.jaspreet.printserver.jobs.JobState
```

Replace with:

```kotlin
import dev.jaspreet.printserver.jobs.JobQueue
import dev.jaspreet.printserver.jobs.JobState
import dev.jaspreet.printserver.jobs.QueueState
```

- [ ] **Step 2: Attach `QueueState` when the queue is built, refresh it on every state change, pass `jobId` to `ActivityLog.record`**

In `startLegacyPipeline()`, the `queue` construction currently reads:

```kotlin
        val queue = JobQueue(
            pipeline, { transport },
            onPipelineStuck = {
                update { ServerStatus(message = "Rendering got stuck — restart the app to recover") }
                stopSelf()
            },
            onJobStateChanged = { job ->
                val status = job.state.toActivityStatus()
                // computeIfAbsent (not getOrPut — that's a plain get-then-put on a
                // ConcurrentHashMap, not atomic) because JobQueue.submit() enqueues the job
                // before firing this callback, so the worker thread can race the submitting
                // thread here for the same new job id.
                val activityId = jobActivityIds.computeIfAbsent(job.id) {
                    ActivityLog.record(
                        tier = 2, name = job.name, status = status,
                        clientAddress = job.clientAddress, format = job.format,
                    )
                }
                ActivityLog.update(activityId) { e ->
                    e.copy(
                        status = status,
                        sizeBytes = if (job.state == JobState.PENDING ||
                            job.state == JobState.PROCESSING
                        ) job.spoolFile.length() else e.sizeBytes,
                        completedAt = if (status != ActivityStatus.PRINTING) System.currentTimeMillis() else e.completedAt,
                        failureReason = if (status == ActivityStatus.FAILED) job.stateReason else e.failureReason,
                    )
                }
            },
        ).also { jobQueue = it }
```

Replace with:

```kotlin
        val queue = JobQueue(
            pipeline, { transport },
            onPipelineStuck = {
                update { ServerStatus(message = "Rendering got stuck — restart the app to recover") }
                stopSelf()
            },
            onJobStateChanged = { job ->
                val status = job.state.toActivityStatus()
                // computeIfAbsent (not getOrPut — that's a plain get-then-put on a
                // ConcurrentHashMap, not atomic) because JobQueue.submit() enqueues the job
                // before firing this callback, so the worker thread can race the submitting
                // thread here for the same new job id.
                val activityId = jobActivityIds.computeIfAbsent(job.id) {
                    ActivityLog.record(
                        tier = 2, name = job.name, status = status,
                        clientAddress = job.clientAddress, format = job.format, jobId = job.id,
                    )
                }
                ActivityLog.update(activityId) { e ->
                    e.copy(
                        status = status,
                        sizeBytes = if (job.state == JobState.PENDING ||
                            job.state == JobState.PROCESSING
                        ) job.spoolFile.length() else e.sizeBytes,
                        completedAt = if (status != ActivityStatus.PRINTING) System.currentTimeMillis() else e.completedAt,
                        failureReason = if (status == ActivityStatus.FAILED) job.stateReason else e.failureReason,
                    )
                }
                QueueState.refresh()
            },
        ).also { jobQueue = it; QueueState.attach(it) }
```

- [ ] **Step 3: Detach `QueueState` when the pipeline stops**

`stopPipeline()` currently reads:

```kotlin
    private fun stopPipeline() {
        advertiser?.stopAll(); advertiser = null
        ippServer?.stop(); ippServer = null
        localIppServer?.stop(); localIppServer = null
        jobQueue?.shutdown(); jobQueue = null
        rawRelay?.stop(); rawRelay = null
        legacyTransport?.close(); legacyTransport = null
        pool?.closeAll(); pool = null
        servedDeviceId = null
        pipelineActive.set(false)
    }
```

Replace with:

```kotlin
    private fun stopPipeline() {
        advertiser?.stopAll(); advertiser = null
        ippServer?.stop(); ippServer = null
        localIppServer?.stop(); localIppServer = null
        jobQueue?.shutdown(); jobQueue = null
        QueueState.detach()
        rawRelay?.stop(); rawRelay = null
        legacyTransport?.close(); legacyTransport = null
        pool?.closeAll(); pool = null
        servedDeviceId = null
        pipelineActive.set(false)
    }
```

- [ ] **Step 4: Build to confirm it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/service/ServerService.kt
git commit -m "feat: attach QueueState to the running job queue, refresh on job changes"
```

---

### Task 8: `QueueCard` in `PrintServerApp`

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/ui/PrintServerApp.kt`

No JVM unit test — this is Compose UI, verified manually in Task 10.

- [ ] **Step 1: Add imports**

The import block currently has:

```kotlin
import dev.jaspreet.printserver.activity.ActivityEntry
import dev.jaspreet.printserver.activity.ActivityStatus
import dev.jaspreet.printserver.service.ServerStatus
```

Replace with:

```kotlin
import dev.jaspreet.printserver.activity.ActivityEntry
import dev.jaspreet.printserver.activity.ActivityStatus
import dev.jaspreet.printserver.jobs.JobState
import dev.jaspreet.printserver.jobs.QueueEntry
import dev.jaspreet.printserver.service.ServerStatus
```

- [ ] **Step 2: Add `queueEntries` and `onCancelJob` params to `PrintServerApp`**

The function signature currently reads:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintServerApp(
    status: ServerStatus,
    activityEntries: List<ActivityEntry>,
    onStartServerClick: () -> Unit,
    onStopServerClick: () -> Unit,
    onBatteryExemptionClick: () -> Unit
) {
```

Replace with:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintServerApp(
    status: ServerStatus,
    activityEntries: List<ActivityEntry>,
    queueEntries: List<QueueEntry>,
    onStartServerClick: () -> Unit,
    onStopServerClick: () -> Unit,
    onBatteryExemptionClick: () -> Unit,
    onCancelJob: (Int) -> Unit,
    onRetryJob: (Int) -> Unit,
) {
```

- [ ] **Step 3: Render `QueueCard` above `ActivityCard`, pass `onRetryJob` into `ActivityCard`**

The line rendering the activity feed currently reads:

```kotlin
                        // Activity Log Feed
                        ActivityCard(entries = activityEntries)
```

Replace with:

```kotlin
                        // Live Print Queue
                        QueueCard(entries = queueEntries, onCancel = onCancelJob)

                        // Activity Log Feed
                        ActivityCard(entries = activityEntries, onRetry = onRetryJob)
```

- [ ] **Step 4: Add the `QueueCard`/`QueueRow` composables**

`ActivityCard` currently starts at:

```kotlin
@Composable
private fun ActivityCard(entries: List<ActivityEntry>) {
```

Insert this new composable pair immediately before it:

```kotlin
@Composable
private fun QueueCard(entries: List<QueueEntry>, onCancel: (Int) -> Unit) {
    if (entries.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                text = "Print Queue",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            entries.forEachIndexed { index, entry ->
                QueueRow(entry = entry, onCancel = { onCancel(entry.id) })
                if (index != entries.lastIndex) {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun QueueRow(entry: QueueEntry, onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            val statusLabel = when (entry.state) {
                JobState.PROCESSING -> "Printing…"
                else -> "Queued" + (entry.position?.let { " · #$it" } ?: "")
            }
            Text(
                text = "$statusLabel · ${elapsedSince(entry.submittedAtMs)} · ${formatBytes(entry.sizeBytes)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        if (entry.state == JobState.PENDING) {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun elapsedSince(startMs: Long): String {
    val minutes = (System.currentTimeMillis() - startMs) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h"
    }
}

```

- [ ] **Step 5: Build to confirm it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (will still fail at this point because `ActivityCard`'s signature hasn't been updated yet — that's Task 9. If building standalone here errors on `ActivityCard(entries = activityEntries, onRetry = onRetryJob)` not matching, that's expected; proceed to Task 9 before the next full build check.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/ui/PrintServerApp.kt
git commit -m "feat: add QueueCard showing live PENDING/PROCESSING jobs"
```

---

### Task 9: Retry button on failed `ActivityRow` entries

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/ui/PrintServerApp.kt`

- [ ] **Step 1: Add `onRetry` param to `ActivityCard` and `ActivityRow`**

`ActivityCard` currently reads:

```kotlin
@Composable
private fun ActivityCard(entries: List<ActivityEntry>) {
    var expandedId by remember { mutableStateOf<Int?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                text = "Recent Activity",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (entries.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Print,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No print jobs yet this session.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        ActivityRow(
                            entry = entry,
                            expanded = expandedId == entry.id,
                            onClick = { expandedId = if (expandedId == entry.id) null else entry.id }
                        )
                    }
                }
            }
        }
    }
}
```

Replace with:

```kotlin
@Composable
private fun ActivityCard(entries: List<ActivityEntry>, onRetry: (Int) -> Unit) {
    var expandedId by remember { mutableStateOf<Int?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                text = "Recent Activity",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (entries.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Print,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No print jobs yet this session.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        ActivityRow(
                            entry = entry,
                            expanded = expandedId == entry.id,
                            onClick = { expandedId = if (expandedId == entry.id) null else entry.id },
                            onRetry = { entry.jobId?.let(onRetry) },
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Add the Retry button to `ActivityRow`**

`ActivityRow` currently reads:

```kotlin
@Composable
private fun ActivityRow(entry: ActivityEntry, expanded: Boolean, onClick: () -> Unit) {
    val (dotColor, label) = when (entry.status) {
        ActivityStatus.PRINTED -> Color(0xFF4CAF50) to "Printed"
        ActivityStatus.PRINTING -> MaterialTheme.colorScheme.primary to "Printing…"
        ActivityStatus.FAILED -> MaterialTheme.colorScheme.error to
            ("Failed" + (entry.failureReason?.let { " · $it" } ?: ""))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Print,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Box(modifier = Modifier.size(8.dp).background(dotColor, RoundedCornerShape(4.dp)))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            Text(
                text = relativeTime(entry),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .padding(start = 48.dp, top = 8.dp)
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                entry.clientAddress?.let { DetailLine("Client", it) }
                entry.sizeBytes?.let { DetailLine("Size", formatBytes(it)) }
                entry.completedAt?.let { DetailLine("Duration", "%.1fs".format((it - entry.startedAt) / 1000.0)) }
                entry.format?.let { DetailLine("Format", it) }
            }
        }
    }
}
```

Replace with:

```kotlin
@Composable
private fun ActivityRow(entry: ActivityEntry, expanded: Boolean, onClick: () -> Unit, onRetry: () -> Unit) {
    val (dotColor, label) = when (entry.status) {
        ActivityStatus.PRINTED -> Color(0xFF4CAF50) to "Printed"
        ActivityStatus.PRINTING -> MaterialTheme.colorScheme.primary to "Printing…"
        ActivityStatus.FAILED -> MaterialTheme.colorScheme.error to
            ("Failed" + (entry.failureReason?.let { " · $it" } ?: ""))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Print,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Box(modifier = Modifier.size(8.dp).background(dotColor, RoundedCornerShape(4.dp)))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            Text(
                text = relativeTime(entry),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        if (entry.status == ActivityStatus.FAILED && entry.jobId != null) {
            TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) {
                Text("Retry")
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .padding(start = 48.dp, top = 8.dp)
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                entry.clientAddress?.let { DetailLine("Client", it) }
                entry.sizeBytes?.let { DetailLine("Size", formatBytes(it)) }
                entry.completedAt?.let { DetailLine("Duration", "%.1fs".format((it - entry.startedAt) / 1000.0)) }
                entry.format?.let { DetailLine("Format", it) }
            }
        }
    }
}
```

- [ ] **Step 3: Build to confirm it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/ui/PrintServerApp.kt
git commit -m "feat: add Retry button to failed activity-feed entries"
```

---

### Task 10: Wire `MainActivity` and verify end-to-end

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/MainActivity.kt`

- [ ] **Step 1: Collect `QueueState.entries` and pass callbacks**

`MainActivity.kt`'s imports and `onCreate` currently read:

```kotlin
import dev.jaspreet.printserver.activity.ActivityLog
import dev.jaspreet.printserver.service.ServerService
import dev.jaspreet.printserver.service.ServerState
import dev.jaspreet.printserver.ui.PrintServerApp
import dev.jaspreet.printserver.ui.theme.PrintServerTheme
import dev.jaspreet.printserver.usb.UsbPrinterManager
```

and

```kotlin
        setContent {
            val status by ServerState.status.collectAsStateWithLifecycle()
            val activityEntries by ActivityLog.entries.collectAsStateWithLifecycle()

            PrintServerTheme {
                PrintServerApp(
                    status = status,
                    activityEntries = activityEntries,
                    onStartServerClick = { startServerIfPermitted() },
                    onStopServerClick = { stopService(Intent(this, ServerService::class.java)) },
                    onBatteryExemptionClick = { requestBatteryExemption() }
                )
            }
        }
```

Replace the import block with:

```kotlin
import dev.jaspreet.printserver.activity.ActivityLog
import dev.jaspreet.printserver.jobs.QueueState
import dev.jaspreet.printserver.service.ServerService
import dev.jaspreet.printserver.service.ServerState
import dev.jaspreet.printserver.ui.PrintServerApp
import dev.jaspreet.printserver.ui.theme.PrintServerTheme
import dev.jaspreet.printserver.usb.UsbPrinterManager
```

Replace the `setContent` block with:

```kotlin
        setContent {
            val status by ServerState.status.collectAsStateWithLifecycle()
            val activityEntries by ActivityLog.entries.collectAsStateWithLifecycle()
            val queueEntries by QueueState.entries.collectAsStateWithLifecycle()

            PrintServerTheme {
                PrintServerApp(
                    status = status,
                    activityEntries = activityEntries,
                    queueEntries = queueEntries,
                    onStartServerClick = { startServerIfPermitted() },
                    onStopServerClick = { stopService(Intent(this, ServerService::class.java)) },
                    onBatteryExemptionClick = { requestBatteryExemption() },
                    onCancelJob = { id -> QueueState.cancel(id) },
                    onRetryJob = { id ->
                        if (QueueState.retry(id) == null) {
                            Toast.makeText(this, "Job no longer available to retry", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
```

- [ ] **Step 2: Build the full debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run the full JVM unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (including the new `JobQueueTest`, `ActivityLogTest`, and `QueueStateTest` additions from Tasks 2–6)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/MainActivity.kt
git commit -m "feat: wire queue visibility and retry/cancel into MainActivity"
```

- [ ] **Step 5: Manual hardware verification**

Install on a device with the Tier 2 printer connected (`./gradlew :app:installDebug`), per the general flow in `docs/superpowers/testing/hardware-smoke-checklist.md`:

1. Submit a print job that will fail (e.g. a truncated/corrupt PDF from a client, or trigger any existing failure path) — confirm it shows FAILED in Recent Activity with a Retry button, and confirm tapping Retry causes the job to run again (watch it reappear in the Print Queue section, then complete or fail again).
2. Submit 2+ real jobs back-to-back from a client — confirm the Print Queue card shows both, with correct "Queued · #1" / "Printing…" labels, elapsed time, and size.
3. Tap Cancel on a queued (not yet printing) job — confirm it disappears from Print Queue and shows as Failed in Recent Activity.
4. Confirm a completed job's spool file doesn't linger (check app's cache/spool dir size stays bounded across several jobs) — this validates Task 2's "only ABORTED jobs are retained" scoping didn't accidentally widen to all terminal states.
