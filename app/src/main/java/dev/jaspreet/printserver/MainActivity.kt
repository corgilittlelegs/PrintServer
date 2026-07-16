package dev.jaspreet.printserver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.jaspreet.printserver.service.ServerService
import dev.jaspreet.printserver.service.ServerState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val printerText = findViewById<TextView>(R.id.printerText)
        val statusText = findViewById<TextView>(R.id.statusText)
        val addressText = findViewById<TextView>(R.id.addressText)
        val legacyBanner = findViewById<TextView>(R.id.legacyBanner)
        val toggleButton = findViewById<Button>(R.id.toggleButton)
        val batteryButton = findViewById<Button>(R.id.batteryButton)

        toggleButton.setOnClickListener {
            val running = ServerState.status.value.running
            val intent = Intent(this, ServerService::class.java)
            if (running) stopService(intent) else startForegroundService(intent)
        }

        batteryButton.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
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
                }
            }
        }

        // Launched by USB attach intent -> start serving immediately.
        if (intent?.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            startForegroundService(Intent(this, ServerService::class.java))
        }
    }
}
