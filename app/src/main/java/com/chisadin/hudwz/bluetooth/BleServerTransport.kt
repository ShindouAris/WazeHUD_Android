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
    val profile: GattProfile = GattProfile.HLP,
    private val logger: ((category: String, message: String) -> Unit)? = null,
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
    private var originalAdapterName: String? = null

    private fun bytesToHex(bytes: ByteArray): String =
        com.chisadin.hudwz.protocol.vietmap.VietMapH1Decoder.bytesToHex(bytes)

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            logger?.invoke("BLE-Server", "Đang phát sóng BLE thành công! Tên: ${adapter.name}")
        }

        override fun onStartFailure(errorCode: Int) {
            if (errorCode != AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) {
                logger?.invoke("BLE-Server", "Phát sóng BLE thất bại: mã lỗi $errorCode")
                fail("Quảng bá BLE thất bại: $errorCode")
            }
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger?.invoke("BLE-Server", "Lỗi thêm dịch vụ GATT: $status")
                return fail("Không thể công bố dịch vụ: $status")
            }
            logger?.invoke("BLE-Server", "Đã thêm dịch vụ GATT ${service.uuid}")
            startAdvertising()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    remote = device
                    logger?.invoke("BLE-Server", "Thiết bị đã kết nối tầng Bluetooth: ${device.address} (${device.name ?: "Unknown"})")
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        logger?.invoke("BLE-Server", "Lỗi kết nối GATT status: $status")
                        fail("Lỗi kết nối thiết bị ngoại vi BLE: $status")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    logger?.invoke("BLE-Server", "Thiết bị đã ngắt kết nối: ${device.address}")
                    if (remote?.address == device.address) {
                        remote = null
                        if (_status.value is TransportStatus.Connected) {
                            _status.value = TransportStatus.Disconnected("Thiết bị ngoại vi đã ngắt kết nối")
                        }
                    }
                    runCatching { server?.cancelConnection(device) }
                    startAdvertising()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, value: Int) {
            mtu = value.coerceAtLeast(23)
            logger?.invoke("BLE-Server", "MTU đã đổi thành $mtu byte cho ${device.address}")
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
            val enabled = descriptor.uuid == profile.cccdUuid &&
                (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE))
            logger?.invoke("BLE-Server", "Client ghi CCCD ${descriptor.uuid}: enabled=$enabled từ ${device.address}")
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

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            remote = device
            logger?.invoke("BLE-Server", "Client yêu cầu đọc ${characteristic.uuid} từ ${device.address}")
            if (characteristic.uuid == profile.notifyUuid && profile == GattProfile.VIETMAP_H1) {
                val value = com.chisadin.hudwz.protocol.vietmap.VietMapH1ReceiverSession.buildDeviceInfoFrame()
                val chunk = if (offset < value.size) value.copyOfRange(offset, value.size) else byteArrayOf()
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, chunk)
            } else {
                @Suppress("DEPRECATION")
                val curVal = characteristic.value ?: byteArrayOf()
                val chunk = if (offset < curVal.size) curVal.copyOfRange(offset, curVal.size) else byteArrayOf()
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, chunk)
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
            remote = device
            val accepted = characteristic.uuid == profile.writeUuid && !preparedWrite && offset == 0
            val hex = bytesToHex(value)
            logger?.invoke("BLE-Server", "Client ghi ${characteristic.uuid} (${value.size}B): $hex")
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
        val service = BluetoothGattService(profile.serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val tx = BluetoothGattCharacteristic(
            profile.writeUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val rxProperties = if (profile == GattProfile.VIETMAP_H1) {
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ
        } else {
            BluetoothGattCharacteristic.PROPERTY_NOTIFY
        }
        rx = BluetoothGattCharacteristic(
            profile.notifyUuid,
            rxProperties,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).also { characteristic ->
            characteristic.addDescriptor(
                BluetoothGattDescriptor(
                    profile.cccdUuid,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }
        service.addCharacteristic(tx)
        service.addCharacteristic(rx)
        if (!activeServer.addService(service)) {
            disconnect()
            throw IllegalStateException("Không thể thêm dịch vụ GATT: ${profile.id}")
        }
        if (profile == GattProfile.VIETMAP_H1) {
            originalAdapterName = adapter.name
            runCatching { adapter.name = profile.advertisedName }
            logger?.invoke("BLE-Server", "Đổi tên Bluetooth sang '${profile.advertisedName}' (gốc: '$originalAdapterName')")
        }
        logger?.invoke("BLE-Server", "Máy chủ GATT đã mở, đang chờ client kết nối...")
        try {
            ready.await()
            logger?.invoke("BLE-Server", "Client đã bật CCCD, phiên BLE sẵn sàng trao đổi dữ liệu!")
        } catch (error: Throwable) {
            disconnect()
            throw error
        }
    }

    override suspend fun write(bytes: ByteArray) = writeMutex.withLock {
        val activeServer = server ?: return@withLock
        val activeDevice = remote ?: return@withLock
        val characteristic = rx ?: return@withLock
        val chunkSize = (mtu - 3).coerceAtLeast(20)
        var offset = 0
        val fullHex = bytesToHex(bytes)
        logger?.invoke("BLE-Server", "TX notify ${characteristic.uuid} (${bytes.size}B): $fullHex")
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
            if (!started) {
                logger?.invoke("BLE-Server", "Lỗi: Không thể gửi chunk notify ${chunk.size}B")
                break
            }
            runCatching { withTimeout(2_500) { result.await() } }
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
        originalAdapterName?.let { name ->
            runCatching { adapter.name = name }
            originalAdapterName = null
        }
        _status.value = TransportStatus.Idle
        logger?.invoke("BLE-Server", "Đã dừng GATT Server và dừng quảng bá BLE")
    }

    private fun startAdvertising() {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return fail("Quảng bá BLE không khả dụng")
        runCatching { advertiser.stopAdvertising(advertiseCallback) }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(profile.serviceUuid))
            .setIncludeDeviceName(false)
            .build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        logger?.invoke("BLE-Server", "Phát sóng BLE Service: ${profile.serviceUuid} (Tên hiển thị: ${adapter.name})")
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private fun fail(reason: String) {
        _status.value = TransportStatus.Failed(reason)
        if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException(reason))
    }
}
