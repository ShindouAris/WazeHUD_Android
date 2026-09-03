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
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.chisadin.hudwz.bubble.Bubble
import com.chisadin.hudwz.bubble.Signs
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
        SettingsSection("Bong bóng nổi") {
            val context = LocalContext.current
            Toggle("Bong bóng nổi trên app khác (tốc độ + cảnh báo)", settings.bubbleEnabled) { enabled ->
                if (enabled && !Bubble.canShow(context)) {
                    Bubble.requestPermission(context)
                    Toast.makeText(
                        context,
                        "Vui lòng cấp quyền 'Hiển thị trên các ứng dụng khác' cho Waze HUD",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                onChange(settings.copy(bubbleEnabled = enabled))
            }
            if (settings.bubbleEnabled) {
                BubbleLayoutPicker(
                    selectedLayout = settings.bubbleLayout,
                    onSelect = { onChange(settings.copy(bubbleLayout = it)) },
                )
                LabeledSlider(
                    "Cỡ bong bóng",
                    settings.bubbleSize.toFloat(),
                    80f..200f,
                    "${settings.bubbleSize}%",
                ) {
                    onChange(settings.copy(bubbleSize = it.toInt()))
                }
            }
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

private data class BubbleLayoutOption(
    val id: Int,
    val title: String,
    val assetPath: String,
)

@Composable
private fun BubbleLayoutPicker(
    selectedLayout: Int,
    onSelect: (Int) -> Unit,
) {
    val options = remember {
        listOf(
            BubbleLayoutOption(2, "Cơ bản", "settings_demo/bubble_bs.png"),
            BubbleLayoutOption(0, "Nằm ngang", "settings_demo/bubble_h.png"),
            BubbleLayoutOption(1, "Nằm dọc", "settings_demo/bubble_v.png"),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Kiểu hiển thị bong bóng", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            options.forEach { option ->
                val selected = option.id == selectedLayout
                val bitmap = rememberAssetBitmap(option.assetPath)
                Card(
                    onClick = { onSelect(option.id) },
                    modifier = Modifier
                        .width(170.dp)
                        .height(130.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = option.title,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Text(
                            text = option.title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberAssetBitmap(path: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(path) {
        Signs.loadAsset(context, path)?.asImageBitmap()
    }
}

