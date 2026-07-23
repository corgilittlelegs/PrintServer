# Ink supply status design

Expose HP ink/supply information in the Android UI when the connected USB printer
publishes it through HP's LEDM device-management XML endpoints.

## Scope

- Add a small model for supply/cartridge status: label, color/type, percent remaining,
  status message, and source endpoint.
- Query the printer over the existing LEDM/MFP USB path using fresh `UsbTransport`
  instances, matching the scan capability query pattern.
- Try known HP device-management endpoints in order, because HP firmware names vary:
  `/DevMgmt/ConsumableConfigDyn.xml`, `/DevMgmt/ConsumableStatusDyn.xml`, and
  `/DevMgmt/ProductStatusDyn.xml`.
- Parse XML tolerantly: ignore namespace prefixes, accept several common percent/status
  tag names, and return "unavailable" instead of breaking print/scan startup.
- Surface the result in the running-server UI near the scanner card.
- Expose the same data to macOS through the synthetic Tier 2 IPP server using the
  CUPS/IPP `marker-*` printer attributes (`marker-names`, `marker-types`,
  `marker-colors`, `marker-levels`, `marker-low-levels`, `marker-high-levels`,
  `marker-message`).

## Non-goals

- Do not call or embed full HPLIP device-manager code. This app currently bundles
  `hpcups` for Tier 2 rendering, not HPLIP's Python/status tooling.
- Do not claim fake percentages. If the printer reports only a state/message and no
  percent, show that instead of inventing a level.
- Do not invent IPP supply levels for macOS. If a cartridge has no reported level,
  advertise `-1` for that marker level so clients can treat it as unknown.
- Do not block print serving if the status endpoint is missing or malformed.

## Verification

- JVM tests cover request construction, content-length/chunked response reads, endpoint
  fallback, flexible XML parsing, IPP marker mapping, and `Get-Printer-Attributes`
  filtering for marker attributes.
- Device verification is by installing the app, starting the server with the HP device
  connected, and checking the Supplies card/logs for either parsed cartridges or a clear
  unavailable reason.
