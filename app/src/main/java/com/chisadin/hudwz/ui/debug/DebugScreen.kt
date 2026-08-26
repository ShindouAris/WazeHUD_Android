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
import com.chisadin.hudwz.domain.DiagnosticEvent
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
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Diagnostics", style = MaterialTheme.typography.headlineLarge)
                Text("Bluetooth and HLP/1 observability", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = {
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, diagnostics()),
                        "Export diagnostics",
                    ),
                )
            }) { androidx.compose.material3.Icon(Icons.Rounded.Share, null); Text(" Export") }
        }
        MetricCard(connection, metrics, parsedPacket)
        OutlinedTextField(
            value = packet,
            onValueChange = { packet = it },
            label = { Text("Inject local HLP frame") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Button(onClick = { onInject(packet) }) { Text("Parse frame") }
        Text("Recent activity", style = MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(events.takeLast(100).reversed(), key = { "${it.elapsedMs}:${it.message}" }) { event ->
                Text("${event.category} · ${event.message}", style = MaterialTheme.typography.bodyLarge)
            }
            if (rawPackets.isNotEmpty()) item { Text("Raw packets", style = MaterialTheme.typography.titleLarge) }
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
            Text("Bluetooth: ${connection.phase}", style = MaterialTheme.typography.titleLarge)
            Text("Device: ${connection.device?.name ?: "None"}")
            Text("Transport: ${connection.transport ?: "None"}")
            Text("MTU: ${metrics.mtu ?: "N/A"}")
            Text("Packets: ${metrics.packetCount} · ${"%.1f".format(metrics.packetRate)}/s")
            Text("Parser errors: ${metrics.parserErrors}")
            Text("Parsed: $parsed")
        }
    }
}
