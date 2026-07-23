# Scan Reliability Report

**Date:** 2026-07-23  
**Scope:** HP DeskJet 2300-series flatbed scanning through this Android print-server app.

## Executive summary

Scanning now works against the tested HP DeskJet 2300-series MFP through macOS Image Capture.
The original failures were not one single eSCL issue; they were a stack of LEDM-over-USB and
eSCL lifecycle mismatches:

- The app selected the wrong HP vendor-specific USB interface for scan traffic.
- The LEDM response parser did not fully match HPLIP's HTTP behavior.
- The app sent DPI-scaled scan regions, causing the scanner to capture only a small strip of
  the platen.
- The app sent LEDM brightness/contrast as `0/0`, which made output look dim; HPLIP's neutral
  LEDM defaults are `1000/1000`.
- The eSCL `NextDocument` endpoint allowed the same completed JPEG to be fetched repeatedly,
  so Image Capture could save many duplicate files without triggering a new hardware scan.

After these fixes, hardware tests produced:

- 75 dpi overview/full-platen scan: approximately 34-55 KB JPEG, around 5 seconds.
- 300 dpi scan: approximately 450 KB from the app and approximately 720 KB once saved by
  Image Capture, around 18 seconds.

## Root causes and fixes

| Area | Root cause | Fix |
| --- | --- | --- |
| USB interface selection | The implementation treated vendor-specific `ff/4/1` as the LEDM scan channel. HPLIP maps LEDM/eSCL scan to `ff/cc/0`; `ff/4/1` is EWS/LEDM. | `ScanUsb.isLedmScan()` now requires class `255`, subclass `204`, protocol `0`. |
| LEDM HTTP parsing | Some printer responses can include stale/preamble bytes before the next `HTTP/1.1` status line; POST `/Scan/Jobs` can return a bodyless `201 Created`. | `ChunkedHttp.readHeader()` resynchronizes to `HTTP/1.1`; `ScanPipeline` skips chunked-body reads for `201 Created`. |
| USB session lifecycle | Holding the print USB interface open while scanning can interfere with the scan interface on this composite device. | Tier 2 print transport is opened lazily and closed before a scan starts; scan requests open fresh transports per logical LEDM operation. |
| Endpoint control traffic | The app sent endpoint-clear control transfers on the scan interface. HPLIP 3.24.4 has analogous clear-halt calls commented out. | Scan interface opens skip endpoint-clear recovery; print paths keep best-effort recovery. |
| Scan region size | Width/Height were scaled by selected DPI. On this device, `/Scan/ScanCaps` reports fixed LEDM scan-region units (`2550x3508`) independent of DPI. At 75 dpi, scaling requested only a top-left strip. | `ScanPipeline` now sends full fixed region dimensions (`2550x3508`) regardless of DPI. |
| Tone defaults | The app hardcoded brightness/contrast as `0/0`, but HPLIP LEDM defaults are `1000/1000` on a `0..2000` scale. | `LedmRequests.createJobBody()` now defaults brightness and contrast to `1000`. |
| eSCL document lifecycle | Completed jobs could serve `NextDocument` repeatedly. Image Capture interpreted repeated fetches as duplicate scans/saves. | `LocalEsclServer` now serves a completed job's `NextDocument` once, then removes the job and deletes the spool file. |
| User visibility | Scan readiness/failure was hidden while print service appeared healthy. | `ServerStatus` now tracks scan state, port, failure reason, and capabilities; UI and notification expose scan status. |

## HPLIP findings used

Reference source: HPLIP 3.24.4.

- `scan/sane/bb_ledm.c`
  - Defines the LEDM request shapes used here: `GET /Scan/ScanCaps`, `GET /Scan/Status`,
    `POST /Scan/Jobs`, `GET <job-url>`, and `GET <BinaryURL>`.
  - Uses `CompressionQFactor=15`, `ContentType=Photo`, `GrayRendering=NTSC`.
  - Passes brightness/contrast variables into `<ToneMap>`.
- `scan/sane/ledmi.h`
  - Defines LEDM brightness and contrast ranges as `0..2000`, default `1000`.
- `io/hpmud/hpmudi.h`, `io/hpmud/hpmud.c`, `io/hpmud/musb.c`
  - Map `HPMUD_LEDM_SCAN_CHANNEL` and `HPMUD_ESCL_SCAN_CHANNEL` to the `ff/cc/0` USB
    composite interface.
  - Map `HPMUD_EWS_LEDM_CHANNEL` to `ff/4/1`.
- `scan/sane/http.c`
  - Resynchronizes response parsing to a real `HTTP/1.1` status line.
  - Treats bodyless `201 Created` responses as valid.

## Current validation

Automated:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Both passed on 2026-07-23 after the scan fixes.

Hardware:

- Installed the debug APK over wireless ADB to device `adb-R9ZX70CTLXN-SzU8Cy._adb-tls-connect._tcp`.
- Confirmed USB descriptor exposes:
  - `ff/cc/0` interface 0 for LEDM scan.
  - `7/1/2` interface 1 for legacy print.
  - two `ff/4/1` interfaces that should not be used for scan data.
- Confirmed macOS Image Capture discovers `PrintServer Bridge`.
- Confirmed Image Capture can create saved JPEG output.
- Confirmed duplicate-save loop stopped after one-shot `NextDocument`.
- Confirmed neutral `1000/1000` brightness/contrast produces a much brighter 300 dpi scan.

## Remaining polish

1. **Orientation.** The saved image is valid but upside down relative to expected document
   orientation. Next likely fix is to map eSCL orientation into LEDM coordinates or rotate
   the served JPEG when the client requests a specific orientation.
2. **Scan settings UI/API.** Brightness and contrast are now protocol parameters with HPLIP
   defaults; they could be exposed later if needed.
3. **Manual matrix.** Re-run a small hardware matrix before declaring scan feature complete:
   - 75 dpi overview.
   - 300 dpi color JPEG.
   - 300 dpi grayscale JPEG.
   - Stop/start Android service, then scan again.
   - Print after scanning to verify lazy print transport reopen.

## Files touched by the fix

- `app/src/main/java/dev/jaspreet/printserver/scan/`
- `app/src/main/java/dev/jaspreet/printserver/escl/LocalEsclServer.kt`
- `app/src/main/java/dev/jaspreet/printserver/usb/`
- `app/src/main/java/dev/jaspreet/printserver/service/`
- `app/src/main/java/dev/jaspreet/printserver/ui/PrintServerApp.kt`
- Matching JVM tests under `app/src/test/`.
