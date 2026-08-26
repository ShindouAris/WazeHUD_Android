package com.chisadin.hudwz.ui.debug

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chisadin.hudwz.domain.ConnectionState
import com.chisadin.hudwz.domain.ConnectionPhase
import com.chisadin.hudwz.domain.DiagnosticEvent
import com.chisadin.hudwz.domain.TransportType
import com.chisadin.hudwz.domain.TransportMetrics

@Composable
fun DebugScreen(
    connection: ConnectionState,
    metrics: TransportMetrics,
    parsedPacket: String,
    rawPackets: List<String>,
    events: List<DiagnosticEvent>,
    onInject: (String) -> Unit,
    diagnostics: () -> String,
) {
    val context = LocalContext.current
    var packet by remember {
        mutableStateOf("{\"v\":1,\"t\":\"s\",\"nav\":1,\"spd\":85,\"lim\":80,\"over\":1,\"trn\":3,\"dst\":350,\"st\":\"QL1A\",\"eta\":\"09:32\",\"rmin\":24,\"rkm\":18.5,\"ts\":1}")
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Chẩn đoán", style = MaterialTheme.typography.headlineLarge)
                    Text("Theo dõi Bluetooth và HLP/1", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, diagnostics()),
                            "Xuất dữ liệu chẩn đoán",
                        ),
                    )
                }) { androidx.compose.material3.Icon(Icons.Rounded.Share, null); Text(" Xuất") }
            }
        }
        item {
            MetricCard(connection, metrics, parsedPacket)
        }
        item {
            OutlinedTextField(
                value = packet,
                onValueChange = { packet = it },
                label = { Text("Chèn khung HLP cục bộ") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
        }
        item {
            Button(onClick = { onInject(packet) }) { Text("Phân tích khung") }
        }
        item {
            Text("Hoạt động gần đây", style = MaterialTheme.typography.titleLarge)
        }
        items(events.takeLast(100).reversed(), key = { "${it.elapsedMs}:${it.message}" }) { event ->
            Text("${event.category} · ${event.message}", style = MaterialTheme.typography.bodyLarge)
        }
        if (rawPackets.isNotEmpty()) {
            item { Text("Gói tin thô", style = MaterialTheme.typography.titleLarge) }
            items(rawPackets.takeLast(30).reversed()) { raw ->
                Card(Modifier.fillMaxWidth()) { Text(raw, Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun MetricCard(connection: ConnectionState, metrics: TransportMetrics, parsed: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Bluetooth: ${phaseLabel(connection.phase)}", style = MaterialTheme.typography.titleLarge)
            Text("Thiết bị: ${connection.device?.name ?: "Không có"}")
            Text("Kiểu kết nối: ${connection.transport?.let(::transportLabel) ?: "Không có"}")
            Text("MTU: ${metrics.mtu ?: "Không có"}")
            Text("Gói tin: ${metrics.packetCount} · ${"%.1f".format(metrics.packetRate)}/giây")
            Text("Lỗi phân tích: ${metrics.parserErrors}")
            Text("Dữ liệu đã phân tích: $parsed")
        }
    }
}

private fun phaseLabel(phase: ConnectionPhase): String = when (phase) {
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
}
