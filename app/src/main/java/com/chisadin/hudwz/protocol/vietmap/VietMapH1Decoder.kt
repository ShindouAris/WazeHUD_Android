package com.chisadin.hudwz.protocol.vietmap

import android.util.Log
import com.chisadin.hudwz.domain.HudAlert
import com.chisadin.hudwz.domain.TurnType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object VietMapH1Decoder {
    val MAGIC_C5 = byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x37.toByte(), 0xC5.toByte())
    val MAGIC_C3 = byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x37.toByte(), 0xC3.toByte())
    val FIXED_AUTH_KEY = byteArrayOf(
        0xF7.toByte(), 0xFD.toByte(), 0x15.toByte(), 0x75.toByte(),
        0x02.toByte(), 0xDA.toByte(), 0x52.toByte(), 0x8E.toByte(),
        0xF0.toByte(), 0xFC.toByte(), 0x31.toByte(), 0x39.toByte(),
        0x72.toByte(), 0x2C.toByte(), 0xE9.toByte(), 0xE2.toByte(),
    )

    sealed interface DecodedMessage {
        data class CurrentSpeed(val speedKmh: Int) : DecodedMessage
        data object Heartbeat : DecodedMessage
        data class CameraAlertItem(val type: Int, val distanceM: Int, val speedLimitKmh: Int)
        data class CameraList(val alerts: List<CameraAlertItem>) : DecodedMessage
        data class Navigation(
            val direction: Int,
            val turnType: TurnType,
            val distanceToTurnM: Int,
            val currentRoad: String,
            val nextRoad: String,
            val remainTimeS: Int,
            val remainDistanceM: Int,
            val currentSpeedKmh: Int,
            val isNavigating: Boolean = distanceToTurnM >= 0,
        ) : DecodedMessage
        data class TimeSync(val timestampSec: Long) : DecodedMessage
        data class TpmsConfig(val paramType: Int, val value: Int) : DecodedMessage
        data class DeviceConfig(val opcode: Int, val payloadHex: String) : DecodedMessage
        data class Handshake(val rawText: String) : DecodedMessage
        data object DeviceInfoQuery : DecodedMessage
        data class UnsupportedPayload(
            val frameKind: String,
            val opcode: Int?,
            val payloadHex: String,
            val length: Int,
            val description: String,
        ) : DecodedMessage
    }

    fun crc16Modbus(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0xFFFF
        val end = offset + length
        for (i in offset until end) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (j in 0 until 8) {
                crc = if ((crc and 1) != 0) {
                    (crc ushr 1) xor 0xA001
                } else {
                    crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    fun md5WordSum(data: ByteArray): Long {
        val digest = MessageDigest.getInstance("MD5").digest(data)
        val buf = ByteBuffer.wrap(digest).order(ByteOrder.BIG_ENDIAN)
        var sum = 0L
        for (i in 0 until 4) {
            sum += buf.int.toLong() and 0xFFFFFFFFL
        }
        return sum and 0xFFFFFFFFL
    }

    fun encodeC3(
        payload: ByteArray,
        sessionKey: ByteArray = FIXED_AUTH_KEY,
        timestampSec: Long? = null,
    ): ByteArray {
        val sec = timestampSec ?: (System.currentTimeMillis() / 1000)
        val declaredLen = payload.size + 12
        val prefix = ByteBuffer.allocate(12 + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC_C3)
            .putInt(declaredLen)
            .putInt(sec.toInt())
            .put(payload)
            .array()

        val sessionAuth = md5WordSum(prefix + sessionKey)
        val fixedAuth = md5WordSum(prefix + FIXED_AUTH_KEY)

        return ByteBuffer.allocate(prefix.size + 8)
            .order(ByteOrder.BIG_ENDIAN)
            .put(prefix)
            .putInt(sessionAuth.toInt())
            .putInt(fixedAuth.toInt())
            .array()
    }

    fun buildDeviceInfoC3Frame(protocol: String = "2.2.2"): ByteArray {
        val info = "MODEL:H1N,HW:1.6.4,FW:1.2.0,PROTOCOL:$protocol,OBDV:NONE_0.0".toByteArray(Charsets.UTF_8)
        val payload = byteArrayOf(0x0E.toByte()) + info
        return encodeC3(payload)
    }

    fun bytesToHex(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): String {
        val sb = StringBuilder(length * 2)
        val end = (offset + length).coerceAtMost(bytes.size)
        for (i in offset until end) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 16) sb.append('0')
            sb.append(Integer.toHexString(b).uppercase())
        }
        return sb.toString()
    }

    fun smoothRoadName(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        // Common Vietnamese expressway full-name completions for truncated prefixes
        if (trimmed.startsWith("Cao Tốc Phan Thiết", ignoreCase = true)) {
            return "Cao Tốc Phan Thiết - Dầu Giây"
        }
        if (trimmed.startsWith("Cao Tốc Hồ Chí Minh", ignoreCase = true) ||
            trimmed.startsWith("Cao Tốc TP.HCM", ignoreCase = true) ||
            trimmed.startsWith("Cao Tốc Long Thành", ignoreCase = true)) {
            return "Cao Tốc TP.HCM - Long Thành - Dầu Giây"
        }
        if (trimmed.startsWith("Cao Tốc Dầu Giây - Phan", ignoreCase = true)) {
            return "Cao Tốc Dầu Giây - Phan Thiết"
        }
        if (trimmed.startsWith("Cao Tốc Vĩnh Hảo", ignoreCase = true)) {
            return "Cao Tốc Vĩnh Hảo - Phan Thiết"
        }
        if (trimmed.startsWith("Cao Tốc Cam Lộ", ignoreCase = true)) {
            return "Cao Tốc Cam Lộ - La Sơn"
        }
        if (trimmed.startsWith("Cao Tốc Đà Nẵng", ignoreCase = true)) {
            return "Cao Tốc Đà Nẵng - Quảng Ngãi"
        }
        if (trimmed.startsWith("Cao Tốc Hà Nội - Hải", ignoreCase = true)) {
            return "Cao Tốc Hà Nội - Hải Phòng"
        }
        if (trimmed.startsWith("Cao Tốc Nội Bài", ignoreCase = true)) {
            return "Cao Tốc Nội Bài - Lào Cai"
        }
        if (trimmed.startsWith("Cao Tốc Pháp Vân", ignoreCase = true)) {
            return "Cao Tốc Pháp Vân - Cầu Giẽ"
        }
        if (trimmed.startsWith("Cao Tốc Cầu Giẽ", ignoreCase = true)) {
            return "Cao Tốc Cầu Giẽ - Ninh Bình"
        }
        if (trimmed.startsWith("Cao Tốc Bến Lức", ignoreCase = true)) {
            return "Cao Tốc Bến Lức - Long Thành"
        }
        if (trimmed.startsWith("Cao Tốc Trung Lương", ignoreCase = true) ||
            trimmed.startsWith("Cao Tốc TP.HCM - Trung", ignoreCase = true)) {
            return "Cao Tốc TP.HCM - Trung Lương"
        }
        if (trimmed.startsWith("Cao Tốc Mỹ Thuận", ignoreCase = true)) {
            return "Cao Tốc Mỹ Thuận - Cần Thơ"
        }

        // If it ends with a single letter after space (e.g. "Đường ABC D"), drop the dangling letter
        val words = trimmed.split(" ")
        if (words.size > 2 && words.last().length == 1) {
            return words.dropLast(1).joinToString(" ")
        }

        return trimmed
    }

    fun mapTurnType(direction: Int): TurnType = when (direction) {
        0 -> TurnType.NONE
        1 -> TurnType.CONTINUE
        2, 0xFE, 254, -2 -> TurnType.SLIGHT_RIGHT // Slight right / fork right
        3 -> TurnType.RIGHT
        4 -> TurnType.SHARP_RIGHT
        5 -> TurnType.U_TURN
        6 -> TurnType.SHARP_LEFT
        7 -> TurnType.LEFT
        8 -> TurnType.SLIGHT_LEFT
        9 -> TurnType.U_TURN_RIGHT
        10 -> TurnType.ROUNDABOUT_STRAIGHT
        11 -> TurnType.SLIGHT_RIGHT // forkSlightRight
        12 -> TurnType.SLIGHT_LEFT  // forkSlightLeft
        14 -> TurnType.ARRIVE
        16 -> TurnType.SLIGHT_RIGHT // mergeSlightRight
        23 -> TurnType.SLIGHT_LEFT  // turnSlightLeft
        24 -> TurnType.SLIGHT_RIGHT // turnSlightRight
        25 -> TurnType.CONTINUE     // turnStraight
        31 -> TurnType.LEFT         // turnLeft
        32 -> TurnType.RIGHT        // turnRight
        33 -> TurnType.SHARP_LEFT   // turnSharpLeft
        34 -> TurnType.SHARP_RIGHT  // turnSharpRight
        35 -> TurnType.SLIGHT_LEFT  // turnSlightLeft
        36 -> TurnType.SLIGHT_RIGHT // turnSlightRight
        37 -> TurnType.CONTINUE     // turnStraight
        else -> TurnType.SLIGHT_RIGHT
    }

    fun mapCameraAlert(item: DecodedMessage.CameraAlertItem, defaultSpeedLimit: Int? = null): HudAlert? {
        // Filter out inactive sentinels (100km placeholder) or none (type 0 is speed limit holder)
        if (item.type == 0 || item.distanceM >= 10000 || item.distanceM == 0x86A0) {
            return null
        }
        val mappedType = when (item.type) {
            1 -> 40  // Camera phạt nguội kiểu 1 -> Phone camera (bigpin_phone_camera.png)
            4 -> 3   // Camera đèn đỏ -> Red light camera (bigpin_red_light_camera.png)
            6 -> 2   // Camera bắn tốc độ -> Speed camera (bigpin_speed_camera.png)
            2 -> 23  // Sắp vào khu dân cư
            3 -> 24  // Sắp ra khu dân cư
            5 -> 4   // Camera theo dõi giao thông
            7 -> 9   // Bắt đầu cấm vượt
            8 -> 10  // Hết cấm vượt
            9 -> 4   // Chú ý giảm tốc độ
            10, 11 -> 12 // Trạm thu phí
            12 -> 18 // Đường hầm
            13 -> 11 // Đường sắt
            14 -> 20 // Trạm dừng nghỉ
            else -> {
                Log.w("WazeHudReceiver", "VietMap: Camera type chưa được ánh xạ: type=${item.type}, dist=${item.distanceM}m, speed=${item.speedLimitKmh}km/h")
                4 // Fallback -> bigpin_hazard.png
            }
        }
        return HudAlert(
            type = mappedType,
            distanceMeters = item.distanceM,
            value = null,
        )
    }

    fun parseFrame(
        frame: ByteArray,
        offset: Int = 0,
        length: Int = frame.size,
    ): DecodedMessage? {
        if (length < 4) return null

        // Check ASCII Handshake string
        if (isAsciiHandshake(frame, offset, length)) {
            val text = String(frame, offset, length, Charsets.US_ASCII).trim()
            return DecodedMessage.Handshake(text)
        }

        // Frame Plain C5: A5 5A 37 C5
        if (startsWith(frame, offset, length, MAGIC_C5)) {
            if (length < 6) return null
            val payloadLen = length - 6
            val storedCrc = ((frame[offset + length - 2].toInt() and 0xFF) shl 8) or
                (frame[offset + length - 1].toInt() and 0xFF)
            val computedCrc = crc16Modbus(frame, offset + 4, payloadLen)
            if (storedCrc != computedCrc) {
                return DecodedMessage.UnsupportedPayload(
                    frameKind = "C5-BadCRC",
                    opcode = null,
                    payloadHex = bytesToHex(frame, offset, length),
                    length = length,
                    description = "Lỗi checksum CRC16: stored=${storedCrc.toString(16)}, computed=${computedCrc.toString(16)}",
                )
            }
            return parseC5Payload(frame, offset + 4, payloadLen)
        }

        // Frame Authenticated C3: A5 5A 37 C3
        if (startsWith(frame, offset, length, MAGIC_C3)) {
            if (length < 20) return null
            val declaredLen = ByteBuffer.wrap(frame, offset + 4, 4).order(ByteOrder.BIG_ENDIAN).int
            val expectedTotal = declaredLen + 8
            if (length < expectedTotal) return null

            // Payload starts at offset 12 and ends at (offset + expectedTotal - 8)
            val payloadLen = expectedTotal - 20
            val payloadOffset = offset + 12
            return parseC3Payload(frame, payloadOffset, payloadLen)
        }

        return DecodedMessage.UnsupportedPayload(
            frameKind = "UNKNOWN",
            opcode = frame[offset].toInt() and 0xFF,
            payloadHex = bytesToHex(frame, offset, length),
            length = length,
            description = "Gói tin không có header chuẩn C3/C5/ASCII",
        )
    }

    private fun parseC5Payload(data: ByteArray, offset: Int, length: Int): DecodedMessage {
        val buf = ByteBuffer.wrap(data, offset, length).order(ByteOrder.BIG_ENDIAN)
        // sendOneCameraInformation: <00 00 00 09> 01 <type:u8> <dist:u16> <speed:u8>
        if (length >= 9 && buf.int == 9 && buf.get().toInt() == 1) {
            val camType = buf.get().toInt() and 0xFF
            val dist = buf.short.toInt() and 0xFFFF
            val speed = buf.get().toInt() and 0xFF
            return DecodedMessage.CameraList(
                listOf(DecodedMessage.CameraAlertItem(camType, dist, speed)),
            )
        }
        val op = if (length > 0) data[offset].toInt() and 0xFF else null
        return DecodedMessage.UnsupportedPayload(
            frameKind = "C5",
            opcode = op,
            payloadHex = bytesToHex(data, offset, length),
            length = length,
            description = "Payload C5 chưa hỗ trợ định dạng",
        )
    }

    private fun parseC3Payload(data: ByteArray, offset: Int, length: Int): DecodedMessage {
        if (length <= 0) {
            return DecodedMessage.UnsupportedPayload(
                frameKind = "C3",
                opcode = null,
                payloadHex = "",
                length = 0,
                description = "Payload C3 rỗng",
            )
        }
        val buf = ByteBuffer.wrap(data, offset, length).order(ByteOrder.BIG_ENDIAN)
        val opcode = buf.get().toInt() and 0xFF

        when (opcode) {
            0x0E -> { // Device-info query: 0e 00
                return DecodedMessage.DeviceInfoQuery
            }
            0x02 -> { // Heartbeat / OBD speed: 02 <speed:u16>
                if (buf.remaining() >= 2) {
                    val rawSpeed = buf.short.toInt() and 0xFFFF
                    return if (rawSpeed == 0xFFFF) {
                        DecodedMessage.Heartbeat
                    } else {
                        DecodedMessage.CurrentSpeed(rawSpeed)
                    }
                }
            }
            0x01 -> { // Multiple cameras list: 01 + repeating <type:u8><dist:u16><speed:u8>
                val list = mutableListOf<DecodedMessage.CameraAlertItem>()
                while (buf.remaining() >= 4) {
                    val type = buf.get().toInt() and 0xFF
                    val dist = buf.short.toInt() and 0xFFFF
                    val speed = buf.get().toInt() and 0xFF
                    list.add(DecodedMessage.CameraAlertItem(type, dist, speed))
                }
                return DecodedMessage.CameraList(list)
            }
            0x0A -> { // Time sync: 0a <ts:u32>
                if (buf.remaining() >= 4) {
                    val ts = buf.int.toLong() and 0xFFFFFFFFL
                    return DecodedMessage.TimeSync(ts)
                }
            }
            0x33 -> { // TPMS thresholds: 33 <type:u8> <val:u16>
                if (buf.remaining() >= 3) {
                    val param = buf.get().toInt() and 0xFF
                    val v = buf.short.toInt() and 0xFFFF
                    return DecodedMessage.TpmsConfig(param, v)
                }
            }
            0x0B, 0x0D, 0x10, 0x11, 0x13 -> { // Device config sync
                return DecodedMessage.DeviceConfig(opcode, bytesToHex(data, offset, length))
            }
            0x40 -> { // Navigation: opcode 0x40
                if (buf.remaining() >= 19) {
                    val dir = buf.get().toInt() and 0xFF
                    val imgLen = buf.int
                    if (imgLen >= 0 && buf.remaining() >= imgLen + 14) {
                        buf.position(buf.position() + imgLen) // Skip image bytes or marker
                        val turnDist = buf.int
                        val nextRoadLen = buf.get().toInt() and 0xFF
                        if (buf.remaining() >= nextRoadLen) {
                            val nextRoadBytes = ByteArray(nextRoadLen)
                            buf.get(nextRoadBytes)
                            val nextRoad = smoothRoadName(String(nextRoadBytes, Charsets.UTF_8))

                            if (buf.remaining() >= 1) {
                                val curRoadLen = buf.get().toInt() and 0xFF
                                if (buf.remaining() >= curRoadLen + 8) {
                                    val curRoadBytes = ByteArray(curRoadLen)
                                    buf.get(curRoadBytes)
                                    val curRoad = smoothRoadName(String(curRoadBytes, Charsets.UTF_8))

                                    val hours = buf.short.toInt() and 0xFFFF
                                    val mins = buf.get().toInt() and 0xFF
                                    val secs = buf.get().toInt() and 0xFF
                                    val remainDistance = buf.int
                                    val rawSpeed = buf.short.toInt() and 0xFFFF
                                    val currentSpeed = if (rawSpeed == 0xFFFF) 0 else rawSpeed
                                    val remainTime = hours * 3600 + mins * 60 + secs

                                    return DecodedMessage.Navigation(
                                        direction = dir,
                                        turnType = mapTurnType(dir),
                                        distanceToTurnM = turnDist,
                                        currentRoad = curRoad,
                                        nextRoad = nextRoad,
                                        remainTimeS = remainTime,
                                        remainDistanceM = remainDistance,
                                        currentSpeedKmh = currentSpeed,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        return DecodedMessage.UnsupportedPayload(
            frameKind = "C3",
            opcode = opcode,
            payloadHex = bytesToHex(data, offset, length),
            length = length,
            description = "Opcode 0x${opcode.toString(16).uppercase()} chưa hỗ trợ trong C3",
        )
    }

    private fun startsWith(data: ByteArray, offset: Int, length: Int, prefix: ByteArray): Boolean {
        if (length < prefix.size) return false
        for (i in prefix.indices) {
            if (data[offset + i] != prefix[i]) return false
        }
        return true
    }

    private fun isAsciiHandshake(data: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 5) return false
        val s = String(data, offset, minOf(length, 30), Charsets.US_ASCII)
        return s.startsWith("FW:") || s.startsWith("PROTOCOL:") || s.startsWith("OBDV:")
    }
}
