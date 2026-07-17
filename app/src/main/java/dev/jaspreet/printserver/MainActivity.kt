package dev.jaspreet.printserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.jaspreet.printserver.service.ServerService
import dev.jaspreet.printserver.service.ServerState
import dev.jaspreet.printserver.usb.UsbPrinterManager
import kotlinx.coroutines.launch

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
        setContentView(R.layout.activity_main)

        val rootLayout = findViewById<View>(R.id.rootLayout)
        val basePadding = rootLayout.paddingLeft // the 16dp set in activity_main.xml, same on all sides
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                basePadding + bars.left, basePadding + bars.top,
                basePadding + bars.right, basePadding + bars.bottom,
            )
            insets
        }

        ContextCompat.registerReceiver(
            this, usbPermissionReceiver, IntentFilter(UsbPrinterManager.ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val printerText = findViewById<TextView>(R.id.printerText)
        val statusText = findViewById<TextView>(R.id.statusText)
        val addressText = findViewById<TextView>(R.id.addressText)
        val legacyBanner = findViewById<TextView>(R.id.legacyBanner)
        val tierText = findViewById<TextView>(R.id.tierText)
        val manufacturerText = findViewById<TextView>(R.id.manufacturerText)
        val modelText = findViewById<TextView>(R.id.modelText)
        val serialText = findViewById<TextView>(R.id.serialText)
        val vidPidText = findViewById<TextView>(R.id.vidPidText)
        val pdlsText = findViewById<TextView>(R.id.pdlsText)
        val connectedText = findViewById<TextView>(R.id.connectedText)
        val toggleButton = findViewById<Button>(R.id.toggleButton)
        val batteryButton = findViewById<Button>(R.id.batteryButton)
        val licensesButton = findViewById<Button>(R.id.licensesButton)

        toggleButton.setOnClickListener {
            val running = ServerState.status.value.running
            if (running) {
                stopService(Intent(this, ServerService::class.java))
            } else {
                startServerIfPermitted()
            }
        }

        batteryButton.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }

        licensesButton.setOnClickListener {
            val notice = assets.open("licenses/NOTICE.md").bufferedReader().readText()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.licenses_button)
                .setMessage(notice)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
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
            }
        }

        // Launched by USB attach intent -> start serving immediately.
        if (intent?.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            startServerIfPermitted()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbPermissionReceiver)
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
