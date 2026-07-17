# Session Handoff — Tier 2 Driverless Printing

## Where things stand

Working in git worktree: `.claude/worktrees/tier2-legacy-driverless` on branch
`worktree-tier2-legacy-driverless`. **Not yet merged into `main`** — main is
still at `be71b7d` (Tier 1 fixes only). All items on the "Do NOT merge to
main until" checklist below are now satisfied; merge is unblocked pending
the user's go-ahead via `superpowers:finishing-a-development-branch`.

## This session: root cause of "-100" / garbled prints found and fixed, plus a real crash

Picked up from the previous handoff's blocker (`gs_error_Fatal`/`-100` on a
real-PDF print job). Root-caused via `superpowers:systematic-debugging` on
physical hardware (Samsung SM-S921E + HP DeskJet 2300 series, both wireless
adb and USB cable used across the session).

### Finding 1 (misdiagnosis in prior handoff): `-100` is a real Ghostscript code

`gs_error_Fatal = -100` is a legitimate gsapi pseudo-error
(`app/src/main/cpp/include/gserrors.h:78`), not `calloc` failing in
`gsjni.c:35` as previously assumed (that branch also happens to return
`-100`, which caused the confusion). Not otherwise relevant.

### Finding 2 (root cause, FIXED): missing `APDK_LITTLE_ENDIAN` define

`app/src/main/cpp/hpcups/ErnieFilter.h`'s `get4Pixel`/`put4Pixel`, and
`app/src/main/cpp/hpcups/CommonDefinitions.h`'s `GetRed`/`GetGreen`/`GetBlue`,
all branch on a **compile-time** macro `APDK_LITTLE_ENDIAN` (not the
`m_eEndian` runtime check `ErnieFilter`'s constructor computes — that's
computed but never actually used to gate these macros, a red herring).
`app/src/main/cpp/CMakeLists.txt` never defined this macro for our arm64-v8a
build (HPLIP's original x86 Linux build system did). Result: on this
little-endian device, pixel color decode/encode took the big-endian
bit-shift path, corrupting every pixel `ErnieFilter` touched — reproduced as
a printed page showing a solid black bar where "Hello, Printer!" should be
(smoke-test PDF, `app/src/androidTest/assets/smoke.pdf`), confirmed via
`adb pull`ed raw PPM (Ghostscript's own output, byte-verified clean/correct)
vs the corrupted PCL3-GUI bytes downstream of `ErnieFilter`.

**Fix**: `app/src/main/cpp/CMakeLists.txt` now defines `APDK_LITTLE_ENDIAN`
in `target_compile_definitions(hpcupsjni ...)`.

### Finding 3 (fixed, was inert): off-by-one in ErnieFilter buffer sizing

`app/src/main/cpp/hpcups/ErnieFilter.cpp` constructor,
`maxCompressionBufSize` computation did integer division
`(rowWidthInPixels-2)/255` **before** casting to `double` for `ceil()`,
defeating the round-up. Fixed to divide in floating point. This buffer
(`m_compression_out_buf`) turned out to be allocated but never written
anywhere in the file — harmless in practice, but a real latent bug, fixed
while in the area.

### Finding 4 (workaround, Ernie disabled): block-merge bug — separate from Finding 5

With Finding 2 fixed but before Finding 5's fix, printed text was legible
but sat on a spurious gray/teal band. A/B tested at the time: specific to
`ErnieFilter`, not present with it disabled. **Ernie remains disabled**
(`app/src/main/cpp/hpcups/Pcl3Gui2.cpp`, `m_run_ernie_filter = false`, with
a comment) as a deliberate quality-only tradeoff (loses edge-sharpening;
content/position/color are otherwise correct). Not revisited after Finding 5
was found and fixed — worth re-testing with Ernie re-enabled in a future
session now that the real cross-job state leak is gone, in case Finding 4
was partly caused by it too. If not, the real bug is still somewhere in
`ErnieFilter::submitRowToFilter`'s 4-row buffering cycle.

### Finding 5 (root cause, FIXED): `Job`'s pipeline/encapsulator never torn down between jobs

Found while re-running `./gradlew :app:connectedDebugAndroidTest`.
`NativePipelineFixtureTest` passed in isolation but **failed**
(`PCL output should be non-trivial`, 831 bytes instead of >1024) when run in
the same instrumentation process as `LegacyPipelineWiringSmokeTest` — i.e.
on the *second* `hpcups_main()` call in one process.

Root-caused with temporary diagnostic logging (added and fully reverted —
see git history if this needs redoing): both calls' 88 non-blank content
rows had byte-identical checksums reaching `Job::SendRasters`, proving the
raster *input* was correct both times — the corruption was purely inside
`Encapsulator`/pipeline execution.

Real cause: `Job::Cleanup()` (called every job via `HPCupsFilter::closeFilter()`)
never freed `m_pPipeline`'s chain or `m_pEncap` — only `Job::~Job()` does
that, which never runs because `Job` is a member of the file-static
`HPCupsFilter filter` (`HPCupsFilter.cpp:48`, `static HPCupsFilter filter;`)
that lives for the process lifetime. Real CUPS invokes hpcups as a fresh OS
process per job, so relying on the destructor was fine upstream. On job 2,
`Pcl3Gui2::Configure()` read `m_pPipeline`'s stale, already-executed pointer
from job 1 and appended job 2's new `Mode10` phase onto job 1's finished
pipeline instead of starting fresh — job 2's real rows got processed through
job 1's stale, already-seeded compressor first, truncating output.

**Fix**: `app/src/main/cpp/hpcups/Job.cpp`, top of `Job::Init()` — free any
leftover pipeline chain and delete any leftover `m_pEncap` before wiring up
the new job's encapsulator, mirroring what `~Job()` already does at true
process teardown. Verified: 5 consecutive `am instrument` runs of both
tests together all pass; `./gradlew :app:connectedDebugAndroidTest` is
green.

**This is plausibly the real explanation for the original bug report**: the
PDF that produced the `-100` error in the previous session almost certainly
wasn't the very first print job attempted that session.

### Finding 6 (root cause, FIXED): `ServerService` crashed on Android 14+ due to `startForeground()` ordering

Discovered during final hardware re-verification (unrelated to the native
pipeline — this is a pure Kotlin/manifest bug, pre-existing before this
session, just not previously hit because it only reproduces with a fresh
app UID and Android 14+'s stricter foreground-service-type enforcement).

`ServerService.onStartCommand()` called `startForeground()`
**unconditionally as the first line**, before checking whether a USB
printer was even attached or permitted. `AndroidManifest.xml` declares
`foregroundServiceType="connectedDevice"` for this service — Android 14+
requires the app to **already hold USB device/accessory permission** at the
exact moment `startForeground()` is called for that type, or it throws
`SecurityException` and crashes the service.

The first fix attempt (checking permission inside `onStartCommand` and
calling `stopSelf()` instead of `startForeground()` on failure) was
**wrong**: once `MainActivity` calls `Context.startForegroundService()`,
Android requires `Service.startForeground()` to be called within the
promotion window *no matter what* — `stopSelf()` does not satisfy that
requirement, and skipping `startForeground()` entirely crashes with a
*different* exception, `ForegroundServiceDidNotStartInTimeException`.

**Real fix**: moved the permission gate to the caller,
`app/src/main/java/dev/jaspreet/printserver/MainActivity.kt` — added
`startServerIfPermitted()`, which only calls `startForegroundService()` once
`UsbPrinterManager.hasPermission(device)` is already true. If not, it calls
`requestPermission()` and relies on a new `usbPermissionReceiver`
(registered for `UsbPrinterManager.ACTION_USB_PERMISSION`) to retry once the
user answers the system dialog (or once the OS auto-grants permission via
the `USB_DEVICE_ATTACHED` manifest intent-filter match). `ServerService`
itself was reverted back to calling `startForeground()` unconditionally and
immediately, as it must.

Verified: tapping "Start Server" with no printer connected no longer
crashes (silently no-ops, matching `findPrinter() == null`); with the
printer connected, tapping it starts the service, notification appears,
`curl`/`ipptool` reach the IPP server, and a real print of `smoke.pdf`
produces clean legible output.

## Do NOT merge to main until

- [x] A real print job completes without exception (verified via `ipptool`
      against the live `LocalIppServer` + physical HP DeskJet 2300, multiple
      times across this session)
- [x] Physical paper comes out, content legible/correct (verified — final
      state has Ernie disabled, see Finding 4)
- [x] `./gradlew :app:testDebugUnitTest` — all 15 classes pass, 0 failures
- [x] `./gradlew :app:connectedDebugAndroidTest` — green (was the Finding 5
      blocker; fixed and reverified)
- [x] `ServerService` no longer crashes on Android 14+ (Finding 6)
- [ ] Then: `superpowers:finishing-a-development-branch` skill for the
      actual merge/PR flow — do not merge manually without it

## Useful context

- Full Tier 2 plan: `docs/superpowers/plans/2026-07-16-tier2-legacy-driverless.md`
- Tier 2 design spec: `docs/superpowers/specs/2026-07-16-tier2-legacy-driverless-printing-design.md`
- Hardware checklist (has a Tier-2 section now): `docs/superpowers/testing/hardware-smoke-checklist.md`
- Native build scripts: `native/build-ghostscript.sh`, `native/fetch-hpcups-sources.sh`
  (both fully reproducible from scratch — verified during a prior session)
- CLAUDE.md at repo root has full architecture overview
- Device used this session: Samsung SM-S921E, both wireless adb
  (`adb connect <ip>:<port>`) and USB cable were used at different points.
  Having both a wireless AND USB adb entry for the same physical device
  simultaneously confuses `connectedDebugAndroidTest` (generic "Process
  crashed" before any test starts) — `adb disconnect <wireless-serial>` when
  a USB cable is also plugged in, or target the USB serial explicitly with
  `adb -s <serial>`.
- Reinstalling the app repeatedly during a session churns its Android UID;
  USB device permission grants are scoped per-UID, so a stale grant from an
  earlier install becomes orphaned. If `printerText` shows "plug in a USB
  printer" despite the printer being attached (`dumpsys usb` shows it), or
  `dumpsys usb`'s `device_permissions` block lists a UID that doesn't match
  `dumpsys package dev.jaspreet.printserver | grep uid=`, a full unplug +
  wait 2s + replug of the USB-OTG cable is needed to re-trigger the OS's
  auto-permission-grant for the current UID (works via the
  `USB_DEVICE_ATTACHED` manifest intent-filter — no dialog shown when this
  app is the sole matching app for the device filter). Check
  `dumpsys usb | grep device_name=/dev/bus` — the device path (e.g.
  `/dev/bus/usb/001/003`) should change on a real replug; if it doesn't, the
  replug didn't register.
- Printer: HP DeskJet 2300 series via USB-OTG, confirmed
  `192.168.0.101:8631` as the `LocalIppServer` address across sessions
