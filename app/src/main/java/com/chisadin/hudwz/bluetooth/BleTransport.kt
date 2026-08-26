package com.chisadin.hudwz.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.chisadin.hudwz.domain.BluetoothDeviceInfo
import com.chisadin.hudwz.domain.TransportType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.math.min

@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val adapter: BluetoothAdapter,
) : BluetoothTransport {
    override val type = TransportType.BLE
    private val _status = MutableStateFlow<TransportStatus>(TransportStatus.Idle)
    override val status: StateFlow<TransportStatus> = _status.asStateFlow()
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    private val writeMutex = Mutex()
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var ready = CompletableDeferred<Unit>()
    private var writeResult: CompletableDeferred<Int>? = null
    private var negotiatedMtu = 23
    private val mainHandler = Handler(Looper.getMainLooper())

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("GATT connection error $status")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!gatt.discoverServices()) fail("Service discovery could not start")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _status.value = TransportStatus.Disconnected("GATT disconnected")
                    if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException("GATT disconnected"))
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Service discovery failed: $status")
                return
            }
            val service: BluetoothGattService = gatt.getService(SERVICE_UUID)
                ?: return fail("HLP service missing")
            tx = service.getCharacteristic(TX_UUID) ?: return fail("HLP TX characteristic missing")
            val rx = service.getCharacteristic(RX_UUID) ?: return fail("HLP RX characteristic missing")
            if (!gatt.setCharacteristicNotification(rx, true)) return fail("Could not enable HLP notifications")
            val descriptor = rx.getDescriptor(CCCD_UUID) ?: return fail("HLP RX CCCD missing")
            val enableValue = if (rx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else if (rx.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else return fail("HLP RX does not support notify or indicate")
            val started = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(descriptor, enableValue) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = enableValue
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            if (!started) fail("Could not write HLP RX CCCD")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("HLP RX subscription failed: $status")
            if (!gatt.requestMtu(247)) markReady()
            else mainHandler.postDelayed({ if (!ready.isCompleted) markReady() }, 1_000)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu.coerceAtLeast(23)
            if (_status.value is TransportStatus.Connected) {
                _status.value = TransportStatus.Connected(negotiatedMtu)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (characteristic.uuid == RX_UUID) characteristic.value?.copyOf()?.let(_incoming::tryEmit)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == RX_UUID) _incoming.tryEmit(value.copyOf())
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeResult?.takeIf { !it.isCompleted }?.complete(status)
        }
    }

    override suspend fun connect(device: BluetoothDeviceInfo, timeoutMillis: Long) {
        disconnect()
        ready = CompletableDeferred()
        _status.value = TransportStatus.Connecting
        val remote = adapter.getRemoteDevice(device.address)
        gatt = remote.connectGatt(context, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
            ?: throw IllegalStateException("connectGatt returned null")
        try {
            withTimeout(timeoutMillis) { ready.await() }
        } catch (error: Throwable) {
            disconnect()
            throw error
        }
    }

    override suspend fun write(bytes: ByteArray) = writeMutex.withLock {
        val activeGatt = gatt ?: throw IllegalStateException("BLE is not connected")
        val characteristic = tx ?: throw IllegalStateException("HLP TX unavailable")
        val payloadSize = (negotiatedMtu - 3).coerceAtLeast(20)
        var offset = 0
        while (offset < bytes.size) {
            val chunk = bytes.copyOfRange(offset, min(bytes.size, offset + payloadSize))
            val result = CompletableDeferred<Int>()
            writeResult = result
            val started = if (Build.VERSION.SDK_INT >= 33) {
                activeGatt.writeCharacteristic(
                    characteristic,
                    chunk,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                activeGatt.writeCharacteristic(characteristic)
            }
            if (!started) throw IllegalStateException("BLE write could not start")
            val status = withTimeout(3_000) { result.await() }
            if (status != BluetoothGatt.GATT_SUCCESS) throw IllegalStateException("BLE write failed: $status")
            offset += chunk.size
        }
        writeResult = null
    }

    override suspend fun disconnect() {
        writeResult?.cancel()
        writeResult = null
        tx = null
        val active = gatt
        gatt = null
        runCatching { active?.disconnect() }
        runCatching { active?.close() }
        negotiatedMtu = 23
        _status.value = TransportStatus.Idle
    }

    private fun markReady() {
        mainHandler.removeCallbacksAndMessages(null)
        _status.value = TransportStatus.Connected(negotiatedMtu)
        if (!ready.isCompleted) ready.complete(Unit)
    }

    private fun fail(reason: String) {
        _status.value = TransportStatus.Failed(reason)
        if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException(reason))
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("8a7e0001-4d6e-4c48-9a9d-484c504c0001")
        val TX_UUID: UUID = UUID.fromString("8a7e0002-4d6e-4c48-9a9d-484c504c0001")
        val RX_UUID: UUID = UUID.fromString("8a7e0003-4d6e-4c48-9a9d-484c504c0001")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
