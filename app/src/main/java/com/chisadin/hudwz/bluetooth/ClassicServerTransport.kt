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
class ClassicServerTransport(
    private val adapter: BluetoothAdapter,
    private val serviceName: String = "HLP SPP",
    private val logger: ((category: String, message: String) -> Unit)? = null,
) : BluetoothTransport {
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
    private var originalAdapterName: String? = null

    override suspend fun connect(device: BluetoothDeviceInfo, timeoutMillis: Long) {
        disconnect()
        kotlinx.coroutines.delay(250)
        _status.value = TransportStatus.Connecting

        if (serviceName.contains("VIETMAP", ignoreCase = true)) {
            val currentName = adapter.name
            if (!currentName.isNullOrBlank() && !currentName.equals("VIETMAP H1N", ignoreCase = true)) {
                originalAdapterName = currentName
                val changed = adapter.setName("VIETMAP H1N")
                logger?.invoke("SPP-Server", "Đổi tên Bluetooth sang 'VIETMAP H1N' (kết quả: $changed)")
            }
        }

        logger?.invoke("SPP-Server", "Đang mở cổng RFCOMM '$serviceName' (UUID ${ClassicTransport.SPP_UUID})...")
        val listener = adapter.listenUsingRfcommWithServiceRecord(serviceName, ClassicTransport.SPP_UUID)
        serverSocket = listener
        logger?.invoke("SPP-Server", "Đang chờ thiết bị (Waze Mod hoặc VietMap Live) kết nối...")
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
        val active = socket ?: throw IllegalStateException("Không nhận được socket SPP")
        logger?.invoke("SPP-Server", "Đã chấp nhận kết nối SPP từ: ${active.remoteDevice.address} (${active.remoteDevice.name ?: "Unknown"})")
        _status.value = TransportStatus.Connected()
        reader = scope.launch {
            val buffer = ByteArray(1024)
            try {
                val input = active.inputStream
                while (isActive) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        val chunk = buffer.copyOf(count)
                        logger?.invoke("SPP-Server", "RX SPP (${chunk.size}B)")
                        _incoming.emit(chunk)
                    }
                }
                logger?.invoke("SPP-Server", "Máy khách đã đóng kết nối SPP")
                _status.value = TransportStatus.Disconnected("Máy khách đã đóng kết nối SPP")
            } catch (error: Throwable) {
                logger?.invoke("SPP-Server", "Lỗi đọc dữ liệu SPP: ${error.message}")
                if (isActive) _status.value = TransportStatus.Disconnected(error.message)
            } finally {
                runCatching { active.close() }
            }
        }
    }

    override suspend fun write(bytes: ByteArray) = writeMutex.withLock {
        val active = socket ?: throw IllegalStateException("Chưa có máy khách SPP kết nối")
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
        originalAdapterName?.let { oldName ->
            adapter.setName(oldName)
            logger?.invoke("SPP-Server", "Khôi phục tên Bluetooth: $oldName")
            originalAdapterName = null
        }
        _status.value = TransportStatus.Idle
    }
}
