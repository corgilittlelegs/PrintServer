# Tier 2 Print Options (Quality, Color/Mono) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a Tier 2 (on-device rendering) client's `print-quality` and `print-color-mode` IPP job-template attributes actually control the rendered output — quality maps to the PPD's `OutputMode` (and drives Ghostscript/hpcups resolution), color/mono maps to the PPD's `ColorModel`.

**Architecture:** `LocalIppServer` parses and clamps the two attributes from the client's request into new `PrintQuality`/`ColorMode` enums (`jobs` package, IPP-agnostic). They ride on `PrintJob` through `JobQueue` into `RenderingPipeline.render()`, which gains two new parameters. `NativeRenderingPipeline` resolves quality to a dpi (driving both Ghostscript's `-r` flag and the hpcups raster header) and builds a CUPS-style options string (`"ColorModel=... OutputMode=..."`) passed into `HpcupsNative`, which threads it into `hpcupsjni.cpp`'s `run_hpcups()` — currently hardcoded to an empty options string.

**Tech Stack:** Kotlin, JIPP (`com.hp.jipp`), JNI/C++ (`hpcupsjni.cpp`), NDK/CMake native build.

Spec: `docs/superpowers/specs/2026-07-18-tier2-print-options-design.md`

**Note on plan independence:** This plan and `docs/superpowers/plans/2026-07-18-queue-visibility-retry-cancel.md` are independent sub-projects that both touch `PrintJob.kt` and `JobQueue.kt`. Every task below shows the file's content as it exists in the current, unmodified repo. If the queue-visibility-retry-cancel plan has already been executed first, `PrintJob.kt` will already have `submittedAtMs`/`retryOf` fields and `JobQueue.retry()` will already exist — in that case, merge this plan's additions in alongside the existing ones (add fields/params next to what's there) rather than reverting anything. Task 2 and Task 3 call this out explicitly at the point it matters.

---

### Task 1: `PrintQuality` and `ColorMode` enums

**Files:**
- Create: `app/src/main/java/dev/jaspreet/printserver/jobs/PrintOptions.kt`

No test file — these are plain enums with no behavior; correctness is exercised through the tasks that consume them.

- [ ] **Step 1: Create the file**

```kotlin
package dev.jaspreet.printserver.jobs

/** Maps to the bundled PPD's OutputMode: DRAFT→FastDraft(300dpi), NORMAL→Normal(600dpi),
 *  HIGH→Best(600dpi). The PPD's fourth mode, Photo(1200dpi), has no standard IPP
 *  print-quality value to map from and is intentionally unreachable. */
enum class PrintQuality { DRAFT, NORMAL, HIGH }

/** Maps to the bundled PPD's ColorModel: COLOR→RGB, MONOCHROME→KGray. */
enum class ColorMode { COLOR, MONOCHROME }
```

- [ ] **Step 2: Build to confirm it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/jobs/PrintOptions.kt
git commit -m "feat: add PrintQuality and ColorMode enums"
```

---

### Task 2: `PrintJob` carries quality and color mode

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/jobs/PrintJob.kt`

- [ ] **Step 1: Add the two constructor parameters**

`PrintJob.kt` currently reads (if Task 1 of the queue-visibility-retry-cancel plan has already run, this file will also have `submittedAtMs`/`retryOf` — see the note below):

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

Replace with:

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
    val quality: PrintQuality = PrintQuality.NORMAL,
    val colorMode: ColorMode = ColorMode.COLOR,
) {
    @Volatile var state: JobState = JobState.PENDING
    @Volatile var stateReason: String = "none"
}
```

**If the other plan already added `submittedAtMs`/`retryOf`:** add `quality`/`colorMode` as two more constructor parameters after `clientAddress` (order doesn't matter for named-arg call sites, but keep it after `clientAddress` and before the `submittedAtMs`/`retryOf` block for readability) — don't remove the existing fields.

- [ ] **Step 2: Build to confirm it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL — this alone won't break anything since both new params have defaults.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/jobs/PrintJob.kt
git commit -m "feat: add quality and colorMode to PrintJob"
```

---

### Task 3: Thread quality/colorMode through `JobQueue`

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/jobs/JobQueue.kt`
- Modify: `app/src/test/java/dev/jaspreet/printserver/jobs/JobQueueTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `JobQueueTest.kt`:

```kotlin
    @Test
    fun `submit passes quality and color mode through to the rendering pipeline`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val done = CountDownLatch(1)
        val pipeline = FakeRenderingPipeline("PCL!".toByteArray())
        val q = JobQueue(pipeline, { printer }) { done.countDown() }
        queue = q
        q.submit(pdf(), "test-doc", quality = PrintQuality.HIGH, colorMode = ColorMode.MONOCHROME)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(PrintQuality.HIGH), pipeline.qualities)
        assertEquals(listOf(ColorMode.MONOCHROME), pipeline.colorModes)
    }

    @Test
    fun `submit defaults to NORMAL quality and COLOR mode when not specified`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val done = CountDownLatch(1)
        val pipeline = FakeRenderingPipeline("PCL!".toByteArray())
        val q = JobQueue(pipeline, { printer }) { done.countDown() }
        queue = q
        q.submit(pdf(), "test-doc")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(PrintQuality.NORMAL), pipeline.qualities)
        assertEquals(listOf(ColorMode.COLOR), pipeline.colorModes)
    }
```

- [ ] **Step 2: Run to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.jobs.JobQueueTest"`
Expected: FAIL — `FakeRenderingPipeline` has no `qualities`/`colorModes` properties yet, and `submit()` has no `quality`/`colorMode` params (compile errors). This will keep failing to compile until Task 6 (Step 3) updates `FakeRenderingPipeline`; that's expected — come back and rerun this exact command after Task 6.

- [ ] **Step 3: Update `submit()`, `reserve()`, and `process()`**

`JobQueue.kt`'s `submit()` currently reads:

```kotlin
    fun submit(spoolFile: File, name: String, format: String = "application/pdf", clientAddress: String? = null): Int {
        val job = PrintJob(nextId.getAndIncrement(), name, spoolFile, format, clientAddress)
        jobs[job.id] = job
        pending.put(job)
        onJobStateChanged(job)
        evictOldTerminalJobs()
        return job.id
    }
```

Replace with:

```kotlin
    fun submit(
        spoolFile: File, name: String, format: String = "application/pdf", clientAddress: String? = null,
        quality: PrintQuality = PrintQuality.NORMAL, colorMode: ColorMode = ColorMode.COLOR,
    ): Int {
        val job = PrintJob(nextId.getAndIncrement(), name, spoolFile, format, clientAddress, quality, colorMode)
        jobs[job.id] = job
        pending.put(job)
        onJobStateChanged(job)
        evictOldTerminalJobs()
        return job.id
    }
```

`reserve()` currently reads:

```kotlin
    fun reserve(spoolFile: File, name: String, format: String = "application/pdf", clientAddress: String? = null): Int {
        val job = PrintJob(nextId.getAndIncrement(), name, spoolFile, format, clientAddress)
        jobs[job.id] = job
        onJobStateChanged(job)
        evictOldTerminalJobs()
        return job.id
    }
```

Replace with:

```kotlin
    fun reserve(
        spoolFile: File, name: String, format: String = "application/pdf", clientAddress: String? = null,
        quality: PrintQuality = PrintQuality.NORMAL, colorMode: ColorMode = ColorMode.COLOR,
    ): Int {
        val job = PrintJob(nextId.getAndIncrement(), name, spoolFile, format, clientAddress, quality, colorMode)
        jobs[job.id] = job
        onJobStateChanged(job)
        evictOldTerminalJobs()
        return job.id
    }
```

In `process()`, this line:

```kotlin
            val future = renderExecutor.submit { pipeline.render(job.spoolFile, rendered, job.format) }
```

Replace with:

```kotlin
            val future = renderExecutor.submit {
                pipeline.render(job.spoolFile, rendered, job.format, job.quality, job.colorMode)
            }
```

**If the other plan already added `JobQueue.retry()`:** it calls `submit(copy, job.name, job.format, job.clientAddress)` — update that call to also pass `job.quality, job.colorMode` so a retried job keeps the original job's options:
`submit(copy, job.name, job.format, job.clientAddress, job.quality, job.colorMode)`.

- [ ] **Step 4: Run the tests to confirm they pass**

This requires Task 6 (the `RenderingPipeline` interface change) to be done first — `FakeRenderingPipeline` won't have `qualities`/`colorModes` until then. Skip running this class standalone for now; it's covered by Task 9's full suite run.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/jobs/JobQueue.kt app/src/test/java/dev/jaspreet/printserver/jobs/JobQueueTest.kt
git commit -m "feat: thread quality/colorMode through JobQueue.submit/reserve/process"
```

---

### Task 4: Widen `PrinterCapabilities`' advertised print-quality

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/ipp/PrinterCapabilities.kt`
- Modify: `app/src/test/java/dev/jaspreet/printserver/ipp/PrinterCapabilitiesTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `PrinterCapabilitiesTest.kt`:

```kotlin
    @Test
    fun `advertises draft normal and high print quality`() {
        val group = caps.asPrinterAttributes()
        assertEquals(
            listOf(
                com.hp.jipp.model.PrintQuality.draft,
                com.hp.jipp.model.PrintQuality.normal,
                com.hp.jipp.model.PrintQuality.high,
            ),
            group.getValues(Types.printQualitySupported),
        )
        assertEquals(com.hp.jipp.model.PrintQuality.normal, group.getValue(Types.printQualityDefault))
    }
```

- [ ] **Step 2: Run to confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.ipp.PrinterCapabilitiesTest"`
Expected: FAIL — currently `printQualitySupported` only advertises `[normal]`.

- [ ] **Step 3: Widen `printQualitySupported`**

In `PrinterCapabilities.kt`, this line:

```kotlin
        Types.printQualityDefault.of(PrintQuality.normal),
        Types.printQualitySupported.of(PrintQuality.normal),
```

Replace with:

```kotlin
        Types.printQualityDefault.of(PrintQuality.normal),
        Types.printQualitySupported.of(PrintQuality.draft, PrintQuality.normal, PrintQuality.high),
```

(`PrintQuality` here is already `com.hp.jipp.model.PrintQuality`, imported at the top of this file — no import changes needed.)

- [ ] **Step 4: Run the test to confirm it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.ipp.PrinterCapabilitiesTest"`
Expected: PASS (all tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/ipp/PrinterCapabilities.kt app/src/test/java/dev/jaspreet/printserver/ipp/PrinterCapabilitiesTest.kt
git commit -m "feat: advertise draft/normal/high print-quality support"
```

---

### Task 5: Parse and clamp print-quality / print-color-mode in `LocalIppServer`

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/ipp/LocalIppServer.kt`
- Modify: `app/src/test/java/dev/jaspreet/printserver/ipp/LocalIppServerTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `LocalIppServerTest.kt`:

```kotlin
    @Test
    fun `print-job resolves print-quality and print-color-mode from the request`() {
        val port = start()
        val request = IppPacket(
            Operation.printJob, 30,
            operationGroup(),
            groupOf(
                Tag.jobAttributes,
                Types.printQuality.of(com.hp.jipp.model.PrintQuality.high),
                Types.printColorMode.of("monochrome"),
            ),
        )
        val resp = ipp(port, request, "%PDF".toByteArray())
        val jobId = resp[Tag.jobAttributes]!!.getValue(Types.jobId)!!
        val job = queue!!.get(jobId)!!
        assertEquals(dev.jaspreet.printserver.jobs.PrintQuality.HIGH, job.quality)
        assertEquals(dev.jaspreet.printserver.jobs.ColorMode.MONOCHROME, job.colorMode)
    }

    @Test
    fun `print-job defaults to NORMAL quality and the printer's default color when attrs are absent`() {
        val port = start()
        val resp = ipp(port, IppPacket(Operation.printJob, 31, operationGroup()), "%PDF".toByteArray())
        val jobId = resp[Tag.jobAttributes]!!.getValue(Types.jobId)!!
        val job = queue!!.get(jobId)!!
        assertEquals(dev.jaspreet.printserver.jobs.PrintQuality.NORMAL, job.quality)
        assertEquals(dev.jaspreet.printserver.jobs.ColorMode.COLOR, job.colorMode) // deskJet2300 supports color
    }

    @Test
    fun `print-job clamps an unrecognized print-color-mode to the printer's default`() {
        val port = start()
        val request = IppPacket(
            Operation.printJob, 32,
            operationGroup(),
            groupOf(Tag.jobAttributes, Types.printColorMode.of("sepia")),
        )
        val resp = ipp(port, request, "%PDF".toByteArray())
        val jobId = resp[Tag.jobAttributes]!!.getValue(Types.jobId)!!
        assertEquals(dev.jaspreet.printserver.jobs.ColorMode.COLOR, queue!!.get(jobId)!!.colorMode)
    }

    @Test
    fun `print-job clamps a color request to monochrome on a monochrome-only printer`() {
        val monoCaps = PrinterCapabilities(
            makeAndModel = "Mono Test Printer",
            formats = listOf("application/pdf"),
            color = false,
            printerUri = URI.create("ipp://127.0.0.1:0/ipp/print"),
            uuid = java.util.UUID.randomUUID(),
        )
        val port = start(capabilities = monoCaps)
        val request = IppPacket(
            Operation.printJob, 33,
            operationGroup(),
            groupOf(Tag.jobAttributes, Types.printColorMode.of("color")),
        )
        val resp = ipp(port, request, "%PDF".toByteArray())
        val jobId = resp[Tag.jobAttributes]!!.getValue(Types.jobId)!!
        assertEquals(dev.jaspreet.printserver.jobs.ColorMode.MONOCHROME, queue!!.get(jobId)!!.colorMode)
    }
```

Also update the `start()` helper to accept capabilities, so the monochrome-only test above can supply its own. `start()` currently reads:

```kotlin
    private fun start(pipeline: dev.jaspreet.printserver.render.RenderingPipeline = FakeRenderingPipeline()): Int {
        val q = JobQueue(pipeline, { FakePrinterTransport { ByteArray(0) } })
        queue = q
        val caps = PrinterCapabilities.deskJet2300(URI.create("ipp://127.0.0.1:0/ipp/print"))
        val s = LocalIppServer(port = 0, capabilities = caps, jobQueue = q, spoolDir = createTempDir())
        s.start(bindAddress = null)
        server = s
        return s.actualPort
    }
```

Replace with:

```kotlin
    private fun start(
        pipeline: dev.jaspreet.printserver.render.RenderingPipeline = FakeRenderingPipeline(),
        capabilities: PrinterCapabilities = PrinterCapabilities.deskJet2300(URI.create("ipp://127.0.0.1:0/ipp/print")),
    ): Int {
        val q = JobQueue(pipeline, { FakePrinterTransport { ByteArray(0) } })
        queue = q
        val s = LocalIppServer(port = 0, capabilities = capabilities, jobQueue = q, spoolDir = createTempDir())
        s.start(bindAddress = null)
        server = s
        return s.actualPort
    }
```

- [ ] **Step 2: Run to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.ipp.LocalIppServerTest"`
Expected: FAIL — `PrintJob` has no `quality`/`colorMode` properties visible from this test with real values yet resolved (compiles once Task 2/3 land, but assertions fail: job.quality is always `NORMAL`/job.colorMode always `COLOR` since `printJob()` doesn't parse the request yet).

- [ ] **Step 3: Add parsing/clamping helpers and wire them in**

In `LocalIppServer.kt`, the import block currently ends with:

```kotlin
import dev.jaspreet.printserver.http.BodyReader
import dev.jaspreet.printserver.http.BodyTooLargeException
import dev.jaspreet.printserver.http.HttpHead
import dev.jaspreet.printserver.jobs.JobQueue
import dev.jaspreet.printserver.jobs.JobState
```

Replace with:

```kotlin
import dev.jaspreet.printserver.http.BodyReader
import dev.jaspreet.printserver.http.BodyTooLargeException
import dev.jaspreet.printserver.http.HttpHead
import dev.jaspreet.printserver.jobs.ColorMode
import dev.jaspreet.printserver.jobs.JobQueue
import dev.jaspreet.printserver.jobs.JobState
import dev.jaspreet.printserver.jobs.PrintQuality
```

`printJob()` currently builds the job with:

```kotlin
        val name = request[Tag.operationAttributes]?.getValue(Types.jobName)?.value ?: "untitled"
        val jobId = jobQueue.submit(spool, name, format, clientAddress)
```

Replace with:

```kotlin
        val name = request[Tag.operationAttributes]?.getValue(Types.jobName)?.value ?: "untitled"
        val jobId = jobQueue.submit(spool, name, format, clientAddress, resolveQuality(request), resolveColorMode(request))
```

`createJob()` currently reads:

```kotlin
        val name = request[Tag.operationAttributes]?.getValue(Types.jobName)?.value ?: "untitled"
        val jobId = jobQueue.reserve(spool, name, format, clientAddress)
```

Replace with:

```kotlin
        val name = request[Tag.operationAttributes]?.getValue(Types.jobName)?.value ?: "untitled"
        val jobId = jobQueue.reserve(spool, name, format, clientAddress, resolveQuality(request), resolveColorMode(request))
```

Add these three private methods right after `documentFormat()` (which currently reads `private fun documentFormat(request: IppPacket): String = request[Tag.operationAttributes]?.getValue(Types.documentFormat) ?: "application/pdf"`):

```kotlin
    /** job-template attributes can legally arrive in either the job-attributes group
     *  (per RFC 8011) or the operation-attributes group — real clients aren't fully
     *  consistent here (jobName above is already read from operation-attributes for
     *  the same reason), so check both. */
    private fun <T : Any> IppPacket.jobTemplateValue(type: com.hp.jipp.encoding.AttributeType<T>): T? =
        this[Tag.jobAttributes]?.getValue(type) ?: this[Tag.operationAttributes]?.getValue(type)

    /** Missing or unsupported print-quality clamps to NORMAL — same silent-default
     *  pattern documentFormat() already uses for an unrecognized document format. */
    private fun resolveQuality(request: IppPacket): PrintQuality = when (request.jobTemplateValue(Types.printQuality)) {
        com.hp.jipp.model.PrintQuality.draft -> PrintQuality.DRAFT
        com.hp.jipp.model.PrintQuality.high -> PrintQuality.HIGH
        else -> PrintQuality.NORMAL
    }

    /** Missing/unrecognized print-color-mode, or "color" requested on a monochrome-only
     *  printer, clamps to the printer's actual default color mode. */
    private fun resolveColorMode(request: IppPacket): ColorMode {
        val requested = request.jobTemplateValue(Types.printColorMode)
        return when {
            requested == "monochrome" -> ColorMode.MONOCHROME
            requested == "color" && capabilities.color -> ColorMode.COLOR
            else -> if (capabilities.color) ColorMode.COLOR else ColorMode.MONOCHROME
        }
    }
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.ipp.LocalIppServerTest"`
Expected: PASS (all tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/ipp/LocalIppServer.kt app/src/test/java/dev/jaspreet/printserver/ipp/LocalIppServerTest.kt
git commit -m "feat: parse and clamp print-quality/print-color-mode job-template attrs"
```

---

### Task 6: Widen the `RenderingPipeline` interface and every implementation

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/render/RenderingPipeline.kt`
- Modify: `app/src/test/java/dev/jaspreet/printserver/render/FakeRenderingPipeline.kt`
- Modify: `app/src/test/java/dev/jaspreet/printserver/jobs/JobQueueTest.kt` (existing inline overrides)
- Modify: `app/src/test/java/dev/jaspreet/printserver/ipp/LocalIppServerTest.kt` (existing inline overrides)

This is a mechanical signature change: Kotlin requires every `override fun render(...)` to repeat the full parameter list, so every implementation of `RenderingPipeline` needs the two new parameters added (even where they're unused). `NativeRenderingPipeline`'s implementation is substantive and handled separately in Task 7.

- [ ] **Step 1: Widen the interface**

`RenderingPipeline.kt` currently reads:

```kotlin
package dev.jaspreet.printserver.render

import java.io.File

/** Converts one spooled document (PDF or JPEG) into printer-ready bytes (PCL3-GUI for hpcups models). */
interface RenderingPipeline {
    /** Renders [document] ([format] is an IPP document-format MIME type) and writes printer bytes to [output]. Throws IOException on failure. */
    fun render(document: File, output: File, format: String = "application/pdf")
}
```

Replace with:

```kotlin
package dev.jaspreet.printserver.render

import dev.jaspreet.printserver.jobs.ColorMode
import dev.jaspreet.printserver.jobs.PrintQuality
import java.io.File

/** Converts one spooled document (PDF or JPEG) into printer-ready bytes (PCL3-GUI for hpcups models). */
interface RenderingPipeline {
    /** Renders [document] ([format] is an IPP document-format MIME type) and writes printer bytes to [output]. Throws IOException on failure. */
    fun render(
        document: File,
        output: File,
        format: String = "application/pdf",
        quality: PrintQuality = PrintQuality.NORMAL,
        colorMode: ColorMode = ColorMode.COLOR,
    )
}
```

- [ ] **Step 2: Build to see every broken implementation**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
Expected: FAIL — compile errors in `NativeRenderingPipeline.kt` (handled in Task 7) and every test file with a `RenderingPipeline` override (handled in Steps 3–5 below). Use this output to double check you've found every site — cross-reference with:

Run: `grep -rn "override fun render(document: File" app/src/test app/src/androidTest`

If this plan is being executed after the queue-visibility-retry-cancel plan, that grep may show additional inline `RenderingPipeline` overrides introduced by that plan's tests (e.g. in `QueueStateTest.kt`, or extra tests added to `JobQueueTest.kt`) beyond the ones listed in Steps 3–5 below — apply the same parameter-widening edit shown there to each one found.

- [ ] **Step 3: Update `FakeRenderingPipeline`**

`FakeRenderingPipeline.kt` currently reads:

```kotlin
package dev.jaspreet.printserver.render

import java.io.File
import java.io.IOException

class FakeRenderingPipeline(
    private val result: ByteArray = "FAKE-PCL".toByteArray(),
    private val failWith: IOException? = null,
) : RenderingPipeline {
    val rendered = mutableListOf<File>()
    val formats = mutableListOf<String>()

    override fun render(document: File, output: File, format: String) {
        rendered += document
        formats += format
        failWith?.let { throw it }
        output.writeBytes(result)
    }
}
```

Replace with:

```kotlin
package dev.jaspreet.printserver.render

import dev.jaspreet.printserver.jobs.ColorMode
import dev.jaspreet.printserver.jobs.PrintQuality
import java.io.File
import java.io.IOException

class FakeRenderingPipeline(
    private val result: ByteArray = "FAKE-PCL".toByteArray(),
    private val failWith: IOException? = null,
) : RenderingPipeline {
    val rendered = mutableListOf<File>()
    val formats = mutableListOf<String>()
    val qualities = mutableListOf<PrintQuality>()
    val colorModes = mutableListOf<ColorMode>()

    override fun render(
        document: File,
        output: File,
        format: String,
        quality: PrintQuality,
        colorMode: ColorMode,
    ) {
        rendered += document
        formats += format
        qualities += quality
        colorModes += colorMode
        failWith?.let { throw it }
        output.writeBytes(result)
    }
}
```

- [ ] **Step 4: Update the three inline overrides in `JobQueueTest.kt`**

`JobQueueTest.kt` has three anonymous `RenderingPipeline` objects with the identical override signature `override fun render(document: File, output: File, format: String) {` — one each in `cancel while pending prevents processing`, `cancel fires onJobStateChanged with CANCELED before deleting the spool file`, and `render timeout fires onJobStateChanged exactly once with ABORTED`. In each of the three, change:

```kotlin
            override fun render(document: File, output: File, format: String) {
```

to:

```kotlin
            override fun render(
                document: File, output: File, format: String,
                quality: PrintQuality, colorMode: ColorMode,
            ) {
```

(The rest of each lambda body is unchanged. `JobQueueTest.kt` is in the `dev.jaspreet.printserver.jobs` package, same as the new enums — no import needed.)

- [ ] **Step 5: Update the two inline overrides in `LocalIppServerTest.kt`**

`LocalIppServerTest.kt` has two anonymous `RenderingPipeline` objects with the same override signature — one each in `print-job reports the job's real queue state, not a hardcoded value` and `get-printer-attributes reports the real queued-job-count, not a hardcoded value`. In each, change:

```kotlin
            override fun render(document: File, output: File, format: String) {
```

to:

```kotlin
            override fun render(
                document: File, output: File, format: String,
                quality: dev.jaspreet.printserver.jobs.PrintQuality, colorMode: dev.jaspreet.printserver.jobs.ColorMode,
            ) {
```

(`LocalIppServerTest.kt` is in the `dev.jaspreet.printserver.ipp` package, so the enum types need full qualification here rather than an import, matching how the file already fully-qualifies `dev.jaspreet.printserver.render.RenderingPipeline` in the same anonymous-object declarations.)

- [ ] **Step 6: Rerun the two blocked test commands from Task 3 and Task 5**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.jobs.JobQueueTest"`
Expected: PASS (all tests, including the two added in Task 3)

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.ipp.LocalIppServerTest"`
Expected: still FAILS on `NativeRenderingPipeline.kt` not compiling — that's Task 7. If it fails only on `render.NativeRenderingPipeline.kt`, that confirms this task's changes are otherwise correct; continue to Task 7.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/render/RenderingPipeline.kt app/src/test/java/dev/jaspreet/printserver/render/FakeRenderingPipeline.kt app/src/test/java/dev/jaspreet/printserver/jobs/JobQueueTest.kt app/src/test/java/dev/jaspreet/printserver/ipp/LocalIppServerTest.kt
git commit -m "feat: widen RenderingPipeline for quality/colorMode, update all test doubles"
```

---

### Task 7: `NativeRenderingPipeline` resolves quality/color into dpi and an options string

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/render/NativeRenderingPipeline.kt`

No JVM unit test — `NativeRenderingPipeline` needs the real native `.so` (existing limitation, see `RenderingPipeline`'s test double being what's exercised on the JVM instead). Verified via the hardware smoke test in Task 9.

- [ ] **Step 1: Resolve dpi from quality and build the CUPS options string**

`NativeRenderingPipeline.kt` currently reads:

```kotlin
package dev.jaspreet.printserver.render

import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException

/**
 * Ghostscript (PDF -> PPM pages) or Android's built-in JPEG decoder (single-page
 * image/jpeg jobs), then hpcups (RGB -> PCL3-GUI). Multi-page PDFs: ppmraw with %d
 * in the output name emits one file per page; pages are encoded in order and
 * concatenated into [output].
 */
class NativeRenderingPipeline(
    private val workDir: File,
    private val ppdPath: String,
    private val dpi: Int = 300,
) : RenderingPipeline {

    private val ghostscript = GhostscriptRenderer(dpi)

    companion object {
        // Bounds a decoded JPEG's memory footprint (ARGB_8888 bitmap + RGB copies below);
        // 50 megapixels is well beyond any realistic printed page at this pipeline's 300dpi.
        private const val MAX_JPEG_PIXELS = 50_000_000L
    }

    override fun render(document: File, output: File, format: String) {
        when (format) {
            "image/jpeg" -> renderJpeg(document, output)
            "image/pwg-raster" -> renderPwgRaster(document, output)
            else -> renderPdf(document, output)
        }
    }

    private fun renderPwgRaster(raster: File, output: File) {
        val code = HpcupsNative.encodeRaster(raster.absolutePath, ppdPath, output.absolutePath)
        if (code != 0) throw IOException("hpcups failed with code $code for PWG Raster")
    }

    private fun renderJpeg(jpeg: File, output: File) {
        // Check the declared dimensions before decoding actual pixels — a tiny file can
        // claim an enormous width/height (decompression bomb) and blow up memory on the
        // full decode below. inJustDecodeBounds only parses the header, no pixel buffer.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(jpeg.absolutePath, bounds)
        val declaredPixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || declaredPixels > MAX_JPEG_PIXELS) {
            throw IOException("JPEG dimensions ${bounds.outWidth}x${bounds.outHeight} exceed limit")
        }
        val bitmap = BitmapFactory.decodeFile(jpeg.absolutePath)
            ?: throw IOException("Could not decode JPEG")
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            // hpcups expects packed RGB triplets, row-major — same layout PpmImage
            // produces from Ghostscript's ppmraw output.
            val rgb = ByteArray(width * height * 3)
            var i = 0
            for (pixel in pixels) {
                rgb[i++] = ((pixel shr 16) and 0xFF).toByte()
                rgb[i++] = ((pixel shr 8) and 0xFF).toByte()
                rgb[i++] = (pixel and 0xFF).toByte()
            }
            val code = HpcupsNative.encode(rgb, width, height, dpi, ppdPath, output.absolutePath)
            if (code != 0) throw IOException("hpcups failed with code $code")
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderPdf(pdf: File, output: File) {
        val pageDir = File(workDir, "pages-${System.nanoTime()}").apply { mkdirs() }
        try {
            val pattern = File(pageDir, "page-%03d.ppm")
            ghostscript.renderToPpm(pdf, pattern)
            val pages = pageDir.listFiles { f -> f.name.endsWith(".ppm") }?.sortedBy { it.name }
                ?: emptyList()
            if (pages.isEmpty()) throw IOException("Ghostscript produced no pages")

            output.outputStream().use { out ->
                for (page in pages) {
                    val img = page.inputStream().buffered().use { PpmImage.parse(it) }
                    val pageOut = File(pageDir, "${page.name}.pcl")
                    val code = HpcupsNative.encode(
                        img.rgb, img.width, img.height, dpi, ppdPath, pageOut.absolutePath,
                    )
                    if (code != 0) throw IOException("hpcups failed with code $code on ${page.name}")
                    pageOut.inputStream().use { it.copyTo(out) }
                }
            }
        } finally {
            pageDir.deleteRecursively()
        }
    }
}
```

Replace with:

```kotlin
package dev.jaspreet.printserver.render

import android.graphics.BitmapFactory
import dev.jaspreet.printserver.jobs.ColorMode
import dev.jaspreet.printserver.jobs.PrintQuality
import java.io.File
import java.io.IOException

/**
 * Ghostscript (PDF -> PPM pages) or Android's built-in JPEG decoder (single-page
 * image/jpeg jobs), then hpcups (RGB -> PCL3-GUI). Multi-page PDFs: ppmraw with %d
 * in the output name emits one file per page; pages are encoded in order and
 * concatenated into [output].
 */
class NativeRenderingPipeline(
    private val workDir: File,
    private val ppdPath: String,
) : RenderingPipeline {

    companion object {
        // Bounds a decoded JPEG's memory footprint (ARGB_8888 bitmap + RGB copies below);
        // 50 megapixels is well beyond any realistic printed page at this pipeline's max dpi.
        private const val MAX_JPEG_PIXELS = 50_000_000L
    }

    override fun render(document: File, output: File, format: String, quality: PrintQuality, colorMode: ColorMode) {
        val dpi = dpiFor(quality)
        val options = hpcupsOptions(quality, colorMode)
        when (format) {
            "image/jpeg" -> renderJpeg(document, output, dpi, options)
            "image/pwg-raster" -> renderPwgRaster(document, output, options)
            else -> renderPdf(document, output, dpi, options)
        }
    }

    // FastDraft/Normal/Best all live in the bundled PPD; Best differs from Normal only
    // via the OutputMode option string below, not resolution — both render at 600dpi.
    // Photo(1200dpi) has no reachable IPP print-quality mapping (see PrintOptions.kt).
    private fun dpiFor(quality: PrintQuality): Int = if (quality == PrintQuality.DRAFT) 300 else 600

    private fun hpcupsOptions(quality: PrintQuality, colorMode: ColorMode): String {
        val outputMode = when (quality) {
            PrintQuality.DRAFT -> "FastDraft"
            PrintQuality.NORMAL -> "Normal"
            PrintQuality.HIGH -> "Best"
        }
        val colorModel = if (colorMode == ColorMode.COLOR) "RGB" else "KGray"
        return "ColorModel=$colorModel OutputMode=$outputMode"
    }

    private fun renderPwgRaster(raster: File, output: File, options: String) {
        val code = HpcupsNative.encodeRaster(raster.absolutePath, ppdPath, output.absolutePath, options)
        if (code != 0) throw IOException("hpcups failed with code $code for PWG Raster")
    }

    private fun renderJpeg(jpeg: File, output: File, dpi: Int, options: String) {
        // Check the declared dimensions before decoding actual pixels — a tiny file can
        // claim an enormous width/height (decompression bomb) and blow up memory on the
        // full decode below. inJustDecodeBounds only parses the header, no pixel buffer.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(jpeg.absolutePath, bounds)
        val declaredPixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || declaredPixels > MAX_JPEG_PIXELS) {
            throw IOException("JPEG dimensions ${bounds.outWidth}x${bounds.outHeight} exceed limit")
        }
        val bitmap = BitmapFactory.decodeFile(jpeg.absolutePath)
            ?: throw IOException("Could not decode JPEG")
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            // hpcups expects packed RGB triplets, row-major — same layout PpmImage
            // produces from Ghostscript's ppmraw output.
            val rgb = ByteArray(width * height * 3)
            var i = 0
            for (pixel in pixels) {
                rgb[i++] = ((pixel shr 16) and 0xFF).toByte()
                rgb[i++] = ((pixel shr 8) and 0xFF).toByte()
                rgb[i++] = (pixel and 0xFF).toByte()
            }
            val code = HpcupsNative.encode(rgb, width, height, dpi, ppdPath, output.absolutePath, options)
            if (code != 0) throw IOException("hpcups failed with code $code")
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderPdf(pdf: File, output: File, dpi: Int, options: String) {
        val pageDir = File(workDir, "pages-${System.nanoTime()}").apply { mkdirs() }
        try {
            val pattern = File(pageDir, "page-%03d.ppm")
            GhostscriptRenderer(dpi).renderToPpm(pdf, pattern)
            val pages = pageDir.listFiles { f -> f.name.endsWith(".ppm") }?.sortedBy { it.name }
                ?: emptyList()
            if (pages.isEmpty()) throw IOException("Ghostscript produced no pages")

            output.outputStream().use { out ->
                for (page in pages) {
                    val img = page.inputStream().buffered().use { PpmImage.parse(it) }
                    val pageOut = File(pageDir, "${page.name}.pcl")
                    val code = HpcupsNative.encode(
                        img.rgb, img.width, img.height, dpi, ppdPath, pageOut.absolutePath, options,
                    )
                    if (code != 0) throw IOException("hpcups failed with code $code on ${page.name}")
                    pageOut.inputStream().use { it.copyTo(out) }
                }
            }
        } finally {
            pageDir.deleteRecursively()
        }
    }
}
```

Note: `dpi`/`GhostscriptRenderer(dpi)` moves from a constructor-level `private val dpi`/`private val ghostscript` to being resolved per-call from `quality`, since different jobs on the same pipeline instance can now request different quality — a single shared `GhostscriptRenderer` instance with a fixed dpi no longer makes sense.

- [ ] **Step 2: Build (will still fail — `HpcupsNative` doesn't have the `options` param yet)**

Run: `./gradlew :app:compileDebugKotlin`
Expected: FAIL — `HpcupsNative.encode`/`encodeRaster` don't accept a 7th/4th `options` arg yet. That's Task 8, next.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/render/NativeRenderingPipeline.kt
git commit -m "feat: resolve print quality to dpi and hpcups options in NativeRenderingPipeline"
```

---

### Task 8: Plumb an options string into `HpcupsNative` and `hpcupsjni.cpp`

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/render/HpcupsNative.kt`
- Modify: `app/src/main/cpp/hpcupsjni.cpp`

No JVM unit test — this is a JNI/native signature change, verified via the hardware smoke test in Task 9 (requires an NDK/CMake rebuild, not exercised by JVM unit tests).

- [ ] **Step 1: Add the `options` parameter to the Kotlin `external fun` declarations**

`HpcupsNative.kt` currently reads:

```kotlin
package dev.jaspreet.printserver.render

object HpcupsNative {
    init { System.loadLibrary("hpcupsjni") }
    /** Returns 0 on success. Not thread-safe — serialize calls (JobQueue does). */
    external fun encode(
        rgb: ByteArray, width: Int, height: Int, dpi: Int,
        ppdPath: String, outPath: String,
    ): Int

    /** Encodes a client-supplied PWG/CUPS raster file to printer-ready PCL3-GUI. */
    external fun encodeRaster(inputPath: String, ppdPath: String, outPath: String): Int
}
```

Replace with:

```kotlin
package dev.jaspreet.printserver.render

object HpcupsNative {
    init { System.loadLibrary("hpcupsjni") }
    /** [options] is a CUPS-style options string, e.g. "ColorModel=RGB OutputMode=Normal".
     *  Returns 0 on success. Not thread-safe — serialize calls (JobQueue does). */
    external fun encode(
        rgb: ByteArray, width: Int, height: Int, dpi: Int,
        ppdPath: String, outPath: String, options: String,
    ): Int

    /** Encodes a client-supplied PWG/CUPS raster file to printer-ready PCL3-GUI.
     *  [options] is a CUPS-style options string, e.g. "ColorModel=KGray OutputMode=FastDraft". */
    external fun encodeRaster(inputPath: String, ppdPath: String, outPath: String, options: String): Int
}
```

- [ ] **Step 2: Thread the options string into `run_hpcups()` and both JNI entry points**

`hpcupsjni.cpp` currently reads:

```cpp
static int run_hpcups(int inputFd, int outputFd, const char *ppd) {
    setenv("PPD", ppd, 1);
    g_hpcups_input_fd = inputFd;
    g_hpcups_output_fd = outputFd;

    char *argv[] = { (char *)"hpcups", (char *)"1", (char *)"android",
                     (char *)"printserver", (char *)"1", (char *)"", NULL };
    return hpcups_main(6, argv);
}
```

Replace with:

```cpp
static int run_hpcups(int inputFd, int outputFd, const char *ppd, const char *options) {
    setenv("PPD", ppd, 1);
    g_hpcups_input_fd = inputFd;
    g_hpcups_output_fd = outputFd;

    char *argv[] = { (char *)"hpcups", (char *)"1", (char *)"android",
                     (char *)"printserver", (char *)"1", (char *)options, NULL };
    return hpcups_main(6, argv);
}
```

The `encode` JNI function currently reads:

```cpp
extern "C" JNIEXPORT jint JNICALL
Java_dev_jaspreet_printserver_render_HpcupsNative_encode(
    JNIEnv *env, jobject thiz,
    jbyteArray jrgb, jint width, jint height, jint dpi,
    jstring jppdPath, jstring joutPath) {

    const char *ppd = env->GetStringUTFChars(jppdPath, NULL);
    const char *outPath = env->GetStringUTFChars(joutPath, NULL);
    jbyte *rgb = env->GetByteArrayElements(jrgb, NULL);
    int result = -1;

    int pipefd[2];
    int outFd = open(outPath, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (outFd >= 0 && pipe(pipefd) == 0) {
        RasterFeed feed = { pipefd[1], (const unsigned char *)rgb,
                            (unsigned)width, (unsigned)height, (unsigned)dpi };
        pthread_t writer;
        pthread_create(&writer, NULL, feed_raster, &feed);
        result = run_hpcups(pipefd[0], outFd, ppd);

        pthread_join(writer, NULL);
        close(pipefd[0]);
    }
    if (outFd >= 0) close(outFd);

    env->ReleaseByteArrayElements(jrgb, rgb, JNI_ABORT);
    env->ReleaseStringUTFChars(jppdPath, ppd);
    env->ReleaseStringUTFChars(joutPath, outPath);
    return result;
}
```

Replace with:

```cpp
extern "C" JNIEXPORT jint JNICALL
Java_dev_jaspreet_printserver_render_HpcupsNative_encode(
    JNIEnv *env, jobject thiz,
    jbyteArray jrgb, jint width, jint height, jint dpi,
    jstring jppdPath, jstring joutPath, jstring joptions) {

    const char *ppd = env->GetStringUTFChars(jppdPath, NULL);
    const char *outPath = env->GetStringUTFChars(joutPath, NULL);
    const char *options = env->GetStringUTFChars(joptions, NULL);
    jbyte *rgb = env->GetByteArrayElements(jrgb, NULL);
    int result = -1;

    int pipefd[2];
    int outFd = open(outPath, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (outFd >= 0 && pipe(pipefd) == 0) {
        RasterFeed feed = { pipefd[1], (const unsigned char *)rgb,
                            (unsigned)width, (unsigned)height, (unsigned)dpi };
        pthread_t writer;
        pthread_create(&writer, NULL, feed_raster, &feed);
        result = run_hpcups(pipefd[0], outFd, ppd, options);

        pthread_join(writer, NULL);
        close(pipefd[0]);
    }
    if (outFd >= 0) close(outFd);

    env->ReleaseByteArrayElements(jrgb, rgb, JNI_ABORT);
    env->ReleaseStringUTFChars(jppdPath, ppd);
    env->ReleaseStringUTFChars(joutPath, outPath);
    env->ReleaseStringUTFChars(joptions, options);
    return result;
}
```

The `encodeRaster` JNI function currently reads:

```cpp
extern "C" JNIEXPORT jint JNICALL
Java_dev_jaspreet_printserver_render_HpcupsNative_encodeRaster(
    JNIEnv *env, jobject thiz,
    jstring jinputPath, jstring jppdPath, jstring joutPath) {

    const char *inputPath = env->GetStringUTFChars(jinputPath, NULL);
    const char *ppd = env->GetStringUTFChars(jppdPath, NULL);
    const char *outPath = env->GetStringUTFChars(joutPath, NULL);
    int result = -1;

    int inputFd = open(inputPath, O_RDONLY);
    int outFd = open(outPath, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (inputFd >= 0 && outFd >= 0) {
        result = run_hpcups(inputFd, outFd, ppd);
    } else {
        LOGE("open failed for raster input=%s output=%s", inputPath, outPath);
    }

    if (inputFd >= 0) close(inputFd);
    if (outFd >= 0) close(outFd);

    env->ReleaseStringUTFChars(jinputPath, inputPath);
    env->ReleaseStringUTFChars(jppdPath, ppd);
    env->ReleaseStringUTFChars(joutPath, outPath);
    return result;
}
```

Replace with:

```cpp
extern "C" JNIEXPORT jint JNICALL
Java_dev_jaspreet_printserver_render_HpcupsNative_encodeRaster(
    JNIEnv *env, jobject thiz,
    jstring jinputPath, jstring jppdPath, jstring joutPath, jstring joptions) {

    const char *inputPath = env->GetStringUTFChars(jinputPath, NULL);
    const char *ppd = env->GetStringUTFChars(jppdPath, NULL);
    const char *outPath = env->GetStringUTFChars(joutPath, NULL);
    const char *options = env->GetStringUTFChars(joptions, NULL);
    int result = -1;

    int inputFd = open(inputPath, O_RDONLY);
    int outFd = open(outPath, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (inputFd >= 0 && outFd >= 0) {
        result = run_hpcups(inputFd, outFd, ppd, options);
    } else {
        LOGE("open failed for raster input=%s output=%s", inputPath, outPath);
    }

    if (inputFd >= 0) close(inputFd);
    if (outFd >= 0) close(outFd);

    env->ReleaseStringUTFChars(jinputPath, inputPath);
    env->ReleaseStringUTFChars(jppdPath, ppd);
    env->ReleaseStringUTFChars(joutPath, outPath);
    env->ReleaseStringUTFChars(joptions, options);
    return result;
}
```

- [ ] **Step 3: Build the native library and the full app**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL — this triggers the CMake/NDK build for `hpcupsjni`; a signature mismatch between the `external fun` declarations and the JNI function definitions would show up here as a native build or `UnsatisfiedLinkError`-prone mismatch (the latter only surfaces at runtime, so also do Task 9's hardware check, not just this build).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/render/HpcupsNative.kt app/src/main/cpp/hpcupsjni.cpp
git commit -m "feat: plumb a CUPS options string into hpcups for print-quality/color-mode"
```

---

### Task 9: Full verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full JVM unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass — including every test added across Tasks 3–6.

- [ ] **Step 2: Build the full debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Manual hardware verification**

Install on a device with the Tier 2 printer connected (`./gradlew :app:installDebug`), per the general flow in `docs/superpowers/testing/hardware-smoke-checklist.md`:

1. Print the same document at draft, normal, and high quality from a real IPP-Everywhere client (macOS's print dialog exposes a quality/media picker for driverless printers) — confirm visibly different output resolution and render speed (draft noticeably faster and coarser).
2. Print in monochrome vs. color from the same client — confirm grayscale output for a monochrome request even though the printer and document both support color.
3. Print with no explicit quality/color selection (client's own defaults) — confirm it still prints successfully at normal quality in color (default clamping path).
4. Confirm no regression on `image/pwg-raster` and `image/jpeg` jobs (both now also pass through `resolveQuality`/`resolveColorMode` via `printJob()`) — print at least one of each format successfully.

- [ ] **Step 4: Update `CLAUDE.md` and `AGENTS.md` if the on-device verification passes**

Per this repo's convention (see `CLAUDE.md`'s own instruction to keep both files in sync), add a short note to the "Tier 2 path" bullet in the Architecture section once hardware-verified, e.g. appending: "`LocalIppServer` resolves per-job `print-quality`/`print-color-mode` job-template attributes (clamped to the printer's supported PPD options) before handing off to `RenderingPipeline`." Make the identical edit to both files.
