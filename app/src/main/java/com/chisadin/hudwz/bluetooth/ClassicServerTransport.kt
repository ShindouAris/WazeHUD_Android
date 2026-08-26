package com.chisadin.hudwz.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.chisadin.hudwz.domain.BluetoothDeviceInfo
import com.chisadin.hudwz.domain.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@SuppressLint("MissingPermission")
class ClassicServerTransport(private val adapter: BluetoothAdapter) : BluetoothTransport {
    override val type = TransportType.CLASSIC
    private val _status = MutableStateFlow<TransportStatus>(TransportStatus.Idle)
    override val status: StateFlow<TransportStatus> = _status.asStateFlow()
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private var serverSocket: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var reader: Job? = null

    override suspend fun connect(device: BluetoothDeviceInfo, timeoutMillis: Long) {
        disconnect()
        _status.value = TransportStatus.Connecting
        val listener = adapter.listenUsingRfcommWithServiceRecord("HLP SPP", ClassicTransport.SPP_UUID)
        serverSocket = listener
        try {
            socket = withTimeout(86_400_000L) {
                withContext(Dispatchers.IO) { listener.accept() }
            }
        } catch (error: Throwable) {
            disconnect()
            throw error
        } finally {
            runCatching { listener.close() }
            serverSocket = null
        }
        val active = socket ?: throw IllegalStateException("SPP accept returned no socket")
        _status.value = TransportStatus.Connected()
        reader = scope.launch {
            val buffer = ByteArray(1024)
            try {
                val input = active.inputStream
                while (isActive) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) _incoming.emit(buffer.copyOf(count))
                }
                _status.value = TransportStatus.Disconnected("Waze Mod closed SPP")
            } catch (error: Throwable) {
                if (isActive) _status.value = TransportStatus.Disconnected(error.message)
            }
        }
    }

    override suspend fun write(bytes: ByteArray) = writeMutex.withLock {
        val active = socket ?: throw IllegalStateException("No Waze Mod SPP client connected")
        withContext(Dispatchers.IO) {
            active.outputStream.write(bytes)
            active.outputStream.flush()
        }
    }

    override suspend fun disconnect() {
        val activeReader = reader
        reader = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { socket?.close() }
        socket = null
        if (activeReader != null && activeReader != kotlinx.coroutines.currentCoroutineContext()[Job]) {
            activeReader.cancelAndJoin()
        }
        _status.value = TransportStatus.Idle
    }
}
