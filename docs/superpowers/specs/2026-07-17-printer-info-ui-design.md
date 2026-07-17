# Printer Info UI — Design

## Problem

The main screen (`MainActivity` + `activity_main.xml`) shows only printer name, status message, and the served address. USB enumeration gives us more identifying information about the connected printer that would help users (especially when troubleshooting an unsupported or misbehaving printer): manufacturer, model, serial number, USB VID:PID, supported PDLs, which tier (1 = IPP-USB passthrough, 2 = on-device rendering) is serving it, and when it connected.

## Data model

Extend `ServerStatus` in `app/src/main/java/dev/jaspreet/printserver/service/ServerState.kt` with:

```kotlin
data class ServerStatus(
    val running: Boolean = false,
    val printerName: String? = null,
    val ippSupported: Boolean = true,
    val ip: String? = null,
    val port: Int? = null,
    val message: String = "Idle",
    // New:
    val manufacturer: String? = null,
    val model: String? = null,
    val serialNumber: String? = null,
    val vidPid: String? = null,
    val pdls: List<String> = emptyList(),
    val tier: Int? = null,           // 1 = IPP-USB passthrough, 2 = on-device rendering
    val connectedAt: Long? = null,   // epoch millis, session-only — set when running=true, cleared on stop
)
```

`connectedAt` is session-only (not persisted): set when the pipeline starts serving this run, cleared when the server stops. It does not survive app restart or track "first ever seen" — that distinction was explicitly decided against to avoid adding a storage layer for a nice-to-have field.

## Where each field comes from

- **`serialNumber`** — `UsbDevice.serialNumber`, read directly once USB permission is held. No extra USB transaction.
- **`vidPid`** — `"%04X:%04X".format(device.vendorId, device.productId)`, direct `UsbDevice` API, no extra USB transaction.
- **`manufacturer`, `model`, `pdls`** — parsed from the IEEE 1284 Device ID string, which is not exposed by `UsbDevice`/`UsbManager` directly. Requires a new USB Printer Class `GET_DEVICE_ID` control transfer (bRequest = 0) issued over the already-open `UsbDeviceConnection`.
- **`tier`** — already implicitly known in `ServerService.startPipeline`: `1` when `usb.openIppTransports(device)` is non-empty (`startIppPipeline`), `2` otherwise (`startLegacyPipeline`). Just needs to be recorded into `ServerStatus`.
- **`connectedAt`** — `System.currentTimeMillis()`, set at the same point `running = true` is set.

### Note on Tier 2 `printerName` accuracy

Today, Tier 2's `printerName` comes from a hardcoded `caps.makeAndModel` placeholder (`PrinterCapabilities.deskJet2300(...)`), not the actual connected device — because Tier 2 printers have no real IPP endpoint to query for their true identity. The new `manufacturer`/`model` fields, sourced from the printer's own Device ID string, will show the *actual* reported model, which is more accurate than the existing placeholder `printerName` for this tier. Fixing `printerName` itself to use this data is out of scope for this change; flagged as a natural follow-up.

## USB layer changes

`UsbPrinterManager` (`app/src/main/java/dev/jaspreet/printserver/usb/UsbPrinterManager.kt`) gets one new method:

```kotlin
fun readDeviceId(device: UsbDevice): String?
```

Opens (or reuses) the device connection and issues the class-specific `GET_DEVICE_ID` control transfer on the printer interface. Returns the raw IEEE 1284 string (e.g. `MFG:HP;CMD:PCL,PJL,POSTSCRIPT;MDL:DeskJet 2700 series;CLS:PRINTER;`) or `null` if the transfer fails, times out, or the printer doesn't implement it.

New pure-Kotlin file `app/src/main/java/dev/jaspreet/printserver/usb/DeviceId.kt`:

```kotlin
data class DeviceIdInfo(
    val manufacturer: String? = null,
    val model: String? = null,
    val commands: List<String> = emptyList(),
)

object DeviceId {
    fun parse(raw: String?): DeviceIdInfo
}
```

Parses the semicolon-delimited `KEY:value;` format (`MFG`/`MFR` → manufacturer, `MDL`/`MODEL` → model, `CMD`/`COMMAND SET` → comma-split PDL list). Tolerant of missing keys, malformed segments, and `null`/empty input — always returns a `DeviceIdInfo`, never throws. This is the only new logic with real branching, and it has no Android dependency, so it's fully JVM-unit-testable.

## Wiring into `ServerService`

In `startPipeline()` (`app/src/main/java/dev/jaspreet/printserver/service/ServerService.kt`), after the existing `usb.hasPermission(device)` check passes and before branching into `startIppPipeline`/`startLegacyPipeline`:

```kotlin
val deviceIdInfo = DeviceId.parse(usb.readDeviceId(device))
```

Pass `deviceIdInfo` into both `startIppPipeline` and `startLegacyPipeline`. Each `update { it.copy(...) }` call (currently at `ServerService.kt:146` and `ServerService.kt:195`) is extended to also set:

```kotlin
manufacturer = deviceIdInfo.manufacturer,
model = deviceIdInfo.model,
serialNumber = device.serialNumber,
vidPid = "%04X:%04X".format(device.vendorId, device.productId),
pdls = deviceIdInfo.commands,
tier = 1,  // or 2, depending on which function
connectedAt = System.currentTimeMillis(),
```

## Error handling

- `readDeviceId` control transfer failure (timeout, unsupported, malformed response) → returns `null`. `DeviceId.parse(null)` returns an all-empty `DeviceIdInfo` — no exception propagates.
- Per product decision, fields with no data (null or empty) are **not** shown as "Unknown" — their UI rows are hidden entirely (`View.GONE`), rather than rendering a placeholder. `serialNumber`, `vidPid`, and `tier` don't depend on the Device ID transfer, so they still populate even if `GET_DEVICE_ID` fails entirely.

## UI changes

`activity_main.xml`: add one `TextView` per new field below the existing `addressText` — `tierText`, `manufacturerText`, `modelText`, `serialText`, `vidPidText`, `pdlsText`, `connectedText`. Each `wrap_content`, styled consistent with the existing white-on-black text views.

`MainActivity.kt`'s existing `ServerState.status.collect { s -> ... }` block (`MainActivity.kt:77`) gets one line per field:

```kotlin
manufacturerText.text = s.manufacturer ?: ""
manufacturerText.visibility = if (s.manufacturer != null) View.VISIBLE else View.GONE
// ... same pattern for model, serialNumber, vidPid, tier
pdlsText.text = s.pdls.joinToString(", ")
pdlsText.visibility = if (s.pdls.isNotEmpty()) View.VISIBLE else View.GONE
connectedText.text = s.connectedAt?.let { DateFormat.getTimeInstance().format(Date(it)) } ?: ""
connectedText.visibility = if (s.connectedAt != null) View.VISIBLE else View.GONE
```

Fields render as always-visible rows when populated (chosen layout: option A — inline block under the existing status text, not a collapsible section or separate dialog), each independently hidden when its value is unavailable.

## Testing

- **`DeviceId.parse()`** — JVM unit test (`app/src/test/.../usb/DeviceIdTest.kt`), table-driven over real-world Device ID strings (HP, Canon vendor formats), a malformed/truncated string, and empty/null input. This is the only new logic worth unit testing.
- **`readDeviceId` control transfer** — like the rest of `AndroidUsbTransport`/`UsbPrinterManager`, this touches real Android USB APIs and isn't JVM-testable. Verified on-device via the hardware smoke checklist.
- **UI visibility toggling** — no existing UI test infrastructure in this project; also covered by the hardware smoke checklist.
- Add to `docs/superpowers/testing/hardware-smoke-checklist.md`: confirm manufacturer/model/serial/PDL fields populate correctly against both a Tier 1 and a Tier 2 printer, and that fields hide gracefully (no crash, no stale data) against a printer that doesn't respond to `GET_DEVICE_ID`.

## Out of scope

- Fixing Tier 2's `printerName` to use the real Device ID model instead of the hardcoded `caps.makeAndModel` placeholder.
- Persisting `connectedAt` across app restarts / tracking "first seen" per printer.
- The `ippSupported` field's existing (seemingly always-`true`) behavior — unrelated pre-existing code, not touched here.
