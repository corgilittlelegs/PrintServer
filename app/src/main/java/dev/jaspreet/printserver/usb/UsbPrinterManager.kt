package dev.jaspreet.printserver.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

class UsbPrinterManager(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findPrinter(): UsbDevice? = usbManager.deviceList.values.firstOrNull { device ->
        device.interfaces().any { it.interfaceClass == IppUsb.CLASS_PRINTER }
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun requestPermission(device: UsbDevice) {
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        usbManager.requestPermission(device, intent)
    }

    /** Opens every IPP-USB interface on the device as an exclusive channel. Empty list = not IPP-USB. */
    fun openIppTransports(device: UsbDevice): List<UsbTransport> {
        val opened = mutableListOf<UsbTransport>()
        try {
            device.interfaces()
                .filter { IppUsb.isIppUsb(it.interfaceClass, it.interfaceSubclass, it.interfaceProtocol) }
                .forEach { iface -> openInterface(device, iface)?.let { opened += it } }
            return opened
        } catch (e: Exception) {
            opened.forEach { try { it.close() } catch (_: Exception) {} }
            throw e
        }
    }

    /** Opens the first classic printer-class interface (for the raw 9100 path). */
    fun openLegacyTransport(device: UsbDevice): UsbTransport? =
        device.interfaces()
            .firstOrNull { IppUsb.isLegacyPrinter(it.interfaceClass, it.interfaceSubclass, it.interfaceProtocol) }
            ?.let { openInterface(device, it) }

    private fun openInterface(device: UsbDevice, iface: UsbInterface): UsbTransport? {
        var outEp: UsbEndpoint? = null
        var inEp: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) outEp = ep
            if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) inEp = ep
        }
        if (outEp == null || inEp == null) return null
        val connection = usbManager.openDevice(device) ?: throw IOException("openDevice failed — permission?")
        if (!connection.claimInterface(iface, true)) {
            connection.close()
            throw IOException("claimInterface failed for interface ${iface.id}")
        }
        return AndroidUsbTransport(connection, iface, outEp, inEp)
    }

    private fun UsbDevice.interfaces(): List<UsbInterface> =
        (0 until interfaceCount).map { getInterface(it) }

    companion object {
        const val ACTION_USB_PERMISSION = "dev.jaspreet.printserver.USB_PERMISSION"
    }
}
