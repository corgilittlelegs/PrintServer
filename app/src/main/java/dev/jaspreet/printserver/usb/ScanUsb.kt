package dev.jaspreet.printserver.usb

object ScanUsb {
    const val CLASS_VENDOR_SPECIFIC = 255
    private const val SUBCLASS_LEDM_SCAN = 204
    private const val PROTOCOL_LEDM_SCAN = 0

    /** HP's proprietary LEDM scan HTTP channel on RAW_MODE MFPs. HPLIP maps
     *  HPMUD_LEDM_SCAN_CHANNEL to fd ff/cc/0 in io/hpmud/musb.c, so select the
     *  vendor-specific interface with subclass 204 and protocol 0. */
    fun isLedmScan(interfaceClass: Int, subclass: Int, protocol: Int): Boolean =
        interfaceClass == CLASS_VENDOR_SPECIFIC &&
            subclass == SUBCLASS_LEDM_SCAN &&
            protocol == PROTOCOL_LEDM_SCAN
}
