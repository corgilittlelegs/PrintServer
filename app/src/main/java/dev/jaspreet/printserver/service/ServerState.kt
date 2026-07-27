package dev.jaspreet.printserver.service

import dev.jaspreet.printserver.scan.ScannerCapabilities
import dev.jaspreet.printserver.scan.ScanColorMode
import dev.jaspreet.printserver.scan.ScanProgressPhase
import dev.jaspreet.printserver.scan.SupplyStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ScanState {
    UNAVAILABLE,
    STARTING,
    READY,
    SCANNING,
    FAILED,
}

data class ScanProgress(
    val phase: ScanProgressPhase,
    val resolution: Int,
    val colorMode: ScanColorMode,
    val startedAtMs: Long = System.currentTimeMillis(),
    val outputBytes: Long? = null,
)

data class ServerStatus(
    val running: Boolean = false,
    val printerName: String? = null,
    val ippSupported: Boolean = true,
    val ip: String? = null,
    val port: Int? = null,
    val message: String = "Idle",
    val manufacturer: String? = null,
    val model: String? = null,
    val serialNumber: String? = null,
    val vidPid: String? = null,
    val pdls: List<String> = emptyList(),
    val tier: Int? = null,
    val connectedAt: Long? = null,
    val scanState: ScanState = ScanState.UNAVAILABLE,
    val scanPort: Int? = null,
    val scanFailureReason: String? = null,
    val scanCapabilities: ScannerCapabilities? = null,
    val scanProgress: ScanProgress? = null,
    val supplyStatus: SupplyStatus? = null,
    val supplyFailureReason: String? = null,
    val profileId: String? = null,
    val profileName: String? = null,
    val unsupportedDevice: Boolean = false,
)

object ServerState {
    private val _status = MutableStateFlow(ServerStatus())
    val status: StateFlow<ServerStatus> = _status.asStateFlow()
    fun update(transform: (ServerStatus) -> ServerStatus) { _status.value = transform(_status.value) }
}
