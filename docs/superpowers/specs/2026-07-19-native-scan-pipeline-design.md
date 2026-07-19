# Scan Pipeline (Spec A) Design — Revision 2

**Status:** Spec A of a two-spec scan-support feature. This spec covers ONLY getting a
scanned image off a USB-connected HP DeskJet 2300-series MFP and onto disk, verified via
a device smoke test. It deliberately does NOT cover network/driverless scanning — that is
Spec B (eSCL HTTP server + mDNS `_uscan._tcp` discovery), a separate design doc to be
written after this one is implemented and hardware-verified.

**This revision replaces Revision 1** (native `libhpmud`/JNI approach), based on concrete
protocol research described below. Revision 1 assumed the MLC channel-multiplexing
protocol and a full native port; that assumption turned out to be wrong for this specific
hardware, and the actual protocol needs no native code at all.

## Goal

Reliably trigger a flatbed scan on the DeskJet 2300-series MFP already used for Tier 2
print testing, and retrieve the resulting image as a file on the Android device — proving
out the protocol layer before any network-facing work is built on top of it.

## Why this is split from the eventual "real" feature

Scan support is architecturally a second subsystem bolted onto this app, not incremental
work on the print path: different USB interface, different protocol, will eventually need
its own HTTP server and mDNS service type. Building the whole thing in one spec would
produce an oversized, hard-to-review plan. This spec scopes to the foundational piece only.

## Hardware findings (from the real device, via `adb shell dumpsys usb`)

The DeskJet 2300-series MFP (vendor_id=1008, product_id=13908) exposes four USB interfaces:

| Interface | class | subclass | protocol | Purpose |
|---|---|---|---|---|
| 0 | 255 (vendor-specific) | 204 | 0 | HP proprietary status/control channel |
| 1 | 7 (printer) | 1 | 2 | Print channel — already used by Tier 2 (`IppUsb.isLegacyPrinter`) |
| 2 | 255 (vendor-specific) | 4 | 1 | Scan channel (data), one of two identical-shaped interfaces |
| 3 | 255 (vendor-specific) | 4 | 1 | Scan channel (data), the other of the pair |

No standard USB Still Image class (06) is present anywhere on this device.

This model is flatbed-only (no ADF) — confirmed with the hardware owner. Spec A and its
eventual Spec B both scope to single flatbed-page scans; ADF/multi-page is an explicit
future extension, not in scope here.

## Protocol findings (from HPLIP 3.24.4 source, `scan/sane/bb_ledm.c` and `io/hpmud`)

HPLIP's `data/models/models.dat` has an entry for `[deskjet_2300_series]` with
`scan-type=7` (LEDM) and, critically, `io-mfp-mode=1` — the same `HPMUD_RAW_MODE`
(plain bidirectional bulk USB) already used for this device's print channel, NOT the
MLC channel-multiplexing protocol Revision 1 assumed.

LEDM turns out to be **literal HTTP/1.1** — real status lines, headers, and
(for one response) a `Content-Length` framed XML body — sent as raw bytes directly over
a bulk USB read/write pair. `io/hpmud`'s only role for RAW_MODE devices is "open a bulk
pipe on the right USB interface"; `hpmud_open_channel(dd, "hp-ledm-scan")` on a RAW_MODE
device just claims the interface, it does no protocol framing. That means we can bypass
`libhpmud`/`libusb` entirely and reuse this project's own `UsbTransport`/
`AndroidUsbTransport` abstraction (already built for the print channel) to do the raw
bulk I/O, then speak the HTTP/1.1 text directly ourselves.

This is functionally the same shape as what Tier 1 already does for IPP-USB printing
(HTTP over a raw USB bulk pipe) — just a different HTTP conversation, over a different
interface. `bb_ledm.c` (the concrete implementation actually used for `scan-type=7`
devices) gave the exact wire format, reproduced below.

### Exact request/response sequence (from `bb_ledm.c`, verified against the real macros)

1. Claim the scan interface (255/subclass 4 — one of interfaces 2/3 above), open a bulk
   read/write pipe via `UsbTransport` (mirrors how `AndroidUsbTransport` already claims
   the print interface).
2. **Check status** — write, read response, confirm idle:
   ```
   GET /Scan/Status HTTP/1.1
   Host: <printer-ip-placeholder>
   User-Agent: hplip
   Accept: text/xml
   Accept-Language: en-us,en
   Accept-Charset:utf-8
   Keep-Alive: 20
   Proxy-Connection: keep-alive
   Cookie: AccessCounter=new
   0

   ```
   Response body contains `<ScannerState>Idle</ScannerState>` (proceed) or
   `<ScannerState>BusyWithScanJob</ScannerState>` (bail with a busy error). Real HP
   firmware ignores the `Host` value for a directly-USB-attached device — any
   non-empty placeholder string works; this needs confirming against the real device in
   Task 2 below, not assumed.
3. **Create scan job** — POST with an XML body:
   ```
   POST /Scan/Jobs HTTP/1.1
   Host: <placeholder>
   User-Agent: hplip
   Accept: text/plain, */*
   Accept-Language: en-us,en
   Accept-Charset: ISO-8859-1,utf-8
   Keep-Alive: 1000
   Proxy-Connection: keep-alive
   Content-Type: */*; charset=UTF-8
   X-Requested-With: XMLHttpRequest
   Content-Length: <n>
   Cookie: AccessCounter=new
   Pragma: no-cache
   Cache-Control: no-cache

   <?xml version="1.0" encoding="UTF-8"?><ScanSettings xmlns="http://www.hp.com/schemas/imaging/con/cnx/scan/2008/08/19"><XResolution>300</XResolution><YResolution>300</YResolution><XStart>0</XStart><Width>2550</Width><YStart>0</YStart><Height>3300</Height><Format>Jpeg</Format><CompressionQFactor>15</CompressionQFactor><ColorSpace>Color</ColorSpace><BitDepth>8</BitDepth><InputSource>Platen</InputSource><InputSourceType>Platen</InputSourceType><GrayRendering>NTSC</GrayRendering><ToneMap><Gamma>0</Gamma><Brightness>0</Brightness><Contrast>0</Contrast><Highlite>0</Highlite><Shadow>0</Shadow></ToneMap><ContentType>Photo</ContentType></ScanSettings>
   ```
   (`Width`/`Height` above are a placeholder US-Letter-at-300dpi guess — Task 3 below
   queries `GET /Scan/ScanCaps` first to confirm the device's actual max platen size and
   supported resolutions rather than hardcoding a guess into the shipped implementation.)
   The body must be followed by writing a zero-length chunk terminator (`"\r\n0\r\n\r\n"`)
   as its own write — `bb_ledm.c` does this as a separate `http_write` call after the
   XML body.
4. Response contains a `Location:` header whose value is the new job's URL (e.g.
   `/Scan/Jobs/JobList/1`) — extract it verbatim, used as-is in step 5, not reparsed into
   components.
5. **Poll the job URL** — repeatedly:
   ```
   GET <job-url-from-Location-header> HTTP/1.1
   Host: <placeholder>
   User-Agent: hplip
   Accept: text/plain
   Accept-Language: en-us,en
   Accept-Charset:utf-8
   X-Requested-With: XMLHttpRequest
   Keep-Alive: 300
   Proxy-Connection: keep-alive
   Cookie: AccessCounter=new
   0

   ```
   with a ~0.5s delay between polls, until the response body contains
   `<PreScanPage>` (page ready — proceed to step 6), `<j:JobState>Completed</j:JobState>`
   (done), or `<j:JobState>Canceled</j:JobState>` / `<PageState>CanceledByDevice</PageState>`
   / `<PageState>CanceledByClient</PageState>` (bail with a canceled error). Absence of
   `<PreScanPage>` on the FIRST poll response means no paper was detected on the flatbed —
   bail with a distinct "no document" error, don't keep polling forever.
6. Extract the `<BinaryURL>...</BinaryURL>` tag's contents from that same response.
7. **Fetch the image** — GET the BinaryURL (same request shape as step 5) — the response
   is an HTTP header followed by the actual JPEG bytes as the body (recall the job request
   in step 3 asked for `<Format>Jpeg</Format>`, so this pipeline's on-disk output is JPEG,
   not raw PNM — see "Output format" note below, this supersedes Revision 1's PNM choice).
8. Close the USB connection.

### Output format (supersedes Revision 1)

Revision 1 chose PNM to match the print pipeline's existing `PpmImage` code. That reasoning
no longer applies: the device's own LEDM protocol already returns JPEG bytes directly (see
step 7) — there is no PNM stage to reuse, and re-encoding JPEG back to PNM would be pure
waste. Spec A writes the JPEG bytes received in step 7 straight to the output `File`,
unmodified.

## Architecture

No native module, no JNI, no new third-party dependency. A new `scan` package, pure
Kotlin:

- `LedmRequests.kt` — pure functions building the exact HTTP request text for each of the
  four request shapes above (status, create-job, poll-job, fetch-binary). Takes plain
  data (resolution, region, job/binary URL string) in, returns a `String` (request text)
  out. No I/O.
- `LedmResponses.kt` — pure functions parsing the exact response shapes above:
  `parseScannerState(String): ScannerState` (enum: IDLE, BUSY), `parseLocationHeader(String): String?`,
  `parsePollResult(String): PollResult` (sealed type: `PageReady(binaryUrl: String)`,
  `Completed`, `Canceled`, `NoDocument`), and a way to split a raw HTTP response into
  header text + body bytes (needed for step 7, where the body is binary JPEG, not text).
  No I/O.
- `ScanPipeline.kt` — orchestrates: opens the USB connection via the existing
  `UsbTransport` interface, drives the request/response sequence above using the pure
  functions from the two files above, writes the final JPEG bytes to a `File`. This is
  the only piece that touches real I/O, mirroring how `NativeRenderingPipeline` is a thin
  orchestration layer around pure `HpcupsNative` calls.
- `UsbScanTransport` — none needed as a *separate* class: the existing `UsbTransport`
  interface (`write`/`read`/`close`) is reused as-is. The only new piece is in
  `UsbPrinterManager` (or a small addition alongside it): opening the scan-data interface
  (255/subclass 4) rather than the print interface (7/1), the same way
  `UsbPrinterManager` already distinguishes IPP-USB vs. legacy-printer interfaces via
  `IppUsb.isIppUsb`/`isLegacyPrinter`.

## Data flow

androidTest → `ScanPipeline.scan(outputFile)` → opens `UsbTransport` on the scan
interface → writes the status request (built by `LedmRequests`), reads the response,
parses it (via `LedmResponses`) to confirm idle → writes the create-job request + XML
body + zero-footer, reads response, parses out the job URL → polls the job URL (writing
the poll request each iteration, reading + parsing each response) until `PageReady` →
writes the binary-fetch request, reads the response, splits header from body → writes the
JPEG body bytes to `outputFile` → closes the transport → test asserts the file is a
non-trivial, valid JPEG (starts with the JPEG magic bytes, plausible size for a scanned
page).

## Error handling

Scoped narrowly for Spec A (no client-facing surface yet): busy scanner, no-document,
canceled-by-device, and any I/O/timeout during the request/response exchange all throw
`IOException` with a clear message distinguishing which of those it was. No retry logic,
no UI surfacing — that belongs to Spec B, once there's an actual eSCL client waiting on a
response.

## Testing

Two tiers, split cleanly along the pure/impure boundary established above:

- **JVM unit tests** (new, real coverage — this revision is more testable than Revision 1
  was): `LedmRequestsTest`, `LedmResponsesTest`. These test the pure request-building and
  response-parsing functions directly against literal captured request/response strings
  (the exact ones documented above) — no fake, no hardware, no `UsbTransport` needed.
- **Device androidTest** (same tier as `LegacyPipelineWiringSmokeTest`/
  `NativePipelineFixtureTest`): exercises `ScanPipeline.scan()` end-to-end against the
  real MFP, physically scanning a real page, asserting a valid JPEG file results.

## Explicitly out of scope for this spec

- eSCL HTTP server, mDNS `_uscan._tcp` advertisement, any client-facing discovery or
  scanning UI (all Spec B).
- ADF/multi-page scanning (this hardware doesn't have one; future work if ever needed).
- Querying `GET /Scan/ScanCaps` for anything beyond confirming max platen size/resolution
  to replace the placeholder region in the create-job request (Task 3) — full capability
  negotiation, resolution options, color-mode options are Spec B's concern, driven by
  real eSCL request attributes.
- Retry/error-surfacing UX (Spec B).
