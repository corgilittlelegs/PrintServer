package dev.jaspreet.printserver.usb

object IppUsb {
    const val CLASS_PRINTER = 7

    /** IPP-USB per spec: interface class 7 (printer), subclass 1, protocol 4. */
    fun isIppUsb(interfaceClass: Int, subclass: Int, protocol: Int): Boolean =
        interfaceClass == CLASS_PRINTER && subclass == 1 && protocol == 4

    /** Classic USB printer-class interface (unidirectional=1 / bidirectional=2 / 1284.4=3). */
    fun isLegacyPrinter(interfaceClass: Int, subclass: Int, protocol: Int): Boolean =
        interfaceClass == CLASS_PRINTER && !isIppUsb(interfaceClass, subclass, protocol)
}
