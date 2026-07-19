# Native Scan Pipeline (Spec A) Design

**Status:** Spec A of a two-spec scan-support feature. This spec covers ONLY getting raw scan
data off a USB-connected HP DeskJet 2300-series MFP and onto disk, verified via a device
smoke test. It deliberately does NOT cover network/driverless scanning — that is Spec B
(eSCL HTTP server + mDNS `_uscan._tcp` discovery), a separate design doc to be written after
this one is implemented and hardware-verified.

## Goal

Reliably trigger a flatbed scan on the DeskJet 2300-series MFP already used for Tier 2
print testing, and retrieve the resulting image as a file on the Android device — proving
out the native USB protocol layer before any network-facing work is built on top of it.

## Why this is split from the eventual "real" feature

Scan support is architecturally a second subsystem bolted onto this app, not incremental
work on the print path:
- Different USB interface classes entirely (confirmed against the real device below), not
  the printer-class interface Tier 1/Tier 2 already use.
- A different, HP-proprietary protocol (MLC, implemented in HPLIP's `libhpmud`), not IPP.
- Will eventually need its own HTTP server, mDNS service type, and discovery/rendering path.

Building the whole thing in one spec would produce an oversized, hard-to-review plan.
This spec scopes to the foundational, highest-technical-risk piece only.

## Hardware findings (from the real device, via `adb shell dumpsys usb`)

The DeskJet 2300-series MFP (vendor_id=1008, product_id=13908) exposes four USB interfaces:

| Interface | class | subclass | protocol | Purpose |
|---|---|---|---|---|
| 0 | 255 (vendor-specific) | 204 | 0 | HP proprietary status/control channel |
| 1 | 7 (printer) | 1 | 2 | Print channel — already used by Tier 2 (`IppUsb.isLegacyPrinter`) |
| 2 | 255 (vendor-specific) | 4 | 1 | Scan channel (data), one of two identical-shaped interfaces |
| 3 | 255 (vendor-specific) | 4 | 1 | Scan channel (data), the other of the pair |

No standard USB Still Image class (06) is present anywhere on this device. This confirms
the scan path requires HP's proprietary MLC protocol, not a generic USB scanner class.

This model is flatbed-only (no ADF) — confirmed with the hardware owner. Spec A and its
eventual Spec B both scope to single flatbed-page scans; ADF/multi-page is an explicit
future extension, not in scope here.

## Architecture

A new native module, `hpmudjni`, mirrors the existing `gsjni`/`hpcupsjni` pattern already
established for the print pipeline: a thin JNI wrapper around `libhpmud`, HPLIP's MLC
channel-protocol implementation. `libhpmud` lives in `io/hpmud` inside the same
`hplip-3.24.4` source tarball already downloaded and vendored for `hpcups` — no new
third-party dependency to vet, just a wider copy from an already-trusted source tree.

A new Kotlin object, `HpmudNative`, declares `external fun`s for the minimal command set
needed (open channel, send scan-initiate command, read scan data, close channel) —
mirroring `HpcupsNative`'s shape. A new `ScanPipeline` class orchestrates these calls and
writes the result to a `File`, deliberately as thin an orchestration layer as
`NativeRenderingPipeline` is around `HpcupsNative` today.

### Open technical risk (explicit, not hidden)

`libhpmud` on desktop Linux talks to the printer via either `libusb` or raw kernel USB
device nodes. Android only exposes USB access through `UsbDeviceConnection`'s raw file
descriptor, usable with the same low-level `usbfs` ioctls that `libusb` itself wraps —
but whether `libhpmud`'s I/O layer can be pointed at that fd directly, or needs its I/O
glue trimmed/replaced while preserving its higher-level MLC packet-framing logic, is
unknown until someone reads the actual `libhpmud` source. **This must be the first task
in the implementation plan** (a source-reading spike, not a code-writing task), with a
concrete go/no-go decision point before the rest of the plan is built out. If `libhpmud`'s
I/O layer turns out to be too tightly coupled to desktop-only USB APIs, the plan may need
to fall back to reimplementing just the MLC packet-framing logic against Android's
`UsbDeviceConnection` directly, discarding `libhpmud`'s I/O layer but keeping its protocol
logic as a reference.

## Components

- `native/fetch-hpcups-sources.sh` — extended to also copy `hplip-3.24.4/io/hpmud` into
  `app/src/main/cpp/hpmud/` (same one-time-setup script pattern as the existing hpcups copy).
- `app/src/main/cpp/hpmudjni.cpp` — new JNI wrapper, same shape as `hpcupsjni.cpp`.
- `app/src/main/java/dev/jaspreet/printserver/scan/HpmudNative.kt` — `external fun`
  declarations (package name TBD at plan time; a new `scan` package keeps this separate
  from `render`, which is print-only).
- `app/src/main/java/dev/jaspreet/printserver/scan/ScanPipeline.kt` — orchestrates the
  JNI calls, writes result to a `File` as raw PNM/PPM bytes (matching the print side's
  existing `PpmImage` format — no new image-encoding dependency needed for this spec;
  JPEG/PNG conversion, if needed, is Spec B's concern once eSCL's required response
  format is known).
- `app/src/main/java/dev/jaspreet/printserver/scan/UsbScanTransport.kt` (naming TBD) —
  opens the `UsbDeviceConnection` against the two vendor-specific interfaces confirmed
  above (255/204 control, 255/4 data) and hands the raw fd to the native layer.

## Data flow

androidTest → `ScanPipeline.scan(outputFile)` → JNI → `libhpmud` opens the MLC channel
over the vendor-specific USB interfaces → sends HP's scan-initiate command sequence →
reads raw scan bytes back over the channel → JNI copies bytes into a PNM file → test
asserts the file is a valid, non-trivial PNM (sane header/dimensions, size roughly
matching a flatbed page at the expected resolution).

## Error handling

Scoped narrowly, since there is no client-facing surface yet: channel-open failure,
scanner-busy/not-ready, and a truncated/short read all throw `IOException` with a clear
message. No retry logic, no UI surfacing — that belongs to Spec B, once there's an actual
eSCL client waiting on a response.

## Testing

One device-only `androidTest`, at the same tier as the existing
`LegacyPipelineWiringSmokeTest`/`NativePipelineFixtureTest`: physically scan a real page on
the flatbed, assert the pipeline produces a valid PNM file of plausible size. No JVM unit
test is possible here, for the same reason `NativeRenderingPipeline` has none — it needs
the real `.so` and real hardware, not a fake.

## Explicitly out of scope for this spec

- eSCL HTTP server, mDNS `_uscan._tcp` advertisement, any client-facing discovery or
  scanning UI (all Spec B).
- ADF/multi-page scanning (this hardware doesn't have one; future work if ever needed).
- Resolution/color-mode options, cropping, or any scan-quality controls (Spec B, once
  driven by real eSCL request attributes).
- Retry/error-surfacing UX (Spec B).
