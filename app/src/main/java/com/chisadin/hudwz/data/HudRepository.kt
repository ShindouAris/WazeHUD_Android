package com.chisadin.hudwz.data

import android.os.SystemClock
import android.util.Log
import com.chisadin.hudwz.domain.ConnectionPhase
import com.chisadin.hudwz.domain.ConnectionState
import com.chisadin.hudwz.domain.DiagnosticEvent
import com.chisadin.hudwz.domain.HudState
import com.chisadin.hudwz.domain.TransportMetrics
import com.chisadin.hudwz.protocol.HlpFrameCodec
import com.chisadin.hudwz.protocol.HlpProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class HudRepository(
    private val context: android.content.Context? = null,
    private val codec: HlpFrameCodec = HlpFrameCodec(),
    private val protocol: HlpProtocol = HlpProtocol(),
) {
    private val prefs by lazy {
        context?.getSharedPreferences("hud_state_cache", android.content.Context.MODE_PRIVATE)
    }

    private fun loadInitialState(): HudState {
        val sp = prefs ?: return HudState()
        val cachedTime = sp.getLong("cache_time", 0L)
        // Nếu cache chưa quá 45 phút, khôi phục tốc độ giới hạn và tên đường
        if (System.currentTimeMillis() - cachedTime < 45 * 60 * 1000L) {
            val limit = sp.getInt("speed_limit", -1).takeIf { it in 10..150 }
            val street = sp.getString("street", null)?.takeIf { it.isNotBlank() }
            val nextStreet = sp.getString("next_street", null)?.takeIf { it.isNotBlank() }
            if (limit != null || street != null) {
                return HudState(
                    speedLimit = limit,
                    street = street,
                    nextStreet = nextStreet,
                    gpsAvailable = true,
                )
            }
        }
        return HudState()
    }

    private val _hudState = MutableStateFlow(loadInitialState())
    val hudState: StateFlow<HudState> = _hudState.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _metrics = MutableStateFlow(TransportMetrics())
    val metrics: StateFlow<TransportMetrics> = _metrics.asStateFlow()

    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()

    private val _rawPackets = MutableStateFlow<List<String>>(emptyList())
    val rawPackets: StateFlow<List<String>> = _rawPackets.asStateFlow()

    private val _parsedPacket = MutableStateFlow("Chưa phân tích gói tin nào")
    val parsedPacket: StateFlow<String> = _parsedPacket.asStateFlow()

    private var sessionId: Long? = null
    private fun currentElapsedRealtime(): Long =
        runCatching { SystemClock.elapsedRealtime() }.getOrDefault(System.currentTimeMillis())

    private var windowStarted = currentElapsedRealtime()
    private var windowPackets = 0L
    @Volatile private var captureRawPackets = false

    fun setCaptureRawPackets(enabled: Boolean) {
        captureRawPackets = enabled
        if (!enabled) _rawPackets.value = emptyList()
    }

    fun setConnection(state: ConnectionState) {
        _connection.value = state
        val connected = state.phase == ConnectionPhase.CONNECTED
        _hudState.update { it.copy(connected = connected) }
        log("Bluetooth", state.message ?: state.phase.name)
        if (!connected) {
            codec.reset()
            _metrics.update { it.copy(lastPacketElapsedMs = null, packetRate = 0.0) }
            if (state.phase == ConnectionPhase.CONNECTING || state.phase == ConnectionPhase.RECONNECTING) {
                sessionId = null
                // Không xoá trắng HudState khi kết nối lại, giữ nguyên giới hạn tốc độ và tên đường
                _hudState.update { it.copy(connected = false) }
            }
        } else {
            _metrics.update { it.copy(lastPacketElapsedMs = currentElapsedRealtime()) }
        }
    }

    fun updateTransportMetrics(transform: (TransportMetrics) -> TransportMetrics) {
        _metrics.update(transform)
    }

    fun updateHudState(transform: (HudState) -> HudState) {
        _hudState.update { cur ->
            val updated = transform(cur)
            if (updated.speedLimit != null || updated.street != null) {
                runCatching {
                    prefs?.edit()?.apply {
                        updated.speedLimit?.let { putInt("speed_limit", it) }
                        updated.street?.let { putString("street", it) }
                        updated.nextStreet?.let { putString("next_street", it) }
                        putLong("cache_time", System.currentTimeMillis())
                        apply()
                    }
                }
            }
            updated
        }
        _metrics.update { it.copy(lastPacketElapsedMs = currentElapsedRealtime()) }
    }

    fun updateGpsSpeed(speedKmh: Int, bearing: Float?) {
        _hudState.update { cur ->
            val overspeed = cur.speedLimit?.let { limit -> speedKmh > limit } ?: false
            cur.copy(
                speed = speedKmh,
                overspeed = overspeed,
                gpsAvailable = true,
                bearingDegrees = bearing ?: cur.bearingDegrees,
            )
        }
    }

    fun accept(bytes: ByteArray): List<ByteArray> {
        val replies = ArrayList<ByteArray>()
        codec.feed(bytes).forEach { result ->
            when (result) {
                is HlpFrameCodec.Result.Error -> parserError(result.reason)
                is HlpFrameCodec.Result.Frame -> {
                    if (result.value.isBlank()) return@forEach
                    recordPacket(result.value)
                    val now = SystemClock.elapsedRealtime()
                    runCatching { protocol.parse(result.value, sessionId, now) }
                        .onSuccess { message ->
                            when (message) {
                                is HlpProtocol.Message.Hello -> {
                                    if (sessionId != message.sessionId) {
                                        sessionId = message.sessionId
                                        _hudState.update { HudState(connected = it.connected, sessionId = sessionId) }
                                    }
                                    _parsedPacket.value = "HELLO phiên=${message.sessionId} tốc độ=${message.rate}Hz"
                                }
                                is HlpProtocol.Message.State -> {
                                    val currentSession = sessionId
                                    val current = _hudState.value
                                    val next = message.value.copy(sessionId = currentSession)
                                    if (currentSession != current.sessionId || next.sourceTimestampMs >= current.sourceTimestampMs) {
                                        _hudState.value = next
                                    }
                                    _parsedPacket.value = "tốc độ=${next.speed} giới hạn=${next.speedLimit} hướng=${next.turn.name} cảnh báo=${next.alerts.size}"
                                }
                                HlpProtocol.Message.Ping -> replies += protocol.pong()
                                HlpProtocol.Message.Pong -> _parsedPacket.value = "PONG"
                                HlpProtocol.Message.Bye -> _parsedPacket.value = "BYE"
                                is HlpProtocol.Message.Error -> parserError("${message.code}: ${message.detail.orEmpty()}")
                                is HlpProtocol.Message.Other -> _parsedPacket.value = "Đã bỏ qua ${message.type}"
                            }
                        }
                        .onFailure { parserError(it.message ?: "INVALID_JSON") }
                }
            }
        }
        return replies
    }

    fun staleForMs(now: Long = currentElapsedRealtime()): Long? =
        _metrics.value.lastPacketElapsedMs?.let { now - it }

    fun log(category: String, message: String) {
        runCatching { Log.d("WazeHudReceiver", "$category: $message") }
        val event = DiagnosticEvent(currentElapsedRealtime(), category, message)
        _events.update { (it + event).takeLast(250) }
    }

    private fun parserError(reason: String) {
        _metrics.update { it.copy(parserErrors = it.parserErrors + 1) }
        log("Giao thức", reason)
    }

    private fun recordPacket(line: String) {
        val now = currentElapsedRealtime()
        windowPackets++
        val elapsed = now - windowStarted
        val rate = if (elapsed >= 1_000) {
            (windowPackets * 1_000.0 / elapsed).also {
                windowStarted = now
                windowPackets = 0
            }
        } else _metrics.value.packetRate
        _metrics.update {
            it.copy(packetCount = it.packetCount + 1, packetRate = rate, lastPacketElapsedMs = now)
        }
        if (captureRawPackets) _rawPackets.update { (it + line).takeLast(200) }
    }
}
