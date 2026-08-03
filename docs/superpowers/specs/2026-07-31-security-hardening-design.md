# PrintServer security hardening design

## Context

The app accepts print and scan requests from the local network and, for Tier 2 printers,
renders untrusted client documents through bundled native code before writing printer bytes
to USB. The production audit found several places where a hostile or buggy LAN client can
consume unbounded resources, replay a two-phase print job, or reach fragile native parsers.

## Goals

- Close the highest-risk native input path first.
- Make two-phase IPP jobs claimable exactly once.
- Keep bounded resource behavior visible in tests.
- Preserve HP-first profile gating and the existing driverless client surface where safe.

## Non-goals

- Adding universal printer support.
- Replacing the native rendering pipeline in this change.
- Introducing IPPS or user accounts before compatibility testing with macOS/iOS/Windows.

## Design

Tier 2 initially restricted its LAN-facing capability advertisement to PDF and JPEG while the
PWG path was hardened. PWG is now restored only for the positively matched DeskJet 2300 profile
after independent validation, dependency upgrades, deterministic and coverage-guided fuzzing,
Android native fixtures, and a gated physical-output test on the real printer. Unknown legacy
printers remain refused before this capability surface exists.

Create-Job / Send-Document receives an internal spooling state. A reserved job starts as
`PENDING`; the first valid Send-Document atomically transitions it to `SPOOLING` before bytes
are read. While `SPOOLING`, duplicate sends and cancellations fail rather than appending to or
deleting the same file. Successful delivery captures the final spool size, returns the job to
`PENDING`, and enqueues it exactly once. Delivery failure transitions the job to `ABORTED`.

The follow-up phase adds a 16-active-job queue limit, four outstanding Create-Job
reservations per client, five-minute reservation expiry, a 400 MB aggregate print-spool
limit, per-client ownership checks for job operations, strict shared HTTP framing, bounded
renderer page/pixel/raster/encoded output, and bounded LEDM/eSCL scan bodies and retention.
Third-party parsers are updated to Ghostscript 10.07.1, CUPS 2.4.19, and JIPP 0.7.18.

Full password authentication remains a product/compatibility feature rather than a safe
default hardening toggle: driverless clients must be tested against the selected IPP auth
scheme and credential workflow. Until then, the service remains Wi-Fi-interface-only and
job operations are isolated by source address. PWG input is accepted only through the bounded
validator and only for a verified profile that explicitly advertises it.

### PWG raster validation boundary

The PWG path must not ask CUPS to validate untrusted input with the same parser that will
later consume it. A small, independent Kotlin validator walks the file first without decoding
pixels or allocating from client-controlled dimensions. It accepts only the subset the verified
profile can produce safely: the network-byte-order `RaS2` PWG signature, version-2 1796-byte
page headers, chunked 8-bit sGray or sRGB pixels, and 300/600 dpi pages. Width, height,
bytes-per-line, page count, decoded byte totals, input size, row repetitions, PackBits runs,
truncation, and trailing data are all checked with overflow-safe arithmetic.

`HpcupsNative.encodeRasterGuarded` owns this gate so no application caller can enter the JNI
`encodeRaster` function without successful validation. Validation failure must happen before
the output file is opened or any CUPS/hpcups state is touched. A deterministic mutation corpus
exercises arbitrary input and mutations of a valid multi-row PWG seed on every JVM test run.
The standalone `PwgRasterJazzerTarget` also supports coverage-guided JVM fuzzing. The initial
Jazzer 0.30.0 campaign completed 100,000 executions, reached 167 coverage points, retained 75
useful corpus variants, and found no crash or unchecked exception.
PWG was re-enabled for the verified DeskJet profile after the Android native fixture and real-HP
physical-output gate passed. A real macOS LAN submission remains the final interoperability gate.

## Verification

- JVM tests must cover PWG capability advertisement and submission for the verified profile;
  malformed input must still be rejected by the independent pre-JNI validator.
- JVM tests must cover one-time Send-Document claim and enqueue.
- JVM tests must cover valid PWG structure, every bounded header field, malformed PackBits
  rows, truncation/trailing data, and a deterministic mutation corpus.
- Existing queue, activity-feed, and capability tests must continue to pass.
