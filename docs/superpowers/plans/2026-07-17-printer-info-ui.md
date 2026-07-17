# Printer Info UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show manufacturer, model, serial number, VID:PID, supported PDLs, serving tier, and connection time for the currently-connected USB printer on the main screen.

**Architecture:** A new pure-Kotlin `DeviceId.parse()` extracts manufacturer/model/PDL fields from the printer's IEEE 1284 Device ID string. `UsbPrinterManager.readDeviceId()` fetches that raw string via a USB Printer Class `GET_DEVICE_ID` control transfer. `ServerStatus` gains fields for all of this (plus serial/VID:PID straight from `UsbDevice`, plus tier and connect time). `ServerService` populates them when it starts serving; `MainActivity` renders them as individually-hideable rows.

**Tech Stack:** Kotlin, Android `UsbManager`/`UsbDeviceConnection`, JUnit (JVM unit tests), existing `ServerState` `StateFlow` pattern.

Spec: `docs/superpowers/specs/2026-07-17-printer-info-ui-design.md`

---

### Task 1: `DeviceId` parser

**Files:**
- Create: `app/src/main/java/dev/jaspreet/printserver/usb/DeviceId.kt`
- Test: `app/src/test/java/dev/jaspreet/printserver/usb/DeviceIdTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.jaspreet.printserver.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceIdTest {

    @Test
    fun `parses a well-formed HP device id string`() {
        val raw = "MFG:HP;CMD:PCL,PJL,POSTSCRIPT;MDL:DeskJet 2700 series;CLS:PRINTER;DES:HP DeskJet 2700 series;"
        val info = DeviceId.parse(raw)
        assertEquals("HP", info.manufacturer)
        assertEquals("DeskJet 2700 series", info.model)
        assertEquals(listOf("PCL", "PJL", "POSTSCRIPT"), info.commands)
    }

    @Test
    fun `accepts MANUFACTURER and MODEL long-form keys`() {
        val raw = "MANUFACTURER:Canon;MODEL:PIXMA MG3600;COMMAND SET:BJL,BJRaster3;"
        val info = DeviceId.parse(raw)
        assertEquals("Canon", info.manufacturer)
        assertEquals("PIXMA MG3600", info.model)
        assertEquals(listOf("BJL", "BJRaster3"), info.commands)
    }

    @Test
    fun `returns empty info for null input`() {
        val info = DeviceId.parse(null)
        assertEquals(null, info.manufacturer)
        assertEquals(null, info.model)
        assertEquals(emptyList<String>(), info.commands)
    }

    @Test
    fun `returns empty info for blank input`() {
        val info = DeviceId.parse("   ")
        assertEquals(null, info.manufacturer)
        assertEquals(null, info.model)
        assertEquals(emptyList<String>(), info.commands)
    }

    @Test
    fun `ignores malformed segments without a colon`() {
        val raw = "MFG:HP;garbage-no-colon;MDL:OfficeJet;"
        val info = DeviceId.parse(raw)
        assertEquals("HP", info.manufacturer)
        assertEquals("OfficeJet", info.model)
    }

    @Test
    fun `missing fields stay null or empty rather than throwing`() {
        val raw = "CLS:PRINTER;DES:Some Printer;"
        val info = DeviceId.parse(raw)
        assertEquals(null, info.manufacturer)
        assertEquals(null, info.model)
        assertEquals(emptyList<String>(), info.commands)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.usb.DeviceIdTest"`
Expected: FAIL to compile — `DeviceId` and `DeviceIdInfo` don't exist yet.

- [ ] **Step 3: Write the implementation**

```kotlin
package dev.jaspreet.printserver.usb

data class DeviceIdInfo(
    val manufacturer: String? = null,
    val model: String? = null,
    val commands: List<String> = emptyList(),
)

/** Parses an IEEE 1284 Device ID string (e.g. "MFG:HP;CMD:PCL,PJL;MDL:DeskJet 2700 series;"). */
object DeviceId {
    fun parse(raw: String?): DeviceIdInfo {
        if (raw.isNullOrBlank()) return DeviceIdInfo()

        val fields = raw.split(";")
            .mapNotNull { segment ->
                val idx = segment.indexOf(':')
                if (idx <= 0) null
                else segment.substring(0, idx).trim().uppercase() to segment.substring(idx + 1).trim()
            }
            .toMap()

        val manufacturer = fields["MFG"] ?: fields["MANUFACTURER"]
        val model = fields["MDL"] ?: fields["MODEL"]
        val commands = (fields["CMD"] ?: fields["COMMAND SET"])
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        return DeviceIdInfo(manufacturer, model, commands)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.usb.DeviceIdTest"`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/usb/DeviceId.kt app/src/test/java/dev/jaspreet/printserver/usb/DeviceIdTest.kt
git commit -m "feat: parse IEEE 1284 device id strings"
```

---

### Task 2: Extend `ServerStatus` with printer-info fields

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/service/ServerState.kt`

- [ ] **Step 1: Add the new fields**

Replace the `ServerStatus` data class:

```kotlin
package dev.jaspreet.printserver.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServerStatus(
    val running: Boolean = false,
    val printerName: String? = null,
    val ippSupported: Boolean = true,
    val ip: String? = null,
    val port: Int? = null,
    val message: String = "Idle",
    val manufacturer: String? = null,
    val model: String? = null,
    val serialNumber: String? = null,
    val vidPid: String? = null,
    val pdls: List<String> = emptyList(),
    val tier: Int? = null,
    val connectedAt: Long? = null,
)

object ServerState {
    private val _status = MutableStateFlow(ServerStatus())
    val status: StateFlow<ServerStatus> = _status.asStateFlow()
    fun update(transform: (ServerStatus) -> ServerStatus) { _status.value = transform(_status.value) }
}
```

- [ ] **Step 2: Build to confirm it still compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (no callers reference the removed positional constructor in a way that breaks — all existing `it.copy(...)` call sites use named args)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/service/ServerState.kt
git commit -m "feat: add printer-info fields to ServerStatus"
```

---

### Task 3: `UsbPrinterManager.readDeviceId()`

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/usb/UsbPrinterManager.kt`

No JVM unit test for this step — it drives real `UsbDeviceConnection.controlTransfer`, which has no meaningful fake without an Android device. Verified via the hardware smoke checklist in Task 6.

- [ ] **Step 1: Add `readDeviceId` to `UsbPrinterManager`**

Add this method to the `UsbPrinterManager` class in `app/src/main/java/dev/jaspreet/printserver/usb/UsbPrinterManager.kt` (after `hasPermission`, before `openIppTransports`):

```kotlin
    /**
     * Reads the printer's IEEE 1284 Device ID string via the USB Printer Class
     * GET_DEVICE_ID control transfer. Returns null if the printer has no
     * printer-class interface, the transfer fails, or it times out — callers
     * should treat that the same as "no info available", not an error.
     */
    fun readDeviceId(device: UsbDevice): String? {
        val iface = device.interfaces().firstOrNull { it.interfaceClass == IppUsb.CLASS_PRINTER }
            ?: return null
        val connection = usbManager.openDevice(device) ?: return null
        return try {
            val buf = ByteArray(1024)
            val read = connection.controlTransfer(
                0xA1, // USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_INTERFACE
                0,    // GET_DEVICE_ID
                0,    // wValue: configuration index
                iface.id, // wIndex: interface number
                buf, buf.size, 5000,
            )
            if (read < 2) return null
            // First 2 bytes are a big-endian length prefix covering themselves + the string.
            val declaredLen = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
            val end = minOf(declaredLen, read)
            if (end <= 2) return null
            String(buf, 2, end - 2, Charsets.US_ASCII)
        } catch (e: Exception) {
            null
        } finally {
            connection.close()
        }
    }
```

- [ ] **Step 2: Build to confirm it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/usb/UsbPrinterManager.kt
git commit -m "feat: read IEEE 1284 device id via USB control transfer"
```

---

### Task 4: Wire printer info into `ServerService`

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/service/ServerService.kt:99-200`

- [ ] **Step 1: Read and parse the device id in `startPipeline`, thread it through**

In `startPipeline()`, after the `usb.hasPermission(device)` check and before `val bindAddr = ...`, add:

```kotlin
            val deviceIdInfo = DeviceId.parse(usb.readDeviceId(device))
```

Change the two branch calls to pass it through:

```kotlin
            val ippTransports = usb.openIppTransports(device)
            if (ippTransports.isNotEmpty()) {
                startIppPipeline(name, ippTransports, bindAddr, device, deviceIdInfo)
            } else {
                startLegacyPipeline(usb, device, bindAddr, deviceIdInfo)
            }
```

Update the two function signatures and their `update { }` calls:

```kotlin
    private fun startIppPipeline(
        name: String,
        transports: List<UsbTransport>,
        bindAddr: java.net.Inet4Address,
        device: android.hardware.usb.UsbDevice,
        deviceIdInfo: DeviceIdInfo,
    ) {
        val channelPool = ChannelPool(transports).also { pool = it }
        channelPool.onAllChannelsDead = {
            update { it.copy(running = false, message = "Printer stopped responding — replug it") }
            stopSelf()
        }
        val info = PrinterQuery.getAttributes(channelPool)
        val monitor = object : ActivityMonitor {
            override fun begin() { wakeLock?.acquire(10 * 60 * 1000L) }
            override fun end() { if (wakeLock?.isHeld == true) wakeLock?.release() }
        }
        val server = IppRelayServer(IPP_PORT, channelPool, monitor).also { ippServer = it }
        server.start(bindAddr)
        advertiser = NsdAdvertiser(this).also {
            it.advertiseIpp(info.makeAndModel, IPP_PORT, TxtRecords.forIpp(info))
        }
        update {
            it.copy(
                running = true, printerName = info.makeAndModel, ippSupported = true,
                ip = bindAddr.hostAddress, port = IPP_PORT, message = "Serving ${info.makeAndModel}",
                manufacturer = deviceIdInfo.manufacturer, model = deviceIdInfo.model,
                serialNumber = device.serialNumber,
                vidPid = "%04X:%04X".format(device.vendorId, device.productId),
                pdls = deviceIdInfo.commands, tier = 1, connectedAt = System.currentTimeMillis(),
            )
        }
        notify("Serving ${info.makeAndModel} at ${bindAddr.hostAddress}:$IPP_PORT")
    }

    private fun startLegacyPipeline(
        usb: UsbPrinterManager,
        device: android.hardware.usb.UsbDevice,
        bindAddr: java.net.Inet4Address,
        deviceIdInfo: DeviceIdInfo,
    ) {
        val transport = usb.openLegacyTransport(device)
            ?: return fail("Printer has no usable USB interface")
        legacyTransport = transport

        // Tier-2: the app itself is the IPP printer; rendering happens on-device.
        val ppd = PpdAsset.extract(this)
        val pipeline = NativeRenderingPipeline(cacheDir, ppd.absolutePath)
        val spoolDir = File(cacheDir, "spool")
        JobQueue.cleanStaleSpool(spoolDir.apply { mkdirs() }) // drop leftovers from a run killed mid-job
        val queue = JobQueue(
            pipeline, { transport },
            onPipelineStuck = {
                update { it.copy(running = false, message = "Rendering got stuck — restart the app to recover") }
                stopSelf()
            },
        ).also { jobQueue = it }
        val caps = PrinterCapabilities.deskJet2300(
            java.net.URI.create("ipp://${bindAddr.hostAddress}:$IPP_PORT/ipp/print")
        )
        val ipp = LocalIppServer(IPP_PORT, caps, queue, spoolDir)
            .also { localIppServer = it }
        ipp.start(bindAddr)

        // Raw 9100 stays available for PC-driver clients.
        val relay = Raw9100Relay(RAW_PORT) { transport }.also { rawRelay = it }
        relay.start(bindAddr)

        advertiser = NsdAdvertiser(this).also {
            // Raw 9100 (_pdl-datastream._tcp) is deliberately NOT advertised over mDNS
            // here: a second Bonjour service type under the same instance name made
            // macOS's Add Printer picker see two candidates for one printer and fall
            // back to a generic, unclassified Bonjour entry instead of confidently
            // resolving driverless/AirPrint support via the _ipp._tcp entry alone.
            // The port-9100 socket itself (Raw9100Relay above) stays open for clients
            // that already have a vendor driver and connect to it by IP directly.
            it.advertiseIpp(caps.makeAndModel, IPP_PORT, TxtRecords.forIpp(caps.toPrinterInfo()))
        }
        update {
            it.copy(
                running = true, printerName = caps.makeAndModel, ippSupported = true,
                ip = bindAddr.hostAddress, port = IPP_PORT,
                message = "Serving ${caps.makeAndModel} (on-device rendering)",
                manufacturer = deviceIdInfo.manufacturer, model = deviceIdInfo.model,
                serialNumber = device.serialNumber,
                vidPid = "%04X:%04X".format(device.vendorId, device.productId),
                pdls = deviceIdInfo.commands, tier = 2, connectedAt = System.currentTimeMillis(),
            )
        }
        notify("Serving ${caps.makeAndModel} at ${bindAddr.hostAddress}:$IPP_PORT")
    }
```

Add the import near the top of the file, alongside the other `dev.jaspreet.printserver.usb` imports:

```kotlin
import dev.jaspreet.printserver.usb.DeviceId
import dev.jaspreet.printserver.usb.DeviceIdInfo
```

- [ ] **Step 2: Also clear the new fields when the pipeline stops**

Find `stopPipeline()` (around `ServerService.kt:208`) and check whether it sets `ServerStatus` back to idle via `update { ServerStatus() }` or similar. Locate that call and confirm it resets to a fresh `ServerStatus()` (which defaults all the new fields to null/empty already) rather than a partial `copy()` that would leave stale printer-info fields visible after stop. If it already constructs a fresh `ServerStatus()`, no change needed — just confirm by reading the surrounding ~15 lines before moving on.

- [ ] **Step 3: Build to confirm it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/service/ServerService.kt
git commit -m "feat: populate printer-info fields when serving starts"
```

---

### Task 5: Show printer info in the UI

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/dev/jaspreet/printserver/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml` (only if it doesn't already define the strings used below — check first)

- [ ] **Step 1: Add TextViews for each new field**

In `activity_main.xml`, insert this block right after the `addressText` `TextView` and before the `legacyBanner` `TextView`:

```xml
    <TextView
        android:id="@+id/tierText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:textSize="14sp"
        android:textColor="#FFFFFF"
        android:visibility="gone" />

    <TextView
        android:id="@+id/manufacturerText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:textSize="14sp"
        android:textColor="#FFFFFF"
        android:visibility="gone" />

    <TextView
        android:id="@+id/modelText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:textSize="14sp"
        android:textColor="#FFFFFF"
        android:visibility="gone" />

    <TextView
        android:id="@+id/serialText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:textSize="14sp"
        android:textColor="#FFFFFF"
        android:visibility="gone" />

    <TextView
        android:id="@+id/vidPidText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:textSize="14sp"
        android:textColor="#FFFFFF"
        android:fontFamily="monospace"
        android:visibility="gone" />

    <TextView
        android:id="@+id/pdlsText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:textSize="14sp"
        android:textColor="#FFFFFF"
        android:visibility="gone" />

    <TextView
        android:id="@+id/connectedText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:textSize="14sp"
        android:textColor="#FFFFFF"
        android:visibility="gone" />
```

- [ ] **Step 2: Wire the new views in `MainActivity`**

In `MainActivity.kt`, add these `findViewById` lines alongside the existing ones (after `val legacyBanner = ...`):

```kotlin
        val tierText = findViewById<TextView>(R.id.tierText)
        val manufacturerText = findViewById<TextView>(R.id.manufacturerText)
        val modelText = findViewById<TextView>(R.id.modelText)
        val serialText = findViewById<TextView>(R.id.serialText)
        val vidPidText = findViewById<TextView>(R.id.vidPidText)
        val pdlsText = findViewById<TextView>(R.id.pdlsText)
        val connectedText = findViewById<TextView>(R.id.connectedText)
```

Then extend the `ServerState.status.collect { s -> ... }` block to also set each field:

```kotlin
                ServerState.status.collect { s ->
                    printerText.text = s.printerName ?: getString(R.string.status_idle)
                    statusText.text = s.message
                    addressText.text = if (s.running && s.ip != null) "http://${s.ip}:${s.port}" else ""
                    legacyBanner.visibility =
                        if (s.running && !s.ippSupported) View.VISIBLE else View.GONE
                    toggleButton.text =
                        getString(if (s.running) R.string.stop_server else R.string.start_server)

                    tierText.text = when (s.tier) {
                        1 -> "Tier 1 (IPP-USB passthrough)"
                        2 -> "Tier 2 (on-device rendering)"
                        else -> ""
                    }
                    tierText.visibility = if (s.tier != null) View.VISIBLE else View.GONE

                    manufacturerText.text = "Manufacturer: ${s.manufacturer}"
                    manufacturerText.visibility = if (s.manufacturer != null) View.VISIBLE else View.GONE

                    modelText.text = "Model: ${s.model}"
                    modelText.visibility = if (s.model != null) View.VISIBLE else View.GONE

                    serialText.text = "Serial: ${s.serialNumber}"
                    serialText.visibility = if (s.serialNumber != null) View.VISIBLE else View.GONE

                    vidPidText.text = "VID:PID ${s.vidPid}"
                    vidPidText.visibility = if (s.vidPid != null) View.VISIBLE else View.GONE

                    pdlsText.text = "PDLs: ${s.pdls.joinToString(", ")}"
                    pdlsText.visibility = if (s.pdls.isNotEmpty()) View.VISIBLE else View.GONE

                    connectedText.text = s.connectedAt?.let {
                        "Connected: ${java.text.DateFormat.getTimeInstance().format(java.util.Date(it))}"
                    } ?: ""
                    connectedText.visibility = if (s.connectedAt != null) View.VISIBLE else View.GONE
                }
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/activity_main.xml app/src/main/java/dev/jaspreet/printserver/MainActivity.kt
git commit -m "feat: show printer manufacturer/model/serial/tier/connect time in UI"
```

---

### Task 6: Manual verification + smoke checklist update

**Files:**
- Modify: `docs/superpowers/testing/hardware-smoke-checklist.md`

- [ ] **Step 1: Add a printer-info verification section**

Add this section to `docs/superpowers/testing/hardware-smoke-checklist.md` (near the top, before the Tier-2 section, since it applies to both tiers):

```markdown
## Printer info card (both tiers)

- [ ] Connect a Tier 1 (IPP-USB) printer, start the server. Manufacturer,
      model, serial, VID:PID, PDL list, "Tier 1", and connect time all show.
- [ ] Connect a Tier 2 (host-based) printer, start the server. Same fields
      show, with "Tier 2" and PDLs reflecting that printer's PCL/PJL support.
- [ ] Manufacturer/model shown match the printer's actual make/model, not a
      generic placeholder.
- [ ] Stop the server. All printer-info rows disappear (no stale data left
      showing after the printer is no longer being served).
- [ ] If reachable, test a printer that doesn't respond to GET_DEVICE_ID (or
      temporarily stub `readDeviceId` to return null): manufacturer/model/PDL
      rows are hidden, not shown as blank or "Unknown" — app doesn't crash,
      serial/VID:PID/tier/connect time still show normally.
```

- [ ] **Step 2: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass including the new `DeviceIdTest`

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/testing/hardware-smoke-checklist.md
git commit -m "docs: add printer-info smoke checklist section"
```
