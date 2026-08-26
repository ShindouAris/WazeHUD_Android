package com.chisadin.hudwz.ui.bluetooth

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    onOpenHud: () -> Unit,
) {
    val context = LocalContext.current
    var pendingScan by remember { mutableStateOf(false) }
    var pendingConnect by remember { mutableStateOf<BluetoothDeviceInfo?>(null) }
    var pendingListen by remember { mutableStateOf<TransportType?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            if (pendingScan) onScan()
            pendingConnect?.let(onConnect)
            pendingListen?.let(onListen)
        }
        pendingScan = false
        pendingConnect = null
        pendingListen = null
    }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) onRefreshPaired()
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
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bluetooth HUD", style = MaterialTheme.typography.headlineLarge)
                Text(connection.message ?: "Choose an HLP data source", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (connection.phase == ConnectionPhase.CONNECTED) {
                Button(onClick = onOpenHud) { Icon(Icons.Rounded.BluetoothConnected, null); Text(" Open HUD") }
            }
        }
        ConnectionSummary(connection)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Receive from Waze Mod", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Start a receiver here, then select this Android device in Waze Mod → HUD Link on a different phone. Bluetooth cannot link two apps on the same phone.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { requestListen(TransportType.BLE) }) {
                        Icon(Icons.Rounded.Bluetooth, contentDescription = null)
                        Text(" Start BLE receiver")
                    }
                    FilledTonalButton(onClick = { requestListen(TransportType.CLASSIC) }) {
                        Text("Start Classic SPP receiver")
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = if (scanning) onStopScan else ::requestScan) {
                if (scanning) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(end = 8.dp))
                else Icon(Icons.Rounded.Refresh, contentDescription = null)
                Text(if (scanning) " Stop" else " Scan BLE")
            }
            OutlinedButton(onClick = {
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }) { Icon(Icons.Rounded.Bluetooth, null); Text(" Enable") }
            if (connection.phase == ConnectionPhase.CONNECTED || connection.phase == ConnectionPhase.CONNECTING || connection.phase == ConnectionPhase.RECONNECTING) {
                OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
            }
            if (settings.preferredDeviceAddress != null) {
                OutlinedButton(onClick = onForget) { Text("Forget saved") }
            }
        }
        Text("Connect as client (advanced)", style = MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices, key = { "${it.transport}:${it.address}" }) { device ->
                DeviceCard(device, connected = connection.device?.address == device.address, onConnect = { requestConnect(device) })
            }
            if (devices.isEmpty()) item {
                Card(Modifier.fillMaxWidth()) {
                    Text("No HLP devices found. Pair Classic SPP devices in Android settings, or start BLE advertising with the fixed HLP service UUID.", Modifier.padding(20.dp))
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
                Text(connection.phase.name, style = MaterialTheme.typography.titleLarge)
                Text(listOfNotNull(connection.device?.name, connection.transport?.name, connection.message).joinToString(" · "))
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
                Text("${device.transport} · ${device.address}${if (device.bonded) " · Paired" else ""}")
                device.rssi?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.SignalCellularAlt, contentDescription = null)
                        Text(" $it dBm")
                    }
                }
            }
            FilledTonalButton(onClick = onConnect, enabled = !connected) {
                Text(if (connected) "Connected" else "Connect")
            }
        }
    }
}
