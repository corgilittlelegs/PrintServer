# Scan Tone Settings Design

## Goal

Expose brightness and contrast for the eSCL scan path so scanner clients can offer user
controls and the app can pass the selected values through to HP LEDM.

## Scope

- Advertise `BrightnessSupport` and `ContrastSupport` in `ScannerCapabilities`.
- Parse optional `Brightness` and `Contrast` from `ScanSettings`.
- Clamp both values to HPLIP's LEDM tone range, `0..2000`, defaulting to neutral `1000`.
- Thread the resolved values through `LocalEsclServer`, `ServerService`, and `ScanPipeline`.
- Add Android app controls for the app-level default brightness/contrast values, because
  not every eSCL client exposes the advertised tone controls in its own UI. Client-sent
  eSCL values override these defaults when present.
- Keep scan initiation in eSCL clients; this is not an in-app scan workflow.

## Notes

The scan reliability report already confirmed the LEDM firmware expects neutral tone at
`1000/1000`, with supported brightness/contrast values from `0..2000`. eSCL models these
as normal image transform parameters on `ScanSettings` and as capability ranges on
`ScannerCapabilities`.
