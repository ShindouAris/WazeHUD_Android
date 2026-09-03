package com.chisadin.hudwz.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.chisadin.hudwz.domain.HudOrientation
import com.chisadin.hudwz.domain.HudSettings
import com.chisadin.hudwz.domain.HudThemeMode
import com.chisadin.hudwz.domain.ReceiverSource
import com.chisadin.hudwz.domain.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

class SettingsRepository(private val context: Context) {
    val settings: Flow<HudSettings> = context.hudDataStore.data
        .catch { error -> if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error }
        .map { values ->
            HudSettings(
                isReceiverMode = values[Keys.isReceiverMode] ?: true,
                preferredTransport = values[Keys.transport].enumOr(TransportType.AUTO),
                autoReconnect = values[Keys.autoReconnect] ?: true,
                preferredDeviceAddress = values[Keys.deviceAddress],
                preferredDeviceName = values[Keys.deviceName],
                connectionTimeoutSeconds = (values[Keys.timeout] ?: 15).coerceIn(5, 60),
                mirrorMode = values[Keys.mirror] ?: false,
                orientation = values[Keys.orientation].enumOr(HudOrientation.SENSOR),
                brightness = (values[Keys.brightness] ?: 1f).coerceIn(.1f, 1f),
                keepScreenAwake = values[Keys.keepAwake] ?: true,
                immersiveMode = values[Keys.immersive] ?: true,
                preventAccidentalTouches = values[Keys.touchLock] ?: false,
                themeMode = values[Keys.theme].enumOr(HudThemeMode.NIGHT),
                fontScale = (values[Keys.fontScale] ?: 1f).coerceIn(.75f, 1.5f),
                showRawPackets = values[Keys.rawPackets] ?: false,
                bluetoothLogs = values[Keys.bluetoothLogs] ?: false,
                protocolLogs = values[Keys.protocolLogs] ?: false,
                wsPort = (values[Keys.wsPort] ?: 8765).coerceIn(1024, 65535),
                wsPath = values[Keys.wsPath] ?: "/hlp",
                bubbleEnabled = values[Keys.bubbleEnabled] ?: false,
                bubbleLayout = values[Keys.bubbleLayout] ?: 0,
                bubbleSize = (values[Keys.bubbleSize] ?: 100).coerceIn(80, 200),
                receiverSource = values[Keys.receiverSource].enumOr(ReceiverSource.WAZE_MOD),
            )
        }

    suspend fun update(transform: (HudSettings) -> HudSettings) {
        context.hudDataStore.edit { values ->
            val current = HudSettings(
                isReceiverMode = values[Keys.isReceiverMode] ?: true,
                preferredTransport = values[Keys.transport].enumOr(TransportType.AUTO),
                autoReconnect = values[Keys.autoReconnect] ?: true,
                preferredDeviceAddress = values[Keys.deviceAddress],
                preferredDeviceName = values[Keys.deviceName],
                connectionTimeoutSeconds = values[Keys.timeout] ?: 15,
                mirrorMode = values[Keys.mirror] ?: false,
                orientation = values[Keys.orientation].enumOr(HudOrientation.SENSOR),
                brightness = values[Keys.brightness] ?: 1f,
                keepScreenAwake = values[Keys.keepAwake] ?: true,
                immersiveMode = values[Keys.immersive] ?: true,
                preventAccidentalTouches = values[Keys.touchLock] ?: false,
                themeMode = values[Keys.theme].enumOr(HudThemeMode.NIGHT),
                fontScale = values[Keys.fontScale] ?: 1f,
                showRawPackets = values[Keys.rawPackets] ?: false,
                bluetoothLogs = values[Keys.bluetoothLogs] ?: false,
                protocolLogs = values[Keys.protocolLogs] ?: false,
                wsPort = values[Keys.wsPort] ?: 8765,
                wsPath = values[Keys.wsPath] ?: "/hlp",
                bubbleEnabled = values[Keys.bubbleEnabled] ?: false,
                bubbleLayout = values[Keys.bubbleLayout] ?: 0,
                bubbleSize = values[Keys.bubbleSize] ?: 100,
                receiverSource = values[Keys.receiverSource].enumOr(ReceiverSource.WAZE_MOD),
            )
            val next = transform(current)
            values[Keys.isReceiverMode] = next.isReceiverMode
            values[Keys.receiverSource] = next.receiverSource.name
            values[Keys.transport] = next.preferredTransport.name
            values[Keys.autoReconnect] = next.autoReconnect
            next.preferredDeviceAddress?.let { values[Keys.deviceAddress] = it }
                ?: values.remove(Keys.deviceAddress)
            next.preferredDeviceName?.let { values[Keys.deviceName] = it }
                ?: values.remove(Keys.deviceName)
            values[Keys.timeout] = next.connectionTimeoutSeconds.coerceIn(5, 60)
            values[Keys.mirror] = next.mirrorMode
            values[Keys.orientation] = next.orientation.name
            values[Keys.brightness] = next.brightness.coerceIn(.1f, 1f)
            values[Keys.keepAwake] = next.keepScreenAwake
            values[Keys.immersive] = next.immersiveMode
            values[Keys.touchLock] = next.preventAccidentalTouches
            values[Keys.theme] = next.themeMode.name
            values[Keys.fontScale] = next.fontScale.coerceIn(.75f, 1.5f)
            values[Keys.rawPackets] = next.showRawPackets
            values[Keys.bluetoothLogs] = next.bluetoothLogs
            values[Keys.protocolLogs] = next.protocolLogs
            values[Keys.wsPort] = next.wsPort.coerceIn(1024, 65535)
            values[Keys.wsPath] = next.wsPath.ifBlank { "/hlp" }
            values[Keys.bubbleEnabled] = next.bubbleEnabled
            values[Keys.bubbleLayout] = next.bubbleLayout
            values[Keys.bubbleSize] = next.bubbleSize.coerceIn(80, 200)
        }
    }

    private inline fun <reified T : Enum<T>> String?.enumOr(default: T): T =
        this?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default

    private object Keys {
        val isReceiverMode = booleanPreferencesKey("is_receiver_mode")
        val receiverSource = stringPreferencesKey("receiver_source")
        val transport = stringPreferencesKey("transport")
        val autoReconnect = booleanPreferencesKey("auto_reconnect")
        val deviceAddress = stringPreferencesKey("device_address")
        val deviceName = stringPreferencesKey("device_name")
        val timeout = intPreferencesKey("connection_timeout")
        val mirror = booleanPreferencesKey("mirror")
        val orientation = stringPreferencesKey("orientation")
        val brightness = floatPreferencesKey("brightness")
        val keepAwake = booleanPreferencesKey("keep_awake")
        val immersive = booleanPreferencesKey("immersive")
        val touchLock = booleanPreferencesKey("touch_lock")
        val theme = stringPreferencesKey("theme")
        val fontScale = floatPreferencesKey("font_scale")
        val rawPackets = booleanPreferencesKey("raw_packets")
        val bluetoothLogs = booleanPreferencesKey("bluetooth_logs")
        val protocolLogs = booleanPreferencesKey("protocol_logs")
        val wsPort = intPreferencesKey("ws_port")
        val wsPath = stringPreferencesKey("ws_path")
        val bubbleEnabled = booleanPreferencesKey("bubble_enabled")
        val bubbleLayout = intPreferencesKey("bubble_layout")
        val bubbleSize = intPreferencesKey("bubble_size")
    }
}
