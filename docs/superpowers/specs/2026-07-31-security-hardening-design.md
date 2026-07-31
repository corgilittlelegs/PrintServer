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

Tier 2 temporarily supports PDF and JPEG only. `image/pwg-raster` is removed from verified
profile capability advertisement, so `Validate-Job`, `Print-Job`, and `Create-Job` reject it
before any spool file is created. The renderer may retain its PWG implementation internally
until a native validator and fuzz-tested dependency upgrade are completed, but the LAN-facing
server must not expose it.

Create-Job / Send-Document receives an internal spooling state. A reserved job starts as
`PENDING`; the first valid Send-Document atomically transitions it to `SPOOLING` before bytes
are read. While `SPOOLING`, duplicate sends and cancellations fail rather than appending to or
deleting the same file. Successful delivery captures the final spool size, returns the job to
`PENDING`, and enqueues it exactly once. Delivery failure transitions the job to `ABORTED`.

Later hardening phases should add active/reserved quotas, stricter HTTP framing, renderer
output caps, scan storage limits, and optional restricted-client mode.

## Verification

- JVM tests must cover unsupported PWG rejection at Validate-Job and Print-Job.
- JVM tests must cover one-time Send-Document claim and enqueue.
- Existing queue, activity-feed, and capability tests must continue to pass.
