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
    private val codec: HlpFrameCodec = HlpFrameCodec(),
    private val protocol: HlpProtocol = HlpProtocol(),
) {
    private val _hudState = MutableStateFlow(HudState())
    val hudState: StateFlow<HudState> = _hudState.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _metrics = MutableStateFlow(TransportMetrics())
    val metrics: StateFlow<TransportMetrics> = _metrics.asStateFlow()

    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()

    private val _rawPackets = MutableStateFlow<List<String>>(emptyList())
    val rawPackets: StateFlow<List<String>> = _rawPackets.asStateFlow()

    private val _parsedPacket = MutableStateFlow("No packet parsed")
    val parsedPacket: StateFlow<String> = _parsedPacket.asStateFlow()

    private var sessionId: Long? = null
    private var windowStarted = SystemClock.elapsedRealtime()
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
                _hudState.value = HudState()
            }
        } else {
            _metrics.update { it.copy(lastPacketElapsedMs = SystemClock.elapsedRealtime()) }
        }
    }

    fun updateTransportMetrics(transform: (TransportMetrics) -> TransportMetrics) {
        _metrics.update(transform)
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
                                    _parsedPacket.value = "HELLO session=${message.sessionId} rate=${message.rate}Hz"
                                }
                                is HlpProtocol.Message.State -> {
                                    val currentSession = sessionId
                                    val current = _hudState.value
                                    val next = message.value.copy(sessionId = currentSession)
                                    if (currentSession != current.sessionId || next.sourceTimestampMs >= current.sourceTimestampMs) {
                                        _hudState.value = next
                                    }
                                    _parsedPacket.value = "speed=${next.speed} limit=${next.speedLimit} turn=${next.turn.name} alerts=${next.alerts.size}"
                                }
                                HlpProtocol.Message.Ping -> replies += protocol.pong()
                                HlpProtocol.Message.Pong -> _parsedPacket.value = "PONG"
                                HlpProtocol.Message.Bye -> _parsedPacket.value = "BYE"
                                is HlpProtocol.Message.Error -> parserError("${message.code}: ${message.detail.orEmpty()}")
                                is HlpProtocol.Message.Other -> _parsedPacket.value = "Ignored ${message.type}"
                            }
                        }
                        .onFailure { parserError(it.message ?: "INVALID_JSON") }
                }
            }
        }
        return replies
    }

    fun staleForMs(now: Long = SystemClock.elapsedRealtime()): Long? =
        _metrics.value.lastPacketElapsedMs?.let { now - it }

    fun log(category: String, message: String) {
        Log.d("WazeHudReceiver", "$category: $message")
        val event = DiagnosticEvent(SystemClock.elapsedRealtime(), category, message)
        _events.update { (it + event).takeLast(250) }
    }

    private fun parserError(reason: String) {
        _metrics.update { it.copy(parserErrors = it.parserErrors + 1) }
        log("Protocol", reason)
    }

    private fun recordPacket(line: String) {
        val now = SystemClock.elapsedRealtime()
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
