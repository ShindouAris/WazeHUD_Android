package com.chisadin.hudwz.ui.bluetooth

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chisadin.hudwz.bluetooth.BluetoothPermissionPolicy
import com.chisadin.hudwz.bluetooth.LanAddressHelper
import com.chisadin.hudwz.domain.BluetoothDeviceInfo
import com.chisadin.hudwz.domain.ConnectionPhase
import com.chisadin.hudwz.domain.ConnectionState
import com.chisadin.hudwz.domain.HudSettings
import com.chisadin.hudwz.domain.TransportType

@Composable
fun ConnectionScreen(
    devices: List<BluetoothDeviceInfo>,
    connection: ConnectionState,
    settings: HudSettings,
    scanning: Boolean,
    onRefreshPaired: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (BluetoothDeviceInfo) -> Unit,
    onListen: (TransportType) -> Unit,
    onListenWifi: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    onOpenHud: () -> Unit,
) {
    val context = LocalContext.current
    var pendingScan by remember { mutableStateOf(false) }
    var pendingConnect by remember { mutableStateOf<BluetoothDeviceInfo?>(null) }
    var pendingListen by remember { mutableStateOf<TransportType?>(null) }
    var pendingEnableBluetooth by remember { mutableStateOf(false) }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) onRefreshPaired()
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            if (pendingScan) onScan()
            pendingConnect?.let(onConnect)
            pendingListen?.let(onListen)
            if (pendingEnableBluetooth) enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
        pendingScan = false
        pendingConnect = null
        pendingListen = null
        pendingEnableBluetooth = false
    }

    fun requestScan() {
        val permissions = BluetoothPermissionPolicy.scanPermissions()
        if (BluetoothPermissionPolicy.has(context, permissions)) onScan() else {
            pendingScan = true
            permissionLauncher.launch(permissions)
        }
    }

    fun requestConnect(device: BluetoothDeviceInfo) {
        val permissions = buildList {
            addAll(BluetoothPermissionPolicy.connectionPermissions())
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        if (BluetoothPermissionPolicy.has(context, permissions)) onConnect(device) else {
            pendingConnect = device
            permissionLauncher.launch(permissions)
        }
    }

    fun requestListen(type: TransportType) {
        val permissions = buildList {
            addAll(BluetoothPermissionPolicy.receiverPermissions(type))
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        if (BluetoothPermissionPolicy.has(context, permissions)) onListen(type) else {
            pendingListen = type
            permissionLauncher.launch(permissions)
        }
    }

    LaunchedEffect(Unit) { onRefreshPaired() }
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Kết nối Bluetooth HUD", style = MaterialTheme.typography.headlineLarge)
                    Text(connection.message ?: "Chọn nguồn dữ liệu HLP", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (connection.phase == ConnectionPhase.CONNECTED) {
                    Button(onClick = onOpenHud) { Icon(Icons.Rounded.BluetoothConnected, null); Text(" Mở HUD") }
                }
            }
        }
        item {
            ConnectionSummary(connection)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Nhận dữ liệu từ Waze Mod", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Bật bộ nhận tại đây, sau đó chọn thiết bị Android này trong Waze Mod → HUD Link trên điện thoại khác. Bluetooth không thể liên kết hai ứng dụng trên cùng một điện thoại.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        val isListeningBle = (connection.phase == ConnectionPhase.CONNECTING || connection.phase == ConnectionPhase.CONNECTED || connection.phase == ConnectionPhase.RECONNECTING) &&
                            connection.transport == TransportType.BLE && settings.isReceiverMode
                        val isListeningClassic = (connection.phase == ConnectionPhase.CONNECTING || connection.phase == ConnectionPhase.CONNECTED || connection.phase == ConnectionPhase.RECONNECTING) &&
                            connection.transport == TransportType.CLASSIC && settings.isReceiverMode

                        Button(
                            onClick = {
                                if (isListeningBle) onDisconnect() else requestListen(TransportType.BLE)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(if (isListeningBle) Icons.Rounded.BluetoothConnected else Icons.Rounded.Bluetooth, contentDescription = null)
                            Text(if (isListeningBle) " Bộ nhận BLE đang chạy (Bấm để dừng)" else " Bật bộ nhận BLE")
                        }
                        FilledTonalButton(
                            onClick = {
                                if (isListeningClassic) onDisconnect() else requestListen(TransportType.CLASSIC)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(if (isListeningClassic) Icons.Rounded.BluetoothConnected else Icons.Rounded.Bluetooth, contentDescription = null)
                            Text(if (isListeningClassic) " Bộ nhận Classic SPP đang chạy (Bấm để dừng)" else " Bật bộ nhận Classic SPP")
                        }
                    }
                }
            }
        }
        item {
            val isListeningWifi = (connection.phase == ConnectionPhase.CONNECTING ||
                connection.phase == ConnectionPhase.CONNECTED ||
                connection.phase == ConnectionPhase.RECONNECTING) &&
                connection.transport == TransportType.WIFI_WEBSOCKET && settings.isReceiverMode
            val lanIp = remember(isListeningWifi) { LanAddressHelper.getLanIp(context) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Wi-Fi WebSocket", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Kết nối WazeMod thông qua Wi-Fi",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (lanIp != null) {
                        val port = settings.wsPort
                        val path = settings.wsPath.ifBlank { "/hlp" }
                        Text(
                            "URL: ws://$lanIp:$port$path \n Hoặc: ws://localhost:$port$path",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Button(
                        onClick = { if (isListeningWifi) onDisconnect() else onListenWifi() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(if (isListeningWifi) Icons.Rounded.Wifi else Icons.Rounded.WifiOff, contentDescription = null)
                        Text(if (isListeningWifi) " Wi-Fi WebSocket đang chạy (Bấm để dừng)" else " Bật Wi-Fi WebSocket Server")
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = if (scanning) onStopScan else ::requestScan) {
                    if (scanning) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(end = 8.dp))
                    else Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Text(if (scanning) " Dừng" else " Quét BLE")
                }
                OutlinedButton(onClick = {
                    val connectPerms = BluetoothPermissionPolicy.connectionPermissions()
                    if (BluetoothPermissionPolicy.has(context, connectPerms)) {
                        enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    } else {
                        pendingEnableBluetooth = true
                        permissionLauncher.launch(connectPerms)
                    }
                }) { Icon(Icons.Rounded.Bluetooth, null); Text(" Bật Bluetooth") }
                if (connection.phase == ConnectionPhase.CONNECTED || connection.phase == ConnectionPhase.CONNECTING || connection.phase == ConnectionPhase.RECONNECTING) {
                    OutlinedButton(onClick = onDisconnect) { Text("Ngắt kết nối") }
                }
                if (settings.preferredDeviceAddress != null || !settings.isReceiverMode) {
                    OutlinedButton(onClick = onForget) { Text("Quên thiết bị đã lưu") }
                }
            }
        }
        item {
            Text("Kết nối ở chế độ máy khách (nâng cao)", style = MaterialTheme.typography.titleLarge)
        }
        items(devices.distinctBy { "${it.transport}:${it.address}" }, key = { "${it.transport}:${it.address}" }) { device ->
            DeviceCard(device, connected = connection.device?.address == device.address, onConnect = { requestConnect(device) })
        }
        if (devices.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text("Không tìm thấy thiết bị HLP. Hãy ghép đôi thiết bị Classic SPP trong cài đặt Android hoặc bật quảng bá BLE với UUID dịch vụ HLP cố định.", Modifier.padding(20.dp))
                }
            }
        }
    }
}

@Composable
private fun ConnectionSummary(connection: ConnectionState) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (connection.phase == ConnectionPhase.CONNECTED) Icons.Rounded.BluetoothConnected else Icons.Rounded.Link,
                contentDescription = null,
                tint = if (connection.phase == ConnectionPhase.CONNECTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(connectionPhaseLabel(connection.phase), style = MaterialTheme.typography.titleLarge)
                Text(listOfNotNull(connection.device?.name, connection.transport?.let(::transportLabel), connection.message).joinToString(" · "))
            }
        }
    }
}

@Composable
private fun DeviceCard(device: BluetoothDeviceInfo, connected: Boolean, onConnect: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (connected) Icons.Rounded.BluetoothConnected else Icons.Rounded.Bluetooth, contentDescription = null)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(device.name, style = MaterialTheme.typography.titleLarge)
                Text("${transportLabel(device.transport)} · ${device.address}${if (device.bonded) " · Đã ghép đôi" else ""}")
                device.rssi?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.SignalCellularAlt, contentDescription = null)
                        Text(" $it dBm")
                    }
                }
            }
            FilledTonalButton(onClick = onConnect, enabled = !connected) {
                Text(if (connected) "Đã kết nối" else "Kết nối")
            }
        }
    }
}

private fun connectionPhaseLabel(phase: ConnectionPhase): String = when (phase) {
    ConnectionPhase.IDLE -> "Chưa kết nối"
    ConnectionPhase.SCANNING -> "Đang quét"
    ConnectionPhase.CONNECTING -> "Đang kết nối"
    ConnectionPhase.CONNECTED -> "Đã kết nối"
    ConnectionPhase.RECONNECTING -> "Đang kết nối lại"
    ConnectionPhase.DISCONNECTING -> "Đang ngắt kết nối"
    ConnectionPhase.ERROR -> "Lỗi"
}

private fun transportLabel(type: TransportType): String = when (type) {
    TransportType.AUTO -> "Tự động"
    TransportType.BLE -> "BLE"
    TransportType.CLASSIC -> "Classic SPP"
    TransportType.WIFI_WEBSOCKET -> "Wi-Fi WebSocket"
}
