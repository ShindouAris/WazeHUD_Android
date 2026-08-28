package com.chisadin.hudwz.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chisadin.hudwz.domain.HudOrientation
import com.chisadin.hudwz.domain.HudSettings
import com.chisadin.hudwz.domain.HudThemeMode
import com.chisadin.hudwz.domain.TransportType

@Composable
fun SettingsScreen(settings: HudSettings, onChange: (HudSettings) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Cài đặt", style = MaterialTheme.typography.headlineLarge)
        SettingsSection("Bluetooth") {
            ChoiceRow("Kiểu kết nối ưu tiên", TransportType.entries, settings.preferredTransport) {
                onChange(settings.copy(preferredTransport = it))
            }
            Toggle("Tự động kết nối lại", settings.autoReconnect) { onChange(settings.copy(autoReconnect = it)) }
            LabeledSlider("Thời gian chờ kết nối", settings.connectionTimeoutSeconds.toFloat(), 5f..60f, "${settings.connectionTimeoutSeconds} giây") {
                onChange(settings.copy(connectionTimeoutSeconds = it.toInt()))
            }
        }
        SettingsSection("HUD") {
            Toggle("Lật gương theo chiều ngang", settings.mirrorMode) { onChange(settings.copy(mirrorMode = it)) }
            ChoiceRow("Hướng màn hình", HudOrientation.entries, settings.orientation) { onChange(settings.copy(orientation = it)) }
            LabeledSlider("Độ sáng", settings.brightness, .1f..1f, "${(settings.brightness * 100).toInt()}%") {
                onChange(settings.copy(brightness = it))
            }
            Toggle("Luôn bật màn hình", settings.keepScreenAwake) { onChange(settings.copy(keepScreenAwake = it)) }
            Toggle("Toàn màn hình", settings.immersiveMode) { onChange(settings.copy(immersiveMode = it)) }
            Toggle("Ngăn chạm nhầm", settings.preventAccidentalTouches) { onChange(settings.copy(preventAccidentalTouches = it)) }
        }
        SettingsSection("Giao diện") {
            ChoiceRow("Chủ đề", HudThemeMode.entries, settings.themeMode) { onChange(settings.copy(themeMode = it)) }
            LabeledSlider("Cỡ chữ", settings.fontScale, .75f..1.5f, "${(settings.fontScale * 100).toInt()}%") {
                onChange(settings.copy(fontScale = it))
            }
        }
        SettingsSection("Nhà phát triển") {
            Toggle("Lưu lịch sử gói tin thô", settings.showRawPackets) { onChange(settings.copy(showRawPackets = it)) }
            Toggle("Nhật ký Bluetooth", settings.bluetoothLogs) { onChange(settings.copy(bluetoothLogs = it)) }
            Toggle("Nhật ký giao thức", settings.protocolLogs) { onChange(settings.copy(protocolLogs = it)) }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        content()
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f)); Text(display) }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun <T : Enum<T>> ChoiceRow(label: String, values: Iterable<T>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            values.forEach { value ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(enumLabel(value)) })
            }
        }
    }
}

private fun enumLabel(value: Enum<*>): String = when (value) {
    TransportType.AUTO -> "Tự động"
    TransportType.BLE -> "BLE"
    TransportType.CLASSIC -> "Classic SPP"
    TransportType.WIFI_WEBSOCKET -> "Wi-Fi WebSocket"
    HudOrientation.SENSOR -> "Theo cảm biến"
    HudOrientation.LANDSCAPE -> "Ngang"
    HudOrientation.PORTRAIT -> "Dọc"
    HudThemeMode.SYSTEM -> "Hệ thống"
    HudThemeMode.DAY -> "Ban ngày"
    HudThemeMode.NIGHT -> "Ban đêm"
    else -> value.name
}
