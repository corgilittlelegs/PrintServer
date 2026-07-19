# eSCL Scan Server (Spec B) Design

**Status:** Spec B of the two-spec scan-support feature. Builds directly on Spec A
(`docs/superpowers/specs/2026-07-19-native-scan-pipeline-design.md`,
`docs/superpowers/plans/2026-07-19-scan-pipeline.md`), which is implemented and
hardware-verified — `ScanPipeline`, `LedmRequests`, `LedmResponses`, `ChunkedHttp`,
`PullReader`, and `ScanUsb`/`UsbPrinterManager.openScanTransport` all exist and work
against the real DeskJet 2300-series MFP.

## Goal

Any device on the LAN (macOS, Windows, iOS, Linux) discovers this app as a network
scanner via mDNS and scans directly from its native scan app — zero drivers — the same
driverless philosophy the print side already delivers via `LocalIppServer`.

## Why this is a separate spec from Spec A

Spec A proved the USB/LEDM protocol layer works. This spec is the network-facing layer on
top of it: a new HTTP server speaking eSCL, a new mDNS service type, and a capability/
request-translation layer between eSCL's wire format and `ScanPipeline`'s Kotlin API.
Architecturally this is the scan-side mirror of `LocalIppServer`/`PrinterCapabilities`,
not an extension of Spec A's files themselves (aside from widening `ScanPipeline`'s
signature, see below).

## Architecture

A new `LocalEsclServer` (new `escl` package), structurally a direct sibling of
`LocalIppServer`: its own `ServerSocket`, its own accept-loop, reusing the existing
`http/` package (`HttpHead`, `BodyReader`) for request parsing — no new third-party HTTP
library, consistent with this project's existing dependency-minimalism (only non-AndroidX
dependency today is `jipp-core`). It implements the standard eSCL endpoints:

- `GET /eSCL/ScannerCapabilities`
- `GET /eSCL/ScannerStatus`
- `POST /eSCL/ScanJobs`
- `GET /eSCL/ScanJobs/{id}/NextDocument`
- `DELETE /eSCL/ScanJobs/{id}`

`DiscoveryAdvertiser` gains one new method, `advertiseEscl(name, port, txt)`, for the
`_uscan._tcp` mDNS service type — the same shape `advertiseIpp` already has in
`NsdAdvertiser`, added alongside it, not replacing anything.

`ScanPipeline` (from Spec A) is widened to accept two new parameters: a resolution
(`Int`, dpi) and a new `ScanColorMode` enum (`COLOR`, `GRAYSCALE`) — deliberately a
*new* enum, not a reuse of print's `ColorMode`, since it lives in the `scan` package
alongside `ScannerState`/`PollResult`, matching how those are named for scan-specific
concepts. Only two values, not three: `bb_ledm.c`'s `ce_element` table defines a `K1`
(1-bit black-and-white) mode, but its own job-creation code
(`bb_start_scan()`'s `BitDepth` computation) hardcodes `8` on every branch regardless —
true 1-bit output isn't actually reachable through this exact protocol path, so
modeling a `BLACK_AND_WHITE` value would advertise a mode that silently downgrades to
grayscale. Anything that isn't `Color8` maps to `ColorSpace=Gray` at 8-bit depth.

A new `LedmCapabilities` module queries `GET /Scan/ScanCaps` live over the USB transport
(reusing Spec A's existing `LedmRequests`/`ChunkedHttp`/`PullReader` machinery — no new
USB/HTTP-framing code needed) and parses the real `<Platen>` min/max size,
`<SupportedResolutions>`, and `<ColorEntries>` tags into a plain data class. This mirrors
Tier 1's `PrinterQuery` (live capability query) rather than Tier 2 print's hardcoded
`PrinterCapabilities` — no guessed/hardcoded resolution or color-mode list anywhere,
since this device's real capabilities are queryable at runtime.

A new `EsclXml` module builds/parses eSCL's own XML wire format: the
`ScannerCapabilities` response, the incoming `ScanSettings` request body, and the
`ScanJobs` response. This is a genuine translation layer (eSCL's schema differs from
LEDM's own XML despite shared HP lineage), not a passthrough.

## Data flow

1. Client discovers the scanner via mDNS `_uscan._tcp`, connects, sends
   `GET /eSCL/ScannerCapabilities`.
2. `LocalEsclServer` calls `LedmCapabilities.query(transport)` (opens the scan USB
   interface via `UsbPrinterManager.openScanTransport`, same as Spec A's hardware test
   does) → live `GET /Scan/ScanCaps` → parses the real platen size/resolutions/color
   modes → `EsclXml` serializes these into the eSCL capabilities response.
3. Client picks resolution/color/region from what was actually offered and sends
   `POST /eSCL/ScanJobs` with a `ScanSettings` XML body.
4. `LocalEsclServer` parses that body via `EsclXml`, maps eSCL's requested
   resolution/color values onto `ScanPipeline`'s new parameters — clamping anything
   unsupported or unrecognized to the device's actual default, the same clamping pattern
   `LocalIppServer.resolveQuality`/`resolveColorMode` already established for print — then
   calls `ScanPipeline.scan(transport, resolution, colorMode, outputFile)`.
5. The `POST` response includes a `Location` header pointing at this app's own
   `NextDocument` URL for the new job (this app's job-id space, independent of and not
   to be confused with LEDM's own internal job URL from Spec A's protocol, which stays
   entirely inside `ScanPipeline`).
6. Client `GET`s that URL; `LocalEsclServer` streams back the JPEG `ScanPipeline` already
   wrote to disk.
7. Client may `DELETE` the job afterward to signal cleanup/cancellation.

Because this hardware is flatbed-only (confirmed in Spec A), there is no ADF job state
to model — every job is a single page, matching `ScanPipeline`'s existing scope.

## Error handling

`ScanPipeline`'s existing `IOException` messages (scanner busy, no document, canceled)
are caught by `LocalEsclServer` and mapped to distinct eSCL job-state values in the
`ScanJobs`/`NextDocument` responses (a client sees the eSCL spec's own defined error/
canceled job states, not a raw HTTP 500). Malformed or oversized client request bodies
reuse `BodyReader`'s existing `BodyTooLargeException`/parse-failure handling verbatim,
the same as `LocalIppServer` today. Concurrent connections are bounded by a semaphore,
matching both existing servers' DoS-hardening pattern.

## Testing

- `EsclXml` and `LedmCapabilities`' parsing logic are pure functions — JVM-unit-tested
  against literal captured XML strings, the same style Spec A used for `LedmResponses`.
- `LocalEsclServer` is JVM-unit-tested end-to-end using `FakePrinterTransport` (for the
  USB side) plus a real socket client (mirrors `LocalIppServerTest`'s existing pattern
  exactly — no fake needed for the HTTP side since it's a real `ServerSocket`).
- Real hardware smoke test: a device `androidTest` plus manual verification from an
  actual client (macOS Image Capture/Preview's scan feature, or `scanimage`/`escl`-aware
  CLI tools on Linux), added to
  `docs/superpowers/testing/hardware-smoke-checklist.md` following that doc's existing
  conventions.

## Explicitly out of scope for this spec

- ADF/multi-page scanning (hardware doesn't have one; unchanged from Spec A).
- Any scan feature beyond resolution + color mode (e.g. cropping to a client-specified
  region beyond the device's full platen, brightness/contrast) — deferred to a future
  spec if ever needed, matching how Tier 2 print options were scoped incrementally.
- Retry semantics for failed scan jobs (a failed job is simply reported as failed; no
  automatic resubmission, matching `ScanPipeline`'s current single-attempt behavior).
