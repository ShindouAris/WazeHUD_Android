package com.chisadin.hudwz.protocol.vietmap

import com.chisadin.hudwz.data.HudRepository
import com.chisadin.hudwz.domain.HudState
import com.chisadin.hudwz.domain.TurnType
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VietMapH1ReceiverSession(
    private val repository: HudRepository,
    private val protocolVersion: String = "2.2.2",
) {
    companion object {
        fun buildDeviceInfoFrame(protocol: String = "2.2.2"): ByteArray =
            VietMapH1Decoder.buildDeviceInfoC3Frame(protocol)

        val HANDSHAKE_GREETING: ByteArray get() = buildDeviceInfoFrame()
    }

    private val buffer = ByteArrayOutputStream()

    @Synchronized
    fun onConnected() {
        buffer.reset()
        repository.log("VietMap", "Phiên kết nối VietMap H1 đã sẵn sàng")
    }

    fun initialGreeting(): ByteArray {
        return buildDeviceInfoFrame(protocolVersion)
    }

    @Synchronized
    fun onDisconnected() {
        buffer.reset()
        repository.log("VietMap", "Đã ngắt kết nối phiên VietMap H1")
    }

    @Synchronized
    fun feed(incoming: ByteArray): List<ByteArray> {
        buffer.write(incoming)
        return processBuffer()
    }

    private fun processBuffer(): List<ByteArray> {
        val replies = mutableListOf<ByteArray>()
        val data = buffer.toByteArray()
        var offset = 0

        while (offset < data.size) {
            val remaining = data.size - offset
            if (remaining <= 0) break

            // Check raw 0x0E query (e.g. 0E 00)
            if (data[offset] == 0x0E.toByte()) {
                val queryLen = if (remaining >= 2 && data[offset + 1] == 0x00.toByte()) 2 else 1
                handleDecodedMessage(VietMapH1Decoder.DecodedMessage.DeviceInfoQuery, replies)
                offset += queryLen
                continue
            }

            if (remaining < 4) break

            // Check ASCII handshake or text message
            if (isAsciiLine(data, offset, remaining)) {
                val lineEnd = findLineEnd(data, offset, remaining)
                if (lineEnd == -1) break // wait for complete line
                val lineLen = lineEnd - offset
                val msg = VietMapH1Decoder.parseFrame(data, offset, lineLen)
                msg?.let { handleDecodedMessage(it, replies) }
                offset = lineEnd
                while (offset < data.size && (data[offset] == '\r'.code.toByte() || data[offset] == '\n'.code.toByte())) {
                    offset++
                }
                continue
            }

            // Check C5
            if (startsWith(data, offset, remaining, VietMapH1Decoder.MAGIC_C5)) {
                // sendOneCameraInformation is 15 bytes total (4 magic + 9 payload + 2 CRC)
                val expectedLen = 15
                if (remaining < expectedLen) break // wait for more bytes
                val msg = VietMapH1Decoder.parseFrame(data, offset, expectedLen)
                msg?.let { handleDecodedMessage(it, replies) }
                offset += expectedLen
                continue
            }

            // Check C3
            if (startsWith(data, offset, remaining, VietMapH1Decoder.MAGIC_C3)) {
                if (remaining < 8) break // wait for length header
                val declaredLen = ByteBuffer.wrap(data, offset + 4, 4).order(ByteOrder.BIG_ENDIAN).int
                if (declaredLen < 12 || declaredLen > 65536) {
                    // Invalid length header, skip one byte and re-sync
                    offset++
                    continue
                }
                val expectedTotal = declaredLen + 8
                if (remaining < expectedTotal) break // wait for complete frame
                val msg = VietMapH1Decoder.parseFrame(data, offset, expectedTotal)
                msg?.let { handleDecodedMessage(it, replies) }
                offset += expectedTotal
                continue
            }

            // Not matching any known header, advance by 1 byte to find next valid frame
            offset++
        }

        // Retain unconsumed bytes in buffer
        buffer.reset()
        if (offset < data.size) {
            buffer.write(data, offset, data.size - offset)
        }
        return replies
    }

    private fun handleDecodedMessage(
        msg: VietMapH1Decoder.DecodedMessage,
        replies: MutableList<ByteArray>,
    ) {
        when (msg) {
            is VietMapH1Decoder.DecodedMessage.DeviceInfoQuery -> {
                val reply = buildDeviceInfoFrame(protocolVersion)
                replies.add(reply)
                repository.log("VietMap", "Nhận truy vấn thông tin thiết bị (0x0E) -> Phản hồi MODEL:H1N PROTOCOL:$protocolVersion")
            }
            is VietMapH1Decoder.DecodedMessage.CurrentSpeed -> {
                repository.log("VietMap", "Tốc độ hiện tại: ${msg.speedKmh} km/h")
                updateHudState { it.copy(speed = msg.speedKmh, connected = true) }
            }
            is VietMapH1Decoder.DecodedMessage.CameraList -> {
                val topSpeedLimit = msg.alerts.firstOrNull { it.speedLimitKmh in 10..150 }?.speedLimitKmh
                val alerts = msg.alerts.mapNotNull { VietMapH1Decoder.mapCameraAlert(it, topSpeedLimit) }
                if (alerts.isNotEmpty()) {
                    repository.log("VietMap", "Nhận ${alerts.size} cảnh báo (giới hạn: ${topSpeedLimit ?: "–"} km/h)")
                }
                updateHudState {
                    it.copy(
                        alerts = alerts,
                        speedLimit = topSpeedLimit ?: it.speedLimit,
                        connected = true,
                    )
                }
            }
            is VietMapH1Decoder.DecodedMessage.Heartbeat -> {
                // Heartbeat keep-alive (02 FF FF)
            }
            is VietMapH1Decoder.DecodedMessage.Navigation -> {
                val remKm = if (msg.remainDistanceM > 0) msg.remainDistanceM / 1000.0 else null
                val remMin = if (msg.remainTimeS > 0) (msg.remainTimeS + 30) / 60 else null
                if (msg.isNavigating) {
                    repository.log(
                        "VietMap",
                        "Điều hướng: ${msg.turnType.name}, ${msg.distanceToTurnM}m tới '${msg.nextRoad}', đường '${msg.currentRoad}' (${msg.currentSpeedKmh} km/h, còn ${remMin ?: 0}p / ${msg.remainDistanceM}m)",
                    )
                    updateHudState {
                        it.copy(
                            turn = msg.turnType,
                            distanceMeters = msg.distanceToTurnM,
                            street = msg.currentRoad.ifBlank { it.street },
                            nextStreet = msg.nextRoad.ifBlank { it.nextStreet },
                            remainingMinutes = remMin ?: it.remainingMinutes,
                            remainingMeters = if (msg.remainDistanceM > 0) msg.remainDistanceM else it.remainingMeters,
                            remainingKm = remKm ?: it.remainingKm,
                            speed = msg.currentSpeedKmh,
                            navigating = true,
                            connected = true,
                        )
                    }
                } else {
                    repository.log(
                        "VietMap",
                        "Chế độ lái tự do / không dẫn đường (đường: '${msg.currentRoad.ifBlank { "–" }}', tốc độ: ${msg.currentSpeedKmh} km/h)",
                    )
                    updateHudState {
                        it.copy(
                            turn = TurnType.NONE,
                            distanceMeters = null,
                            nextStreet = null,
                            remainingMinutes = null,
                            remainingMeters = null,
                            remainingKm = null,
                            street = msg.currentRoad.ifBlank { it.street },
                            speed = msg.currentSpeedKmh,
                            navigating = false,
                            connected = true,
                        )
                    }
                }
            }
            is VietMapH1Decoder.DecodedMessage.TimeSync -> {
                repository.log("VietMap", "Đồng bộ thời gian: ${msg.timestampSec}")
            }
            is VietMapH1Decoder.DecodedMessage.TpmsConfig -> {
                val typeName = when (msg.paramType) {
                    0x10 -> "Áp suất max"
                    0x11 -> "Áp suất min"
                    0x12 -> "Nhiệt độ max"
                    else -> "0x${msg.paramType.toString(16)}"
                }
                repository.log("VietMap", "Cài đặt TPMS ($typeName): ${msg.value}")
            }
            is VietMapH1Decoder.DecodedMessage.DeviceConfig -> {
                repository.log("VietMap", "Cấu hình thiết bị (0x${msg.opcode.toString(16).uppercase()}): ${msg.payloadHex}")
            }
            is VietMapH1Decoder.DecodedMessage.Handshake -> {
                repository.log("VietMap", "Nhận bắt tay: ${msg.rawText}")
            }
            is VietMapH1Decoder.DecodedMessage.UnsupportedPayload -> {
                val opcodeStr = msg.opcode?.let { "0x" + it.toString(16).uppercase() } ?: "N/A"
                repository.log(
                    "VML-Unsupported",
                    "[Frame ${msg.frameKind} Opcode $opcodeStr] len=${msg.length} hex=${msg.payloadHex} - ${msg.description}",
                )
            }
        }
    }

    private fun updateHudState(update: (HudState) -> HudState) {
        repository.updateHudState(update)
        val current = repository.hudState.value
        repository.setConnection(
            repository.connection.value.copy(
                message = "VietMap Live: ${current.speed ?: 0} km/h",
            ),
        )
    }

    private fun startsWith(data: ByteArray, offset: Int, length: Int, prefix: ByteArray): Boolean {
        if (length < prefix.size) return false
        for (i in prefix.indices) {
            if (data[offset + i] != prefix[i]) return false
        }
        return true
    }

    private fun isAsciiLine(data: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 3) return false
        val b0 = data[offset].toInt().toChar()
        val b1 = data[offset + 1].toInt().toChar()
        return (b0 == 'F' && b1 == 'W') || (b0 == 'P' && b1 == 'R') || (b0 == 'O' && b1 == 'B')
    }

    private fun findLineEnd(data: ByteArray, offset: Int, length: Int): Int {
        val end = offset + length
        for (i in offset until end) {
            if (data[i] == '\n'.code.toByte() || data[i] == '\r'.code.toByte()) return i
        }
        return -1
    }
}
