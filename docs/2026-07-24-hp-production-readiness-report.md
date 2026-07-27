# HP Printer Production-Readiness Report

Date: 2026-07-24

## Scope Update

The app should not claim universal support for any printer at this stage. The realistic production scope is:

- HP printers that already speak IPP-USB, where the app can act as a USB-to-LAN IPP relay.
- HP host-based printers only after the app has a verified model profile for that family, including PPD/options, printer language, media limits, scan protocol, and hardware smoke-test results.

Non-HP printers are explicitly out of scope for now. If HP-first feedback is positive, support for other vendors can be evaluated as separate driver/protocol projects rather than as a generic fallback.

## Current Implementation Summary

- Tier 1 handles IPP-USB printers by relaying IPP/HTTP bytes between LAN clients and the printer USB bulk endpoints.
- Tier 2 handles one HP host-based path by running a synthetic IPP server, rendering PDF/JPEG/PWG-Raster on Android, and encoding output with Ghostscript plus HPLIP `hpcups`.
- Scanning exposes a local eSCL server to clients, backed by HP LEDM scan requests over a USB vendor interface.
- Ink/supply status uses the same HP LEDM management path when available.

This is a strong HP DeskJet 2300-series proof point, not yet a production HP printer platform.

## Comparison With HPLIP

HPLIP is a full HP Linux printing and imaging stack. It includes model data, PPDs, `hpcups`, `hpijs`, the `hpaio` SANE scanner backend, status/service tools, plugin handling for models that require proprietary components, fax/photo-card utilities, and HP-specific transport backends.

This app currently uses only a subset of that ecosystem:

- `hpcups` native encoding.
- A bundled CUPS-raster subset.
- One bundled HP DeskJet 2300-series PPD.
- LEDM scan/status behavior modeled from HPLIP reference behavior.
- JIPP for IPP packet encoding/decoding.

The main gap is not that HPLIP is missing; it is that HPLIP's model selection and device database are not represented in the app. Production HP support requires selecting the right driver profile per HP model instead of treating every non-IPP USB printer as DeskJet 2300-compatible.

Sources:

- HPLIP project summary: https://launchpad.net/hplip
- Ubuntu HPLIP package component summary: https://launchpad.net/ubuntu/focal/arm64/hplip
- IPP Everywhere overview and requirements: https://pwg.org/ipp/everywhere.html
- IPP Everywhere self-certification tools: https://www.pwg.org/ippeveselfcert/
- Mopria eSCL specification page: https://mopria.org/spec-download

## Printing Flaws And Risks

### 1. Legacy HP fallback is too broad

`ServerService.startPipeline()` chooses Tier 2 for any USB printer-class device that is not IPP-USB. Tier 2 then always uses `PrinterCapabilities.deskJet2300(...)`, `hp_deskjet_2300_series.ppd`, and the PCL3-GUI/PCL3-GUI2 `hpcups` path.

That is acceptable for the verified DeskJet 2300 family. It is unsafe for HP models using different PDLs or PPD requirements, such as HP LaserJet PCL5/PCL6, PostScript-capable devices, LIDIL families, ZjStream/JetReady families, plugin-required models, or newer IPP/driverless models that should remain Tier 1.

Production requirement:

- Add HP model detection before Tier 2 starts.
- Introduce a `PrinterProfile` registry keyed by IEEE-1284 Device ID, VID/PID, `CMD`, `MDL`, and known HPLIP model aliases.
- Refuse unsupported HP models with a clear UI state instead of attempting DeskJet output.

### 2. Capability advertisement is hardcoded

Tier 2 advertises PDF, PWG-Raster, JPEG, A4/Letter, color, one-sided printing, 300 dpi IPP resolution, and fixed AirPrint/URF tokens. Some of these values are model-specific and some do not fully match the internal rendering behavior.

Production requirement:

- Generate IPP and DNS-SD TXT capabilities from the selected HP profile.
- Keep `document-format-supported`, `media-supported`, margins, quality modes, color modes, and URF tokens honest per model.
- Run PWG IPP Everywhere self-certification against every supported profile.

### 3. Unsupported document formats are not rejected early enough

`LocalIppServer` accepts the client's `document-format`, but unsupported values effectively fall through to PDF rendering. This can turn a client or model mismatch into a late render failure.

Production requirement:

- Reject unsupported formats in `Validate-Job`, `Print-Job`, `Create-Job`, and `Send-Document`.
- Return IPP errors and status messages that clients can understand.
- Add tests for unsupported `application/postscript`, `application/vnd.hp-pcl`, `image/urf`, `application/octet-stream`, and unknown MIME types.

### 4. HPLIP plugin-required HP models are not handled

Some HP models require HPLIP's proprietary plugin or firmware/helper components. The current app cannot fetch, verify, license, or run those components.

Production requirement:

- Mark plugin-required models unsupported unless a legally and technically acceptable plugin strategy is designed.
- Surface this as "HP model detected, driver component unavailable" rather than generic failure.

### 5. Job options are intentionally narrow

The current Tier 2 path supports a useful minimum: quality and color mode. Production HP support needs model-specific handling for media source/type, margins, borderless modes, legal/photo sizes, copies, collation, scaling, orientation, duplex where available, and possibly printer maintenance/status commands.

Production requirement:

- Use each HP profile's PPD/options as the source of truth.
- Add option validation and mapping before rendering.
- Do not advertise options that the native backend cannot honor.

### 6. Resource handling is good for prototypes but needs production soak testing

The queue is serial and bounded, with spool cleanup and render timeouts. However, all incoming Tier 2 HTTP bodies are read into memory before spooling, and large multi-page color documents can stress Android memory/cache.

Production requirement:

- Stream IPP document payloads to spool files instead of holding full jobs in memory.
- Add per-profile page-size and document-size limits.
- Run long-session soak tests with repeated large jobs.

## Scanning Flaws And Risks

### 1. Scan backend is HP LEDM-specific

The app advertises eSCL to network clients, but the USB backend only opens HP LEDM-style vendor interfaces and sends LEDM HTTP requests. This is fine for verified HP DeskJet/Inkjet MFPs that expose LEDM, but it is not a generic scanner backend.

Production requirement:

- Treat LEDM as one HP scan backend.
- Add profile gating so scan is enabled only for HP models whose LEDM behavior is verified.
- Add separate backends later if needed, such as native device eSCL relay, WSD Scan, or additional HP protocol variants.

### 2. Tier 1 IPP-USB scanning is disabled

For IPP-USB printers, the app starts the print relay and explicitly marks scanning unavailable. Many modern HP MFPs may expose scan capabilities separately, so this misses valid HP scan devices.

Production requirement:

- During Tier 1 startup, also probe for scan-capable HP interfaces or network-like USB endpoints.
- Start eSCL only if scan capability probing succeeds.
- Keep print relay startup independent from scan startup.

### 3. Scan region is hardcoded for DeskJet 2300 behavior

`LedmCapabilities` reads live `MaxWidth` and `MaxHeight`, but `ScanPipeline` uses fixed 2550 x 3508 region values. This matches the verified DeskJet 2300 path but is not safe across HP MFP families.

Production requirement:

- Pass probed scanner dimensions into `ScanPipeline`.
- Store scan-region semantics in the HP profile because LEDM coordinate units can be model-specific.
- Add tests for non-DeskJet dimensions.

### 4. Scan feature coverage is minimal

The current scan path is flatbed JPEG only. Production HP MFP support may require ADF, duplex ADF, multiple pages, document-format negotiation, scan size selection, resolution limits, cancel handling, and better busy/status mapping.

Production requirement:

- Keep initial production scope to flatbed JPEG for verified HP models.
- Advertise only features actually implemented.
- Add ADF and multipage as explicit future profile capabilities.

## Production-Ready HP Roadmap

1. Rename the product scope in docs/UI/README from "any printer" to "HP USB printer bridge" or "HP-first USB print and scan bridge".
2. Add a `PrinterProfile` abstraction with:
   - supported model aliases,
   - VID/PID hints,
   - IEEE-1284 `CMD`/`MDL` matching,
   - PPD asset,
   - native encoder family,
   - print capabilities,
   - scan backend and scan capabilities policy.
3. Gate Tier 2 startup on a positive HP profile match.
4. Refuse unknown HP models cleanly.
5. Import or generate profiles for the next few HP families from HPLIP data, starting with models that do not require proprietary plugins.
6. Split scan support into backends and enable LEDM only when profile-supported.
7. Stream job bodies to disk and harden IPP validation.
8. Run PWG IPP Everywhere self-certification for each advertised print profile.
9. Run eSCL client interop testing on macOS Image Capture, Windows Scan, sane-airscan, and Android/Mopria clients for each scan profile.
10. Maintain a hardware compatibility matrix with exact model, VID/PID, firmware, print path, scan path, tested clients, and known limitations.

## Recommended Near-Term Acceptance Criteria

For the first HP production target, the app should be considered ready only when:

- Unknown non-IPP HP printers are refused unless matched to a verified profile.
- DeskJet 2300-series still passes all existing JVM tests and hardware smoke tests.
- IPP Everywhere self-certification passes for the advertised Tier 2 queue.
- eSCL scanning passes at supported resolutions/color modes from at least macOS Image Capture and sane-airscan.
- The README and app UI no longer imply non-HP or unsupported HP models are expected to work.
- Licensing notes clearly state Ghostscript/HPLIP/CUPS obligations and plugin-required model limitations.

## Conclusion

The strongest direction is HP-first, not universal. Tier 1 can support HP IPP-USB devices broadly because those printers do their own rendering. Tier 2 should become a profile-driven HP host-based printer layer, beginning with the already verified DeskJet 2300 family. That gives the app a credible production path without pretending that one PCL3-GUI encoder can drive every printer.
