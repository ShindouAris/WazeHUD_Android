package com.chisadin.hudwz.bluetooth

import com.chisadin.hudwz.domain.BluetoothDeviceInfo
import com.chisadin.hudwz.domain.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface TransportStatus {
    data object Idle : TransportStatus
    data object Connecting : TransportStatus
    data class Connected(val mtu: Int? = null) : TransportStatus
    data class Disconnected(val reason: String? = null) : TransportStatus
    data class Failed(val reason: String) : TransportStatus
}

interface BluetoothTransport {
    val type: TransportType
    val status: StateFlow<TransportStatus>
    val incoming: Flow<ByteArray>

    suspend fun connect(device: BluetoothDeviceInfo, timeoutMillis: Long)
    suspend fun write(bytes: ByteArray)
    suspend fun disconnect()
}
