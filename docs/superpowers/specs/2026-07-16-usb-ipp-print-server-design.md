# USB → Network Print Server for Android — Design Spec

**Date:** 2026-07-16
**Status:** Approved by user
**Scope:** v1 — personal use / sideload first, Play Store later

## Problem

USB-only printers can't be reached from phones, laptops, or tablets on the network. Existing Android apps (PrinterShare, PrintHand, Lets Print Droid) rely on proprietary clients, cloud routing, or unstable legacy protocols. Goal: plug a USB printer into a spare Android phone and have it appear as a modern driverless network printer to every device on the LAN — no client software, no drivers.

## Key architectural decision: IPP-USB passthrough, no rendering

There is no universal printer language, but there is a universal printing *protocol*: IPP Everywhere / AirPrint. Modern printers (~2013+) additionally implement **IPP-over-USB** (USB interface class 7, subclass 1, protocol 4): the printer speaks HTTP/IPP directly over the USB cable.

Therefore the app is a **protocol-aware byte relay**, not a print engine:

- Client OSes (Windows, macOS, iOS, Linux CUPS, Android) discover the printer via mDNS, query its capabilities through the relay, **render documents themselves** (PWG-Raster / PDF / PCLm), and POST the result.
- The app pipes HTTP between network sockets and USB bulk endpoints. It never parses or renders document content on the print path.
- No Ghostscript, no NDK, no PDL translation. That entire problem class is deleted by scoping v1 to IPP-USB capable printers.

### v1 scope decisions (user-confirmed)

| Decision | Choice |
|---|---|
| Printer support | Modern USB printers with IPP-USB (~2013+) |
| Non-IPP-USB printers | "Unsupported" UI banner + raw port-9100 relay for driver-equipped clients |
| Host phone printing | Network only; host prints via Android's built-in Default Print Service discovering the server (verify in testing) |
| Approach | Pure Kotlin, no native code; mDNS behind swappable interface |
| Distribution | Sideload first, built clean enough for Play Store later |

## Architecture

```
┌─ Android phone (dedicated, plugged in) ─────────────────┐
│  Foreground Service (persistent notification)           │
│  ├── DiscoveryAdvertiser (_ipp._tcp, _pdl-datastream)   │
│  ├── IppRelayServer   :8631  HTTP ⟷ USB IPP channels   │
│  ├── Raw9100Relay     :9100  TCP bytes → USB bulk OUT   │
│  └── UsbPrinterManager (detect, permission, endpoints)  │
└──────────── USB OTG ── Printer (IPP-USB class 7/1/4) ───┘
```

Port 8631 is used because Android forbids non-root binding below 1024 (standard IPP port 631). mDNS advertises the actual port, so clients don't care.

## Components

1. **UsbPrinterManager**
   - Enumerate USB devices, filter class 7 (printer).
   - Request permission; register `USB_DEVICE_ATTACHED` intent filter in the manifest so permission persists and the service auto-starts on plug-in.
   - Detect IPP-USB by interface descriptor (class 7 / subclass 1 / protocol 4). If absent → mark printer "legacy": only the 9100 relay activates and the UI shows an unsupported-for-driverless banner.
   - Claim interfaces, map bulk IN/OUT endpoint pairs.

2. **IppUsbChannelPool**
   - The IPP-USB spec maps each USB interface pair to one HTTP channel; a full HTTP request/response must complete atomically per channel.
   - Printers expose ≥2 IPP-USB interfaces → maintain a pool; one HTTP transaction at a time per channel; queue when all busy.
   - This is the highest-risk correctness area. A channel must never be released carrying a half-finished transaction.

3. **IppRelayServer** (listens :8631)
   - Minimal HTTP handling: parse request line + headers, stream body (including chunked encoding), forward over a leased USB channel, stream the response back.
   - Rewrite the `Host:` header to what the printer expects (`localhost`); some printer firmware rejects unknown hosts.
   - No IPP body parsing on the hot path.

4. **PrinterAttributes**
   - On startup/replug, send `Get-Printer-Attributes` (built with HP JIPP) over USB.
   - Extract make/model, `document-format-supported`, color support, printer UUID, `urf-supported`.
   - Feeds mDNS TXT records and the UI status screen.

5. **DiscoveryAdvertiser** (interface, swappable implementation)
   - v1 impl: `NsdManager`. Advertise `_ipp._tcp` on 8631 with TXT records: `txtvers=1`, `rp=ipp/print`, `pdl=<from printer>`, `UUID=<from printer>`, `color=T/F`, and AirPrint `URF=<urf-supported>` when the printer reports it (required for iOS).
   - Advertise `_pdl-datastream._tcp` on 9100 for legacy printers.
   - Re-register on network change callbacks (IP change, Wi-Fi reconnect).
   - Known risk: NsdManager flakiness on some OEMs under Doze. Mitigated by the dedicated-phone use case (plugged in, foreground service, battery-optimization exemption). If real-world flakiness appears, swap in an NDK mDNSResponder implementation behind the same interface — that is the designed upgrade path, not a v1 requirement.

6. **Raw9100Relay**
   - `ServerSocket` on 9100 → verbatim pipe to bulk OUT endpoint. For clients that have the printer's driver installed.

7. **ServerService** (foreground service)
   - Owns all components' lifecycles. Persistent notification with printer status.
   - Partial wakelock held only during active jobs (POST start → last USB byte acknowledged).
   - Starts on app launch and on USB attach intent.
   - Binds listeners to the Wi-Fi interface only; never the cellular interface.

8. **UI — single screen**
   - Printer name/model/status, server on/off toggle, device IP + port, unsupported-printer banner, battery-optimization exemption prompt.

## Data flow (print from a Mac)

1. Mac browses mDNS → finds the printer at `<phone-ip>:8631`, TXT `rp=ipp/print`.
2. Mac POSTs `Get-Printer-Attributes` → relay leases a USB channel → printer replies with real capabilities → streamed back.
3. Mac renders the document into the printer's preferred format and POSTs `Print-Job`.
4. Relay streams the body network→USB in ~64 KB chunks via `bulkTransfer`. Backpressure is natural: `bulkTransfer` blocks while the printer's buffer is full.
5. Printer's HTTP response (job id, state) streams back; the Mac shows progress.

Legacy path: client with vendor driver sends raw PDL to :9100 → piped verbatim to bulk OUT.

## Error handling

| Event | Handling |
|---|---|
| USB unplug mid-job | Close channels, kill client sockets, deregister mDNS, notification "printer disconnected" |
| Replug | Attach intent → restart pipeline, re-query attributes, re-advertise |
| Wi-Fi drop / IP change | Network callback → re-register mDNS with the new address |
| Doze / OEM killing | Foreground service; wakelock during jobs; UI prompts for battery-optimization whitelist |
| Client abandons mid-POST | Socket timeout → drain the USB channel to idle before releasing it back to the pool |
| Printer stall (`bulkTransfer` < 0) | Retry ×3 → clear-halt via `controlTransfer` → full device reconnect |
| No IPP-USB support | 9100 relay only + UI banner explaining driverless is unavailable |

## Security

- Bind to the Wi-Fi LAN interface only.
- No authentication in v1 (matches consumer network printers); document that the server must not be port-forwarded to the internet.

## Testing

- **Unit:** HTTP parser (headers, chunked bodies), channel-pool state machine, TXT record builder. USB layer sits behind an interface with a fake-printer implementation.
- **Integration (no hardware):** fake USB layer + a real IPP client library exercising the full relay stack end to end.
- **Hardware smoke checklist (manual, v1):** real printer + macOS system dialog, Windows 11 add-printer, iPhone AirPrint, Linux CUPS, Android Default Print Service from the host phone itself.
- **Discovery check:** `dns-sd -B _ipp._tcp` from a Mac.

## Stack

- Kotlin + coroutines, single-module Android app.
- Dependencies: AndroidX + HP JIPP (`com.hp.jipp:jipp-core`) only. No Ktor needed — the relay's HTTP needs are narrow enough for a small hand-rolled parser (fewer deps, full control over streaming).
- Min SDK 26.

## Explicitly out of scope for v1

- Ghostscript/rendering pipeline for legacy printers (Tier 2)
- ESC/POS thermal printer support (Tier 3)
- Dedicated Android `PrintService` for the host phone
- IPPS/TLS, authentication, multi-printer support
- NDK mDNSResponder (designed-for upgrade path only)
