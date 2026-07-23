package dev.jaspreet.printserver.scan

enum class ScanProgressPhase {
    STARTING,
    SCANNER_WORKING,
    RECEIVING_IMAGE,
    READY,
    FAILED,
}
