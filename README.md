# PrintServer 🖨️📱

[![Android](https://img.shields.io/badge/Platform-Android_8.0%2B_%28API_26%2B%29-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin_2.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/UI-Jetpack_Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![NDK](https://img.shields.io/badge/Native-NDK_%2F_CMake_arm64--v8a-00599C?style=flat-square&logo=cplusplus&logoColor=white)](https://developer.android.com/ndk)
[![License](https://img.shields.io/badge/License-GPL--2.0-blue.svg?style=flat-square)](LICENSE)

**PrintServer** turns any Android device connected to a USB printer into a driverless network print & scan server. It broadcasts standard **mDNS (Bonjour)** services over your local Wi-Fi network, allowing macOS, iOS, Windows, Linux, and Android clients to discover the printer and print or scan seamlessly with **zero driver installation**—matching native AirPrint, IPP-Everywhere, and eSCL AirScan capabilities.

---

## 🌟 Key Features

- ⚡ **Dual-Tier Printing Architecture**:
  - **Tier 1 (IPP-USB Relay)**: Zero-copy HTTP/IPP passthrough for modern USB printers (~2013+) supporting IPP-over-USB.
  - **Tier 2 (On-Device Native Rendering)**: Synthetic IPP server with embedded native Ghostscript and HPLIP `hpcups` rasterization for legacy/host-based (GDI) PCL3-GUI printers.
- 📡 **AirPrint & IPP-Everywhere Discovery**: Automatic mDNS service registration (`_ipp._tcp`, `_printer._tcp`, `_pdl-datastream._tcp`).
- 📄 **Network Scanning (eSCL / AirScan)**: Full Apple AirScan (`_uscan._tcp`) support translating eSCL XML network requests into hardware USB HP LEDM commands.
- 🔌 **Raw Port-9100 Fallback**: Stream binary print payloads directly for clients with vendor drivers installed.
- 📊 **Live Hardware & Ink Monitoring**: Real-time IEEE-1284 device identification, printer status, serial number, connected tier, and ink/supply level detection.
- 📝 **Activity Feed & Queue Visibility**: Real-time job status tracking (Queued, Rendering, Printing, Completed, Failed) with retained activity history.
- 🛡️ **Network & DoS Hardening**: Wi-Fi interface binding, concurrent client connection limits, partial wakelocks, and graceful USB detach/reattach handling.

---

## 🏗️ System Architecture

```
                  ┌─────────────────────────────────────────┐
                  │              LAN Clients                │
                  │   (macOS, iOS, Windows, Linux, Android) │
                  └──────────────────┬──────────────────────┘
                                     │
                    mDNS / AirPrint  │ IPP / eSCL / Raw 9100
                    Discovery        │ over Wi-Fi
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                           Android Device                                │
│                                                                         │
│  ┌────────────────────────┐                   ┌──────────────────────┐  │
│  │   DiscoveryAdvertiser  │                   │    ServerService     │  │
│  │   (mDNS / NsdManager)  │                   │ (Foreground Service) │  │
│  └────────────────────────┘                   └──────────┬───────────┘  │
│                                                          │              │
│       ┌──────────────────────────────────────────────────┴──────────┐   │
│       │                                                             │   │
│       ▼                                                             ▼   │
│  ┌──────────────────────────────┐              ┌─────────────────────────────┐  │
│  │  Tier 1: IppRelayServer      │              │  Tier 2: LocalIppServer     │  │
│  │  (IPP-USB Direct Relay)      │              │  (Synthetic IPP + Render)   │  │
│  └──────────────┬───────────────┘              └─────────────┬───────────────┘  │
│                 │                                            │                  │
│                 │   ┌────────────────────────────────────────┘                  │
│                 │   │                                                           │
│                 ▼   ▼                                                           │
│  ┌──────────────────────────────┐              ┌─────────────────────────────┐  │
│  │     Scan: LocalEsclServer    │              │   Single-Worker JobQueue    │  │
│  │     (eSCL ──> HP LEDM)       │              │  Ghostscript ──> hpcups PCL │  │
│  └──────────────┬───────────────┘              └─────────────┬───────────────┘  │
│                 │                                            │                  │
│                 └───────────────────┬────────────────────────┘                  │
│                                     │                                           │
│                                     ▼                                           │
│                         ┌───────────────────────┐                               │
│                         │  AndroidUsbTransport  │                               │
│                         └───────────┬───────────┘                               │
└─────────────────────────────────────┼───────────────────────────────────────────┘
                                      │ USB Bulk Transfers (OTG)
                                      ▼
                          ┌───────────────────────┐
                          │      USB Printer      │
                          └───────────────────────┘
```

---

## 📊 Tier Comparison

| Feature | Tier 1 (IPP-USB Relay) | Tier 2 (Native Rendering) |
| :--- | :--- | :--- |
| **Target Printers** | Modern IPP-USB printers (~2013+) | Legacy / Host-based / GDI (e.g. HP DeskJet) |
| **USB Protocol** | Class 7 / Subclass 1 / Protocol 4 | Standard USB Bulk Print Endpoints |
| **Document Processing**| Client-side rendering (Transparent Relay) | On-device PDF → PPM → PCL3-GUI pipeline |
| **Native Dependencies**| None (Pure Kotlin / Java) | Cross-compiled Ghostscript + HPLIP `hpcups` JNI |
| **Memory Footprint** | Extremely low (< 15MB) | Low to moderate (~50MB during active render) |

---

## 📱 User Interface

The app is built using **Jetpack Compose** with dynamic design system components:

- **Server Control**: Single-tap service start/stop pinned strictly to active Wi-Fi interfaces.
- **Printer Info Card**: Live display of manufacturer, model, serial number, connection tier, and ink levels.
- **Scan & Job Progress**: Visual progress indicators for active eSCL scans and rendering queues.
- **Activity Log**: Retains up to 200 past print/scan job entries with detailed completion statuses.

---

## 🛠️ Building & Installation

### Prerequisites

1. **Android Studio**: Ladybug (2024.2.1+) or newer.
2. **Android NDK**: NDK r26+ installed via SDK Manager.
3. **CMake**: 3.22.1+.
4. **Android Device**: Running Android 8.0 (API 26) or higher with USB-OTG host support.

### One-Time Native Setup

Before compiling Tier 2 native binaries for the first time, run the automated setup scripts to fetch and patch external native dependencies (Ghostscript & HPLIP):

```bash
# Fetch and prepare HPLIP/hpcups source files
./native/fetch-hpcups-sources.sh

# Cross-compile Ghostscript shared library for arm64-v8a
./native/build-ghostscript.sh
```

### Build Commands

```bash
# Build Debug APK
./gradlew :app:assembleDebug

# Run JVM Unit Tests (Includes FakePrinterTransport & Pipeline Mocks)
./gradlew :app:testDebugUnitTest

# Install on Connected Android Device
./gradlew :app:installDebug
```

---

## 🔌 Hardware Setup Guide

1. Connect your printer to your Android device using a **USB-OTG adapter**.
2. Launch **PrintServer** and grant USB access when prompted.
3. Connect your Android device to your local **Wi-Fi network**.
4. Tap **Start Server**.
5. On your Mac, iOS device, PC, or Android phone, open the system print/scan dialog:
   - **macOS**: Go to *System Settings → Printers & Scanners*. Your USB printer will automatically appear as an AirPrint / Network Printer and AirScan Scanner.
   - **iOS**: Open any document, tap *Share → Print*. The printer appears in the AirPrint picker.
   - **Windows / Linux**: Discover via IPP / mDNS or add manually using `ipp://<android-ip>:8631/ipp/print`.

---

## 🧪 Testing & Verification

The project includes both JVM unit test suites and hardware verification tools:

- **Unit Tests**: Full coverage of IPP protocol parsing, eSCL XML generation, queue state machines, and HTTP chunking using `FakePrinterTransport`.
- **On-Device Smoke Tests**: Native rendering pipeline smoke test available under `app/src/androidTest/`.
- **Hardware Smoke Test Guide**: Check `docs/superpowers/testing/hardware-smoke-checklist.md` for manual hardware test procedures.

---

## 📜 Open Source Licenses & Acknowledgments

PrintServer incorporates cross-compiled open-source components for on-device PDF rasterization:

- **[HP JIPP Core](https://github.com/HP/jipp)**: Apache-2.0 License.
- **[Ghostscript](https://ghostscript.com)**: AGPLv3 License.
- **[HPLIP / hpcups](https://developers.hp.com/hp-linux-imaging-and-printing)**: GPLv2 / Apache-2.0 License.
- **[CUPS Raster Library](https://openprinting.github.io/cups/)**: Apache-2.0 License with OpenPrinting exceptions.

See `app/src/main/assets/licenses/` for complete license texts.

---

<p align="center">Built with ❤️ for driverless printing.</p>
