# AGENTS.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

App is built and working, not a stub. Both tiers (Tier 1 relay, Tier 2 native rendering) are implemented, tested, and hardware-verified end-to-end against a real HP DeskJet 2300 series printer. 64+ commits of history exist — this is a real git repo, fully init'd.

A third feature, **printer-info UI** (manufacturer/model/serial/tier/connect-time display), was added after the two tiers and is also complete — see `docs/superpowers/specs/2026-07-17-printer-info-ui-design.md` / `docs/superpowers/plans/2026-07-17-printer-info-ui.md`.

Plans in `docs/superpowers/plans/` remain useful as historical design records and for understanding *why* something is structured a certain way, but do not treat them as "not yet done" — check the actual source under `app/src/` first. If you're adding genuinely new functionality (not covered by an existing plan), still write a plan/spec first per `superpowers:writing-plans` rather than freelancing architecture.

## What this project is

An Android app that turns a USB-connected printer into a driverless network printer: any device on the LAN (macOS, Windows, iOS, Linux, other Android) discovers it via mDNS and prints to it with zero drivers installed, the same way AirPrint/IPP-Everywhere printers work.

1. **Tier 1 — IPP-USB passthrough** (`docs/superpowers/specs/2026-07-16-usb-ipp-print-server-design.md`). For printers made ~2013+ that speak IPP natively over the USB cable (USB interface class 7 / subclass 1 / protocol 4). The app is a pure byte relay — it never parses or renders documents. Clients render to PDF/PWG-Raster themselves; the app pipes HTTP between a LAN socket and the printer's USB bulk endpoints.

2. **Tier 2 — on-device rendering for host-based printers** (`docs/superpowers/specs/2026-07-16-tier2-legacy-driverless-printing-design.md`). For older/cheap USB printers with no IPP-USB (host-based/GDI designs, e.g. HP DeskJet Ink Advantage family) that only understand a proprietary PDL. Here the app *is* the IPP printer: it runs its own synthetic IPP server, rasterizes incoming PDFs with a cross-compiled Ghostscript, then encodes to the printer's PCL3-GUI dialect with a cross-compiled `hpcups` (from HPLIP), before writing to USB. This tier requires an Android NDK/CMake native build; Tier 1 does not.

Both tiers share one foreground service, one mDNS advertiser, and a raw port-9100 fallback for clients that already have the printer's vendor driver installed.

## Architecture

```
Client (LAN) ──HTTP/IPP──> [Android app :8631] ──USB bulk──> Printer
```

- **Tier 1 path**: `IppRelayServer` leases a channel from `ChannelPool` (one exclusive channel per IPP-USB interface pair — a channel must complete one full HTTP transaction before release; a channel that errors mid-transaction is discarded, never released back to the pool) and calls `HttpRelay.forward`, which streams the client's request straight to the printer's USB channel and the printer's HTTP response straight back. `PrinterQuery` does one JIPP-based `Get-Printer-Attributes` call at startup to build the mDNS TXT record (`TxtRecords`).
- **Tier 2 path**: `LocalIppServer` answers IPP itself using hardcoded `PrinterCapabilities` (there's no real printer-side IPP to query). `Print-Job` spools the PDF into a single-worker `JobQueue` (deliberately serial — the native Ghostscript/hpcups libraries are not reentrant), which runs it through `RenderingPipeline` (Ghostscript JNI → PPM, `PpmImage` parses it, `hpcups` JNI → PCL3-GUI) before writing the result to the printer's USB transport.
- **Shared USB layer**: all protocol/relay code talks only to the `UsbTransport` interface (`write`/`read`/`close`), never to Android's `UsbManager`/`UsbDeviceConnection` directly. This is what makes the HTTP/IPP/queue logic testable on the plain JVM via `FakePrinterTransport` — only `AndroidUsbTransport` and `UsbPrinterManager` touch real Android USB APIs, and those are verified on-device, not by JVM unit tests.
- **Discovery**: `DiscoveryAdvertiser` is an interface around `NsdManager` (`NsdAdvertiser`) specifically so a flaky-NsdManager fallback (an NDK-embedded mDNSResponder) can be swapped in later without touching relay code.
- **Lifecycle**: everything lives inside one `ServerService` (foreground service + partial wakelock during active jobs + USB-detach handling); the service binds only to the Wi-Fi interface (`WifiAddress`), never 0.0.0.0 or cellular.
- **Printer info**: `PrinterInfo.kt` + IEEE-1284 device-id parsing populate manufacturer/model/serial/tier/connect-time, surfaced in `MainActivity`'s UI and cleared when the server stops.
- **Activity log**: `activity/ActivityLog.kt` tracks PRINTED/FAILED job entries, capped at 200 retained. Tier 1 detects print activity by peeking the first 4 bytes (version + operation-id) of `application/ipp` request bodies in `IppRelayServer`, then re-prepends those bytes via `SequenceInputStream` so `HttpRelay.forward` still sees the original byte stream untouched — it never inspects attribute-groups or document content. `JobStateMapping.kt` maps Tier 2 `JobState` to `ActivityStatus`. Surfaced via `ActivityCard` in `PrintServerApp`.
- **DoS hardening**: `IppRelayServer`/`LocalIppServer` cap concurrent LAN connections with a semaphore (excess closed immediately, not queued); `JobQueue` evicts terminal jobs past 200 retained, matching `ActivityLog`'s cap. Both guard against unbounded thread/memory growth from a flood of connections on a long-running session.
- **Scan pipeline (Spec A)**: `ScanPipeline` drives HP's LEDM scan protocol — real HTTP/1.1 over a raw USB bulk pipe (interface 255/4), the same shape Tier 1 uses for IPP-USB printing — to pull a flatbed scan off the DeskJet 2300-series MFP as a JPEG file. No network-facing scanning yet (no eSCL server, no mDNS `_uscan._tcp` advertisement) — that's a separate, not-yet-built follow-on (Spec B).

Source layout: `app/src/main/java/dev/jaspreet/printserver/{discovery,http,ipp,jobs,relay,render,service,usb}/` (31 Kotlin files) plus `MainActivity.kt`. Tests: `app/src/test/` (18 JVM unit test files, using `FakePrinterTransport` / `FakeRenderingPipeline`), `app/src/androidTest/` (2 device-only tests: `LegacyPipelineWiringSmokeTest`, `NativePipelineFixtureTest`, plus bundled `smoke.pdf`).

## Native build (Tier 2)

`app/src/main/cpp/` contains a full CMake build: `gsjni` (JNI wrapper around prebuilt `app/src/main/jniLibs/arm64-v8a/libgs.so`) and `hpcupsjni` (compiles HPLIP's `hpcups/` + `hpcups-common/` + a bundled CUPS-raster subset under `cupsraster/cups/`). `abiFilters` is `arm64-v8a` only.

`native/build-ghostscript.sh` and `native/fetch-hpcups-sources.sh` are **one-time manual setup scripts** — run them before the first Gradle build to cross-compile Ghostscript and populate the `cpp/` source tree; not part of the normal `./gradlew` flow and don't need re-running unless that native tree is wiped.

## Commands

```bash
./gradlew :app:assembleDebug                                          # build
./gradlew :app:testDebugUnitTest                                      # all JVM unit tests (no device needed)
./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.relay.ChannelPoolTest"   # single test class
./gradlew :app:connectedDebugAndroidTest                               # device-only tests (Tier 2 native smoke test)
./gradlew :app:installDebug                                            # install on a connected device for manual/hardware testing
```

Hardware smoke-test checklist (manual, run against real printers/clients — not automated): `docs/superpowers/testing/hardware-smoke-checklist.md`.

## Hardware-testing gotchas (learned the hard way — see SESSION_HANDOFF.md for full detail)

- Wireless adb + USB cable connected to the **same** device simultaneously confuses `connectedDebugAndroidTest` (generic "Process crashed" before any test runs). Fix: `adb disconnect <wireless-serial>` when a USB cable is also plugged in, or target explicitly with `adb -s <serial>`.
- Reinstalling the app churns its Android UID; USB permission grants are UID-scoped, so a stale grant from an earlier install goes orphaned. Symptom: UI says "plug in a USB printer" despite `dumpsys usb` showing it attached, or `dumpsys usb`'s `device_permissions` UID doesn't match `dumpsys package dev.jaspreet.printserver | grep uid=`. Fix: unplug, wait ~2s, replug the USB-OTG cable to re-trigger the OS auto-grant. Verify the replug actually registered via `dumpsys usb | grep device_name=/dev/bus` (device path should change).
- Android 14+ requires USB permission to already be held at the exact moment `startForeground()` is called for a `connectedDevice`-typed service, or it throws `SecurityException`. The permission gate must live in the caller (`MainActivity.startServerIfPermitted()`), not inside `ServerService` — `ServerService.onStartCommand()` must call `startForeground()` unconditionally and immediately, since skipping/deferring it throws `ForegroundServiceDidNotStartInTimeException` instead.
- HPLIP's native code (`ErnieFilter`, `CommonDefinitions.h`) branches on the compile-time macro `APDK_LITTLE_ENDIAN`, not any runtime endianness check — if this ever needs re-adding after a CMake change, define it in `target_compile_definitions(hpcupsjni ...)`, not in Kotlin/Java.
- Native singletons matter: `HPCupsFilter` is a file-static instance (`static HPCupsFilter filter;` in `HPCupsFilter.cpp`) that lives for the whole process, unlike real CUPS which forks a fresh process per job — any native pipeline/encapsulator state must be explicitly torn down at `Job::Init()`/`Cleanup()`, not left to a destructor that will never run between jobs in this process model.

## Key constraints to respect when implementing

- Package is `dev.jaspreet.printserver`, min SDK 26, compileSdk/targetSdk 35, Kotlin + coroutines, AGP 8.5.2 / Kotlin 2.0.20, Java/Kotlin target 17. No Gradle version catalog (`libs.versions.toml`) — versions are hardcoded in the build files.
- Only non-AndroidX Tier-1 dependency: `com.hp.jipp:jipp-core` (IPP packet parsing). Tier 2 adds no new Kotlin deps but does add a full NDK/CMake native build (Ghostscript AGPL, hpcups/CUPS-raster GPL/Apache-2.0) — fine for personal sideload, but licensing must be revisited before any Play Store release (see `app/src/main/assets/licenses/`).
- Servers bind to the Wi-Fi interface address only, never `0.0.0.0` — see `WifiAddress`.

## Other docs in this repo

- `AGENTS.md` — same content as this file, for Codex; keep both in sync when editing either.
- `SESSION_HANDOFF.md` — a point-in-time engineering log from one debugging session (native endian bug, cross-job state leak, Android 14 foreground-service crash), not a standing project-state doc. Check its "merged into main?" claim against `git log` before trusting it — it may predate a merge that has since happened.
