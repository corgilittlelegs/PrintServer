package dev.jaspreet.printserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jaspreet.printserver.activity.ActivityLog
import dev.jaspreet.printserver.jobs.QueueState
import dev.jaspreet.printserver.scan.ScanToneSettingsState
import dev.jaspreet.printserver.service.ServerService
import dev.jaspreet.printserver.service.ServerState
import dev.jaspreet.printserver.ui.PrintServerApp
import dev.jaspreet.printserver.ui.theme.PrintServerTheme
import dev.jaspreet.printserver.usb.UsbPrinterManager

class MainActivity : AppCompatActivity() {

    // Runs after the user grants (or denies) the USB permission dialog triggered by
    // startServerIfPermitted() below; retries the start now that a decision has been made.
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbPrinterManager.ACTION_USB_PERMISSION) return
            startServerIfPermitted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Setup the compose view and bind our state & action handlers
        setContent {
            val status by ServerState.status.collectAsStateWithLifecycle()
            val activityEntries by ActivityLog.entries.collectAsStateWithLifecycle()
            val queueEntries by QueueState.entries.collectAsStateWithLifecycle()
            val scanToneSettings by ScanToneSettingsState.settings.collectAsStateWithLifecycle()

            PrintServerTheme {
                PrintServerApp(
                    status = status,
                    scanToneSettings = scanToneSettings,
                    activityEntries = activityEntries,
                    queueEntries = queueEntries,
                    onStartServerClick = { startServerIfPermitted() },
                    onStopServerClick = { stopService(Intent(this, ServerService::class.java)) },
                    onBatteryExemptionClick = { requestBatteryExemption() },
                    onCancelJob = { id -> QueueState.cancel(id) },
                    onRetryJob = { id ->
                        if (QueueState.retry(id) == null) {
                            Toast.makeText(this, "Job no longer available to retry", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onScanToneSettingsChange = { brightness, contrast ->
                        ScanToneSettingsState.update(brightness, contrast)
                    },
                )
            }
        }

        ContextCompat.registerReceiver(
            this, usbPermissionReceiver, IntentFilter(UsbPrinterManager.ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        handleUsbAttachIntent(intent)
    }

    // MainActivity is singleTop (see AndroidManifest.xml) so a USB-attach intent while the
    // activity is already on top is delivered here instead of spawning a second instance.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAttachIntent(intent)
    }

    private fun handleUsbAttachIntent(intent: Intent?) {
        if (intent?.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            startServerIfPermitted()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbPermissionReceiver)
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Battery optimization is already disabled for this app", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
        )
    }

    // Only calls startForegroundService() once USB permission for the printer is already
    // held — see the comment in ServerService.onStartCommand for why that ordering matters.
    // If permission isn't held yet, requests it and relies on usbPermissionReceiver to retry
    // once the user answers the system dialog.
    private fun startServerIfPermitted() {
        val usb = UsbPrinterManager(this)
        val device = usb.findPrinter() ?: run {
            ServerState.update { it.copy(message = "No USB printer detected — plug one in and try again") }
            return
        }
        if (!usb.hasPermission(device)) {
            usb.requestPermission(device)
            return
        }
        startForegroundService(Intent(this, ServerService::class.java))
    }
}
