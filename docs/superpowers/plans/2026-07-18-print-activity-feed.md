# Print Activity Feed Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a session-only "Recent Activity" feed to the main screen showing print jobs as they're received/printed/fail, for both Tier 1 (relay) and Tier 2 (native rendering).

**Architecture:** A new `ActivityLog` singleton (mirrors the existing `ServerState` pattern) holds an in-memory, capped `StateFlow<List<ActivityEntry>>`. Tier 2's `JobQueue` gains a state-change callback that `ServerService` translates into activity entries. Tier 1's `IppRelayServer` peeks the first 4 bytes of IPP requests (version + operation-id only, never touching document bytes) to classify Print-Job/Send-Document/Create-Job calls without breaking the relay's zero-buffering byte-streaming design. A new `ActivityCard` composable renders the feed on the main screen.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), kotlinx.coroutines StateFlow, JUnit4 (JVM unit tests, no Robolectric/Android dependencies in this test tree).

**Spec:** `docs/superpowers/specs/2026-07-18-print-activity-feed-design.md`

**Correction vs. spec:** the spec suggested Tier 1 entries could show a `format`. In practice IPP-over-HTTP wraps the whole request (attributes + document) under one `Content-Type: application/ipp` — the document's actual PDL/format is inside the IPP attribute group we're deliberately not parsing. So Tier 1 entries leave `format` as `null`; only Tier 2 (which already parses `document-format` for its own pipeline) populates it. This plan implements that corrected behavior.

---

### Task 1: `ActivityEntry` / `ActivityLog` data model

**Files:**
- Create: `app/src/main/java/dev/jaspreet/printserver/activity/ActivityLog.kt`
- Test: `app/src/test/java/dev/jaspreet/printserver/activity/ActivityLogTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.activity.ActivityLogTest"`
Expected: FAIL — `ActivityLog`/`ActivityStatus` unresolved references (files don't exist yet).

- [ ] **Step 3: Write the implementation**

```kotlin
package dev.jaspreet.printserver.activity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

enum class ActivityStatus { PRINTING, PRINTED, FAILED }

data class ActivityEntry(
    val id: Int,
    val tier: Int,                     // 1 or 2
    val name: String,                  // Tier 2: client-sent job name; Tier 1: "Print request"
    val status: ActivityStatus,
    val startedAt: Long,
    val completedAt: Long? = null,
    val clientAddress: String? = null,
    val sizeBytes: Long? = null,
    val format: String? = null,        // Tier 2 only — see plan header for why Tier 1 can't populate this
    val failureReason: String? = null,
)

/**
 * Session-only, in-memory print activity feed. Mirrors the ServerState singleton
 * pattern (service/ServerState.kt) — a plain object with a MutableStateFlow, no DI.
 */
object ActivityLog {
    private const val MAX_ENTRIES = 200

    private val nextId = AtomicInteger(1)
    private val _entries = MutableStateFlow<List<ActivityEntry>>(emptyList())
    val entries: StateFlow<List<ActivityEntry>> = _entries.asStateFlow()

    /** Creates a new entry (newest-first) and returns its id for later [update] calls. */
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

    /** No-op if [id] isn't present (e.g. it already scrolled off the cap). */
    fun update(id: Int, transform: (ActivityEntry) -> ActivityEntry) {
        _entries.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    fun clear() { _entries.value = emptyList() }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.activity.ActivityLogTest"`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/activity/ActivityLog.kt app/src/test/java/dev/jaspreet/printserver/activity/ActivityLogTest.kt
git commit -m "feat: add ActivityLog session-only print activity store"
```

---

### Task 2: `JobQueue` state-change callback + client address on `PrintJob`

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/jobs/PrintJob.kt`
- Modify: `app/src/main/java/dev/jaspreet/printserver/jobs/JobQueue.kt`
- Modify: `app/src/test/java/dev/jaspreet/printserver/jobs/JobQueueTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `JobQueueTest.kt` (new test, existing tests untouched):

```kotlin
    @Test
    fun `onJobStateChanged fires PENDING then PROCESSING then COMPLETED, in order`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val done = CountDownLatch(1)
        val states = java.util.Collections.synchronizedList(mutableListOf<JobState>())
        val q = JobQueue(
            FakeRenderingPipeline("PCL!".toByteArray()), { printer },
            onJobStateChanged = { job -> states += job.state },
        ) { done.countDown() }
        queue = q
        q.submit(pdf(), "test-doc", clientAddress = "192.168.1.42")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(JobState.PENDING, JobState.PROCESSING, JobState.COMPLETED), states)
    }

    @Test
    fun `submitted job carries the client address through to PrintJob`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val done = CountDownLatch(1)
        val q = JobQueue(FakeRenderingPipeline(), { printer }) { done.countDown() }
        queue = q
        val id = q.submit(pdf(), "test-doc", clientAddress = "10.0.0.5")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("10.0.0.5", q.get(id)!!.clientAddress)
    }

    @Test
    fun `cancel fires onJobStateChanged with CANCELED before deleting the spool file`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingPipeline = object : dev.jaspreet.printserver.render.RenderingPipeline {
            override fun render(document: File, output: File, format: String) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                output.writeBytes("X".toByteArray())
            }
        }
        val states = java.util.Collections.synchronizedList(mutableListOf<JobState>())
        val q = JobQueue(
            blockingPipeline, { FakePrinterTransport { ByteArray(0) } },
            onJobStateChanged = { job -> states += job.state },
        ) {}
        queue = q
        q.submit(pdf(), "job-a")
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val second = q.submit(pdf(), "job-b")
        assertTrue(q.cancel(second))
        release.countDown()
        assertTrue(states.contains(JobState.CANCELED))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.jobs.JobQueueTest"`
Expected: FAIL to compile — `onJobStateChanged` param and `clientAddress` param don't exist yet.

- [ ] **Step 3: Write the implementation**

Edit `PrintJob.kt` — add `clientAddress`:

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
}
```

Edit `JobQueue.kt` constructor — add `onJobStateChanged` as a new param **before** the existing `onJobFinished` (which must stay last so existing trailing-lambda call sites in `JobQueueTest` keep compiling unchanged):

```kotlin
class JobQueue(
    private val pipeline: RenderingPipeline,
    private val transportProvider: () -> UsbTransport,
    private val renderTimeoutMs: Long = 120_000,
    private val onPipelineStuck: () -> Unit = {},
    /** Fired on every PrintJob state transition (PENDING at submit/reserve, PROCESSING at
     *  render start, terminal state at the end) — for live activity-feed UI. Unlike
     *  [onJobFinished], this also fires for CANCELED and fires multiple times per job. */
    private val onJobStateChanged: (PrintJob) -> Unit = {},
    private val onJobFinished: (PrintJob) -> Unit = {},
) {
```

Update `submit()` and `reserve()` to accept `clientAddress` and fire the new callback:

```kotlin
    fun submit(spoolFile: File, name: String, format: String = "application/pdf", clientAddress: String? = null): Int {
        val job = PrintJob(nextId.getAndIncrement(), name, spoolFile, format, clientAddress)
        jobs[job.id] = job
        pending.put(job)
        onJobStateChanged(job)
        return job.id
    }

    fun reserve(spoolFile: File, name: String, format: String = "application/pdf", clientAddress: String? = null): Int {
        val job = PrintJob(nextId.getAndIncrement(), name, spoolFile, format, clientAddress)
        jobs[job.id] = job
        onJobStateChanged(job)
        return job.id
    }
```

Update `process()` to fire on entering PROCESSING (right after the existing `synchronized(job) { ...; job.state = JobState.PROCESSING }` block):

```kotlin
    private fun process(job: PrintJob) {
        synchronized(job) {
            if (job.state == JobState.CANCELED) return
            job.state = JobState.PROCESSING
        }
        onJobStateChanged(job)
        val rendered = File(job.spoolFile.parentFile!!, "${job.spoolFile.name}.out")
        try {
            checkFreeSpace(job.spoolFile.parentFile)
            val future = renderExecutor.submit { pipeline.render(job.spoolFile, rendered, job.format) }
            try {
                future.get(renderTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                Log.e(TAG, "Job ${job.id} (${job.name}) render exceeded ${renderTimeoutMs}ms — poisoning queue")
                poisoned = true
                job.state = JobState.ABORTED
                job.stateReason = "render-timeout"
                onJobStateChanged(job)
                onPipelineStuck()
                return
            }
            writeToUsb(rendered)
            job.state = JobState.COMPLETED
        } catch (e: Exception) {
            Log.e(TAG, "Job ${job.id} (${job.name}) failed", e)
            job.state = JobState.ABORTED
            job.stateReason = "document-format-error"
        } finally {
            job.spoolFile.delete()
            rendered.delete()
            onJobStateChanged(job)
            onJobFinished(job)
        }
    }
```

Update `failWithoutRendering()` similarly:

```kotlin
    private fun failWithoutRendering(job: PrintJob, reason: String) {
        synchronized(job) {
            if (job.state == JobState.CANCELED) return
            job.state = JobState.ABORTED
        }
        job.stateReason = reason
        job.spoolFile.delete()
        onJobStateChanged(job)
        onJobFinished(job)
    }
```

Update `cancel()` to fire the callback before deleting the spool file (so a listener can still read `job.spoolFile` if needed), without calling it while holding the per-job lock:

```kotlin
    fun cancel(id: Int): Boolean {
        val job = jobs[id] ?: return false
        val canceled = synchronized(job) {
            if (job.state != JobState.PENDING) return false
            job.state = JobState.CANCELED
            true
        }
        if (canceled) {
            onJobStateChanged(job)
            job.spoolFile.delete()
        }
        return canceled
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.jobs.JobQueueTest"`
Expected: PASS (all existing tests + 3 new ones, 7 total)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/jobs/PrintJob.kt app/src/main/java/dev/jaspreet/printserver/jobs/JobQueue.kt app/src/test/java/dev/jaspreet/printserver/jobs/JobQueueTest.kt
git commit -m "feat: add JobQueue state-change callback and client address tracking"
```

---

### Task 3: Thread client address from `LocalIppServer` into `JobQueue`

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/ipp/LocalIppServer.kt`

- [ ] **Step 1: Edit `handleClient` to capture the client address and pass it through**

`handleClient` currently does `client.use { ... val cin = ...; val cout = ...; while (true) { ...; handleIpp(body) ... } }`. Capture the address once at the top and thread it through `handleIpp` → `printJob`/`createJob`:

```kotlin
    private fun handleClient(client: Socket) {
        client.soTimeout = 30_000
        val clientAddress = client.inetAddress?.hostAddress
        client.use {
            val cin = BufferedInputStream(client.getInputStream())
            val cout = BufferedOutputStream(client.getOutputStream())
            while (true) {
                val head = try { HttpHead.parse(cin) ?: break } catch (_: IOException) { break }
                val response = try {
                    val body = BodyReader.readAll(head, cin, maxDocumentBytes)
                    handleIpp(body, clientAddress)
                } catch (e: BodyTooLargeException) {
                    errorResponse(0, Status.clientErrorRequestEntityTooLarge)
                } catch (e: IOException) {
                    break
                } catch (e: Exception) {
                    errorResponse(0, Status.serverErrorInternalError)
                }
                val respBytes = ByteArrayOutputStream().also { IppOutputStream(it).write(response) }.toByteArray()
                cout.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\n" +
                        "Content-Length: ${respBytes.size}\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
                )
                cout.write(respBytes)
                cout.flush()
                if (response.status == Status.clientErrorRequestEntityTooLarge) break
                if (head.get("Connection")?.equals("close", ignoreCase = true) == true) break
            }
        }
    }

    private fun handleIpp(body: ByteArray, clientAddress: String?): IppPacket {
        val input = IppInputStream(ByteArrayInputStream(body))
        val request = input.readPacket()
        val document = input.readBytes()

        return when (request.code) {
            Operation.getPrinterAttributes.code -> getPrinterAttributes(request)
            Operation.validateJob.code -> IppPacket(
                Status.successfulOk, request.requestId, operationGroup(),
            )
            Operation.printJob.code -> printJob(request, document, clientAddress)
            Operation.createJob.code -> createJob(request, clientAddress)
            Operation.sendDocument.code -> sendDocument(request, document)
            Operation.closeJob.code -> closeJob(request)
            Operation.getJobAttributes.code -> jobAttributes(request)
            Operation.getJobs.code -> getJobs(request)
            Operation.cancelJob.code -> cancelJob(request)
            Operation.cancelMyJobs.code -> cancelMyJobs(request)
            Operation.identifyPrinter.code -> IppPacket(Status.successfulOk, request.requestId, operationGroup())
            else -> errorResponse(request.requestId, Status.serverErrorOperationNotSupported)
        }
    }
```

Update `printJob` and `createJob` signatures to accept and forward `clientAddress` (rest of each function body is unchanged):

```kotlin
    private fun printJob(request: IppPacket, document: ByteArray, clientAddress: String?): IppPacket {
        if (document.isEmpty()) {
            return errorResponse(request.requestId, Status.clientErrorBadRequest)
        }
        val requestedUri = request[Tag.operationAttributes]?.getValue(Types.printerUri)
        if (requestedUri == null) {
            return errorResponse(request.requestId, Status.clientErrorBadRequest)
        }
        val format = documentFormat(request)
        spoolDir.mkdirs()
        val spool = File.createTempFile("job", spoolExtension(format), spoolDir)
        spool.writeBytes(document)
        val name = request[Tag.operationAttributes]?.getValue(Types.jobName)?.value ?: "untitled"
        val jobId = jobQueue.submit(spool, name, format, clientAddress)
        val actualState = jobQueue.get(jobId)?.state ?: JobState.PENDING
        return IppPacket(
            Status.successfulOk, request.requestId,
            operationGroup(),
            groupOf(
                Tag.jobAttributes,
                Types.jobId.of(jobId),
                Types.jobUri.of(capabilities.printerUri.resolve("job/$jobId")),
                Types.jobState.of(ippState(actualState)),
                Types.jobStateReasons.of("none"),
            ),
        )
    }

    private fun createJob(request: IppPacket, clientAddress: String?): IppPacket {
        val requestedUri = request[Tag.operationAttributes]?.getValue(Types.printerUri)
        if (requestedUri == null) {
            return errorResponse(request.requestId, Status.clientErrorBadRequest)
        }
        val format = documentFormat(request)
        spoolDir.mkdirs()
        val spool = File.createTempFile("job", spoolExtension(format), spoolDir)
        val name = request[Tag.operationAttributes]?.getValue(Types.jobName)?.value ?: "untitled"
        val jobId = jobQueue.reserve(spool, name, format, clientAddress)
        return IppPacket(
            Status.successfulOk, request.requestId,
            operationGroup(),
            groupOf(
                Tag.jobAttributes,
                Types.jobId.of(jobId),
                Types.jobUri.of(capabilities.printerUri.resolve("job/$jobId")),
                Types.jobState.of(IppJobState.pending),
                Types.jobStateReasons.of("job-incoming"),
            ),
        )
    }
```

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run the full JVM unit test suite to verify nothing else broke**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (no test in this repo currently calls `handleIpp`/`printJob`/`createJob` directly — they're exercised only through the socket-level `LocalIppServer` integration path, if any test does, it must still pass unchanged since only new trailing params with no default were added as required positional/lambda-adjacent args; check `app/src/test/java/dev/jaspreet/printserver/ipp/` for any `LocalIppServerTest` and confirm)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/ipp/LocalIppServer.kt
git commit -m "feat: thread client address from LocalIppServer into JobQueue"
```

---

### Task 4: Wire Tier 2 (`ServerService`) to `ActivityLog`

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/service/ServerService.kt`

- [ ] **Step 1: Add the import and per-job activity-id map, wire `onJobStateChanged` in `startLegacyPipeline`**

Add import near the other `dev.jaspreet.printserver.*` imports (after the `discovery` imports, alphabetically before `ipp`):

```kotlin
import dev.jaspreet.printserver.activity.ActivityLog
import dev.jaspreet.printserver.activity.ActivityStatus
```

In `startLegacyPipeline` (`ServerService.kt:163-218`), replace the `JobQueue(...)` construction:

```kotlin
        val jobActivityIds = java.util.concurrent.ConcurrentHashMap<Int, Int>()
        val queue = JobQueue(
            pipeline, { transport },
            onPipelineStuck = {
                update { ServerStatus(message = "Rendering got stuck — restart the app to recover") }
                stopSelf()
            },
            onJobStateChanged = { job ->
                val status = when (job.state) {
                    dev.jaspreet.printserver.jobs.JobState.PENDING,
                    dev.jaspreet.printserver.jobs.JobState.PROCESSING -> ActivityStatus.PRINTING
                    dev.jaspreet.printserver.jobs.JobState.COMPLETED -> ActivityStatus.PRINTED
                    dev.jaspreet.printserver.jobs.JobState.ABORTED,
                    dev.jaspreet.printserver.jobs.JobState.CANCELED -> ActivityStatus.FAILED
                }
                val activityId = jobActivityIds.getOrPut(job.id) {
                    ActivityLog.record(
                        tier = 2, name = job.name, status = status,
                        clientAddress = job.clientAddress, format = job.format,
                    )
                }
                ActivityLog.update(activityId) { e ->
                    e.copy(
                        status = status,
                        sizeBytes = if (job.state == dev.jaspreet.printserver.jobs.JobState.PENDING ||
                            job.state == dev.jaspreet.printserver.jobs.JobState.PROCESSING
                        ) job.spoolFile.length() else e.sizeBytes,
                        completedAt = if (status != ActivityStatus.PRINTING) System.currentTimeMillis() else e.completedAt,
                        failureReason = if (status == ActivityStatus.FAILED) job.stateReason else e.failureReason,
                    )
                }
            },
        ).also { jobQueue = it }
```

(The fully-qualified `JobState` references avoid adding a same-named import that could collide if this file ever imports another `JobState`-like enum; feel free to add a normal `import dev.jaspreet.printserver.jobs.JobState` at the top instead and drop the qualification, either is fine — this file doesn't currently import it.)

- [ ] **Step 2: Clear the log when the server stops**

In `onDestroy()` (`ServerService.kt:238-244`):

```kotlin
    override fun onDestroy() {
        stopPipeline()
        runCatching { unregisterReceiver(detachReceiver) }
        while (wakeLock?.isHeld == true) wakeLock?.release()
        update { ServerStatus() }
        ActivityLog.clear()
        super.onDestroy()
    }
```

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/service/ServerService.kt
git commit -m "feat: record Tier 2 print jobs into ActivityLog"
```

---

### Task 5: Tier 1 IPP-operation peek + `ActivityLog` entries in `IppRelayServer`

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/relay/IppRelayServer.kt`
- Modify: `app/src/test/java/dev/jaspreet/printserver/relay/IppRelayServerTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `IppRelayServerTest.kt`:

```kotlin
import dev.jaspreet.printserver.activity.ActivityLog
import dev.jaspreet.printserver.activity.ActivityStatus
import org.junit.Before
```

```kotlin
    @Before
    fun resetActivityLog() {
        ActivityLog.clear()
    }

    @Test
    fun `records a PRINTED activity entry for a Print-Job request`() {
        val port = startServer()
        // IPP packet header: version 1.1, operation-id 0x0002 (Print-Job), request-id 1,
        // followed by end-of-attributes-tag (0x03) — a minimal-but-parseable-length packet.
        // IppRelayServer only reads the first 4 bytes; it never decodes this as IPP.
        val body = byteArrayOf(0x01, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x01, 0x03)
        val conn = URL("http://127.0.0.1:$port/ipp/print").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/ipp")
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }
        assertEquals(200, conn.responseCode)
        conn.inputStream.readBytes()

        val entries = ActivityLog.entries.value
        assertEquals(1, entries.size)
        assertEquals(1, entries[0].tier)
        assertEquals("Print request", entries[0].name)
        assertEquals(ActivityStatus.PRINTED, entries[0].status)
        assertEquals(body.size.toLong(), entries[0].sizeBytes)
        assertEquals("127.0.0.1", entries[0].clientAddress)
    }

    @Test
    fun `does not record an entry for a non-print IPP operation`() {
        val port = startServer()
        // operation-id 0x000B = Get-Printer-Attributes.
        val body = byteArrayOf(0x01, 0x01, 0x00, 0x0B, 0x00, 0x00, 0x00, 0x01, 0x03)
        val conn = URL("http://127.0.0.1:$port/ipp/print").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/ipp")
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }
        assertEquals(200, conn.responseCode)
        conn.inputStream.readBytes()

        assertTrue(ActivityLog.entries.value.isEmpty())
    }

    @Test
    fun `does not record an entry for a non-IPP request`() {
        val port = startServer()
        val conn = URL("http://127.0.0.1:$port/ipp/print").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(3)
        conn.outputStream.use { it.write("abc".toByteArray()) }
        assertEquals(200, conn.responseCode)
        conn.inputStream.readBytes()

        assertTrue(ActivityLog.entries.value.isEmpty())
    }

    @Test
    fun `peeking the operation id does not alter bytes forwarded to the printer`() {
        val body = byteArrayOf(0x01, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x01, 0x03, 0x41, 0x42)
        val printer = FakePrinterTransport { req ->
            val len = "len=${req.size}"
            ("HTTP/1.1 200 OK\r\nContent-Length: ${len.length}\r\n\r\n$len").toByteArray(Charsets.ISO_8859_1)
        }
        val s = IppRelayServer(port = 0, pool = ChannelPool(listOf(printer)))
        s.start(bindAddress = null)
        server = s

        val conn = URL("http://127.0.0.1:${s.actualPort}/ipp/print").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/ipp")
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }
        assertEquals(200, conn.responseCode)
        val respBody = conn.inputStream.readBytes().toString(Charsets.ISO_8859_1)

        // The printer must have received exactly body.size bytes — proving the 4 peeked
        // bytes were re-prepended, not dropped.
        assertEquals("len=${body.size}", respBody)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.relay.IppRelayServerTest"`
Expected: FAIL — new tests fail (no activity entries recorded yet; `IppRelayServer` doesn't reference `ActivityLog`).

- [ ] **Step 3: Write the implementation**

Edit `IppRelayServer.kt`:

```kotlin
package dev.jaspreet.printserver.relay

import android.util.Log
import dev.jaspreet.printserver.activity.ActivityLog
import dev.jaspreet.printserver.activity.ActivityStatus
import dev.jaspreet.printserver.http.HttpHead
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/**
 * Accepts LAN HTTP connections and relays each transaction over a pooled
 * IPP-USB channel. Thread-per-connection: both socket and USB I/O block.
 */
class IppRelayServer(
    private val port: Int,
    private val pool: ChannelPool,
    private val monitor: ActivityMonitor = ActivityMonitor.NONE,
    private val leaseTimeoutMs: Long = 60_000,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    val actualPort: Int get() = serverSocket?.localPort ?: port

    fun start(bindAddress: InetAddress?) {
        val ss = ServerSocket(port, 50, bindAddress)
        serverSocket = ss
        executor.execute {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: IOException) { break }
                executor.execute { handleClient(client) }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = 30_000
        val clientAddress = client.inetAddress?.hostAddress
        client.use {
            val cin = BufferedInputStream(client.getInputStream())
            val cout = BufferedOutputStream(client.getOutputStream())
            while (true) {
                // Parse the head BEFORE leasing, so an idle keep-alive
                // connection never pins a printer channel.
                val head = try { HttpHead.parse(cin) ?: break } catch (_: SocketTimeoutException) { break } catch (_: IOException) { break }
                val channel = try {
                    pool.lease(leaseTimeoutMs)
                } catch (e: Exception) {
                    Log.w(TAG, "no free printer channel available, rejecting request", e)
                    writeServiceUnavailable(cout)
                    break
                }

                var relayInput: InputStream = cin
                var activityId: Int? = null
                val isIpp = head.get("Content-Type")?.startsWith("application/ipp", ignoreCase = true) == true
                if (isIpp) {
                    val (peeked, opId) = peekIppOperation(cin)
                    relayInput = peeked
                    if (opId != null && opId in PRINT_OPERATIONS) {
                        activityId = ActivityLog.record(
                            tier = 1, name = "Print request", status = ActivityStatus.PRINTING,
                            clientAddress = clientAddress,
                            sizeBytes = head.get("Content-Length")?.toLongOrNull(),
                        )
                    }
                }

                monitor.begin()
                try {
                    HttpRelay.forward(head, relayInput, cout, channel)
                    pool.release(channel)
                    activityId?.let { id ->
                        ActivityLog.update(id) { it.copy(status = ActivityStatus.PRINTED, completedAt = System.currentTimeMillis()) }
                    }
                } catch (e: Exception) {
                    // Channel state unknown mid-transaction: never reuse it.
                    Log.w(TAG, "discarding channel after transaction failure", e)
                    pool.discard(channel)
                    activityId?.let { id ->
                        ActivityLog.update(id) { it.copy(status = ActivityStatus.FAILED, completedAt = System.currentTimeMillis(), failureReason = e.message) }
                    }
                    break
                } finally {
                    monitor.end()
                }
                if (head.get("Connection")?.equals("close", ignoreCase = true) == true) break
            }
        }
    }

    /**
     * Reads up to the first 4 bytes of an IPP request (version-major, version-minor,
     * operation-id) without touching anything beyond that — no attribute-group or
     * document parsing. Always returns a stream that reproduces the original byte
     * sequence exactly (the peeked bytes are re-prepended via SequenceInputStream),
     * so HttpRelay.forward's zero-buffering behavior is unaffected. Returns a null
     * operation-id if fewer than 4 bytes were available (malformed/short request —
     * let HttpRelay/the printer surface that error naturally).
     */
    private fun peekIppOperation(cin: InputStream): Pair<InputStream, Int?> {
        val peek = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = cin.read(peek, read, 4 - read)
            if (n < 0) break
            read += n
        }
        val combined = SequenceInputStream(ByteArrayInputStream(peek, 0, read), cin)
        val opId = if (read == 4) ((peek[2].toInt() and 0xFF) shl 8) or (peek[3].toInt() and 0xFF) else null
        return combined to opId
    }

    private fun writeServiceUnavailable(cout: OutputStream) {
        try {
            cout.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            cout.flush()
        } catch (_: IOException) {
            // Client already gone; nothing more we can do.
        }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: IOException) {}
        executor.shutdownNow()
    }

    private companion object {
        const val TAG = "IppRelayServer"

        // IPP operation-ids that initiate a print (Print-Job, Create-Job, Send-Document).
        // Everything else (Get-Printer-Attributes, Validate-Job, Cancel-Job, ...) stays silent.
        val PRINT_OPERATIONS = setOf(0x0002, 0x0005, 0x0006)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.relay.IppRelayServerTest"`
Expected: PASS (all existing tests + 4 new ones)

- [ ] **Step 5: Run the full relay test package to confirm `HttpRelay`/`ChannelPool` interaction is unaffected**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.relay.*"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/relay/IppRelayServer.kt app/src/test/java/dev/jaspreet/printserver/relay/IppRelayServerTest.kt
git commit -m "feat: record Tier 1 print activity via IPP operation-id peek"
```

---

### Task 6: `ActivityCard` composable

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/ui/PrintServerApp.kt`

- [ ] **Step 1: Add imports**

Add alongside the existing imports at the top of `PrintServerApp.kt`:

```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import dev.jaspreet.printserver.activity.ActivityEntry
import dev.jaspreet.printserver.activity.ActivityStatus
```

- [ ] **Step 2: Add the `ActivityCard` composable** (place it after the `PrintServerApp` function, at file scope)

```kotlin
@Composable
private fun ActivityCard(entries: List<ActivityEntry>) {
    var expandedId by remember { mutableStateOf<Int?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PureWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                text = "Recent Activity",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground
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
                        tint = MediumGray.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No print jobs yet this session.",
                        fontSize = 13.sp,
                        color = MediumGray
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

@Composable
private fun ActivityRow(entry: ActivityEntry, expanded: Boolean, onClick: () -> Unit) {
    val (dotColor, label) = when (entry.status) {
        ActivityStatus.PRINTED -> Color(0xFF4CAF50) to "Printed"
        ActivityStatus.PRINTING -> SlateBlue to "Printing…"
        ActivityStatus.FAILED -> Color(0xFFD32F2F) to
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
                    .background(LightSlate.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = Icons.Default.Print, contentDescription = null, tint = SlateBlue)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = entry.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Charcoal)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Box(modifier = Modifier.size(8.dp).background(dotColor, RoundedCornerShape(4.dp)))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = label, fontSize = 12.sp, color = MediumGray)
                }
            }
            Text(text = relativeTime(entry), fontSize = 12.sp, color = MediumGray)
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .padding(start = 48.dp, top = 8.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
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

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, fontSize = 12.sp, color = MediumGray, fontWeight = FontWeight.Medium)
        Text(text = value, fontSize = 12.sp, color = Charcoal, fontWeight = FontWeight.SemiBold)
    }
}

private fun relativeTime(entry: ActivityEntry): String {
    val elapsedMs = System.currentTimeMillis() - entry.startedAt
    val minutes = elapsedMs / 60_000
    return when {
        entry.status == ActivityStatus.PRINTING && entry.completedAt == null && minutes < 1 -> "now"
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        else -> "${minutes / 60}h ago"
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
```

- [ ] **Step 3: Insert `ActivityCard` into the active-sharing view**

In `PrintServerApp`, the "ACTIVE SHARING STATE VIEW" branch currently ends with the "Connection Specifications" card (`details.forEach { ... }` block) followed directly by the "Bottom Stop Sharing Button". Add a new `activityEntries` parameter to `PrintServerApp` and insert the card between them:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintServerApp(
    status: ServerStatus,
    activityEntries: List<ActivityEntry>,
    onStartServerClick: () -> Unit,
    onStopServerClick: () -> Unit,
    onBatteryExemptionClick: () -> Unit,
    onLicensesClick: () -> Unit
) {
```

Immediately after the "Connection Specifications" `Card { ... }` block closes (right before `// Bottom Stop Sharing Button`):

```kotlin
                    ActivityCard(entries = activityEntries)

                    // Bottom Stop Sharing Button
```

- [ ] **Step 4: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (will fail until Task 7 updates the caller in `MainActivity.kt` — if building standalone fails only on `MainActivity.kt`'s call site, that's expected and resolved next task; if it fails inside `PrintServerApp.kt` itself, fix before proceeding)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/ui/PrintServerApp.kt
git commit -m "feat: add ActivityCard to the active-sharing screen"
```

---

### Task 7: Wire `MainActivity` to `ActivityLog`

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/MainActivity.kt`

- [ ] **Step 1: Collect `ActivityLog.entries` and pass it to `PrintServerApp`**

```kotlin
import dev.jaspreet.printserver.activity.ActivityLog
```

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
                    onBatteryExemptionClick = {
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:$packageName"))
                        )
                    },
                    onLicensesClick = { showLicensesDialog() }
                )
            }
        }
```

- [ ] **Step 2: Build to verify the full module compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/MainActivity.kt
git commit -m "feat: wire ActivityLog into MainActivity and PrintServerApp"
```

---

### Task 8: Full verification pass

**Files:** none (verification only)

- [ ] **Step 1: Run the full JVM unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (including the new `ActivityLogTest`, updated `JobQueueTest`, updated `IppRelayServerTest`)

- [ ] **Step 2: Full debug build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Manual on-device smoke test** (requires a connected Android device/emulator and, ideally, a real USB printer per `docs/superpowers/testing/hardware-smoke-checklist.md`)

Run: `./gradlew :app:installDebug`

Then manually:
1. Start sharing (either tier). Confirm the "Recent Activity" card shows the empty state.
2. Send a print job from a LAN client. Confirm a row appears showing "Printing…", then transitions to "Printed" (Tier 2) once rendering completes, or shows "Printed" shortly after the relay completes (Tier 1).
3. Tap the row — confirm it expands to show client IP / size / duration (Tier 2 also shows format).
4. Tap again — confirms it collapses.
5. Send a deliberately malformed/oversized job if feasible, or disconnect the printer mid-job, to confirm a "Failed" row appears with a reason.
6. Stop sharing, start again — confirm the activity list is empty again (session-only, per spec).

- [ ] **Step 4: No commit for this task** — it's verification only. If any step surfaces a bug, fix it in the relevant earlier task's files and commit that fix separately with a `fix:` message.
