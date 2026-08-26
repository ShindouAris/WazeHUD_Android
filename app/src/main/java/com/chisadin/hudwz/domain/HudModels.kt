package com.chisadin.hudwz.domain

import kotlinx.serialization.Serializable

@Serializable
enum class TransportType { AUTO, BLE, CLASSIC }

enum class ConnectionPhase {
    IDLE, SCANNING, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTING, ERROR
}

@Serializable
enum class TurnType(val code: Int) {
    NONE(0), CONTINUE(1), LEFT(2), RIGHT(3), SLIGHT_LEFT(4), SLIGHT_RIGHT(5),
    SHARP_LEFT(6), SHARP_RIGHT(7), U_TURN(8), U_TURN_RIGHT(9), ROUNDABOUT(10),
    ROUNDABOUT_LEFT(11), ROUNDABOUT_RIGHT(12), KEEP_LEFT(13), KEEP_RIGHT(14),
    EXIT_LEFT(15), EXIT_RIGHT(16), ARRIVE(17), FERRY(18),
    ROUNDABOUT_STRAIGHT(19), ROUNDABOUT_U_TURN(20);

    companion object {
        fun fromCode(code: Int): TurnType = entries.firstOrNull { it.code == code } ?: NONE
    }
}

@Serializable
data class LaneGuidance(
    val directionsMask: Int,
    val selectedMask: Int,
)

@Serializable
data class HudAlert(
    val type: Int,
    val distanceMeters: Int,
    val value: Int? = null,
    val severity: Int? = null,
    val delayMinutes: Int? = null,
)

@Serializable
data class HudState(
    val navigating: Boolean = false,
    val speed: Int? = null,
    val speedLimit: Int? = null,
    val overspeed: Boolean = false,
    val distanceMeters: Int? = null,
    val turn: TurnType = TurnType.NONE,
    val nextTurn: TurnType = TurnType.NONE,
    val roundaboutExit: Int? = null,
    val lanes: List<LaneGuidance> = emptyList(),
    val street: String? = null,
    val nextStreet: String? = null,
    val eta: String? = null,
    val remainingMinutes: Int? = null,
    val remainingMeters: Int? = null,
    val remainingKm: Double? = null,
    val gpsAvailable: Boolean = true,
    val noPassingZone: Boolean = false,
    val zoneRemainingMeters: Int? = null,
    val zoneRecommendedSpeed: Int? = null,
    val zoneProgress: Int? = null,
    val alerts: List<HudAlert> = emptyList(),
    val connected: Boolean = false,
    val sessionId: Long? = null,
    val sourceTimestampMs: Long = 0,
    val receivedAtElapsedMs: Long = 0,
)

@Serializable
enum class HudWidgetType {
    SPEED, SPEED_LIMIT, TURN, NEXT_TURN, DISTANCE, STREET, NEXT_STREET,
    ETA, REMAINING, GPS, CONNECTION, ALERTS, LANES
}

val HudWidgetType.locksAspectRatio: Boolean
    get() = this == HudWidgetType.SPEED ||
        this == HudWidgetType.SPEED_LIMIT ||
        this == HudWidgetType.TURN ||
        this == HudWidgetType.NEXT_TURN

enum class HudLayerMove { BRING_TO_FRONT, MOVE_UP, MOVE_DOWN, SEND_TO_BACK }

fun reorderHudElements(
    elements: List<HudElementConfig>,
    elementId: String,
    move: HudLayerMove,
): List<HudElementConfig> {
    val from = elements.indexOfFirst { it.id == elementId }
    if (from < 0 || elements.size < 2) return elements
    val to = when (move) {
        HudLayerMove.BRING_TO_FRONT -> elements.lastIndex
        HudLayerMove.MOVE_UP -> (from + 1).coerceAtMost(elements.lastIndex)
        HudLayerMove.MOVE_DOWN -> (from - 1).coerceAtLeast(0)
        HudLayerMove.SEND_TO_BACK -> 0
    }
    if (from == to) return elements
    return elements.toMutableList().apply {
        val element = removeAt(from)
        add(to, element)
    }
}

@Serializable
enum class HudFontWeight { NORMAL, BOLD, BLACK }

@Serializable
enum class HudTextAlignment { START, CENTER, END }

@Serializable
enum class HudElementOrientation { AUTO, HORIZONTAL, VERTICAL }

@Serializable
data class HudElementConfig(
    val id: String,
    val type: HudWidgetType,
    val x: Float,
    val y: Float,
    val widthDp: Float,
    val heightDp: Float,
    val scale: Float = 1f,
    val visible: Boolean = true,
    val opacity: Float = 1f,
    val fontSizeSp: Float = 28f,
    val fontWeight: HudFontWeight = HudFontWeight.BOLD,
    val textAlignment: HudTextAlignment = HudTextAlignment.CENTER,
    val iconSizeDp: Float = 72f,
    val spacingDp: Float = 8f,
    val orientation: HudElementOrientation = HudElementOrientation.AUTO,
)

fun defaultHudElement(
    type: HudWidgetType,
    id: String,
    x: Float = .5f,
    y: Float = .5f,
): HudElementConfig {
    val base = HudElementConfig(
        id = id,
        type = type,
        x = x.coerceAtLeast(0f),
        y = y.coerceAtLeast(0f),
        widthDp = 140f,
        heightDp = 72f,
    )
    return when (type) {
        HudWidgetType.SPEED -> base.copy(widthDp = 130f, heightDp = 130f, fontSizeSp = 44f)
        HudWidgetType.SPEED_LIMIT -> base.copy(widthDp = 120f, heightDp = 120f, fontSizeSp = 46f, iconSizeDp = 108f)
        HudWidgetType.TURN -> base.copy(widthDp = 120f, heightDp = 120f, iconSizeDp = 108f)
        HudWidgetType.NEXT_TURN -> base.copy(widthDp = 76f, heightDp = 76f, iconSizeDp = 68f)
        HudWidgetType.DISTANCE -> base.copy(widthDp = 140f, heightDp = 44f, fontSizeSp = 27f)
        HudWidgetType.STREET, HudWidgetType.NEXT_STREET -> base.copy(widthDp = 250f, heightDp = 42f, fontSizeSp = 20f)
        HudWidgetType.ETA -> base.copy(widthDp = 120f, heightDp = 40f, fontSizeSp = 20f)
        HudWidgetType.REMAINING -> base.copy(widthDp = 260f, heightDp = 42f, fontSizeSp = 17f)
        HudWidgetType.GPS, HudWidgetType.CONNECTION -> base.copy(widthDp = 120f, heightDp = 38f, iconSizeDp = 21f, fontSizeSp = 12f)
        HudWidgetType.ALERTS -> base.copy(widthDp = 92f, heightDp = 196f, iconSizeDp = 48f, orientation = HudElementOrientation.VERTICAL)
        HudWidgetType.LANES -> base.copy(widthDp = 250f, heightDp = 54f, iconSizeDp = 24f)
    }
}

@Serializable
data class HudProfile(
    val id: String,
    val name: String,
    val hudScale: Float = 1f,
    val layoutVersion: Int = 1,
    val elements: List<HudElementConfig>,
) {
    companion object {
        fun defaultProfile() = HudProfile(
            id = "default",
            name = "Default",
            layoutVersion = 2,
            elements = listOf(
                HudElementConfig("lanes", HudWidgetType.LANES, 22f, 14f, 250f, 54f),
                HudElementConfig("turn", HudWidgetType.TURN, 42f, 76f, 120f, 120f, iconSizeDp = 108f),
                HudElementConfig("distance", HudWidgetType.DISTANCE, 32f, 204f, 150f, 44f, fontSizeSp = 28f),
                HudElementConfig("limit", HudWidgetType.SPEED_LIMIT, 292f, 48f, 190f, 190f, fontSizeSp = 72f),
                HudElementConfig("speed", HudWidgetType.SPEED, 500f, 92f, 130f, 130f, fontSizeSp = 44f),
                HudElementConfig("street", HudWidgetType.STREET, 270f, 254f, 330f, 42f, fontSizeSp = 21f),
                HudElementConfig("remaining", HudWidgetType.REMAINING, 290f, 304f, 290f, 40f, fontSizeSp = 17f),
                HudElementConfig("alerts", HudWidgetType.ALERTS, 690f, 48f, 92f, 210f, iconSizeDp = 48f, orientation = HudElementOrientation.VERTICAL),
                HudElementConfig("connection", HudWidgetType.CONNECTION, 650f, 306f, 132f, 38f, fontSizeSp = 12f),
            ),
        )

        fun minimalProfile() = HudProfile(
            id = "minimal",
            name = "Minimal",
            layoutVersion = 2,
            elements = listOf(
                HudElementConfig("turn", HudWidgetType.TURN, 42f, 86f, 130f, 130f, iconSizeDp = 117f),
                HudElementConfig("distance", HudWidgetType.DISTANCE, 36f, 224f, 150f, 44f, fontSizeSp = 28f),
                HudElementConfig("speed", HudWidgetType.SPEED, 330f, 66f, 210f, 210f, fontSizeSp = 72f),
                HudElementConfig("limit", HudWidgetType.SPEED_LIMIT, 570f, 105f, 126f, 126f, fontSizeSp = 48f),
                HudElementConfig("street", HudWidgetType.STREET, 270f, 292f, 320f, 42f, fontSizeSp = 21f),
            ),
        )

        fun largeSpeedProfile() = HudProfile(
            id = "large-speed",
            name = "Large Speed",
            layoutVersion = 2,
            elements = listOf(
                HudElementConfig("speed", HudWidgetType.SPEED, 220f, 18f, 320f, 320f, fontSizeSp = 112f),
                HudElementConfig("limit", HudWidgetType.SPEED_LIMIT, 570f, 78f, 150f, 150f, fontSizeSp = 56f),
                HudElementConfig("connection", HudWidgetType.CONNECTION, 638f, 306f, 144f, 38f, fontSizeSp = 12f),
            ),
        )
    }
}

fun emptyHudProfile(id: String, name: String): HudProfile = HudProfile(
    id = id,
    name = name,
    layoutVersion = 2,
    elements = emptyList(),
)

const val HUD_REFERENCE_WIDTH_DP = 800f
const val HUD_REFERENCE_HEIGHT_DP = 360f

fun migrateHudProfile(profile: HudProfile): HudProfile {
    if (profile.layoutVersion >= 2) return profile
    return profile.copy(
        layoutVersion = 2,
        elements = profile.elements.map { element ->
            val height = if (element.type.locksAspectRatio) element.widthDp else element.heightDp
            element.copy(
                x = element.x.coerceIn(0f, 1f) * (HUD_REFERENCE_WIDTH_DP - element.widthDp * element.scale * profile.hudScale)
                    .coerceAtLeast(0f),
                y = element.y.coerceIn(0f, 1f) * (HUD_REFERENCE_HEIGHT_DP - height * element.scale * profile.hudScale)
                    .coerceAtLeast(0f),
            )
        },
    )
}

@Serializable
enum class HudOrientation { SENSOR, LANDSCAPE, PORTRAIT }

@Serializable
enum class HudThemeMode { SYSTEM, DAY, NIGHT }

@Serializable
data class HudSettings(
    val preferredTransport: TransportType = TransportType.AUTO,
    val autoReconnect: Boolean = true,
    val preferredDeviceAddress: String? = null,
    val preferredDeviceName: String? = null,
    val connectionTimeoutSeconds: Int = 15,
    val mirrorMode: Boolean = false,
    val orientation: HudOrientation = HudOrientation.LANDSCAPE,
    val brightness: Float = 1f,
    val keepScreenAwake: Boolean = true,
    val immersiveMode: Boolean = true,
    val preventAccidentalTouches: Boolean = false,
    val themeMode: HudThemeMode = HudThemeMode.NIGHT,
    val fontScale: Float = 1f,
    val showRawPackets: Boolean = false,
    val bluetoothLogs: Boolean = false,
    val protocolLogs: Boolean = false,
)

data class BluetoothDeviceInfo(
    val address: String,
    val name: String,
    val transport: TransportType,
    val bonded: Boolean,
    val rssi: Int? = null,
)

data class ConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val device: BluetoothDeviceInfo? = null,
    val transport: TransportType? = null,
    val message: String? = null,
    val retryAttempt: Int = 0,
)

data class TransportMetrics(
    val mtu: Int? = null,
    val packetCount: Long = 0,
    val packetRate: Double = 0.0,
    val parserErrors: Long = 0,
    val lastPacketElapsedMs: Long? = null,
    val lastRssi: Int? = null,
)

data class DiagnosticEvent(
    val elapsedMs: Long,
    val category: String,
    val message: String,
)
