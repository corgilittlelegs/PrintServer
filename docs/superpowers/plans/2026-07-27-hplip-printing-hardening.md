# HPLIP Printing Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the verified Tier 2 HPLIP printing path so the app only sends `hpcups` output to compatible HP printers, advertises capabilities that match the selected PPD/native renderer, handles large jobs without avoidable memory pressure, and has stronger device-native regression coverage for HPLIP rendering.

**Starting point:** On 2026-07-27, `./gradlew :app:testDebugUnitTest`, `./gradlew :app:assembleDebug`, and the two print-focused device-native tests passed on SM-X210:

- `dev.jaspreet.printserver.LegacyPipelineWiringSmokeTest`
- `dev.jaspreet.printserver.NativePipelineFixtureTest`

The full connected test suite was not clean because the unrelated scan hardware fixture could not find a USB DeskJet MFP. Actual paper output over USB should still be re-smoke-tested after these changes.

**Non-goals:**

- Do not add broad non-HP printer support.
- Do not import a full HPLIP model database in this plan; that belongs to `docs/superpowers/plans/2026-07-24-hp-profile-driven-support.md`.
- Do not implement new HPLIP backends beyond the current DeskJet 2300 PCL3-GUI path.
- Do not claim IPP Everywhere certification; only prepare the implementation for a later certification run.

**Primary files:**

- `app/src/main/java/dev/jaspreet/printserver/service/ServerService.kt`
- `app/src/main/java/dev/jaspreet/printserver/ipp/PrinterCapabilities.kt`
- `app/src/main/java/dev/jaspreet/printserver/ipp/LocalIppServer.kt`
- `app/src/main/java/dev/jaspreet/printserver/http/BodyReader.kt`
- `app/src/main/java/dev/jaspreet/printserver/jobs/JobQueue.kt`
- `app/src/main/java/dev/jaspreet/printserver/relay/Raw9100Relay.kt`
- `app/src/main/java/dev/jaspreet/printserver/render/NativeRenderingPipeline.kt`
- `app/src/main/java/dev/jaspreet/printserver/render/HpcupsNative.kt`
- `app/src/main/cpp/hpcupsjni.cpp`
- `app/src/main/assets/ppd/hp_deskjet_2300_series.ppd`
- `app/src/androidTest/java/dev/jaspreet/printserver/`

---

## Phase 1: Safe Tier 2 Startup

### Task 1: Add a minimal verified profile gate

**Files:**

- Create: `app/src/main/java/dev/jaspreet/printserver/profile/VerifiedPrinterProfile.kt`
- Create: `app/src/main/java/dev/jaspreet/printserver/profile/VerifiedPrinterProfiles.kt`
- Create: `app/src/test/java/dev/jaspreet/printserver/profile/VerifiedPrinterProfilesTest.kt`
- Modify: `app/src/main/java/dev/jaspreet/printserver/service/ServerService.kt`

- [ ] Define a small `VerifiedPrinterProfile` data class for the current verified Tier 2 path.
- [ ] Include the DeskJet 2300 profile with model aliases from the bundled PPD's IEEE-1284 line.
- [ ] Match using parsed `DeviceIdInfo.manufacturer`, `DeviceIdInfo.model`, `DeviceIdInfo.commands`, and VID/PID when available.
- [ ] In `ServerService.startPipeline()`, keep IPP-USB as Tier 1 when available.
- [ ] In the no-IPP-USB branch, require a positive verified profile match before starting Tier 2.
- [ ] Refuse unknown legacy printers with a clear message instead of falling through to DeskJet 2300 rendering.
- [ ] Add JVM tests for exact DeskJet 2300 match, alias match, non-HP rejection, and unsupported HP rejection.

### Task 2: Make profile selection visible in service state

**Files:**

- Modify: `app/src/main/java/dev/jaspreet/printserver/service/ServerState.kt`
- Modify: `app/src/main/java/dev/jaspreet/printserver/service/ServerService.kt`
- Modify: `app/src/main/java/dev/jaspreet/printserver/ui/PrintServerApp.kt`

- [ ] Add fields for selected profile id/name and support status.
- [ ] Show verified profile details in the printer-info UI when Tier 2 starts.
- [ ] For unsupported devices, show the detected manufacturer/model and why startup was refused.
- [ ] Keep the UI text concise; do not add a marketing/explainer section.

---

## Phase 2: Honest Capabilities

### Task 3: Move Tier 2 capabilities onto the profile

**Files:**

- Modify: `VerifiedPrinterProfile.kt`
- Modify: `PrinterCapabilities.kt`
- Modify: `TxtRecords.kt`
- Modify: `PrinterCapabilitiesTest.kt`
- Modify: `TxtRecordsTest.kt`

- [ ] Add profile fields for supported document formats, media sizes, color modes, quality modes, and supported resolutions.
- [ ] Build `PrinterCapabilities` from the selected profile instead of calling `PrinterCapabilities.deskJet2300(...)` directly.
- [ ] Keep the stable UUID behavior so clients do not see a new printer after every app restart.
- [ ] Ensure mDNS TXT values come from the same profile capability source as IPP attributes.

### Task 4: Fix resolution and quality advertisement

**Files:**

- Modify: `PrinterCapabilities.kt`
- Modify: `NativeRenderingPipeline.kt`
- Modify: tests around capabilities and print options

- [ ] Advertise every resolution the current pipeline can actually produce for the profile.
- [ ] For DeskJet 2300, align `print-quality-supported`, `printer-resolution-supported`, and URF `RS` tokens with the 300 dpi Draft and 600 dpi Normal/High renderer behavior.
- [ ] Do not advertise 1200 dpi Photo until an IPP option maps to it and an Android native fixture proves it works.
- [ ] Add tests proving the profile-derived IPP attributes match the rendering quality mapping.

### Task 5: Validate document formats before spooling

**Files:**

- Modify: `LocalIppServer.kt`
- Modify: `LocalIppServerTest.kt`

- [ ] Reject unsupported `document-format` in `Validate-Job`.
- [ ] Reject unsupported `document-format` in `Print-Job` before creating a spool file.
- [ ] Reject unsupported `document-format` in `Create-Job`.
- [ ] Keep `Send-Document` bound to the reserved job's already-validated format.
- [ ] Add tests for `application/postscript`, `application/vnd.hp-pcl`, `image/urf`, `application/octet-stream`, and an unknown MIME type.

---

## Phase 3: Large Job Resource Handling

### Task 6: Add a bounded body spooler

**Files:**

- Create: `app/src/main/java/dev/jaspreet/printserver/http/BodySpooler.kt`
- Create: `app/src/test/java/dev/jaspreet/printserver/http/BodySpoolerTest.kt`
- Modify: `LocalIppServer.kt`

- [ ] Stream decoded HTTP bodies to a caller-provided file without materializing the full document as a `ByteArray`.
- [ ] Preserve existing chunked transfer decoding behavior.
- [ ] Enforce the existing max body size during streaming.
- [ ] Delete partial spool files when the request is too large or malformed.
- [ ] Keep `BodyReader.readAll()` for small non-document IPP operations if that remains simpler.

### Task 7: Split IPP attributes from document payload without full-copying the document

**Files:**

- Modify: `LocalIppServer.kt`
- Add/modify tests in `LocalIppServerTest.kt`

- [ ] Parse the IPP packet from a bounded operation prefix.
- [ ] Stream the remaining document payload to the spool file for `Print-Job`.
- [ ] For `Create-Job`, keep reservation behavior document-free.
- [ ] For `Send-Document`, append the streamed document payload to the reserved spool file.
- [ ] Preserve support for persistent HTTP connections.

### Task 8: Add job-size and free-space feedback

**Files:**

- Modify: `JobQueue.kt`
- Modify: `ActivityLog.kt` or activity mapping if needed
- Modify tests

- [ ] Surface `request-entity-too-large` distinctly from render/document-format failures.
- [ ] Keep the existing free-space check before rendering.
- [ ] Record the final spooled byte count in activity entries.
- [ ] Add tests that too-large jobs fail before rendering and do not leave partial files behind.

---

## Phase 4: Native HPLIP/JNI Robustness

### Task 9: Harden JNI setup and cleanup

**Files:**

- Modify: `app/src/main/cpp/hpcupsjni.cpp`
- Modify/add Android native tests if practical

- [ ] Check all `GetStringUTFChars` and `GetByteArrayElements` results before use.
- [ ] Check `open`, `pipe`, and `pthread_create` failures and log the failing operation.
- [ ] Close every opened file descriptor on every setup-failure path.
- [ ] Ensure the writer pipe is closed if `pthread_create` fails.
- [ ] Return stable negative setup codes that Kotlin can convert to useful error messages.

### Task 10: Map HPLIP failures to clearer Kotlin errors

**Files:**

- Modify: `HpcupsNative.kt`
- Modify: `NativeRenderingPipeline.kt`
- Modify: `JobQueue.kt`

- [ ] Wrap native return codes in a small Kotlin error mapper.
- [ ] Distinguish setup failures from `hpcups` nonzero exits.
- [ ] Preserve current job terminal state behavior: failed renders become aborted jobs.
- [ ] Include the profile id, format, quality, and color mode in render failure logs.

### Task 11: Keep HPLIP calls serialized by contract

**Files:**

- Modify: `HpcupsNative.kt`
- Modify: `JobQueue.kt`
- Modify tests/fakes if needed

- [ ] Document that all `HpcupsNative` calls must happen behind the single render queue.
- [ ] Add an internal guard or test-only hook that fails if two native encodes overlap in process.
- [ ] Verify retry behavior cannot run a second native render while a timed-out native render is still executing.

---

## Phase 5: USB Write Serialization

### Task 12: Introduce a shared legacy printer write lock

**Files:**

- Create: `app/src/main/java/dev/jaspreet/printserver/usb/LegacyPrinterSession.kt` or equivalent
- Modify: `ServerService.kt`
- Modify: `JobQueue.kt`
- Modify: `Raw9100Relay.kt`
- Add tests around write ordering

- [ ] Route both Tier 2 rendered jobs and raw port 9100 writes through the same session/lock.
- [ ] Ensure only one logical writer can send bytes to the legacy USB transport at a time.
- [ ] Decide policy when a raw 9100 client connects during an active IPP job: wait briefly, reject, or close immediately.
- [ ] Surface conflicts in logs so mixed-client failures are diagnosable.

### Task 13: Isolate scan-driven transport closure from active print writes

**Files:**

- Modify: `ServerService.kt`
- Modify/add tests where practical

- [ ] Ensure `closeLegacyTransportForScan()` cannot close the transport while an IPP or raw write is in progress.
- [ ] If a scan is requested while printing, return a busy response or wait until the print write completes.
- [ ] Keep current behavior of closing the legacy print transport before opening scan transport once it is safe.

---

## Phase 6: Native And Hardware Regression Coverage

### Task 14: Add multi-page native print fixture

**Files:**

- Add asset: `app/src/androidTest/assets/multipage-smoke.pdf`
- Add/modify: `app/src/androidTest/java/dev/jaspreet/printserver/NativePipelineFixtureTest.kt`

- [ ] Render a two-page PDF through `NativeRenderingPipeline`.
- [ ] Assert output is non-trivial and starts with ESC.
- [ ] Assert the output is larger than the one-page fixture, without depending on exact bytes.
- [ ] Save artifacts on failure like the existing native tests.

### Task 15: Add quality/color native fixture coverage

**Files:**

- Modify: `NativePipelineFixtureTest.kt`
- Modify: `LegacyPipelineWiringSmokeTest.kt` if needed

- [ ] Run a small fixture through Draft/Normal/High.
- [ ] Run a small fixture through RGB and KGray.
- [ ] Assert all modes produce non-empty PCL and no native crash.
- [ ] Optionally compare output sizes or hashes only if stable across devices/builds.

### Task 16: Add hardware smoke checklist items for HPLIP printing

**Files:**

- Modify: `docs/superpowers/testing/hardware-smoke-checklist.md`

- [ ] Add DeskJet 2300 Tier 2 paper-output checks for PDF, JPEG, and PWG Raster.
- [ ] Add Draft/Normal/High and color/monochrome checks.
- [ ] Add one multi-page PDF check.
- [ ] Add mixed-client check: IPP print while raw 9100 client attempts to connect.
- [ ] Include required logs: `adb logcat -s ServerService JobQueue hpcupsjni gsjni`.

---

## Verification Commands

Run after each phase unless the phase only changes docs:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Run on a connected Android device after native/JNI/rendering changes:

```bash
ANDROID_SERIAL=<single-device-serial> ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.jaspreet.printserver.LegacyPipelineWiringSmokeTest,dev.jaspreet.printserver.NativePipelineFixtureTest
```

Run before claiming end-to-end printing is verified:

```bash
./gradlew :app:installDebug
adb logcat -c
adb logcat -s ServerService JobQueue hpcupsjni gsjni
```

Then manually start the app, connect the verified DeskJet 2300-series printer over USB, grant USB permission if prompted, print from a LAN client, and confirm paper output.

---

## Acceptance Criteria

- Unknown legacy printers no longer receive DeskJet 2300 PCL3-GUI output.
- DeskJet 2300 Tier 2 still starts with the verified profile and advertises IPP/mDNS capabilities that match the renderer.
- Unsupported document formats fail early with client-visible IPP errors.
- Large print documents are streamed to spool with bounded memory use.
- `hpcupsjni` setup failures are deterministic, logged, and do not leak file descriptors.
- IPP-rendered jobs, raw 9100 writes, and scan transport closure cannot race the same legacy USB transport.
- One-page, multi-page, quality, and color-mode native print fixtures pass on device.
- Actual paper output passes the updated hardware smoke checklist on the verified DeskJet 2300-series printer.
