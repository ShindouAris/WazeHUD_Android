package com.chisadin.hudwz.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.chisadin.hudwz.domain.BluetoothDeviceInfo
import com.chisadin.hudwz.domain.TransportType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

class BluetoothDeviceScanner(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
) {
    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<BluetoothDeviceInfo> {
        val activeAdapter = adapter
        if (activeAdapter == null || !BluetoothPermissionPolicy.has(context, BluetoothPermissionPolicy.connectionPermissions())) {
            return emptyList()
        }
        return runCatching {
            activeAdapter.bondedDevices.map { device ->
                BluetoothDeviceInfo(
                    address = device.address,
                    name = device.name ?: "Thiết bị đã ghép đôi",
                    transport = if (device.type == BluetoothDevice.DEVICE_TYPE_LE) TransportType.BLE else TransportType.CLASSIC,
                    bonded = true,
                )
            }.sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    @SuppressLint("MissingPermission")
    fun scanBle(timeoutMillis: Long = 12_000): Flow<List<BluetoothDeviceInfo>> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !BluetoothPermissionPolicy.has(context, BluetoothPermissionPolicy.scanPermissions())) {
            close(IllegalStateException("Thiếu quyền quét Bluetooth hoặc bộ điều hợp không khả dụng"))
            return@callbackFlow
        }
        val found = linkedMapOf<String, BluetoothDeviceInfo>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val info = BluetoothDeviceInfo(
                    address = device.address,
                    name = result.scanRecord?.deviceName ?: device.name ?: "Thiết bị BLE",
                    transport = TransportType.BLE,
                    bonded = device.bondState == BluetoothDevice.BOND_BONDED,
                    rssi = result.rssi,
                )
                found[info.address] = info
                trySend(found.values.sortedByDescending { it.rssi ?: Int.MIN_VALUE })
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Quét BLE thất bại: $errorCode"))
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleTransport.SERVICE_UUID)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(filters, settings, callback) }
            .onFailure { close(it) }
        val timeout = launch {
            delay(timeoutMillis)
            close()
        }
        awaitClose {
            timeout.cancel()
            runCatching { scanner.stopScan(callback) }
        }
    }
}
