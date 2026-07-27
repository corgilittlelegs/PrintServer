package dev.jaspreet.printserver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dev.jaspreet.printserver.MainActivity
import dev.jaspreet.printserver.R
import dev.jaspreet.printserver.discovery.DiscoveryAdvertiser
import dev.jaspreet.printserver.discovery.NsdAdvertiser
import dev.jaspreet.printserver.activity.ActivityLog
import dev.jaspreet.printserver.activity.ActivityStatus
import dev.jaspreet.printserver.activity.toActivityStatus
import dev.jaspreet.printserver.escl.EsclTxtRecords
import dev.jaspreet.printserver.escl.LocalEsclServer
import dev.jaspreet.printserver.ipp.LocalIppServer
import dev.jaspreet.printserver.ipp.PrinterCapabilities
import dev.jaspreet.printserver.ipp.PrinterQuery
import dev.jaspreet.printserver.ipp.TxtRecords
import dev.jaspreet.printserver.jobs.JobQueue
import dev.jaspreet.printserver.profile.VerifiedPrinterProfiles
import dev.jaspreet.printserver.jobs.JobState
import dev.jaspreet.printserver.jobs.QueueState
import dev.jaspreet.printserver.relay.ActivityMonitor
import dev.jaspreet.printserver.relay.ChannelPool
import dev.jaspreet.printserver.relay.IppRelayServer
import dev.jaspreet.printserver.relay.Raw9100Relay
import dev.jaspreet.printserver.render.NativeRenderingPipeline
import dev.jaspreet.printserver.render.PpdAsset
import dev.jaspreet.printserver.scan.LedmCapabilities
import dev.jaspreet.printserver.scan.ScanColorMode
import dev.jaspreet.printserver.scan.ScanPipeline
import dev.jaspreet.printserver.scan.ScanProgressPhase
import dev.jaspreet.printserver.scan.ScanToneSettingsState
import dev.jaspreet.printserver.scan.ScannerCapabilities
import dev.jaspreet.printserver.scan.LedmSupplyStatus
import dev.jaspreet.printserver.scan.SupplyStatus
import dev.jaspreet.printserver.usb.DeviceId
import dev.jaspreet.printserver.usb.DeviceIdInfo
import dev.jaspreet.printserver.usb.UsbPrinterManager
import dev.jaspreet.printserver.usb.UsbTransport
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ServerService : Service() {

    private var pool: ChannelPool? = null
    private var ippServer: IppRelayServer? = null
    private var rawRelay: Raw9100Relay? = null
    private var legacyTransport: UsbTransport? = null
    private var advertiser: DiscoveryAdvertiser? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var jobQueue: JobQueue? = null
    private var localIppServer: LocalIppServer? = null
    private var localEsclServer: LocalEsclServer? = null
    private val pipelineActive = AtomicBoolean(false)
    private val usbIoLock = Any()
    @Volatile private var servedDeviceId: Int? = null

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val detached = IntentCompat.getParcelableExtra(
                intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java,
            )
            val served = servedDeviceId ?: return // no printer being served yet — ignore all detaches
            if (detached != null && detached.deviceId != served) {
                // Some other peripheral on the same hub detached — the served printer is still connected.
                return
            }
            update { ServerStatus(message = "Printer disconnected") }
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "printserver:jobs")
            .apply { setReferenceCounted(true) }
        ContextCompat.registerReceiver(
            this, detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground() must be called unconditionally and immediately: once the caller
        // has invoked Context.startForegroundService(), Android requires this service to call
        // startForeground() within the promotion window regardless of what happens after —
        // calling stopSelf() instead does NOT satisfy that requirement and crashes with
        // ForegroundServiceDidNotStartInTimeException. So the USB device/permission check (a
        // foregroundServiceType="connectedDevice" service must already hold USB permission at
        // this call, or the OS throws SecurityException) has to happen in the caller
        // (MainActivity) BEFORE it ever calls startForegroundService(), not here.
        startForeground(NOTIFICATION_ID, buildNotification("Starting print server…"))
        if (pipelineActive.compareAndSet(false, true)) {
            thread(name = "pipeline-start") { startPipeline() }
        }
        return START_STICKY
    }

    private fun startPipeline() {
        try {
            val usb = UsbPrinterManager(this)
            val device = usb.findPrinter()
                ?: return fail("No USB printer connected")
            if (!usb.hasPermission(device)) {
                usb.requestPermission(device)
                return fail("Grant the USB permission dialog, then toggle the server on again")
            }
            val deviceIdInfo = DeviceId.parse(usb.readDeviceId(device))
            val bindAddr = WifiAddress.get(this)
                ?: return fail("Wi-Fi is not connected")
            val name = device.productName ?: "USB Printer"
            servedDeviceId = device.deviceId

            val ippTransports = usb.openIppTransports(device)
            if (ippTransports.isNotEmpty()) {
                startIppPipeline(name, ippTransports, bindAddr, device, deviceIdInfo)
            } else {
                val profile = VerifiedPrinterProfiles.match(
                    deviceIdInfo, vendorId = device.vendorId, productId = device.productId,
                )
                if (profile == null) {
                    val detected = listOfNotNull(deviceIdInfo.manufacturer, deviceIdInfo.model)
                        .joinToString(" ")
                        .ifBlank { "this printer" }
                    return fail(
                        "$detected is not a verified printer for on-device rendering — " +
                            "only the HP DeskJet 2300 series is currently supported",
                    )
                }
                startLegacyPipeline(usb, device, bindAddr, deviceIdInfo)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline start failed", e)
            fail(e.message ?: "Unknown startup error")
        }
    }

    private fun startIppPipeline(
        name: String,
        transports: List<UsbTransport>,
        bindAddr: java.net.Inet4Address,
        device: android.hardware.usb.UsbDevice,
        deviceIdInfo: DeviceIdInfo,
    ) {
        val channelPool = ChannelPool(transports).also { pool = it }
        channelPool.onAllChannelsDead = {
            update { ServerStatus(message = "Printer stopped responding — replug it") }
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
                scanState = ScanState.UNAVAILABLE, scanPort = null,
                scanFailureReason = "No eSCL scan server is available for Tier 1 IPP-USB printers",
                scanCapabilities = null,
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
        val initialLegacyTransport = usb.openLegacyTransport(device)
            ?: return fail("Printer has no usable USB interface")
        initialLegacyTransport.close()

        // Tier-2: the app itself is the IPP printer; rendering happens on-device.
        val ppd = PpdAsset.extract(this)
        val pipeline = NativeRenderingPipeline(cacheDir, ppd.absolutePath)
        val spoolDir = File(cacheDir, "spool")
        JobQueue.cleanStaleSpool(spoolDir.apply { mkdirs() }) // drop leftovers from a run killed mid-job
        // Unlike ActivityLog's 200-entry cap, this map has none — it's fine because it's
        // scoped to one startLegacyPipeline run (one sharing session), not the process lifetime.
        val jobActivityIds = java.util.concurrent.ConcurrentHashMap<Int, Int>()
        val queue = JobQueue(
            pipeline, { legacyTransportFor(usb, device) },
            onPipelineStuck = {
                update { ServerStatus(message = "Rendering got stuck — restart the app to recover") }
                stopSelf()
            },
            onJobStateChanged = { job ->
                val status = job.state.toActivityStatus()
                // computeIfAbsent (not getOrPut — that's a plain get-then-put on a
                // ConcurrentHashMap, not atomic) because JobQueue.submit() enqueues the job
                // before firing this callback, so the worker thread can race the submitting
                // thread here for the same new job id.
                val activityId = jobActivityIds.computeIfAbsent(job.id) {
                    ActivityLog.record(
                        tier = 2, name = job.name, status = status,
                        clientAddress = job.clientAddress, format = job.format, jobId = job.id,
                    )
                }
                ActivityLog.update(activityId) { e ->
                    e.copy(
                        status = status,
                        sizeBytes = if (job.state == JobState.PENDING ||
                            job.state == JobState.PROCESSING
                        ) job.spoolFile.length() else e.sizeBytes,
                        completedAt = if (status != ActivityStatus.PRINTING) System.currentTimeMillis() else e.completedAt,
                        failureReason = if (status == ActivityStatus.FAILED) job.stateReason else e.failureReason,
                    )
                }
                QueueState.refresh()
            },
        ).also { jobQueue = it; QueueState.attach(it) }
        val caps = PrinterCapabilities.deskJet2300(
            java.net.URI.create("ipp://${bindAddr.hostAddress}:$IPP_PORT/ipp/print")
        )
        val supplyResult = querySupplyStatusWithRetry(usb, device)
        val ipp = LocalIppServer(
            IPP_PORT,
            caps,
            queue,
            spoolDir,
            supplyStatusProvider = { ServerState.status.value.supplyStatus ?: supplyResult.status },
        )
            .also { localIppServer = it }
        ipp.start(bindAddr)

        // Raw 9100 stays available for PC-driver clients.
        val relay = Raw9100Relay(RAW_PORT) { legacyTransportFor(usb, device) }.also { rawRelay = it }
        relay.start(bindAddr)

        // Scan side (Spec B): open the LEDM scan interface and, if the live ScanCaps
        // query succeeds, start the eSCL server on it. A missing scan interface or a
        // failed capability query just means this printer doesn't support (or we can't
        // yet drive) scanning -- the print pipeline above must not be affected.
        var scanResult = queryScanCapabilitiesWithRetry(usb, device)
        var liveScanCapabilities = scanResult.capabilities
        var scanState = if (liveScanCapabilities != null) ScanState.READY else ScanState.UNAVAILABLE
        var scanFailureReason = scanResult.failureReason
        if (liveScanCapabilities != null) {
            // Starting the eSCL server (binding ESCL_PORT) is likewise isolated: a
            // bind failure here (e.g. stale TIME_WAIT/address-in-use) must not take
            // down the print pipeline that's already running above.
            try {
                LocalEsclServer(
                    port = ESCL_PORT,
                    makeAndModel = caps.makeAndModel,
                    capabilities = liveScanCapabilities,
                    spoolDir = spoolDir,
                    performScan = { resolution, colorMode, brightness, contrast, output ->
                        update {
                            it.copy(
                                scanState = ScanState.SCANNING,
                                scanFailureReason = null,
                                scanProgress = ScanProgress(
                                    phase = ScanProgressPhase.STARTING,
                                    resolution = resolution,
                                    colorMode = colorMode,
                                ),
                            )
                        }
                        try {
                            synchronized(usbIoLock) {
                                closeLegacyTransportForScan()
                                scanWithCandidateFallback(usb, device, output, resolution, colorMode, brightness, contrast)
                            }
                            val outputBytes = output.length()
                            update {
                                it.copy(
                                    scanState = ScanState.READY,
                                    scanFailureReason = null,
                                    scanProgress = ScanProgress(
                                        phase = ScanProgressPhase.READY,
                                        resolution = resolution,
                                        colorMode = colorMode,
                                        startedAtMs = it.scanProgress?.startedAtMs ?: System.currentTimeMillis(),
                                        outputBytes = outputBytes,
                                    ),
                                )
                            }
                        } catch (e: Exception) {
                            val reason = e.message ?: e.javaClass.simpleName
                            update {
                                it.copy(
                                    scanState = ScanState.FAILED,
                                    scanFailureReason = reason,
                                    scanProgress = it.scanProgress?.copy(
                                        phase = ScanProgressPhase.FAILED,
                                    ),
                                )
                            }
                            throw e
                        }
                    },
                    defaultToneSettings = { ScanToneSettingsState.settings.value },
                ).also { localEsclServer = it }.start(bindAddr)
            } catch (e: Exception) {
                scanFailureReason = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "eSCL server start failed, scan server not started: $scanFailureReason")
                localEsclServer = null
                liveScanCapabilities = null
                scanState = ScanState.FAILED
            }
        }

        advertiser = NsdAdvertiser(this).also {
            // Raw 9100 (_pdl-datastream._tcp) is deliberately NOT advertised over mDNS
            // here: a second Bonjour service type under the same instance name made
            // macOS's Add Printer picker see two candidates for one printer and fall
            // back to a generic, unclassified Bonjour entry instead of confidently
            // resolving driverless/AirPrint support via the _ipp._tcp entry alone.
            // The port-9100 socket itself (Raw9100Relay above) stays open for clients
            // that already have a vendor driver and connect to it by IP directly.
            it.advertiseIpp(caps.makeAndModel, IPP_PORT, TxtRecords.forIpp(caps.toPrinterInfo()))
            if (liveScanCapabilities != null) {
                it.advertiseEscl(caps.makeAndModel, ESCL_PORT, EsclTxtRecords.forEscl(liveScanCapabilities, caps.makeAndModel))
            }
        }
        val hostAddress = bindAddr.hostAddress ?: "unknown"
        update {
            it.copy(
                running = true, printerName = caps.makeAndModel, ippSupported = true,
                ip = hostAddress, port = IPP_PORT,
                message = legacyServingMessage(caps.makeAndModel, scanState, scanFailureReason),
                manufacturer = deviceIdInfo.manufacturer, model = deviceIdInfo.model,
                serialNumber = device.serialNumber,
                vidPid = "%04X:%04X".format(device.vendorId, device.productId),
                pdls = deviceIdInfo.commands, tier = 2, connectedAt = System.currentTimeMillis(),
                scanState = scanState,
                scanPort = if (scanState == ScanState.READY) ESCL_PORT else null,
                scanFailureReason = scanFailureReason,
                scanCapabilities = liveScanCapabilities,
                supplyStatus = supplyResult.status,
                supplyFailureReason = supplyResult.failureReason,
            )
        }
        notify(legacyNotificationMessage(caps.makeAndModel, hostAddress, scanState, scanFailureReason))
    }

    private fun updateScanProgressPhase(
        phase: ScanProgressPhase,
        resolution: Int,
        colorMode: ScanColorMode,
    ) {
        update {
            val current = it.scanProgress
            it.copy(
                scanState = ScanState.SCANNING,
                scanFailureReason = null,
                scanProgress = ScanProgress(
                    phase = phase,
                    resolution = resolution,
                    colorMode = colorMode,
                    startedAtMs = current?.startedAtMs ?: System.currentTimeMillis(),
                    outputBytes = current?.outputBytes,
                ),
            )
        }
    }

    private fun legacyTransportFor(
        usb: UsbPrinterManager,
        device: android.hardware.usb.UsbDevice,
    ): UsbTransport = synchronized(usbIoLock) {
        legacyTransport ?: (usb.openLegacyTransport(device)
            ?: throw java.io.IOException("Printer interface no longer available"))
            .also { legacyTransport = it }
    }

    private fun closeLegacyTransportForScan() {
        legacyTransport?.close()
        legacyTransport = null
    }

    private fun scanWithCandidateFallback(
        usb: UsbPrinterManager,
        device: android.hardware.usb.UsbDevice,
        output: File,
        resolution: Int,
        colorMode: ScanColorMode,
        brightness: Int,
        contrast: Int,
    ) {
        val candidates = usb.scanTransportCandidates(device)
        if (candidates.isEmpty()) {
            throw java.io.IOException("Scan interface no longer available")
        }
        var lastFailure: Exception? = null
        for (candidate in candidates) {
            try {
                Log.i(TAG, "scan_diag candidate=${candidate.label} result=starting")
                scanWithRetry(
                    openScanTransport = {
                        candidate.open() ?: throw java.io.IOException("Scan interface ${candidate.label} unavailable")
                    },
                    output = output,
                    resolution = resolution,
                    colorMode = colorMode,
                    brightness = brightness,
                    contrast = contrast,
                    candidateLabel = candidate.label,
                    attempts = 1,
                    onProgress = { phase -> updateScanProgressPhase(phase, resolution, colorMode) },
                )
                Log.i(TAG, "scan_diag candidate=${candidate.label} result=success")
                return
            } catch (e: Exception) {
                lastFailure = e
                Log.w(TAG, "scan_diag candidate=${candidate.label} result=failure reason=${e.message ?: e.javaClass.simpleName}", e)
            }
        }
        throw lastFailure ?: java.io.IOException("All scan interfaces failed")
    }

    /** ScanCaps is a startup probe against a second USB interface, so keep it bounded
     *  and retryable: a transient open/read failure should not permanently hide scanning,
     *  but a genuinely scan-incapable printer should still fail startup promptly. Each
     *  attempt opens and closes its own transport. */
    private fun queryScanCapabilitiesWithRetry(
        usb: UsbPrinterManager,
        device: android.hardware.usb.UsbDevice,
    ): ScanCapsProbeResult {
        var lastFailure: String? = null
        repeat(RETRY_ATTEMPTS) { attempt ->
            val startedAt = System.currentTimeMillis()
            try {
                val capsTransport = usb.openScanTransport(device)
                    ?: return ScanCapsProbeResult(
                        capabilities = null,
                        failureReason = "Scan interface unavailable",
                    )
                try {
                    val caps = LedmCapabilities.query(capsTransport)
                    Log.i(
                        TAG,
                        "scan_diag op=ScanCaps attempt=${attempt + 1}/$RETRY_ATTEMPTS result=success durationMs=${System.currentTimeMillis() - startedAt}",
                    )
                    return ScanCapsProbeResult(capabilities = caps, failureReason = null)
                } finally {
                    capsTransport.close()
                }
            } catch (e: Exception) {
                lastFailure = e.message ?: e.javaClass.simpleName
                Log.w(
                    TAG,
                    "scan_diag op=ScanCaps attempt=${attempt + 1}/$RETRY_ATTEMPTS result=failure durationMs=${System.currentTimeMillis() - startedAt} reason=$lastFailure",
                    e,
                )
                if (attempt < RETRY_ATTEMPTS - 1) Thread.sleep(RETRY_DELAY_MS)
            }
        }
        val failure = lastFailure ?: "ScanCaps query failed"
        Log.w(TAG, "ScanCaps query failed after $RETRY_ATTEMPTS attempts, scan server not started: $failure")
        return ScanCapsProbeResult(capabilities = null, failureReason = failure)
    }

    private fun querySupplyStatusWithRetry(
        usb: UsbPrinterManager,
        device: android.hardware.usb.UsbDevice,
    ): SupplyProbeResult {
        var lastFailure: String? = null
        repeat(SUPPLY_RETRY_ATTEMPTS) { attempt ->
            val startedAt = System.currentTimeMillis()
            try {
                val status = LedmSupplyStatus.query(
                    openTransport = {
                        usb.openScanTransport(device)
                            ?: throw java.io.IOException("LEDM device-management interface unavailable")
                    },
                )
                Log.i(
                    TAG,
                    "supply_diag attempt=${attempt + 1}/$SUPPLY_RETRY_ATTEMPTS result=success durationMs=${System.currentTimeMillis() - startedAt} source=${status.sourcePath} cartridges=${status.cartridges.size}",
                )
                return SupplyProbeResult(status = status, failureReason = null)
            } catch (e: Exception) {
                lastFailure = e.message ?: e.javaClass.simpleName
                Log.w(
                    TAG,
                    "supply_diag attempt=${attempt + 1}/$SUPPLY_RETRY_ATTEMPTS result=failure durationMs=${System.currentTimeMillis() - startedAt} reason=$lastFailure",
                    e,
                )
                if (attempt < SUPPLY_RETRY_ATTEMPTS - 1) Thread.sleep(RETRY_DELAY_MS)
            }
        }
        return SupplyProbeResult(status = null, failureReason = lastFailure ?: "Supply status query failed")
    }

    /** Runs one scan attempt and records structured diagnostics. The caller controls
     *  [attempts]; production uses one attempt per selected scan interface so a physical
     *  scan is never duplicated after the carriage has started moving, while tests can
     *  still exercise bounded retry behavior through this seam. */
    private fun scanWithRetry(
        openScanTransport: () -> UsbTransport,
        output: File,
        resolution: Int,
        colorMode: ScanColorMode,
        brightness: Int,
        contrast: Int,
        candidateLabel: String = "default",
        attempts: Int = SCAN_RETRY_ATTEMPTS,
        onProgress: (ScanProgressPhase) -> Unit = {},
    ) {
        repeat(attempts) { attempt ->
            val startedAt = System.currentTimeMillis()
            try {
                ScanPipeline(openScanTransport, onProgress = onProgress).scan(output, resolution, colorMode, brightness, contrast)
                val outputBytes = validateScanOutput(output)
                Log.i(
                    TAG,
                    "scan_diag op=ScanJob candidate=$candidateLabel attempt=${attempt + 1}/$attempts result=success durationMs=${System.currentTimeMillis() - startedAt} resolution=$resolution colorMode=$colorMode brightness=$brightness contrast=$contrast outputBytes=$outputBytes",
                )
                return
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                Log.w(
                    TAG,
                    "scan_diag op=ScanJob candidate=$candidateLabel attempt=${attempt + 1}/$attempts result=failure durationMs=${System.currentTimeMillis() - startedAt} resolution=$resolution colorMode=$colorMode brightness=$brightness contrast=$contrast reason=$reason",
                    e,
                )
                if (attempt == attempts - 1) throw e
                Thread.sleep(SCAN_RETRY_DELAY_MS)
            }
        }
    }

    private fun validateScanOutput(output: File): Long {
        val size = output.length()
        if (size < MIN_VALID_SCAN_BYTES) {
            throw java.io.IOException("Scan output too small: $size bytes")
        }
        val header = output.inputStream().use { input ->
            ByteArray(3).also { bytes ->
                val read = input.read(bytes)
                if (read < bytes.size) throw java.io.IOException("Scan output truncated: $read header bytes")
            }
        }
        val isJpeg = (header[0].toInt() and 0xFF) == 0xFF &&
            (header[1].toInt() and 0xFF) == 0xD8 &&
            (header[2].toInt() and 0xFF) == 0xFF
        if (!isJpeg) {
            throw java.io.IOException(
                "Scan output is not JPEG: header=" +
                    header.joinToString("") { "%02x".format(it.toInt() and 0xFF) },
            )
        }
        return size
    }

    private data class ScanCapsProbeResult(
        val capabilities: ScannerCapabilities?,
        val failureReason: String?,
    )

    private data class SupplyProbeResult(
        val status: SupplyStatus?,
        val failureReason: String?,
    )

    private fun legacyServingMessage(
        makeAndModel: String,
        scanState: ScanState,
        scanFailureReason: String?,
    ): String = when (scanState) {
        ScanState.READY -> "Serving $makeAndModel (printing and scanning)"
        ScanState.FAILED -> "Serving $makeAndModel; scanning failed: ${scanFailureReason ?: "unknown error"}"
        ScanState.UNAVAILABLE -> "Serving $makeAndModel; scanning unavailable: ${scanFailureReason ?: "not detected"}"
        ScanState.STARTING -> "Serving $makeAndModel; scanning starting"
        ScanState.SCANNING -> "Serving $makeAndModel; scanning"
    }

    private fun legacyNotificationMessage(
        makeAndModel: String,
        hostAddress: String,
        scanState: ScanState,
        scanFailureReason: String?,
    ): String = when (scanState) {
        ScanState.READY -> "Printing ready at $hostAddress:$IPP_PORT; scanning ready at $hostAddress:$ESCL_PORT"
        ScanState.FAILED -> "Printing ready; scanning failed: ${scanFailureReason ?: "unknown error"}"
        ScanState.UNAVAILABLE -> "Printing ready; scanning unavailable: ${scanFailureReason ?: "not detected"}"
        ScanState.STARTING -> "Printing ready; scanning starting for $makeAndModel"
        ScanState.SCANNING -> "Printing ready; scanning active for $makeAndModel"
    }

    private fun fail(message: String) {
        update { ServerStatus(message = message) }
        notify(message)
        stopSelf()
    }

    private fun stopPipeline() {
        advertiser?.stopAll(); advertiser = null
        ippServer?.stop(); ippServer = null
        localIppServer?.stop(); localIppServer = null
        localEsclServer?.stop(); localEsclServer = null
        jobQueue?.shutdown(); jobQueue = null
        QueueState.detach()
        rawRelay?.stop(); rawRelay = null
        legacyTransport?.close(); legacyTransport = null
        pool?.closeAll(); pool = null
        servedDeviceId = null
        pipelineActive.set(false)
    }

    override fun onDestroy() {
        stopPipeline()
        runCatching { unregisterReceiver(detachReceiver) }
        while (wakeLock?.isHeld == true) wakeLock?.release()
        update { ServerStatus() }
        ActivityLog.clear()
        super.onDestroy()
    }

    private fun update(transform: (ServerStatus) -> ServerStatus) = ServerState.update(transform)

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Print server", NotificationManager.IMPORTANCE_LOW)
        )
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }

    private fun notify(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "ServerService"
        private const val CHANNEL_ID = "printserver"
        private const val NOTIFICATION_ID = 1
        const val IPP_PORT = 8631
        const val RAW_PORT = 9100
        const val ESCL_PORT = 8632
        private const val RETRY_ATTEMPTS = 4
        private const val SUPPLY_RETRY_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 400L
        private const val SCAN_RETRY_ATTEMPTS = 3
        private const val SCAN_RETRY_DELAY_MS = 5000L // HPLIP's own guidance: "retry after a few seconds"
        private const val MIN_VALID_SCAN_BYTES = 1024L
    }
}
