package dev.jaspreet.printserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jaspreet.printserver.activity.ActivityLog
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
        super.onCreate(savedInstanceState)

        // Setup the compose view and bind our state & action handlers
        setContent {
            val status by ServerState.status.collectAsStateWithLifecycle()
            val activityEntries by ActivityLog.entries.collectAsStateWithLifecycle()

            PrintServerTheme {
                PrintServerApp(
                    status = status,
                    activityEntries = activityEntries,
                    onStartServerClick = { startServerIfPermitted() },
                    onStopServerClick = { stopService(Intent(this, ServerService::class.java)) },
                    onBatteryExemptionClick = {
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:$packageName"))
                        )
                    },
                    onLicensesClick = { showLicensesDialog() }
                )
            }
        }

        ContextCompat.registerReceiver(
            this, usbPermissionReceiver, IntentFilter(UsbPrinterManager.ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Launched by USB attach intent -> start serving immediately.
        if (intent?.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            startServerIfPermitted()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbPermissionReceiver)
    }

    private fun showLicensesDialog() {
        val notice = assets.open("licenses/NOTICE.md").bufferedReader().readText()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.licenses_button)
            .setMessage(notice)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // Only calls startForegroundService() once USB permission for the printer is already
    // held — see the comment in ServerService.onStartCommand for why that ordering matters.
    // If permission isn't held yet, requests it and relies on usbPermissionReceiver to retry
    // once the user answers the system dialog.
    private fun startServerIfPermitted() {
        val usb = UsbPrinterManager(this)
        val device = usb.findPrinter() ?: return
        if (!usb.hasPermission(device)) {
            usb.requestPermission(device)
            return
        }
        startForegroundService(Intent(this, ServerService::class.java))
    }
}
