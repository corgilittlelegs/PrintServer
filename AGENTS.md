# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project state

No application code exists yet. This repo currently contains only design docs and implementation plans under `docs/superpowers/`. The Android project itself (Gradle files, `app/` module, source) gets created by executing the plans below — do not assume `./gradlew` or an `app/` directory exist until Plan 1 / Task 1 has been run.

Before writing any code here, read the relevant plan in `docs/superpowers/plans/` — each task specifies exact file paths, complete code, and the test to write first. Follow `superpowers:subagent-driven-development` or `superpowers:executing-plans` to work through them; do not freelance new architecture ad hoc.

## What this project is

An Android app that turns a USB-connected printer into a driverless network printer: any device on the LAN (macOS, Windows, iOS, Linux, other Android) discovers it via mDNS and prints to it with zero drivers installed, the same way AirPrint/IPP-Everywhere printers work.

Two specs/plans exist, in build order:

1. **Tier 1 — IPP-USB passthrough** (`docs/superpowers/specs/2026-07-16-usb-ipp-print-server-design.md`, plan `docs/superpowers/plans/2026-07-16-usb-ipp-print-server.md`). For printers made ~2013+ that speak IPP natively over the USB cable (USB interface class 7 / subclass 1 / protocol 4). The app is a pure byte relay — it never parses or renders documents. Clients render to PDF/PWG-Raster themselves; the app pipes HTTP between a LAN socket and the printer's USB bulk endpoints.

2. **Tier 2 — on-device rendering for host-based printers** (`docs/superpowers/specs/2026-07-16-tier2-legacy-driverless-printing-design.md`, plan `docs/superpowers/plans/2026-07-16-tier2-legacy-driverless.md`). For older/cheap USB printers with no IPP-USB (host-based/GDI designs, e.g. HP DeskJet Ink Advantage family) that only understand a proprietary PDL. Here the app *is* the IPP printer: it runs its own synthetic IPP server, rasterizes incoming PDFs with a cross-compiled Ghostscript, then encodes to the printer's PCL3-GUI dialect with a cross-compiled `hpcups` (from HPLIP), before writing to USB. This tier requires an Android NDK/CMake native build; Tier 1 does not.

Both tiers share one foreground service, one mDNS advertiser, and a raw port-9100 fallback for clients that already have the printer's vendor driver installed.

## Architecture (once built)

```
Client (LAN) ──HTTP/IPP──> [Android app :8631] ──USB bulk──> Printer
```

- **Tier 1 path**: `IppRelayServer` leases a channel from `ChannelPool` (one exclusive channel per IPP-USB interface pair — a channel must complete one full HTTP transaction before release; a channel that errors mid-transaction is discarded, never released back to the pool) and calls `HttpRelay.forward`, which streams the client's request straight to the printer's USB channel and the printer's HTTP response straight back. `PrinterQuery` does one JIPP-based `Get-Printer-Attributes` call at startup to build the mDNS TXT record (`TxtRecords`).
- **Tier 2 path**: `LocalIppServer` answers IPP itself using hardcoded `PrinterCapabilities` (there's no real printer-side IPP to query). `Print-Job` spools the PDF into a single-worker `JobQueue` (deliberately serial — the native Ghostscript/hpcups libraries are not reentrant), which runs it through `RenderingPipeline` (Ghostscript JNI → PPM, `PpmImage` parses it, `hpcups` JNI → PCL3-GUI) before writing the result to the printer's USB transport.
- **Shared USB layer**: all protocol/relay code talks only to the `UsbTransport` interface (`write`/`read`/`close`), never to Android's `UsbManager`/`UsbDeviceConnection` directly. This is what makes the HTTP/IPP/queue logic testable on the plain JVM via `FakePrinterTransport` — only `AndroidUsbTransport` and `UsbPrinterManager` touch real Android USB APIs, and those are verified on-device, not by JVM unit tests.
- **Discovery**: `DiscoveryAdvertiser` is an interface around `NsdManager` (`NsdAdvertiser`) specifically so a flaky-NsdManager fallback (an NDK-embedded mDNSResponder) can be swapped in later without touching relay code.
- **Lifecycle**: everything lives inside one `ServerService` (foreground service + partial wakelock during active jobs + USB-detach handling); the service binds only to the Wi-Fi interface (`WifiAddress`), never 0.0.0.0 or cellular.

## Commands (once the Gradle project exists, per Plan 1 Task 1)

```bash
./gradlew :app:assembleDebug                                          # build
./gradlew :app:testDebugUnitTest                                      # all JVM unit tests (no device needed)
./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.relay.ChannelPoolTest"   # single test class
./gradlew :app:connectedDebugAndroidTest                               # device-only tests (Tier 2 native smoke test)
./gradlew :app:installDebug                                            # install on a connected device for manual/hardware testing
```

Hardware smoke-test checklists (manual, run against real printers/clients — not automated): `docs/superpowers/testing/hardware-smoke-checklist.md` (created by Plan 1 Task 14, extended by Plan 2 Task 13).

## Key constraints to respect when implementing

- Package is `dev.jaspreet.printserver`, min SDK 26, Kotlin + coroutines.
- Only non-AndroidX Tier-1 dependency: `com.hp.jipp:jipp-core` (IPP packet parsing). Tier 2 adds no new Kotlin deps but does add a full NDK/CMake native build (Ghostscript AGPL, hpcups/CUPS-raster GPL/Apache-2.0) — fine for personal sideload, but licensing must be revisited before any Play Store release.
- Servers bind to the Wi-Fi interface address only, never `0.0.0.0` — see `WifiAddress`.
- Git: the plans include commit steps per task, but this repo was not yet `git init`'d as of the last planning session — confirm with the user before assuming git history exists or committing.
