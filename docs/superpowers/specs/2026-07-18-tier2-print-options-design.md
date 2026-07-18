# Tier 2 print options (quality, color/mono) — design

Date: 2026-07-18

## Context

Tier 1 (IPP-USB passthrough) already supports print options for free — the client's
own driver sets them and the app relays raw bytes untouched. This spec covers Tier 2
only, where the app itself is the IPP printer (`LocalIppServer`) and job-template
attributes from `Print-Job`/`Create-Job` requests are currently parsed not at all.

Second of several print-management sub-projects on top of the existing relay/render
architecture (see `CLAUDE.md`); queue visibility/retry-cancel is a separate, already
spec'd sub-project. Ink-level reporting and scan support are separate future
sub-projects.

## Scope

Two job-template attributes, both real options the bundled PPD
(`app/src/main/assets/ppd/hp_deskjet_2300_series.ppd`) actually supports:

- **Print quality**: PPD `OutputMode` = FastDraft(300dpi) / Normal(600dpi) / Best(600dpi)
  / Photo(1200dpi). Mapped from IPP's standard 3-value `print-quality` (draft=3,
  normal=4, high=5) as draft→FastDraft, normal→Normal, high→Best. Photo mode is
  unreachable — there's no standard IPP value for a 4th tier, and heuristically
  guessing "photo intent" from format/name isn't reliable enough to build.
- **Color/mono**: PPD `ColorModel` = RGB / CMYGray / KGray. Mapped from IPP's standard
  `print-color-mode` (color / monochrome) directly to RGB / KGray.

**Explicitly out of scope:**
- **Duplex** — the PPD has no `*Duplex` entry at all; this printer class has no duplex
  hardware. Not buildable.
- **Paper size** — the PDF a client sends already carries its own page geometry from
  the client's own print dialog. Forcing a mismatched IPP `media` attribute server-side
  onto Ghostscript's render risks cropping/scaling artifacts against content the client
  already laid out for a specific size. Redundant in the common case; dropped.

## Design

### New enums (`app/src/main/java/dev/jaspreet/printserver/jobs/`)

```kotlin
enum class PrintQuality { DRAFT, NORMAL, HIGH }   // → PPD FastDraft/Normal/Best
enum class ColorMode { COLOR, MONOCHROME }         // → PPD ColorModel RGB/KGray
```

Kept in the `jobs` package, not the JIPP model — matches the existing rule that only
`LocalIppServer`/`IppRelayServer` touch IPP encoding types directly; `JobQueue`,
`PrintJob`, and `RenderingPipeline` stay IPP-agnostic.

### `PrintJob` (`jobs/PrintJob.kt`)

Adds `quality: PrintQuality = PrintQuality.NORMAL` and `colorMode: ColorMode`
(constructor param, no default — callers must supply it, since the correct default
depends on `PrinterCapabilities.color` which `PrintJob` itself doesn't know about).

### `LocalIppServer` (`ipp/LocalIppServer.kt`)

- `printJob()` and `createJob()` read `Types.printQuality` and `Types.printColorMode`
  from the request's job-template attribute group (same group `Types.jobName` is
  already read from).
- Mapping/clamping logic (new private helper, e.g. `resolveQuality()`/`resolveColorMode()`):
  - `com.hp.jipp.model.PrintQuality` draft/normal/high → the new enum; missing or any
    other value (out of 3–5 range, or absent) clamps to `NORMAL` — same silent-default
    pattern `documentFormat()` already uses for an unrecognized document format.
  - `print-color-mode` "color"/"monochrome" → `COLOR`/`MONOCHROME`; missing or
    unrecognized clamps to `capabilities.color`'s default (`COLOR` if the printer
    supports color, else `MONOCHROME`). Requesting `COLOR` when
    `capabilities.color == false` also clamps to `MONOCHROME` — same clamp path, not a
    separate branch.
- `jobQueue.submit(...)`/`jobQueue.reserve(...)` calls gain the resolved
  `quality`/`colorMode` args.

### `PrinterCapabilities` (`ipp/PrinterCapabilities.kt`)

`asPrinterAttributes()`'s `printQualitySupported` widens from `PrintQuality.normal`
only to `listOf(PrintQuality.draft, PrintQuality.normal, PrintQuality.high)`.
`printQualityDefault` stays `normal`. `printColorModeSupported`/`Default` are already
correct (`color`-conditional list) — no change.

### `RenderingPipeline` (`render/RenderingPipeline.kt`)

`render()` gains two params: `quality: PrintQuality = PrintQuality.NORMAL, colorMode: ColorMode`.
(`colorMode` has no default here either, for the same reason as `PrintJob` — callers
own the printer-default logic.)

### `NativeRenderingPipeline` (`render/NativeRenderingPipeline.kt`)

- Resolves `quality` → dpi: `DRAFT`→300, `NORMAL`/`HIGH`→600 (Photo's 1200 is
  unreachable per scope above). This dpi now drives both `GhostscriptRenderer`'s `-r`
  flag (currently hardcoded to a fixed 300 default at the class level) and the raster
  header's `HWResolution` fed to hpcups — the two must move together, since the raster
  header's declared resolution and the actual pixel data Ghostscript produced have to
  agree.
- Builds a CUPS-style options string from `quality`/`colorMode`, e.g.
  `"ColorModel=KGray OutputMode=FastDraft"`, passed to `HpcupsNative.encode`/
  `encodeRaster`.

### `HpcupsNative` (`render/HpcupsNative.kt`) + `hpcupsjni.cpp`

- `encode()`/`encodeRaster()` gain a `options: String` param (JNI signature change).
- `hpcupsjni.cpp`'s `run_hpcups()` gains a `const char *options` param, used for
  `argv[5]` instead of the current hardcoded `""`. Pure plumbing — no new native
  algorithm, `hpcups`/`HPCupsFilter` already know how to parse a CUPS options string,
  they just never receive a non-empty one today.
- Requires an NDK/CMake rebuild (native source change) and on-device re-verification —
  not exercised by JVM unit tests, same limitation `NativeRenderingPipeline` already has.

## Error handling

- Invalid/out-of-range `print-quality`, unrecognized `print-color-mode`, or `COLOR`
  requested on a monochrome-only printer: all clamp to a default and the job proceeds
  — no `client-error-attributes-or-values` rejection path, matching the existing
  silent-default precedent (`documentFormat()`).
- A bad options string reaching hpcups shouldn't happen (the app only ever emits the
  small fixed set of PPD-valid keywords) — if it somehow did, it surfaces exactly like
  any other hpcups failure today: nonzero return code → `IOException` in
  `NativeRenderingPipeline` → job `ABORTED` in `JobQueue.process()`'s existing catch
  block. No new error path needed.
- No interaction with the render-timeout/poisoning logic in `JobQueue` — quality/color
  don't change how long a render takes in a way that needs new handling.

## Testing

JVM unit tests (`app/src/test/`):
- `LocalIppServer`: Print-Job and Create-Job/Send-Document requests with valid
  `print-quality`/`print-color-mode` produce a `PrintJob` with the correct enum values;
  missing attrs default to NORMAL / printer's default color; out-of-range or garbage
  values clamp instead of erroring; `COLOR` requested on a monochrome-only
  `PrinterCapabilities` clamps to `MONOCHROME`.
- `PrinterCapabilities`: `printQualitySupported` advertises draft/normal/high.
- `FakeRenderingPipeline` (and the inline fakes in `JobQueueTest.kt`, which have their
  own `render()` overrides — all call sites need the signature update) updated to
  accept and record `quality`/`colorMode`, so `JobQueue.process()` tests can assert the
  job's stored values reach `pipeline.render()` unchanged, including through the
  reserve/enqueue two-phase path.

Not JVM-testable — needs the real native `.so` (existing limitation shared with all of
`NativeRenderingPipeline`): dpi resolution actually producing a different-resolution
Ghostscript raster, and the options string actually changing hpcups's PCL3 output.
Covered by hardware smoke-test only.

Manual/hardware verification (extends
`docs/superpowers/testing/hardware-smoke-checklist.md`): print the same document at
draft/normal/high quality from a real IPP-Everywhere client (macOS), confirm visibly
different resolution and render speed; print in monochrome vs. color, confirm
grayscale output for a `KGray`/monochrome request even when submitted from a
color-capable client.
