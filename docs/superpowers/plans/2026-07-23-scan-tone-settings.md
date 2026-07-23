# Scan Tone Settings Plan

- [x] Add shared scan tone constants for LEDM min/max/default.
- [x] Advertise eSCL brightness/contrast support ranges.
- [x] Parse optional brightness/contrast from incoming eSCL `ScanSettings`.
- [x] Clamp missing or out-of-range tone values before scan execution.
- [x] Pass resolved tone values into `ScanPipeline` and LEDM `CreateJob`.
- [x] Add JVM unit coverage for parsing, capability XML, server forwarding, and LEDM XML.
- [x] Add Android app controls for default scan brightness/contrast when client UIs hide
  eSCL tone controls.
