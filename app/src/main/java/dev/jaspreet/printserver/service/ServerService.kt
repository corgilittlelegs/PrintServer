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
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.jaspreet.printserver.MainActivity
import dev.jaspreet.printserver.R
import dev.jaspreet.printserver.discovery.DiscoveryAdvertiser
import dev.jaspreet.printserver.discovery.NsdAdvertiser
import dev.jaspreet.printserver.ipp.PrinterQuery
import dev.jaspreet.printserver.ipp.TxtRecords
import dev.jaspreet.printserver.relay.ActivityMonitor
import dev.jaspreet.printserver.relay.ChannelPool
import dev.jaspreet.printserver.relay.IppRelayServer
import dev.jaspreet.printserver.relay.Raw9100Relay
import dev.jaspreet.printserver.usb.UsbPrinterManager
import dev.jaspreet.printserver.usb.UsbTransport
import kotlin.concurrent.thread

class ServerService : Service() {

    private var pool: ChannelPool? = null
    private var ippServer: IppRelayServer? = null
    private var rawRelay: Raw9100Relay? = null
    private var advertiser: DiscoveryAdvertiser? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                update { it.copy(running = false, message = "Printer disconnected") }
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "printserver:jobs")
            .apply { setReferenceCounted(true) }
        registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Starting print server…"))
        thread(name = "pipeline-start") { startPipeline() }
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
            val bindAddr = WifiAddress.get(this)
                ?: return fail("Wi-Fi is not connected")
            val name = device.productName ?: "USB Printer"

            val ippTransports = usb.openIppTransports(device)
            if (ippTransports.isNotEmpty()) {
                startIppPipeline(name, ippTransports, bindAddr)
            } else {
                startLegacyPipeline(name, usb, device, bindAddr)
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
            it.copy(running = true, printerName = info.makeAndModel, ippSupported = true,
                ip = bindAddr.hostAddress, port = IPP_PORT, message = "Serving ${info.makeAndModel}")
        }
        notify("Serving ${info.makeAndModel} at ${bindAddr.hostAddress}:$IPP_PORT")
    }

    private fun startLegacyPipeline(
        name: String,
        usb: UsbPrinterManager,
        device: android.hardware.usb.UsbDevice,
        bindAddr: java.net.Inet4Address,
    ) {
        val transport = usb.openLegacyTransport(device)
            ?: return fail("Printer has no usable USB interface")
        val relay = Raw9100Relay(RAW_PORT) { transport }.also { rawRelay = it }
        relay.start(bindAddr)
        advertiser = NsdAdvertiser(this).also { it.advertiseRaw(name, RAW_PORT) }
        update {
            it.copy(running = true, printerName = name, ippSupported = false,
                ip = bindAddr.hostAddress, port = RAW_PORT,
                message = "$name lacks IPP-USB. Driverless printing unavailable; raw port 9100 active for clients with the vendor driver.")
        }
        notify("$name on raw port $RAW_PORT (no driverless support)")
    }

    private fun fail(message: String) {
        update { it.copy(running = false, message = message) }
        notify(message)
        stopSelf()
    }

    private fun stopPipeline() {
        advertiser?.stopAll(); advertiser = null
        ippServer?.stop(); ippServer = null
        rawRelay?.stop(); rawRelay = null
        pool?.closeAll(); pool = null
    }

    override fun onDestroy() {
        stopPipeline()
        runCatching { unregisterReceiver(detachReceiver) }
        while (wakeLock?.isHeld == true) wakeLock?.release()
        update { it.copy(running = false) }
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
