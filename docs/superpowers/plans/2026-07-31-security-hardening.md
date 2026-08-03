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

- [x] Add active/reserved job quotas and reservation expiry.
- [x] Add aggregate spool storage limits and per-client reservation limits.
- [x] Isolate IPP job operations by client address.
- [x] Unify strict HTTP body framing validation.
- [x] Add renderer decoded-output/page/time/storage caps.
- [x] Bound LEDM response bodies and eSCL scan storage/retention.
- [x] Upgrade Ghostscript to 10.07.1, CUPS to 2.4.19, and JIPP to 0.7.18.
- [x] Run Android native fixtures and a gated real-HP PWG physical-output test.
- [x] Add an independent, bounded PWG raster validator plus deterministic and
  coverage-guided fuzzing before considering PWG support again.
- [x] Restore PWG capability only for the verified DeskJet profile after those gates pass.
- [ ] Run a real macOS PWG submission through the LAN-facing IPP path.
- [ ] Add optional user-configured authentication only after AirPrint/Windows compatibility design and testing.
- [x] Add compatibility-preserving restricted-client IPv4/CIDR mode across IPP, raw printing, and eSCL.
