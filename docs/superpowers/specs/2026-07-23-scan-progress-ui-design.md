# Scan progress UI design

Expose honest scan progress in the Android UI without inventing percentage completion.

HP LEDM gives only coarse state:

- scanner status: idle vs busy
- job poll: processing/still waiting, ready-to-upload, completed, canceled
- image fetch: the app is actively receiving JPEG bytes

Therefore the UI should show phase-based progress:

- Starting scan
- Scanner is working
- Receiving image
- Scan ready
- Scan failed

The card may show elapsed time, requested DPI, color mode, and final output size, but it must not show a fake percentage unless a future device exposes real progress.
