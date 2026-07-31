# PrintServer security hardening plan

## Phase 1: immediate network-facing risk reduction

- Remove `image/pwg-raster` from Tier 2 verified profile advertisement.
- Keep PDF/JPEG support unchanged.
- Add tests that PWG raster is no longer advertised or accepted.

## Phase 2: two-phase job state safety

- Add an internal `SPOOLING` state to reserved jobs.
- Add a queue method that atomically claims a reserved job for Send-Document.
- Make Send-Document stream only after the claim succeeds.
- Make enqueue legal only after the job was claimed for spooling.
- Make failed delivery abort a `SPOOLING` job cleanly.
- Add queue and IPP server tests for duplicate Send-Document rejection.

## Phase 3: follow-up hardening

- Add active/reserved job quotas and reservation expiry.
- Add aggregate spool storage and per-client rate limits.
- Unify strict HTTP body framing validation.
- Add renderer decoded-output/page/time/storage caps.
- Bound eSCL scan storage and delivery.
- Upgrade Ghostscript/CUPS/JIPP and add native raster validation before considering PWG support again.
