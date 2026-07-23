# Scan Reliability Report

**Date:** 2026-07-23  
**Scope:** HP DeskJet 2300-series flatbed scanning through this Android print-server app.

## Executive summary

The eSCL server implementation is functionally present and its JVM tests pass, but scan
availability and scan failures are almost invisible to the user. The service can be shown
as successfully running for printing while the scanner was never started. On the tested
hardware, the remaining failures are non-deterministic USB LEDM transport failures rather
than a known eSCL protocol defect.

The immediate work should therefore be observability and reproducible hardware diagnosis,
not further speculative changes to HTTP framing or retry loops.

## What is already fixed

The following earlier protocol issues are fixed in the current source:

- The `_uscan._tcp` TXT record advertises `rs=eSCL`, matching the served paths.
- `ScannerStatus` reports the required `pwg:State` element.
- eSCL advertises version `2.0`, not HP's unrelated LEDM protocol version.
- Live scan capabilities parse the actual nested LEDM resolution XML.
- Requested scan width and height scale with requested resolution.
- The scan path clears endpoint halt state, opens fresh USB connections per logical LEDM
  operation, uses bounded capability/scan retries, and includes short settle delays.

These changes are covered by JVM tests and were sufficient for macOS Image Capture to
discover the scanner, fetch capabilities, confirm it is idle, and submit scan jobs.

## Current failure modes

| Symptom | Current behavior | Root cause / interpretation |
| --- | --- | --- |
| The app says it is serving, but no scanner appears on the network | The ScanCaps probe fails four times; printing continues and eSCL is not started or advertised | Expected fail-soft design, but not surfaced in UI or notification |
| Scanner appears but a submitted scan fails | eSCL job becomes `Aborted`; client gets an error instead of an image | LEDM bulk USB request/response failure, observed at inconsistent stages |
| Some attempts begin physical scanning then fail | Whole scan retries twice more after a five-second pause | The observed failure is non-deterministic; no deterministic protocol point has been isolated |
| User expects a Scan button in the Android app | No local scan UI exists | The app only exposes a network eSCL scanner on port 8632 |

The hardware-debugging record reports failures on status, job creation, polling, and image
fetch across different attempts—even after USB replug and printer power cycle. That pattern
does not support changing one parser or one request template as a reliable fix.

## Recommended fixes, in priority order

### 1. Make scan availability visible

Add explicit scan fields to `ServerStatus` and show them in the main UI and foreground
notification:

- `scanState`: `Unavailable`, `Starting`, `Ready`, `Scanning`, or `Failed`.
- `scanPort`: `8632` when ready.
- `scanFailureReason`: the final capability-probe error or latest failed scan error.
- `scanCapabilities`: detected resolutions and color modes, when available.

This is the highest-value code change. It turns the current silent failure into an actionable
status such as: “Printing ready; scanning unavailable: ScanCaps USB read timed out.”

### 2. Preserve structured diagnostics

Record the operation, attempt number, USB interface ID, endpoint addresses, elapsed time,
HTTP status line, and response framing result for every LEDM request. Do not log document
content or JPEG bytes. Surface the most recent failure in the UI and retain a small bounded
history for export through `adb logcat`.

The current code logs exception messages, but it discards each HTTP response status line and
does not identify which LEDM operation failed. That makes hardware failures hard to compare.

### 3. Add a dedicated device-level scan smoke test

Add an `androidTest` that runs `ScanPipeline` against the connected MFP and verifies that the
output is a non-trivial JPEG (magic bytes plus a reasonable minimum size). It should be
hardware-only and skipped/clearly failed when the expected scan interface is absent.

JVM tests validate the protocol logic using scripted data; they cannot validate Android USB
host-controller behavior.

### 4. Capture a known-good and failing USB exchange

This is the required step before changing LEDM transport behavior further. Capture USB traffic
from a Linux host running HPLIP against the same printer, then compare it to a rooted Android
`usbmon` capture (or equivalent). Determine whether Android receives no bytes, receives an
incomplete response, or loses framing after a specific operation.

Do not reintroduce a tight zero-byte-read retry loop: testing already showed that it made this
hardware consistently worse.

### 5. Isolate the physical USB path

Test a known-good powered OTG adapter/cable and another Android host device. The remaining
failure pattern is consistent with host/cable signal or power instability during bulk traffic,
especially around scanner-motor startup. This is a diagnostic experiment, not a code fix.

## What not to change without evidence

- Do not alter the unusual LEDM request footer or HTTP framing: it mirrors HPLIP's working
  implementation and has already reached actual scan-job submission.
- Do not increase retries indefinitely; that hides the real failure and can duplicate work.
- Do not change eSCL discovery/XML fields that have already been proven with Image Capture.
- Do not treat a running print server as proof that a scan server is running.

## Validation checklist

1. Start the service with the MFP connected and run:

   ```sh
   adb logcat -c
   adb logcat -s ServerService LocalEsclServer AndroidRuntime
   ```

2. Confirm the app reports **Scan ready** and port **8632**; otherwise capture the displayed
   capability-probe reason.
3. From macOS, confirm discovery with `dns-sd -B _uscan._tcp`.
4. In Image Capture or Preview, scan one flatbed page at 300 dpi, then 600 dpi.
5. On failure, retain the structured operation/attempt logs and note whether the scanner lamp
   or carriage started moving.
6. Repeat with a different OTG adapter/cable before changing transport code.

## Evidence

- `app/src/main/java/dev/jaspreet/printserver/service/ServerService.kt`
- `app/src/main/java/dev/jaspreet/printserver/scan/ScanPipeline.kt`
- `app/src/main/java/dev/jaspreet/printserver/usb/AndroidUsbTransport.kt`
- `docs/superpowers/testing/2026-07-20-escl-scan-hardware-debugging.md`
- `./gradlew :app:testDebugUnitTest` — passing on 2026-07-23.
