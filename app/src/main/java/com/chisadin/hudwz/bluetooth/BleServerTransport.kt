package com.chisadin.hudwz.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
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
import kotlin.math.min

@SuppressLint("MissingPermission")
class BleServerTransport(
    private val context: Context,
    private val manager: BluetoothManager,
    private val adapter: BluetoothAdapter,
) : BluetoothTransport {
    override val type = TransportType.BLE
    private val _status = MutableStateFlow<TransportStatus>(TransportStatus.Idle)
    override val status: StateFlow<TransportStatus> = _status.asStateFlow()
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    private val writeMutex = Mutex()
    private var server: BluetoothGattServer? = null
    private var remote: BluetoothDevice? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var ready = CompletableDeferred<Unit>()
    private var notificationResult: CompletableDeferred<Int>? = null
    private var mtu = 23

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            fail("Quảng bá BLE thất bại: $errorCode")
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("Không thể công bố dịch vụ HLP: $status")
            startAdvertising()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("Lỗi kết nối thiết bị ngoại vi BLE: $status")
            when (newState) {
                // A GATT server can receive callbacks for unrelated active LE links. A peer only
                // becomes the HLP client after it subscribes to our RX CCCD below.
                BluetoothProfile.STATE_CONNECTED -> Unit
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (remote?.address == device.address) {
                        remote = null
                        _status.value = TransportStatus.Disconnected("Waze Mod đã ngắt kết nối")
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, value: Int) {
            mtu = value.coerceAtLeast(23)
            if (_status.value is TransportStatus.Connected) _status.value = TransportStatus.Connected(mtu)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val enabled = descriptor.uuid == BleTransport.CCCD_UUID &&
                (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE))
            if (responseNeeded) {
                server?.sendResponse(
                    device,
                    requestId,
                    if (enabled) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    offset,
                    null,
                )
            }
            if (enabled) {
                remote = device
                _status.value = TransportStatus.Connected(mtu)
                if (!ready.isCompleted) ready.complete(Unit)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val accepted = characteristic.uuid == BleTransport.TX_UUID && !preparedWrite && offset == 0
            if (accepted) _incoming.tryEmit(value.copyOf())
            if (responseNeeded) {
                server?.sendResponse(
                    device,
                    requestId,
                    if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    offset,
                    null,
                )
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notificationResult?.takeIf { !it.isCompleted }?.complete(status)
        }
    }

    override suspend fun connect(device: BluetoothDeviceInfo, timeoutMillis: Long) {
        disconnect()
        if (!adapter.isMultipleAdvertisementSupported) {
            throw IllegalStateException("Thiết bị Android này không hỗ trợ quảng bá ngoại vi BLE")
        }
        ready = CompletableDeferred()
        _status.value = TransportStatus.Connecting
        val activeServer = manager.openGattServer(context, callback)
            ?: throw IllegalStateException("Không thể mở máy chủ BLE GATT")
        server = activeServer
        val service = BluetoothGattService(BleTransport.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val tx = BluetoothGattCharacteristic(
            BleTransport.TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        rx = BluetoothGattCharacteristic(
            BleTransport.RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).also { characteristic ->
            characteristic.addDescriptor(
                BluetoothGattDescriptor(
                    BleTransport.CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }
        service.addCharacteristic(tx)
        service.addCharacteristic(rx)
        if (!activeServer.addService(service)) {
            disconnect()
            throw IllegalStateException("Không thể thêm dịch vụ HLP GATT")
        }
        try {
            ready.await()
        } catch (error: Throwable) {
            disconnect()
            throw error
        }
    }

    override suspend fun write(bytes: ByteArray) = writeMutex.withLock {
        val activeServer = server ?: throw IllegalStateException("Bộ nhận BLE chưa chạy")
        val activeDevice = remote ?: throw IllegalStateException("Chưa có máy khách Waze Mod kết nối")
        val characteristic = rx ?: throw IllegalStateException("HLP RX không khả dụng")
        val chunkSize = (mtu - 3).coerceAtLeast(20)
        var offset = 0
        while (offset < bytes.size) {
            val chunk = bytes.copyOfRange(offset, min(bytes.size, offset + chunkSize))
            val result = CompletableDeferred<Int>()
            notificationResult = result
            val started = if (Build.VERSION.SDK_INT >= 33) {
                activeServer.notifyCharacteristicChanged(activeDevice, characteristic, false, chunk) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                activeServer.notifyCharacteristicChanged(activeDevice, characteristic, false)
            }
            if (!started) throw IllegalStateException("Không thể bắt đầu thông báo BLE")
            val resultStatus = withTimeout(3_000) { result.await() }
            if (resultStatus != BluetoothGatt.GATT_SUCCESS) throw IllegalStateException("Thông báo BLE thất bại: $resultStatus")
            offset += chunk.size
        }
        notificationResult = null
    }

    override suspend fun disconnect() {
        notificationResult?.cancel()
        notificationResult = null
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        remote?.let { device -> runCatching { server?.cancelConnection(device) } }
        remote = null
        rx = null
        runCatching { server?.clearServices() }
        runCatching { server?.close() }
        server = null
        mtu = 23
        _status.value = TransportStatus.Idle
    }

    private fun startAdvertising() {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return fail("Quảng bá BLE không khả dụng")
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleTransport.SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private fun fail(reason: String) {
        _status.value = TransportStatus.Failed(reason)
        if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException(reason))
    }
}
