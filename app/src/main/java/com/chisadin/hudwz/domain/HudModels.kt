package com.chisadin.hudwz.domain

import kotlinx.serialization.Serializable

@Serializable
enum class TransportType { AUTO, BLE, CLASSIC, WIFI_WEBSOCKET }

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
    val trafficDelayMinutes: Int? = null,
    val trafficSeverity: Int? = null,
    val bearingDegrees: Float? = null,
    val connected: Boolean = false,
    val sessionId: Long? = null,
    val sourceTimestampMs: Long = 0,
    val receivedAtElapsedMs: Long = 0,
)

@Serializable
enum class HudWidgetType {
    SPEED, SPEED_NUMBER, SPEED_NUMBER_ONLY, SPEED_LIMIT, SPEED_LIMIT_BAR,
    TURN, NEXT_TURN, DISTANCE, STREET, NEXT_STREET,
    ETA, REMAINING, GPS, CONNECTION, ALERTS, LANES, TRAFFIC_DELAY,
    CUSTOM_TEXT, CUSTOM_IMAGE, PHONE_BATTERY,
    CLOCK, COMPASS, TRIP_PROGRESS
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
    val customText: String = "Chữ tùy chỉnh",
    val customImageUri: String? = null,
    val locked: Boolean = false,
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
        HudWidgetType.SPEED_NUMBER -> base.copy(widthDp = 130f, heightDp = 90f, fontSizeSp = 72f)
        HudWidgetType.SPEED_NUMBER_ONLY -> base.copy(widthDp = 130f, heightDp = 60f, fontSizeSp = 72f)
        HudWidgetType.SPEED_LIMIT -> base.copy(widthDp = 120f, heightDp = 120f, fontSizeSp = 46f, iconSizeDp = 108f)
        HudWidgetType.SPEED_LIMIT_BAR -> base.copy(widthDp = 220f, heightDp = 24f, fontSizeSp = 12f)
        HudWidgetType.TURN -> base.copy(widthDp = 120f, heightDp = 120f, iconSizeDp = 108f)
        HudWidgetType.NEXT_TURN -> base.copy(widthDp = 76f, heightDp = 76f, iconSizeDp = 68f)
        HudWidgetType.DISTANCE -> base.copy(widthDp = 140f, heightDp = 44f, fontSizeSp = 27f)
        HudWidgetType.STREET, HudWidgetType.NEXT_STREET -> base.copy(widthDp = 250f, heightDp = 42f, fontSizeSp = 20f)
        HudWidgetType.ETA -> base.copy(widthDp = 120f, heightDp = 40f, fontSizeSp = 20f)
        HudWidgetType.REMAINING -> base.copy(widthDp = 260f, heightDp = 42f, fontSizeSp = 17f)
        HudWidgetType.GPS -> base.copy(widthDp = 120f, heightDp = 38f, iconSizeDp = 21f, fontSizeSp = 12f)
        HudWidgetType.CONNECTION -> base.copy(widthDp = 160f, heightDp = 42f, iconSizeDp = 24f, fontSizeSp = 15f)
        HudWidgetType.ALERTS -> base.copy(widthDp = 92f, heightDp = 196f, iconSizeDp = 48f, orientation = HudElementOrientation.VERTICAL)
        HudWidgetType.LANES -> base.copy(widthDp = 250f, heightDp = 54f, iconSizeDp = 24f)
        HudWidgetType.TRAFFIC_DELAY -> base.copy(widthDp = 176f, heightDp = 62f, iconSizeDp = 46f, fontSizeSp = 22f)
        HudWidgetType.CUSTOM_TEXT -> base.copy(widthDp = 240f, heightDp = 50f, fontSizeSp = 24f)
        HudWidgetType.CUSTOM_IMAGE -> base.copy(widthDp = 160f, heightDp = 110f, spacingDp = 0f)
        HudWidgetType.PHONE_BATTERY -> base.copy(widthDp = 160f, heightDp = 42f, iconSizeDp = 24f, fontSizeSp = 15f)
        HudWidgetType.CLOCK -> base.copy(widthDp = 130f, heightDp = 48f, fontSizeSp = 28f)
        HudWidgetType.COMPASS -> base.copy(widthDp = 96f, heightDp = 50f, fontSizeSp = 18f, iconSizeDp = 24f)
        HudWidgetType.TRIP_PROGRESS -> base.copy(widthDp = 220f, heightDp = 34f)
    }
}

@Serializable
enum class HudProfileOrientationMode {
    AUTO,            // Tự động nhận diện
    BOTH,            // Hỗ trợ cả ngang và dọc
    PORTRAIT_ONLY,   // Chỉ dọc
    LANDSCAPE_ONLY,  // Chỉ ngang
}

@Serializable
data class HudProfile(
    val id: String,
    val name: String,
    val hudScale: Float = 1f,
    val layoutVersion: Int = 4,
    val elements: List<HudElementConfig>,
    val portraitElements: List<HudElementConfig> = emptyList(),
    val orientationMode: HudProfileOrientationMode = HudProfileOrientationMode.AUTO,
) {
    val effectiveOrientationMode: HudProfileOrientationMode
        get() = when (orientationMode) {
            HudProfileOrientationMode.BOTH -> HudProfileOrientationMode.BOTH
            HudProfileOrientationMode.PORTRAIT_ONLY -> HudProfileOrientationMode.PORTRAIT_ONLY
            HudProfileOrientationMode.LANDSCAPE_ONLY -> HudProfileOrientationMode.LANDSCAPE_ONLY
            HudProfileOrientationMode.AUTO -> {
                val hasLandscape = elements.isNotEmpty() && elements.any { it.visible }
                val hasPortrait = portraitElements.isNotEmpty() && portraitElements.any { it.visible }
                when {
                    hasLandscape && hasPortrait -> HudProfileOrientationMode.BOTH
                    hasPortrait -> HudProfileOrientationMode.PORTRAIT_ONLY
                    hasLandscape && !hasPortrait -> HudProfileOrientationMode.LANDSCAPE_ONLY
                    else -> HudProfileOrientationMode.BOTH
                }
            }
        }

    val isPortraitOnly: Boolean get() = effectiveOrientationMode == HudProfileOrientationMode.PORTRAIT_ONLY
    val isLandscapeOnly: Boolean get() = effectiveOrientationMode == HudProfileOrientationMode.LANDSCAPE_ONLY
    val supportsBoth: Boolean get() = effectiveOrientationMode == HudProfileOrientationMode.BOTH

    fun elementsFor(isPortrait: Boolean): List<HudElementConfig> = when (effectiveOrientationMode) {
        HudProfileOrientationMode.PORTRAIT_ONLY -> portraitElements.ifEmpty { defaultPortraitElementsFor(this) }
        HudProfileOrientationMode.LANDSCAPE_ONLY -> elements
        else -> if (isPortrait) {
            portraitElements.ifEmpty { defaultPortraitElementsFor(this) }
        } else {
            elements
        }
    }

    companion object {
        fun defaultProfile() = HudProfile(
            id = "default",
            name = "Mặc định",
            layoutVersion = 4,
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
            portraitElements = defaultPortraitProfileElements(),
        )

        fun minimalProfile() = HudProfile(
            id = "minimal",
            name = "Tối giản",
            layoutVersion = 4,
            elements = listOf(
                HudElementConfig("turn", HudWidgetType.TURN, 42f, 86f, 130f, 130f, iconSizeDp = 117f),
                HudElementConfig("distance", HudWidgetType.DISTANCE, 36f, 224f, 150f, 44f, fontSizeSp = 28f),
                HudElementConfig("speed", HudWidgetType.SPEED, 330f, 66f, 210f, 210f, fontSizeSp = 72f),
                HudElementConfig("limit", HudWidgetType.SPEED_LIMIT, 570f, 105f, 126f, 126f, fontSizeSp = 48f),
                HudElementConfig("street", HudWidgetType.STREET, 270f, 292f, 320f, 42f, fontSizeSp = 21f),
            ),
            portraitElements = minimalPortraitProfileElements(),
        )

        fun largeSpeedProfile() = HudProfile(
            id = "large-speed",
            name = "Tốc độ lớn",
            layoutVersion = 4,
            elements = listOf(
                HudElementConfig("speed", HudWidgetType.SPEED, 220f, 18f, 320f, 320f, fontSizeSp = 112f),
                HudElementConfig("limit", HudWidgetType.SPEED_LIMIT, 570f, 78f, 150f, 150f, fontSizeSp = 56f),
                HudElementConfig("connection", HudWidgetType.CONNECTION, 638f, 306f, 144f, 38f, fontSizeSp = 12f),
            ),
            portraitElements = largeSpeedPortraitProfileElements(),
        )
    }
}

fun defaultPortraitProfileElements(): List<HudElementConfig> = listOf(
    HudElementConfig("lanes", HudWidgetType.LANES, 16f, 16f, 328f, 54f),
    HudElementConfig("turn", HudWidgetType.TURN, 24f, 82f, 130f, 130f, iconSizeDp = 118f),
    HudElementConfig("distance", HudWidgetType.DISTANCE, 18f, 222f, 142f, 44f, fontSizeSp = 28f),
    HudElementConfig("limit", HudWidgetType.SPEED_LIMIT, 194f, 82f, 142f, 142f, fontSizeSp = 56f),
    HudElementConfig("speed", HudWidgetType.SPEED, 55f, 276f, 250f, 150f, fontSizeSp = 84f),
    HudElementConfig("street", HudWidgetType.STREET, 20f, 440f, 320f, 44f, fontSizeSp = 21f),
    HudElementConfig("remaining", HudWidgetType.REMAINING, 20f, 492f, 320f, 40f, fontSizeSp = 17f),
    HudElementConfig("alerts", HudWidgetType.ALERTS, 20f, 546f, 320f, 110f, iconSizeDp = 48f, orientation = HudElementOrientation.HORIZONTAL),
    HudElementConfig("connection", HudWidgetType.CONNECTION, 100f, 690f, 160f, 38f, fontSizeSp = 13f),
)

fun minimalPortraitProfileElements(): List<HudElementConfig> = listOf(
    HudElementConfig("turn", HudWidgetType.TURN, 24f, 70f, 130f, 130f, iconSizeDp = 118f),
    HudElementConfig("distance", HudWidgetType.DISTANCE, 20f, 210f, 140f, 44f, fontSizeSp = 28f),
    HudElementConfig("limit", HudWidgetType.SPEED_LIMIT, 196f, 70f, 140f, 140f, fontSizeSp = 56f),
    HudElementConfig("speed", HudWidgetType.SPEED, 55f, 280f, 250f, 180f, fontSizeSp = 90f),
    HudElementConfig("street", HudWidgetType.STREET, 20f, 480f, 320f, 44f, fontSizeSp = 21f),
)

fun largeSpeedPortraitProfileElements(): List<HudElementConfig> = listOf(
    HudElementConfig("speed", HudWidgetType.SPEED, 30f, 120f, 300f, 300f, fontSizeSp = 120f),
    HudElementConfig("limit", HudWidgetType.SPEED_LIMIT, 105f, 450f, 150f, 150f, fontSizeSp = 60f),
    HudElementConfig("connection", HudWidgetType.CONNECTION, 100f, 650f, 160f, 38f, fontSizeSp = 13f),
)

fun defaultPortraitElementsFor(profile: HudProfile): List<HudElementConfig> = when (profile.id) {
    "default" -> defaultPortraitProfileElements()
    "minimal" -> minimalPortraitProfileElements()
    "large-speed" -> largeSpeedPortraitProfileElements()
    else -> {
        val adapted = autoAdaptToPortrait(profile.elements)
        adapted.ifEmpty { defaultPortraitProfileElements() }
    }
}

fun autoAdaptToPortrait(elements: List<HudElementConfig>): List<HudElementConfig> {
    return elements.mapIndexed { index, element ->
        val safeWidth = element.widthDp.coerceAtMost(320f)
        val safeHeight = if (element.type.locksAspectRatio) safeWidth else element.heightDp.coerceAtMost(250f)
        val x = ((HUD_PORTRAIT_REFERENCE_WIDTH_DP - safeWidth) / 2f).coerceAtLeast(0f)
        val y = (index * 75f + 20f).coerceIn(0f, HUD_PORTRAIT_REFERENCE_HEIGHT_DP - safeHeight)
        element.copy(
            x = x,
            y = y,
            widthDp = safeWidth,
            heightDp = safeHeight,
            orientation = if (element.type == HudWidgetType.ALERTS) HudElementOrientation.HORIZONTAL else element.orientation,
        )
    }
}

fun emptyHudProfile(id: String, name: String): HudProfile = HudProfile(
    id = id,
    name = name,
    layoutVersion = 4,
    elements = emptyList(),
    portraitElements = emptyList(),
)

const val HUD_REFERENCE_WIDTH_DP = 800f
const val HUD_REFERENCE_HEIGHT_DP = 360f
const val HUD_PORTRAIT_REFERENCE_WIDTH_DP = 360f
const val HUD_PORTRAIT_REFERENCE_HEIGHT_DP = 800f

fun migrateHudProfile(profile: HudProfile): HudProfile {
    val positioned = if (profile.layoutVersion >= 2) profile else profile.copy(
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
    val v3 = if (positioned.layoutVersion >= 3) positioned else {
        val hasConnection = positioned.elements.any { it.type == HudWidgetType.CONNECTION }
        var convertedBattery = false
        val mergedElements = positioned.elements.mapNotNull { element ->
            when {
                element.type != HudWidgetType.PHONE_BATTERY -> element
                hasConnection -> null
                !convertedBattery -> {
                    convertedBattery = true
                    element.copy(type = HudWidgetType.CONNECTION)
                }
                else -> null
            }
        }
        positioned.copy(layoutVersion = 3, elements = mergedElements)
    }
    if (v3.layoutVersion >= 4) return v3
    val portrait = v3.portraitElements.ifEmpty { defaultPortraitElementsFor(v3) }
    return v3.copy(layoutVersion = 4, portraitElements = portrait)
}

@Serializable
enum class HudOrientation { SENSOR, LANDSCAPE, PORTRAIT }

@Serializable
enum class HudThemeMode { SYSTEM, DAY, NIGHT }

@Serializable
data class HudSettings(
    val isReceiverMode: Boolean = true,
    val preferredTransport: TransportType = TransportType.AUTO,
    val autoReconnect: Boolean = true,
    val preferredDeviceAddress: String? = null,
    val preferredDeviceName: String? = null,
    val connectionTimeoutSeconds: Int = 15,
    val mirrorMode: Boolean = false,
    val orientation: HudOrientation = HudOrientation.SENSOR,
    val brightness: Float = 1f,
    val keepScreenAwake: Boolean = true,
    val immersiveMode: Boolean = true,
    val preventAccidentalTouches: Boolean = false,
    val themeMode: HudThemeMode = HudThemeMode.NIGHT,
    val fontScale: Float = 1f,
    val showRawPackets: Boolean = false,
    val bluetoothLogs: Boolean = false,
    val protocolLogs: Boolean = false,
    val wsPort: Int = 8765,
    val wsPath: String = "/hlp",
    val bubbleEnabled: Boolean = false,
    val bubbleLayout: Int = 0,
    val bubbleSize: Int = 100,
    val receiverSource: ReceiverSource = ReceiverSource.WAZE_MOD,
)

enum class ReceiverSource {
    WAZE_MOD,
    VIETMAP_LIVE,
}

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

private val diagnosticEventSeq = java.util.concurrent.atomic.AtomicLong(1)

data class DiagnosticEvent(
    val elapsedMs: Long,
    val category: String,
    val message: String,
    val id: Long = diagnosticEventSeq.getAndIncrement(),
)
