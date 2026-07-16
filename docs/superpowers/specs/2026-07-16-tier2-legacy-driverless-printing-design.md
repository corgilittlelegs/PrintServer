# Tier-2 (Legacy/Host-Based) Driverless Printing — Design Spec

**Date:** 2026-07-16
**Status:** Approved by user
**Depends on:** `docs/superpowers/specs/2026-07-16-usb-ipp-print-server-design.md` (Tier 1). This is an additive sub-project layered on that architecture — it reuses `UsbTransport`, `AndroidUsbTransport`, `UsbPrinterManager`, HP JIPP, and `DiscoveryAdvertiser` from the Tier-1 implementation plan.

## Problem

Tier 1 only serves printers that speak IPP natively over USB (IPP-USB, ~2013+). The user's actual printer — an **HP DeskJet Ink Advantage 2338** — is USB-only, has no network hardware, and does not speak IPP-USB. It is a **host-based/GDI printer**: all rendering historically happens on the connected PC, and the printer only understands a proprietary raster wrapper called **PCL3-GUI**.

Tier 1's v1 plan already covers this printer only via a raw port-9100 relay, which requires the connecting client to have HP's own driver installed — defeating the "any device, no drivers" goal for exactly the printer that motivated this project.

## Feasibility findings (why this design looks the way it does)

- HP's closed-source `hp-plugin` binary — needed by some HP inkjets for advanced compression/fax — **cannot run on Android**: it is normally downloaded and executed as a runtime binary, and Android's W^X enforcement (since ~API 29) blocks executing any native code not bundled into the APK's `lib/<abi>/` at install time and loaded as a proper shared library.
- This specific printer, however, is driven by **`hpcups`**, HP's *open-source* C++ CUPS filter (part of HPLIP, GPL-licensed) using printer language `pcl3gui` — not the closed plugin. The user confirmed this printer already works via HPLIP on Ubuntu. This removes the W^X blocker: `hpcups` can be cross-compiled and bundled like any other native library.
- **Gutenprint was evaluated as an alternative and ruled out.** Its actively-maintained branch dropped support for most HP DeskJet models, and its printer database predates the PCL3-GUI raster format this printer generation uses. `hpcups` is the only credible path.

## Architecture

```
┌─ Android phone ────────────────────────────────────────────┐
│  LocalIppServer :8631   (same port Tier 1 uses)             │
│  ├── synthetic Get-Printer-Attributes (hardcoded caps)      │
│  ├── Print-Job → enqueues PDF bytes + returns job-id        │
│  └── Get-Job-Attributes / Cancel-Job (by job-id)            │
│                                                              │
│  JobQueue (single worker — one printer, one channel)         │
│  ├── Ghostscript (NDK .so) : PDF → CUPS-Raster              │
│  └── hpcups        (NDK .so) : CUPS-Raster → PCL3-GUI bytes │
│                                                              │
│  UsbPrinterManager.openLegacyTransport() ──USB──> Printer    │
└──────────────────────────────────────────────────────────────┘
```

Unlike Tier 1, there is no printer-side IPP conversation to relay — the app itself **is** the IPP printer the client talks to. Two open-source C libraries are cross-compiled via NDK: **Ghostscript** (PDF → CUPS-Raster, the same standard intermediate format real CUPS's `pdftoraster` filter produces) and **hpcups** (CUPS-Raster → this printer's actual PCL3-GUI byte stream, pulled from HPLIP source — the same code already proven correct on the user's Ubuntu machine).

Discovery is unchanged from Tier 1: `DiscoveryAdvertiser.advertiseIpp(...)` advertises `_ipp._tcp`; clients cannot tell a Tier-2 printer from a Tier-1 one.

## Components

1. **LocalIppServer** — parses IPP requests via HP JIPP over the same HTTP socket handling as Tier 1. Supports exactly four operations: `Get-Printer-Attributes`, `Validate-Job`, `Print-Job`, `Get-Job-Attributes`, plus `Cancel-Job`. Anything else returns `server-error-operation-not-supported`.

2. **PrinterCapabilities** — hardcoded per known model (no attribute query possible — there's no printer-side IPP to ask). v1 covers the DeskJet 2130/2300 series family, which shares hardware and PPD data: `document-format-supported=[application/pdf]`, `color-supported`, `media-supported` (A4/Letter), fixed `print-quality`.

3. **JobQueue** — single background worker thread. Deliberately no concurrency: one physical printer, one USB channel (unlike Tier 1's multi-channel IPP-USB pool). FIFO. Job states: `pending → processing → completed` / `aborted` / `canceled`.

4. **RenderingPipeline** (interface) — `render(pdfFile, capabilities): ByteArray`. Kept behind an interface so JVM unit tests never touch native code, mirroring the `UsbTransport` testability pattern from Tier 1. Real implementation chains `GhostscriptRenderer` → `HpcupsEncoder`. `FakeRenderingPipeline` returns canned bytes in tests.

5. **GhostscriptRenderer** (JNI) — invokes Ghostscript's built-in `cups` output device against the spooled PDF at a fixed 300dpi, Letter-or-A4 default. No per-job quality options in v1.

6. **HpcupsEncoder** (JNI) — feeds the CUPS-Raster bytes plus a bundled PPD/attribute asset (extracted from HPLIP's data files for this printer family) into hpcups's encoding routine; returns PCL3-GUI bytes.

7. **ServerService wiring** — `UsbPrinterManager.findPrinter()` (Tier 1) already distinguishes IPP-USB vs. legacy interfaces. When legacy, the service now stands up `LocalIppServer` + `JobQueue` + `DiscoveryAdvertiser.advertiseIpp(...)` in addition to the existing raw-9100 relay (kept for PC-driver clients that prefer it).

## Job lifecycle (data flow)

1. Client discovers the printer via mDNS — identical to Tier 1; the synthetic nature is invisible to clients.
2. Client POSTs `Get-Printer-Attributes` → `LocalIppServer` returns the hardcoded `PrinterCapabilities`. Client sees `application/pdf` as the only accepted format and renders any document (Word, images, web pages, etc.) to PDF accordingly — this is standard OS print-pipeline behavior, not something the app needs to handle.
3. Client POSTs `Print-Job` with a PDF body → server spools it to a temp file, creates a job record (`state=pending`), pushes to `JobQueue`, responds immediately with `job-id` + `job-state=processing`.
4. Worker thread: Ghostscript renders PDF → CUPS-Raster → hpcups encodes → PCL3-GUI bytes → written to USB bulk OUT via the existing legacy `UsbTransport`.
5. Job marked `completed` or `aborted`. Client may poll `Get-Job-Attributes`.
6. Temp PDF file deleted after the job finishes, success or failure — never left on disk.

## Error handling

| Event | Handling |
|---|---|
| Ghostscript render failure (corrupt/unsupported PDF) | Job → `aborted`, `job-state-reasons=document-format-error`, temp file deleted |
| hpcups encode failure | Job → `aborted`, `job-state-reasons` set to the closest matching IPP reason, full detail logged |
| USB write failure mid-job | Reuse Tier 1's existing retry logic in `AndroidUsbTransport` (retry ×3, then abort) |
| `Cancel-Job` while `pending` | Removed from queue before starting, marked `canceled` |
| `Cancel-Job` while `processing` | Best-effort only: honored if the worker hasn't started the USB write stage yet; once bytes are hitting the printer, cancellation is not honored — documented limitation, consistent with how most cheap printers behave regardless of software |
| Two jobs submitted concurrently | Second sits in queue; `Get-Job-Attributes` reports `pending` until its turn |
| Phone reboot / service restart mid-job | Queue is in-memory only — job is lost, client's connection drops and the client naturally retries. No job persistence in v1 (YAGNI for a personal-use app) |

## Testing

- **Unit (JVM, no native code):** `LocalIppServer` request/response cycles built and parsed via JIPP, driven against `FakeRenderingPipeline`. `JobQueue` state transitions including the pending/processing cancel race. `PrinterCapabilities` attribute serialization.
- **Native build smoke test (device only, not part of the JVM suite):** a small manual `NativeRenderTest` — install the debug APK, feed one known-good PDF through the real Ghostscript+hpcups pipeline, confirm output is nonzero length and starts with the expected PCL3 escape sequence. Run once per native-lib change; no CI exists for this personal project.
- **Hardware smoke checklist addition** (extends the Tier-1 checklist): print a real PDF from Mac/Windows/iOS/Linux through this pipeline to the 2338; confirm physical output is correct. This is the test that actually matters — the other two just catch obvious breakage before spending paper.

## Explicitly out of scope for this addition

- Any printer model outside the DeskJet 2130/2300 family (each additional model needs its own `PrinterCapabilities` + bundled PPD data — future work, not blocking)
- Job persistence across app/service restarts
- Per-job print options (quality, media size, duplex) — fixed defaults only
- Mid-transfer job cancellation
- Non-PDF wire formats (PWG-Raster, URF) — PDF-only, per user decision; OS-side rendering already handles all document types before they reach the wire
- A CI pipeline for the native build (manual smoke test only)
