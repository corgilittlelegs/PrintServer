# HP Profile-Driven Print/Scan Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move from one hardcoded HP DeskJet 2300 Tier-2 profile to a broader HP-first architecture: detect the connected HP model, select the best supported print/scan profile from bundled HPLIP-derived data, use IPP-USB relay where available, use HPLIP `hpcups` profiles for open host-based models, and clearly mark unknown/plugin-required models as unsupported or experimental.

**Non-goal:** Do not claim or implement non-HP printer support in this plan. Do not pretend untested HP models are hardware-certified. The app can make best-effort profile matches from HPLIP data, but the UI/docs must distinguish "verified" from "profile available, unverified on hardware".

**Architecture:** `UsbPrinterManager` + IEEE-1284 `DeviceId` parsing -> `HpPrinterProfileRegistry` -> selected `HpPrinterProfile` -> Tier 1 IPP-USB relay, Tier 2 synthetic IPP + selected PPD/native encoder, optional HP scan backend (`LEDM` first). Unknown or unsupported HP devices surface a clear app state and never receive DeskJet 2300 PCL3-GUI bytes by accident.

**Primary reference docs:**
- Report: `docs/2026-07-24-hp-production-readiness-report.md`
- Existing Tier 2 design: `docs/superpowers/specs/2026-07-16-tier2-legacy-driverless-printing-design.md`
- Existing scan design: `docs/superpowers/specs/2026-07-19-native-scan-pipeline-design.md`
- Existing scan reliability report: `docs/2026-07-23-scan-reliability-report.md`

**Licensing note:** HPLIP, Ghostscript, and bundled CUPS code have GPL/AGPL/Apache obligations already documented in the repo. Importing more HPLIP PPD/model data may expand those obligations. Before public distribution, confirm license compatibility and source-offer requirements.

---

## Phase 1: Product Scope And Safety Guardrails

### Task 1: Rename scope from "any printer" to HP-first

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: app UI strings if they imply all-printer universality
- Optional: create/update a compatibility note under `docs/`

- [ ] Replace broad claims such as "any USB printer" with "HP-first USB print and scan bridge".
- [ ] Explain the support tiers:
  - Verified: tested on real hardware.
  - Profile available: HPLIP-derived support exists but hardware is not yet confirmed.
  - Unsupported: unknown model, plugin-required model, or unsupported PDL/scan protocol.
- [ ] Keep the DeskJet 2300 series listed as verified.
- [ ] Keep non-HP support explicitly out of scope for now.
- [ ] Run `./gradlew :app:testDebugUnitTest`.

### Task 2: Add explicit unsupported-device state

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/service/ServerState.kt`
- Modify: `app/src/main/java/dev/jaspreet/printserver/service/ServerService.kt`
- Modify: `app/src/main/java/dev/jaspreet/printserver/ui/PrintServerApp.kt`
- Add/modify tests where existing service state is unit-testable

- [ ] Add fields for `supportLevel`, `supportReason`, `selectedProfileId`, and `verifiedHardware`.
- [ ] When a non-IPP legacy HP model has no safe profile, stop startup with a clear message.
- [ ] When a non-HP model is detected, report "non-HP printers are not supported yet".
- [ ] Never fall through to the DeskJet 2300 renderer for an unknown model.

---

## Phase 2: Profile Model And Registry

### Task 3: Define `HpPrinterProfile`

**Files:**
- Create: `app/src/main/java/dev/jaspreet/printserver/profile/HpPrinterProfile.kt`
- Create: `app/src/test/java/dev/jaspreet/printserver/profile/HpPrinterProfileTest.kt`

- [ ] Define profile fields:
  - `id`
  - display name
  - supported model aliases
  - optional VID/PID hints
  - IEEE-1284 `MFG`, `MDL`, `CMD` matchers
  - print backend type: `IPP_USB`, `HPCUPS_PCL3GUI`, future `HPCUPS_LIDIL`, future `HPCUPS_ZJSTREAM`, `UNSUPPORTED_PLUGIN_REQUIRED`, `UNSUPPORTED_UNKNOWN`
  - PPD asset path if needed
  - supported document formats
  - media/capability data
  - scan backend: `NONE`, `HP_LEDM`, future backends
  - verification status
- [ ] Add tests for exact model match, alias match, VID/PID fallback, and plugin-required classification.

### Task 4: Implement `HpPrinterProfileRegistry`

**Files:**
- Create: `app/src/main/java/dev/jaspreet/printserver/profile/HpPrinterProfileRegistry.kt`
- Create: `app/src/test/java/dev/jaspreet/printserver/profile/HpPrinterProfileRegistryTest.kt`

- [ ] Start with a checked-in static registry containing the verified DeskJet 2300 profile.
- [ ] Add explicit unsupported/plugin-required example profiles as tests, even before importing full HPLIP data.
- [ ] Match by parsed `DeviceIdInfo`, raw IEEE-1284 string, VID/PID, and IPP-USB presence.
- [ ] Return a structured result: `Supported(profile)`, `Unsupported(reason)`, or `Unknown(reason)`.

### Task 5: Wire profile selection into startup

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/service/ServerService.kt`
- Modify: `app/src/main/java/dev/jaspreet/printserver/render/PpdAsset.kt`
- Modify: `app/src/main/java/dev/jaspreet/printserver/ipp/PrinterCapabilities.kt`
- Add tests around pure profile/capability generation

- [ ] Read Device ID before deciding Tier 2 profile.
- [ ] If IPP-USB exists, prefer Tier 1 relay.
- [ ] If no IPP-USB exists, require a supported HP profile before starting Tier 2.
- [ ] Replace `PrinterCapabilities.deskJet2300(...)` usage with profile-derived capabilities.
- [ ] Replace single hardcoded `PpdAsset` path with profile-selected PPD extraction.

---

## Phase 3: HPLIP Data Import

### Task 6: Add an HPLIP profile-generation script

**Files:**
- Create: `tools/hplip/extract_hp_profiles.py` or Kotlin/JVM equivalent
- Create: `tools/hplip/README.md`
- Create generated artifact: `app/src/main/assets/hp_profiles.json`
- Optional generated artifacts: selected PPDs under `app/src/main/assets/ppd/`

- [ ] Parse HPLIP model metadata and PPD files from a pinned HPLIP source release.
- [ ] Extract model aliases, PPD names, printer language fields such as `hpPrinterLanguage`, and plugin-required markers where available.
- [ ] Emit a compact JSON profile file suitable for Android asset loading.
- [ ] Keep the generator deterministic so profile diffs are reviewable.
- [ ] Document exactly which HPLIP release is used.

### Task 7: Load generated profiles at runtime

**Files:**
- Modify: `HpPrinterProfileRegistry`
- Add: asset-loading tests if practical

- [ ] Load `hp_profiles.json` from assets.
- [ ] Merge generated profiles with hand-curated overrides.
- [ ] Hand overrides win when a model has known Android-specific limitations.
- [ ] Keep DeskJet 2300 as a hand-verified override.

### Task 8: Classify plugin-required and unsupported models

**Files:**
- Modify: profile generator
- Modify: `HpPrinterProfileRegistry`
- Modify: UI status presentation

- [ ] Detect HPLIP plugin-required models where the metadata exposes that.
- [ ] Mark them as unsupported by default.
- [ ] Show a clear message: "HP model detected, but this model requires a driver component that is not bundled."
- [ ] Add tests to ensure plugin-required models do not start Tier 2 rendering.

---

## Phase 4: Print Backend Generalization

### Task 9: Make `NativeRenderingPipeline` profile-aware

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/render/NativeRenderingPipeline.kt`
- Modify: `app/src/main/java/dev/jaspreet/printserver/render/HpcupsNative.kt`
- Modify: related tests/fakes

- [ ] Pass the selected profile into the rendering pipeline.
- [ ] Use profile-selected PPD and options.
- [ ] Keep PCL3-GUI as the first supported native backend.
- [ ] Return a clean unsupported-backend error for generated profiles whose backend has not been implemented yet.

### Task 10: Validate IPP document formats early

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/ipp/LocalIppServer.kt`
- Modify: `app/src/test/java/dev/jaspreet/printserver/ipp/LocalIppServerTest.kt`

- [ ] In `Validate-Job`, reject unsupported `document-format`.
- [ ] In `Print-Job`, reject unsupported formats before spooling.
- [ ] In `Create-Job` and `Send-Document`, preserve and validate the reserved job format.
- [ ] Add tests for PostScript, PCL, URF, octet-stream, and unknown formats.

### Task 11: Stream incoming print documents to spool

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/http/BodyReader.kt` or add `BodySpooler.kt`
- Modify: `LocalIppServer`
- Add tests for chunked and content-length spooling

- [ ] Replace full in-memory document reads for print jobs with bounded streaming to a spool file.
- [ ] Keep small IPP operation bodies readable in memory where there is no document payload.
- [ ] Preserve chunked transfer decoding.
- [ ] Enforce max job bytes during streaming.

### Task 12: Profile-derived print capabilities

**Files:**
- Modify: `PrinterCapabilities.kt`
- Modify: `TxtRecords.kt`
- Add tests

- [ ] Generate supported media, color, quality, document formats, and URF/TXT values from the selected profile.
- [ ] Do not advertise features that the pipeline ignores.
- [ ] Keep conservative defaults for generated-but-unverified profiles.

---

## Phase 5: Scan Backend Generalization

### Task 13: Add scan backend abstraction

**Files:**
- Create: `app/src/main/java/dev/jaspreet/printserver/scan/ScanBackend.kt`
- Modify: `ServerService`
- Modify: scan tests/fakes

- [ ] Define `ScanBackend` with capability query and scan execution methods.
- [ ] Implement `HpLedmScanBackend` by wrapping existing `LedmCapabilities` and `ScanPipeline`.
- [ ] Enable scan only when the selected profile supports the backend.
- [ ] Keep scan startup isolated from print startup.

### Task 14: Remove hardcoded LEDM scan region

**Files:**
- Modify: `ScanPipeline.kt`
- Modify: `LedmCapabilities.kt`
- Modify tests

- [ ] Pass max scan region from queried capabilities/profile into `ScanPipeline`.
- [ ] Default to DeskJet 2300 dimensions only for the verified DeskJet 2300 profile.
- [ ] Add tests proving non-DeskJet dimensions flow into the LEDM create-job request.

### Task 15: Profile-derived eSCL advertisement

**Files:**
- Modify: `LocalEsclServer`
- Modify: `EsclXml`
- Modify: `EsclTxtRecords`
- Add tests

- [ ] Advertise only implemented scan inputs and formats.
- [ ] Keep initial production scope to flatbed JPEG.
- [ ] Mark ADF, duplex, multipage, PNG, PDF scan output as future capabilities, not advertised features.

---

## Phase 6: Compatibility Matrix And Feedback Loop

### Task 16: Add compatibility matrix

**Files:**
- Create: `docs/hp-compatibility-matrix.md`

- [ ] Columns:
  - model
  - VID:PID
  - Device ID `MDL`
  - Device ID `CMD`
  - selected profile
  - print backend
  - scan backend
  - verification status
  - tested clients
  - notes/failures
- [ ] Add the verified DeskJet 2300-series result.
- [ ] Add generated-but-unverified examples only if clearly marked unverified.

### Task 17: Add diagnostics export

**Files:**
- Modify UI/service as needed
- Create diagnostics formatter under `service` or `profile`
- Add unit tests for redaction/format

- [ ] Export non-sensitive diagnostics:
  - app version
  - Android version/API
  - USB VID:PID
  - raw/parsed IEEE-1284 Device ID
  - interface descriptors
  - selected profile result
  - print/scan backend decision
  - last startup failure reason
- [ ] Redact serial number by default or make it opt-in.
- [ ] Make the output easy for users to paste into an issue.

### Task 18: Add community verification workflow

**Files:**
- Modify: `README.md`
- Create: `docs/hp-model-feedback-template.md`

- [ ] Provide a template for users to report HP model results.
- [ ] Ask for diagnostics export, test client, printed document type, scan app, and observed behavior.
- [ ] Add instructions for marking a generated profile as verified after credible hardware feedback.

---

## Phase 7: Certification And Release Gates

### Task 19: IPP Everywhere self-certification gate

**Files:**
- Create: `docs/testing/ipp-everywhere-self-certification.md`
- Modify hardware checklist

- [ ] Document how to run PWG `dnssd-tests`, `ipp-tests`, and `document-tests`.
- [ ] Require passing results for every profile advertised as verified driverless.
- [ ] Store summaries, not huge generated artifacts, in the repo.

### Task 20: eSCL interop gate

**Files:**
- Create: `docs/testing/escl-interop.md`
- Modify hardware checklist

- [ ] Test macOS Image Capture.
- [ ] Test sane-airscan on Linux.
- [ ] Test Windows Scan where available.
- [ ] Record supported resolutions/color modes and failures in the compatibility matrix.

### Task 21: Release checklist

**Files:**
- Create: `docs/release-checklist.md`

- [ ] Confirm app docs say HP-first, not all-printer universal.
- [ ] Confirm unknown HP models fail safely.
- [ ] Confirm plugin-required models fail safely.
- [ ] Confirm DeskJet 2300 verified path still works.
- [ ] Confirm license notices/source-offer obligations are current.
- [ ] Confirm compatibility matrix is updated.

---

## Suggested Milestone Order

1. **Safety milestone:** Tasks 1-5. Unknown printers no longer get wrong bytes.
2. **HPLIP data milestone:** Tasks 6-8. The app can identify many HP models and classify support.
3. **Print breadth milestone:** Tasks 9-12. More HP print profiles can be attempted safely.
4. **Scan breadth milestone:** Tasks 13-15. HP scan support becomes backend/profile-driven.
5. **Feedback milestone:** Tasks 16-18. Users can help verify models you do not own.
6. **Production gate milestone:** Tasks 19-21. Verified models have certification/interoperability evidence.

## Key Acceptance Rule

A model is not "supported" merely because HPLIP has files for it. It becomes:

- **Profile available** when the app can select a plausible HPLIP-derived profile and backend.
- **Verified** only after real hardware or high-quality community evidence proves print/scan behavior works.
- **Unsupported** when the model needs a plugin, an unimplemented backend, or a protocol path the app cannot safely drive.
