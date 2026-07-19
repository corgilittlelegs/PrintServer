package dev.jaspreet.printserver.usb

object ScanUsb {
    const val CLASS_VENDOR_SPECIFIC = 255

    /** HP's proprietary LEDM scan-data interface on RAW_MODE MFPs (confirmed against a
     *  real HP DeskJet 2300-series unit via `adb shell dumpsys usb`): vendor-specific
     *  class, subclass 4. Distinct from that same device's other vendor-specific
     *  interface (subclass 204), which is HP's status/control channel, not scan data. */
    fun isLedmScan(interfaceClass: Int, subclass: Int): Boolean =
        interfaceClass == CLASS_VENDOR_SPECIFIC && subclass == 4
}
