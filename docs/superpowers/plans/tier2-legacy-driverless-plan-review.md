# Tier-2 Legacy Driverless Printing Plan Review

Source plan: `/Users/jaspreet/Documents/Personal/PrintServer/docs/superpowers/plans/2026-07-16-tier2-legacy-driverless.md`

Review date: 2026-07-16

## Summary

The plan is strong: it is concrete, task-sized, test-aware, and correctly treats the native rendering chain as the main risk. The architecture is plausible for a host-based HP DeskJet family: synthetic IPP server, single-worker job queue, Ghostscript PDF-to-PPM rendering, hpcups PCL3-GUI encoding, and USB output.

The main improvements are about reducing native build fragility, proving the PDF-to-PCL path earlier, improving IPP/client compatibility, avoiding memory pressure, and capturing better diagnostic evidence from real hardware.

## Highest-Priority Improvements

### 1. Add an early native feasibility spike

The plan builds Ghostscript in Task 2 and hpcups in Task 4, but the first true end-to-end native proof is Task 12. Add a Task 4.5 that runs:

```text
fixture PDF -> Ghostscript -> PPM page(s) -> hpcups -> PCL file
```

Run this on a real arm64 Android device before implementing the full job queue and IPP server. This shortens the feedback loop around the riskiest part of the project.

### 2. Make hpcups source patches reproducible

Task 4 currently instructs workers to patch hpcups by grepping and editing source files. That is practical, but fragile. Add checked-in patch files, for example:

```text
native/patches/hplip-3.24.4/0001-expose-hpcups-main.patch
native/patches/hplip-3.24.4/0002-route-filter-fds.patch
```

Then make `native/fetch-hpcups-sources.sh` apply them. This keeps future reruns from drifting silently.

### 3. Verify downloaded native source archives

Ghostscript, HPLIP, and CUPS versions are pinned, but the archives are not checksum-verified. Add SHA256 validation after each download. This makes native builds more reproducible and safer to debug.

### 4. Confirm Ghostscript includes the required output device

The Ghostscript build uses `--with-drivers=FILES`. Add an explicit post-build check that `ppmraw` is present and usable, since the pipeline depends on it.

Suggested check:

```bash
strings app/src/main/jniLibs/arm64-v8a/libgs.so | grep ppmraw
```

Better still, add a tiny native/device smoke invocation that renders one fixture PDF to PPM immediately after building `libgs.so`.

### 5. Stream print output instead of loading it all into memory

`JobQueue` reads the entire rendered PCL file into memory before writing to USB:

```kotlin
val bytes = rendered.readBytes()
transportProvider().write(bytes, 0, bytes.size)
```

For multi-page color output, this could become large. Prefer streaming the rendered file to `UsbTransport` in chunks, or add a transport helper that copies from an `InputStream`.

### 6. Add resource guardrails

The plan accepts arbitrary PDF bodies and renders at 300 DPI. Add limits for:

- maximum incoming IPP document size
- maximum page count
- maximum temp directory/rendered output size
- minimum free cache space before accepting a job

Return a clear IPP error when limits are exceeded.

### 7. Add real-client IPP compatibility checks earlier

The JVM IPP tests are valuable, but they mostly prove compatibility with JIPP-generated packets. Add `ipptool` or CUPS-based probes before hardware smoke testing. This can catch missing required attributes and client expectations much earlier.

Useful checks:

```bash
ipptool -tv ipp://<phone-ip>:8631/ipp/print get-printer-attributes.test
lp -h <phone-ip>:8631 -d ipp/print sample.pdf
```

### 8. Improve IPP validation and attribute behavior

The local IPP server should test and handle:

- `requested-attributes`
- unsupported `document-format`
- missing or wrong `printer-uri`
- `job-uri` as well as `job-id`
- media defaults and supported media
- unsupported print options such as duplex or quality

This will make macOS, iOS, Windows, and CUPS clients less likely to behave strangely.

### 9. Return accurate job state from `Print-Job`

`printJob()` returns `processing` immediately, but the job may only be pending. Return the actual queue state after submit, or default to `pending` unless the worker has already picked it up.

### 10. Clarify cancellation behavior

The plan only supports canceling pending jobs. That is reasonable for a simple USB pipeline, but it should be explicit. For jobs already rendering or writing to USB, return `client-error-not-possible` and expose a useful `job-state-reasons` value.

### 11. Preserve native debug artifacts on failure

The native smoke test only checks that output is non-trivial and starts with ESC. On failure, preserve:

- source PDF
- generated PPM pages
- generated PCL output
- hpcups exit code
- Ghostscript exit code
- filtered logcat output for `hpcupsjni` and `gsjni`

This will make real-device debugging much easier.

### 12. Add a licensing compliance task

The plan correctly notes Ghostscript AGPL and hpcups GPL. Add a concrete task to bundle license files, attribution, and source-availability notes. Even for personal sideloading, this keeps the project tidy and avoids forgetting before wider distribution.

## Smaller Polish

- Task 4 lists `SRC/render/HpcupsEncoder.kt`, but the later implementation creates `HpcupsNative.kt` and `NativeRenderingPipeline.kt`. Either add `HpcupsEncoder.kt` or remove it from the file list.
- Add PPM parser tests for integer overflow before `width * height * 3`.
- Clean old spool/temp directories on service start, not only after each job.
- Consider deriving the stable printer UUID from device/printer identity if multiple phones or printers may appear on the same network.
- Test Android lifecycle cases: screen off, phone locked, USB detached mid-job, Wi-Fi change, service restart, and app process death.
- Add printer status transitions for USB unavailable, out of paper, or transport failure if those signals are available.

## Suggested New Tasks

### Task 4.5: Native pipeline fixture smoke test

Add a device-only fixture test that renders a one-page PDF to PCL using the native chain, without IPP or USB. Preserve artifacts when it fails.

### Task 4.6: Native source reproducibility

Add tarball SHA256 checks, checked-in hpcups patch files, and a script that stages and patches sources deterministically.

### Task 11.5: IPP interoperability probes

Add manual or scripted `ipptool`, CUPS `lp`, and chunked `Print-Job` probes against `LocalIppServer`.

### Task 12.5: Resource and lifecycle hardening

Add body size limits, cache space checks, spool cleanup on startup, and Android lifecycle tests.

## Bottom Line

The plan is executable as written, but it would be much safer with one earlier native end-to-end proof, reproducible native patching, streaming output, and stronger real-client IPP checks. Those changes preserve the plan's good step-by-step shape while reducing the chance of late surprises on hardware.
