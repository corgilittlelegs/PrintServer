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
import dev.jaspreet.printserver.ipp.LocalIppServer
import dev.jaspreet.printserver.ipp.PrinterCapabilities
import dev.jaspreet.printserver.ipp.PrinterQuery
import dev.jaspreet.printserver.ipp.TxtRecords
import dev.jaspreet.printserver.jobs.JobQueue
import dev.jaspreet.printserver.jobs.JobState
import dev.jaspreet.printserver.relay.ActivityMonitor
import dev.jaspreet.printserver.relay.ChannelPool
import dev.jaspreet.printserver.relay.IppRelayServer
import dev.jaspreet.printserver.relay.Raw9100Relay
import dev.jaspreet.printserver.render.NativeRenderingPipeline
import dev.jaspreet.printserver.render.PpdAsset
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
    private val pipelineActive = AtomicBoolean(false)
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
        val jobActivityIds = java.util.concurrent.ConcurrentHashMap<Int, Int>()
        val queue = JobQueue(
            pipeline, { transport },
            onPipelineStuck = {
                update { ServerStatus(message = "Rendering got stuck — restart the app to recover") }
                stopSelf()
            },
            onJobStateChanged = { job ->
                val status = when (job.state) {
                    JobState.PENDING,
                    JobState.PROCESSING -> ActivityStatus.PRINTING
                    JobState.COMPLETED -> ActivityStatus.PRINTED
                    JobState.ABORTED,
                    JobState.CANCELED -> ActivityStatus.FAILED
                }
                val activityId = jobActivityIds.getOrPut(job.id) {
                    ActivityLog.record(
                        tier = 2, name = job.name, status = status,
                        clientAddress = job.clientAddress, format = job.format,
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

    private fun fail(message: String) {
        update { ServerStatus(message = message) }
        notify(message)
        stopSelf()
    }

    private fun stopPipeline() {
        advertiser?.stopAll(); advertiser = null
        ippServer?.stop(); ippServer = null
        localIppServer?.stop(); localIppServer = null
        jobQueue?.shutdown(); jobQueue = null
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
    }
}
