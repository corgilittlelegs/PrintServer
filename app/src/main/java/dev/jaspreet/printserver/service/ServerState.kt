package dev.jaspreet.printserver.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServerStatus(
    val running: Boolean = false,
    val printerName: String? = null,
    val ippSupported: Boolean = true,
    val ip: String? = null,
    val port: Int? = null,
    val message: String = "Idle",
)

object ServerState {
    private val _status = MutableStateFlow(ServerStatus())
    val status: StateFlow<ServerStatus> = _status.asStateFlow()
    fun update(transform: (ServerStatus) -> ServerStatus) { _status.value = transform(_status.value) }
}
