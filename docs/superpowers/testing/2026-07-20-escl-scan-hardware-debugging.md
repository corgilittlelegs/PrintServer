# eSCL Scan Hardware Debugging Log — 2026-07-20

Point-in-time engineering log from one long hardware-verification session against a
real HP DeskJet 2300-series MFP and a real macOS eSCL client (Image Capture). Not a
standing project-state doc — check `git log` and `LedmCapabilities.kt`'s doc comment
against current code before trusting anything here as still accurate.

## What was being tested

The eSCL scan server (Spec B) had passed all JVM unit tests and code review, but had
never been exercised against real hardware or a real eSCL client. This session drove it
end-to-end: real printer over USB, real Android device, real macOS Image Capture as the
client, using `adb`, `curl`, and `dns-sd` to observe what was actually happening on the
wire.

## Confirmed, fixed, protocol-level bugs

All of these were found by testing against a real client and comparing our XML/TXT
record output to either the real device's actual responses or a real client's actual
parsing source code — not guesswork.

1. **mDNS TXT record's `rs` value was `"t"` instead of `"eSCL"`**
   (`EsclTxtRecords.kt`). `rs` is the root resource path a client prepends to every
   request URL (`GET /{rs}/ScannerCapabilities`). With the wrong value, Image Capture
   computed a URL this server never receives a request on and failed with "Failed to
   open a connection to the device (-21345)" — a real, TCP-level connection failure,
   even though `curl` hitting `/eSCL/...` directly worked fine and mDNS discovery found
   the device correctly. This was the single highest-impact fix of the session: nothing
   about eSCL worked from a real client before this.

2. **`ScannerStatus`'s top-level state was `<scan:State>` instead of `<pwg:State>`**
   (`EsclXml.kt`). Confirmed against `sane-airscan`'s actual C source
   (`airscan-escl.c`'s `escl_parse_scanner_status()`), which explicitly looks up
   `pwg:State`, not `scan:State`. With the wrong namespace, a client could never
   confirm the scanner was Idle, so it polled `ScannerStatus` forever and never
   progressed to `POST /eSCL/ScanJobs` — even though capabilities were being served
   correctly.

3. **eSCL protocol version hardcoded to `"2.63"`** — that's HP's internal LEDM
   protocol version number (copied verbatim from a real captured LEDM `ScanCaps`
   response), not a real eSCL version. Real eSCL versions look like `"2.0"`. Changed to
   `"2.0"`.

4. **`LedmCapabilities.parse()`'s resolution regex never matched the real device's XML
   shape** — the real device nests `<Resolution><XResolution>N</XResolution>
   <YResolution>N</YResolution></Resolution>`, not the flat `<Resolution>N</Resolution>`
   the original regex assumed. Every eSCL client was being told the scanner supported
   zero resolutions. Fixed to parse `<XResolution>` within the `<SupportedResolutions>`
   block.

5. **`ScanPipeline`'s `Width`/`Height` were fixed 300dpi pixel counts** regardless of
   requested resolution — a 600dpi request would only capture a quarter of the page (a
   crop), not the full page at higher DPI. Fixed to scale proportionally to the
   requested resolution.

**Proof these are real, not theoretical**: over the course of this session, Image
Capture went from "cannot connect at all" → discovers the device, connects, fetches
real capabilities (`MaxWidth=2550`, 6 real resolutions), confirms Idle status, and
actually `POST`s scan jobs (it retried on its own 8 times in one run). That is full
eSCL protocol compliance, confirmed against an unmodified real Apple client.

## USB-transport-level fixes, grounded in HPLIP's actual reference source

HPLIP (`scan/sane/bb_ledm.c`, `io/hpmud/musb.c`) is the real, working reference
implementation this LEDM protocol was ported from. Comparing our Android USB
implementation against it (not against our own assumptions) surfaced real gaps:

1. **Endpoint halt/data-toggle clearing.** HPLIP's `musb_raw_channel_close` calls
   `libusb_clear_halt()` on both bulk endpoints before releasing an interface — resetting
   host/device data-toggle synchronization on every channel close. Our
   `claimInterface()`-only approach never did this. Android's `UsbDeviceConnection` has
   no public `clearHalt` equivalent, so we issue the same standard
   `CLEAR_FEATURE(ENDPOINT_HALT)` control transfer by hand, on both endpoints, right
   after claim (`UsbPrinterManager.openInterface`).

2. **Fresh connection per logical request.** HPLIP's `bb_start_scan()` opens/closes its
   `http_handle` separately for the status check, the create-job request, and the final
   binary fetch — reusing one connection only across the poll loop's own iterations.
   `ScanPipeline` previously held one connection open across the *entire*
   status→createJob→poll→fetch sequence. Refactored to match: `ScanPipeline` now takes
   an `openTransport: () -> UsbTransport` factory and opens/closes per logical request.

3. **Read timeout.** HPLIP uses a 10-second read timeout for these same requests; ours
   was 60s. Reduced to 10s so a dead read fails fast enough for the (also newly added)
   retry logic to actually get a chance to try again, instead of a single dead read
   burning a full minute.

4. **Scan-level retry.** HP's own official Known Issues documentation states: "Channel
   write error" for the LEDM protocol "is a limitation with the I/O interfaces for the
   device. If this occurs, retry the same operation after a few seconds." This is HP
   acknowledging the exact failure mode we were seeing as a known hardware/firmware
   limitation, not a driver bug — with retry as the *official* recommended fix. Added
   `ServerService.scanWithRetry`: up to 3 attempts, 2s apart. (Previously only the
   capability query had retry logic, not the actual scan.)

5. **Capability-query hardening.** `LedmCapabilities.query()` now rejects
   degenerate/empty parses (zero max size, no resolutions, no color modes) as failures
   instead of silently returning them — a misframed/truncated read can "parse" without
   throwing but produce garbage. `ServerService.queryScanCapabilitiesWithRetry` retries
   this up to 4 times.

## A real regression found and reverted during this session

HPLIP's `musb_read()` treats a zero-length `bulkTransfer` result as "nothing yet, not an
error" and loops retrying internally against a shrinking timeout budget, rather than
failing immediately. This was implemented in `AndroidUsbTransport.read()` to match — and
hardware testing showed it made things **measurably worse**: failures went from
intermittent (sometimes the scanner would physically activate and get well into an
actual scan) to **consistently failing on the very first request, every single attempt,
even after both a USB replug and a full printer power cycle**.

The working theory: a tight retry loop with no delay between iterations further
destabilizes an already-marginal USB interface rather than riding out a transient blip —
essentially self-inflicted congestion. This was reverted back to a single-shot read
(`AndroidUsbTransport.read()`, see its doc comment) — do not re-attempt this without new
evidence (a real USB traffic capture showing *why* zero-length packets happen and
whether immediate retry vs. a delayed retry actually helps).

## Settle delays added (unproven but not shown to hurt)

Two fixed-duration pauses were added based on where hardware testing observed failures
clustering:

- `UsbPrinterManager.openScanTransport`: 150ms after claiming the interface, before any
  traffic — the very first request on a freshly claimed connection was seen failing
  even with halt cleared.
- `ScanPipeline.scan()`: 1500ms after the create-job response is accepted, before
  polling starts — the flatbed carriage/lamp physically starts moving right around this
  point, and reads were seen failing specifically in this window (motor-startup
  electrical noise is the leading suspect, unconfirmed).

Unlike the reverted retry loop, these are pure added latency (not busy-looping) and
were not observed to make things worse. They're speculative mitigations, not confirmed
fixes — keep them, but don't treat their presence as evidence they're doing anything.

## Current state: what actually still fails

Even with every fix above in place, scans against the real hardware are **genuinely
non-deterministic**. Across many attempts in this session:
- Sometimes the very first request (`GET /Scan/Status`) fails or times out.
- Sometimes everything succeeds through capability query, status, create-job, and the
  scanner physically activates (carriage moves, lamp lights) and gets significantly
  into an actual scan before failing.
- A USB replug and even a full printer power-cycle did not produce a consistently
  working state — behavior varies attempt to attempt.

This pattern (failures at inconsistent points, not a single deterministic step) points
to real USB signal-integrity issues specific to this phone / OTG cable / printer
combination, not a remaining logic bug in this codebase. HPLIP's own official
documentation acknowledges this class of failure as a real, known limitation for LEDM
devices generally.

## What would actually move this forward

Everything achievable through log-reading, curl, and reference-source comparison has
been done. The next real step needs USB bus-level visibility that isn't available from
this session:

- A USB traffic capture (Wireshark + `usbmon` on a rooted Android device, or attaching
  the same printer to a Linux PC running real HPLIP and capturing with Wireshark there)
  to see what's actually happening at the packet level during a failure — is the device
  genuinely not sending anything, sending a malformed packet, or is something being lost
  on the host side?
- Trying a different USB-OTG cable/adapter, since cheap OTG adapters are a well-known
  real-world source of intermittent bulk-transfer flakiness, independent of any code.
- Trying a different Android device, to isolate whether this phone's specific USB host
  controller chip is the variable.

## Files most relevant to this history

- `app/src/main/java/dev/jaspreet/printserver/scan/LedmCapabilities.kt` — carries a
  living "known issue" doc comment; check it for the most current understanding before
  re-investigating from scratch.
- `app/src/main/java/dev/jaspreet/printserver/scan/ScanPipeline.kt`
- `app/src/main/java/dev/jaspreet/printserver/usb/AndroidUsbTransport.kt`
- `app/src/main/java/dev/jaspreet/printserver/usb/UsbPrinterManager.kt`
- `app/src/main/java/dev/jaspreet/printserver/service/ServerService.kt`
- `app/src/main/java/dev/jaspreet/printserver/escl/EsclXml.kt`,
  `EsclTxtRecords.kt`

Relevant commits (chronological): `aafecfd`, `1ca0908`, `19fe5d0`, `462fd2d`, `c8aef67`,
`2c8d82e`, `aa231c4`, `625408e`, `b8a0bd5`, `3d524b9`, `2548aec`, `2d7b319`, `775a1c0`,
`4515c84`, `7e00141`.
