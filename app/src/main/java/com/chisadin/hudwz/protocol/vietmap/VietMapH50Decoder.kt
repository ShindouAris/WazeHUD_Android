package com.chisadin.hudwz.protocol.vietmap

import android.util.Log
import com.chisadin.hudwz.domain.HudAlert
import com.chisadin.hudwz.domain.LaneGuidance
import com.chisadin.hudwz.domain.TurnType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bộ giải mã giao thức VietMap HUD H50 qua BLE GATT.
 *
 * Cấu trúc khung tin H50:
 * - Header: 0xA6, 0x6A (2 bytes)
 * - Command (Opcode): 1 byte
 * - Length: 1 hoặc 2 bytes (độ dài dữ liệu payload)
 * - Payload: data bytes
 * - Checksum: 1 byte (XOR hoặc SUM của các byte payload)
 * - Trailer: 0x0D, 0x0A (2 bytes: \r\n)
 */
object VietMapH50Decoder {

    val HEADER = byteArrayOf(0xA6.toByte(), 0x6A.toByte())
    val TRAILER = byteArrayOf(0x0D.toByte(), 0x0A.toByte())

    const val CMD_SYNC_TIME = 0x01
    const val CMD_SEND_SPEED_TWO = 0x02
    const val CMD_UPCOMING_ALERT = 0x03
    const val CMD_EST_NAV_INFO = 0x04
    const val CMD_SEND_LANE_INFO = 0x05
    const val CMD_NAVIGATION_INFO = 0x06
    const val CMD_SEND_IMAGE = 0x07
    const val CMD_LEFT_TYPE = 0x08
    const val CMD_QUERY_OBD_DATA = 0x23
    const val CMD_QUERY_OBD_VERSION = 0x24
    const val CMD_SET_LAYOUT_VALUE = 0x09
    const val CMD_SET_BRIGHTNESS = 0x0A
    const val CMD_SET_VOLUME = 0xA1
    const val CMD_QUERY_LAYOUT = 0xF9
    const val CMD_QUERY_BRIGHTNESS = 0xFA
    const val CMD_QUERY_SERI = 0xFB
    const val CMD_QUERY_VERSION = 0xFC

    sealed class DecodedMessage {
        data class TimeSync(val timestampSeconds: Long) : DecodedMessage()

        data class LeftType(val mode: Int) : DecodedMessage()

        data class SpeedTwo(
            val currentLimitKmh: Int,
            val secondaryField1: Int,
            val secondaryField2: Int,
            val currentRoad: String,
        ) : DecodedMessage()

        data class UpcomingAlert(
            val alertType: Int,
            val distanceM: Int,
            val speedLimitKmh: Int = 0,
            val rawPayload: ByteArray? = null,
        ) : DecodedMessage()

        data class UpcomingAlerts(
            val alerts: List<UpcomingAlert>,
        ) : DecodedMessage()

        data class EstimatedNavigation(
            val remainingDistanceMeters: Long,
            val etaHour: Int,
            val etaMinute: Int,
            val progress: Int,
        ) : DecodedMessage() {
            val durationMinutes: Int get() = etaHour * 60 + etaMinute

            fun calculateEtaTime(currentMillis: Long = System.currentTimeMillis()): String {
                val totalMins = durationMinutes
                if (totalMins <= 0) return ""
                val arrivalMillis = currentMillis + totalMins * 60_000L
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = arrivalMillis }
                return "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
            }
        }

        data class LaneInfo(
            val visible: Boolean,
            val lanes: List<LaneGuidance>,
        ) : DecodedMessage()

        data class NavigationInfo(
            val turnType: TurnType,
            val distanceToTurnM: Int,
            val currentRoad: String,
            val nextRoad: String,
        ) : DecodedMessage()

        data class ImageData(
            val imageBytes: ByteArray,
            val width: Int,
            val height: Int,
        ) : DecodedMessage()

        data class QueryCommand(
            val cmd: Int,
            val name: String,
            val timestamp: ByteArray? = null,
        ) : DecodedMessage()

        data class SeriResponse(
            val seri: String,
        ) : DecodedMessage()

        data class VersionResponse(
            val version: String,
        ) : DecodedMessage()

        data class RawCommand(
            val cmd: Int,
            val payload: ByteArray,
        ) : DecodedMessage()
    }

    private val AES_KEY = "Walkiz52832iLock".toByteArray(Charsets.UTF_8)
    private val AES_IV = "1234567890ABCDEF".toByteArray(Charsets.UTF_8)

    fun decrypt(ciphertext: ByteArray): ByteArray? {
        if (ciphertext.isEmpty() || ciphertext.size % 16 != 0) return null
        return try {
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding")
            val keySpec = javax.crypto.spec.SecretKeySpec(AES_KEY, "AES")
            val ivSpec = javax.crypto.spec.IvParameterSpec(AES_IV)
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec)
            cipher.doFinal(ciphertext)
        } catch (_: Throwable) {
            null
        }
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val paddedLen = if (plaintext.size % 16 == 0) plaintext.size else ((plaintext.size / 16) + 1) * 16
        val padded = if (paddedLen == plaintext.size) plaintext else plaintext.copyOf(paddedLen)
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding")
        val keySpec = javax.crypto.spec.SecretKeySpec(AES_KEY, "AES")
        val ivSpec = javax.crypto.spec.IvParameterSpec(AES_IV)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(padded)
    }

    fun buildEncryptedResponse(cmd: Int, payload: ByteArray = byteArrayOf(), timestamp: ByteArray? = null): ByteArray {
        val rawLen = 2 + 4 + 2 + 1 + payload.size + 2
        val paddedLen = if (rawLen % 16 == 0) rawLen else ((rawLen / 16) + 1) * 16
        val frame = ByteArray(paddedLen)
        frame[0] = HEADER[0]
        frame[1] = HEADER[1]
        if (timestamp != null && timestamp.size == 4) {
            System.arraycopy(timestamp, 0, frame, 2, 4)
        } else {
            val nowSec = (System.currentTimeMillis() / 1000).toInt()
            frame[2] = ((nowSec shr 24) and 0xFF).toByte()
            frame[3] = ((nowSec shr 16) and 0xFF).toByte()
            frame[4] = ((nowSec shr 8) and 0xFF).toByte()
            frame[5] = (nowSec and 0xFF).toByte()
        }
        frame[6] = ((paddedLen shr 8) and 0xFF).toByte()
        frame[7] = (paddedLen and 0xFF).toByte()
        frame[8] = cmd.toByte()
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, frame, 9, payload.size)
        }
        frame[9 + payload.size] = TRAILER[0]
        frame[10 + payload.size] = TRAILER[1]
        return encrypt(frame)
    }

    /**
     * Tính toán Checksum XOR cho khung tin H50.
     */
    fun calculateChecksum(data: ByteArray, start: Int, length: Int): Byte {
        var cs = 0
        for (i in start until (start + length)) {
            cs = cs xor (data[i].toInt() and 0xFF)
        }
        return cs.toByte()
    }

    /**
     * Đóng gói khung tin gửi cho VietMap Live:
     * Header (A6 6A) + Cmd (1B) + Len (2B) + Payload + Checksum (1B) + Trailer (0D 0A)
     */
    fun buildFrame(cmd: Int, payload: ByteArray): ByteArray {
        val totalSize = 2 + 1 + 2 + payload.size + 1 + 2
        val bb = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        bb.put(HEADER)
        bb.put(cmd.toByte())
        bb.putShort(payload.size.toShort())
        bb.put(payload)
        val cs = calculateChecksum(bb.array(), 2, 3 + payload.size)
        bb.put(cs)
        bb.put(TRAILER)
        return bb.array()
    }

    /**
     * Tạo khung tin phản hồi Serial number (cho query 0xFB)
     */
    fun buildSeriResponse(seri: String = "H50-WZ888888"): ByteArray {
        return buildFrame(CMD_QUERY_SERI, seri.toByteArray(Charsets.UTF_8))
    }

    /**
     * Tạo khung tin phản hồi Version (cho query 0xFC)
     */
    fun buildVersionResponse(version: String = "H50-V1.0.0"): ByteArray {
        return buildFrame(CMD_QUERY_VERSION, version.toByteArray(Charsets.UTF_8))
    }

    /**
     * Tìm vị trí khung tin hoàn chỉnh đầu tiên trong buffer.
     * Trả về Pair(startIndex, endIndexInclusive) hoặc null nếu chưa có khung đầy đủ.
     */
    fun findFrame(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Pair<Int, Int>? {
        val end = offset + length
        var i = offset
        while (i <= end - 4) { // Tối thiểu 6 bytes: A6 6A CMD CS 0D 0A
            if (buffer[i] == 0xA6.toByte() && buffer[i + 1] == 0x6A.toByte()) {
                // Tìm trailer 0D 0A
                for (j in (i + 3) until (end - 1)) {
                    if (buffer[j] == 0x0D.toByte() && buffer[j + 1] == 0x0A.toByte()) {
                        return Pair(i, j + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * Giải mã một khung tin H50 hoàn chỉnh bắt đầu từ A6 6A và kết thúc bằng 0D 0A.
     */
    fun parseFrame(frame: ByteArray, offset: Int = 0, length: Int = frame.size - offset): DecodedMessage? {
        if (length < 6) return null
        var workFrame = frame
        var workOffset = offset
        var workLength = length

        if (workLength >= 16 && workLength % 16 == 0 &&
            (workFrame[workOffset] != HEADER[0] || workFrame[workOffset + 1] != HEADER[1])
        ) {
            val slice = if (workOffset == 0 && workLength == workFrame.size) workFrame else workFrame.copyOfRange(workOffset, workOffset + workLength)
            val decrypted = decrypt(slice)
            if (decrypted != null && decrypted.size >= 6 && decrypted[0] == HEADER[0] && decrypted[1] == HEADER[1]) {
                workFrame = decrypted
                workOffset = 0
                workLength = decrypted.size
            }
        }

        val start = workOffset
        val end = workOffset + workLength

        if (workFrame[start] != 0xA6.toByte() || workFrame[start + 1] != 0x6A.toByte()) {
            return null
        }

        // Kiểm tra khung BLE có chứa Timestamp (4B) + Length (2B):
        // [A6 6A] [Timestamp: 4B] [PaddedLen: 2B] [CMD: 1B] [Payload...] [0D 0A]
        if (workLength >= 11) {
            val declaredPaddedLen = ((workFrame[start + 6].toInt() and 0xFF) shl 8) or (workFrame[start + 7].toInt() and 0xFF)
            if (declaredPaddedLen == workLength || declaredPaddedLen == 16 || declaredPaddedLen == 32 || declaredPaddedLen == 48) {
                val ts = workFrame.copyOfRange(start + 2, start + 6)
                val cmd = workFrame[start + 8].toInt() and 0xFF
                var trailerIdx = -1
                for (j in (start + 9) until end - 1) {
                    if (workFrame[j] == 0x0D.toByte() && workFrame[j + 1] == 0x0A.toByte()) {
                        trailerIdx = j
                        break
                    }
                }
                if (trailerIdx != -1) {
                    val payload = workFrame.copyOfRange(start + 9, trailerIdx)
                    return routeCommand(cmd, payload, ts)
                }
            }
        }

        if (workFrame[end - 2] != 0x0D.toByte() || workFrame[end - 1] != 0x0A.toByte()) {
            return null
        }

        val cmd = workFrame[start + 2].toInt() and 0xFF

        // Xác định vị trí payload cho khung Classic không mã hóa:
        // Cấu trúc chuẩn: [A6 6A] [CMD: 1B] [LEN: 2B hoặc 1B] [PAYLOAD...] [CS: 1B] [0D 0A]
        var payloadStart = start + 3
        var payloadLen = (end - 3) - payloadStart

        // Nếu có độ dài 2-byte BE rõ ràng
        if (length >= 8) {
            val declaredLen2 = ((frame[start + 3].toInt() and 0xFF) shl 8) or (frame[start + 4].toInt() and 0xFF)
            if (declaredLen2 == length - 8) { // 2 header + 1 cmd + 2 len + declaredLen + 1 cs + 2 trailer = length
                payloadStart = start + 5
                payloadLen = declaredLen2
            } else {
                // Thử độ dài 1-byte
                val declaredLen1 = frame[start + 3].toInt() and 0xFF
                if (declaredLen1 == length - 7) {
                    payloadStart = start + 4
                    payloadLen = declaredLen1
                }
            }
        } else if (length >= 7) {
            val declaredLen1 = frame[start + 3].toInt() and 0xFF
            if (declaredLen1 == length - 7) {
                payloadStart = start + 4
                payloadLen = declaredLen1
            }
        }

        val payload = frame.copyOfRange(payloadStart, payloadStart + payloadLen)
        return routeCommand(cmd, payload, null)
    }

    private fun routeCommand(cmd: Int, payload: ByteArray, timestamp: ByteArray?): DecodedMessage? {
        return when (cmd) {
            CMD_SYNC_TIME -> DecodedMessage.TimeSync(timestamp?.let(::u32) ?: 0L)
            CMD_SEND_SPEED_TWO -> parseSpeedTwo(payload)
            CMD_UPCOMING_ALERT -> parseUpcomingAlerts(payload)
            CMD_EST_NAV_INFO -> parseEstNavInfo(payload)
            CMD_SEND_LANE_INFO -> parseLaneInfo(payload)
            CMD_NAVIGATION_INFO -> parseNavigationInfo(payload)
            CMD_SEND_IMAGE -> parseImageData(payload)
            CMD_LEFT_TYPE -> {
                val mode = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0
                DecodedMessage.LeftType(mode)
            }
            CMD_QUERY_OBD_DATA -> if (payload.isEmpty()) {
                DecodedMessage.QueryCommand(cmd, "queryOBDData", timestamp)
            } else {
                DecodedMessage.RawCommand(cmd, payload)
            }
            CMD_QUERY_VERSION -> if (payload.isNotEmpty()) {
                DecodedMessage.VersionResponse(String(payload, Charsets.UTF_8))
            } else {
                DecodedMessage.QueryCommand(cmd, "queryVersion", timestamp)
            }
            CMD_QUERY_OBD_VERSION -> if (payload.isEmpty()) {
                DecodedMessage.QueryCommand(cmd, "queryOBDVersion", timestamp)
            } else {
                DecodedMessage.RawCommand(cmd, payload)
            }
            CMD_QUERY_SERI -> if (payload.isNotEmpty()) {
                DecodedMessage.SeriResponse(String(payload, Charsets.UTF_8))
            } else {
                DecodedMessage.QueryCommand(cmd, "querySeri", timestamp)
            }
            CMD_QUERY_BRIGHTNESS -> DecodedMessage.QueryCommand(cmd, "queryBrightness", timestamp)
            CMD_QUERY_LAYOUT -> DecodedMessage.QueryCommand(cmd, "queryLayout", timestamp)
            else -> {
                try {
                    Log.i("WazeHudReceiver", "H50: Nhận opcode chưa xử lý chuyên biệt: cmd=0x${cmd.toString(16).uppercase()}, len=${payload.size}B")
                } catch (_: Throwable) {}
                DecodedMessage.RawCommand(cmd, payload)
            }
        }
    }

    /** Opcode 0x02: [currentLimit, secondary1, secondary2, textFlag, textLen, road UTF-8]. */
    private fun parseSpeedTwo(payload: ByteArray): DecodedMessage.SpeedTwo? {
        if (payload.size < 5) return null
        val textLength = payload[4].toInt() and 0xFF
        if (5 + textLength > payload.size) return null
        return DecodedMessage.SpeedTwo(
            currentLimitKmh = payload[0].toInt() and 0xFF,
            secondaryField1 = payload[1].toInt() and 0xFF,
            secondaryField2 = payload[2].toInt() and 0xFF,
            currentRoad = String(payload, 5, textLength, Charsets.UTF_8),
        )
    }

    /** Opcode 0x03: exactly two records [type, distance u24 BE, auxiliary]. */
    private fun parseUpcomingAlerts(payload: ByteArray): DecodedMessage.UpcomingAlerts? {
        if (payload.size < 10) return null
        fun record(offset: Int) = DecodedMessage.UpcomingAlert(
            alertType = payload[offset].toInt() and 0xFF,
            distanceM = u24(payload, offset + 1),
            speedLimitKmh = payload[offset + 4].toInt() and 0xFF,
            rawPayload = payload.copyOfRange(offset, offset + 5),
        )
        return DecodedMessage.UpcomingAlerts(listOf(record(0), record(5)))
    }

    /** Opcode 0x04: [remaining distance u24 BE, ETA hour, ETA minute, progress]. */
    private fun parseEstNavInfo(payload: ByteArray): DecodedMessage.EstimatedNavigation? {
        if (payload.size < 6) return null
        return DecodedMessage.EstimatedNavigation(
            remainingDistanceMeters = u24(payload, 0).toLong(),
            etaHour = payload[3].toInt() and 0xFF,
            etaMinute = payload[4].toInt() and 0xFF,
            progress = payload[5].toInt() and 0xFF,
        )
    }

    /**
     * Opcode 0x05: [visible, slotCapacity (9), 9 centered H50 lane-type slots].
     */
    fun parseLaneInfo(payload: ByteArray): DecodedMessage.LaneInfo {
        if (payload.size < 11) return DecodedMessage.LaneInfo(false, emptyList())

        val visible = payload[0].toInt() != 0
        if (!visible) return DecodedMessage.LaneInfo(false, emptyList())

        val rawLaneCount = payload[1].toInt() and 0xFF
        val slots = payload.copyOfRange(2, 11)

        // H50 gửi mảng 9 slots cố định căn giữa (đệm 0 ở hai đầu cho phần cứng LED 9 vị trí của HUD H50).
        // VietMap Live thực tế luôn gửi payload[1] = 9 (kích thước mảng).
        // Ta trích xuất các làn thực tế dựa trên vùng slots khác 0 hoặc rawLaneCount (nếu 1..8).
        val rawLanes = if (rawLaneCount in 1..8) {
            val firstLane = (9 - rawLaneCount) / 2
            (0 until rawLaneCount).map { slots[firstLane + it].toInt() and 0xFF }
        } else {
            val firstIndex = slots.indexOfFirst { it.toInt() != 0 }
            val lastIndex = slots.indexOfLast { it.toInt() != 0 }
            if (firstIndex == -1 || lastIndex == -1) {
                return DecodedMessage.LaneInfo(false, emptyList())
            }
            slots.slice(firstIndex..lastIndex).map { it.toInt() and 0xFF }
        }

        val lanes = rawLanes.mapNotNull { raw ->
            val (dirMask, _) = mapSingleLaneByte(raw)
            if (dirMask != 0) {
                LaneGuidance(
                    directionsMask = dirMask,
                    selectedMask = dirMask,
                )
            } else null
        }

        return DecodedMessage.LaneInfo(visible && lanes.isNotEmpty(), lanes)
    }

    /**
     * Ánh xạ mã làn đường đơn lẻ sang directionsMask và selectedMask của WazeHUD.
     * Canonical renderer mask: bit0 straight, bit1 slight-left, bit2 left, bit3 sharp-left,
     * bit4 slight-right, bit5 right, bit6 sharp-right, bit7 U-turn.
     */
    fun mapSingleLaneByte(raw: Int): Pair<Int, Int> {
        val dirMask = when (raw and 0xFF) {
            0x01 -> 4                    // left
            0x02 -> 4 or 32              // left + right
            0x03 -> 4 or 128             // left + U-left
            0x04 -> 32                   // right
            0x05 -> 1                    // straight
            0x06 -> 1 or 4               // straight + left
            0x07 -> 1 or 4 or 32         // straight + left + right
            0x08 -> 1 or 4 or 32 or 128  // straight + left + right + U-left
            0x09 -> 1 or 4 or 128        // straight + left + U-left
            0x0A -> 1 or 32              // straight + right
            0x0B -> 1 or 32 or 128       // straight + right + U-left
            0x0C, 0x0D -> 1 or 128       // straight + U-left/U-right
            0x0E -> 128                  // U-left
            else -> 0
        }
        return Pair(dirMask, 0)
    }

    /**
     * Opcode 0x06: Chi tiết điều hướng (Mũi tên rẽ, cự ly rẽ, tên đường)
     * Payload: [maneuver: 1B] [distance: u24 BE] [textFlag: 1B] [len: 1B] [road UTF-8]
     */
    private fun parseNavigationInfo(payload: ByteArray): DecodedMessage.NavigationInfo? {
        if (payload.size < 6) return null
        val maneuverCode = payload[0].toInt() and 0xFF
        val turnType = mapH50Maneuver(maneuverCode)

        val distToTurn = u24(payload, 1)
        val textLength = payload[5].toInt() and 0xFF
        if (6 + textLength > payload.size) return null
        val nextRoad = String(payload, 6, textLength, Charsets.UTF_8)
        return DecodedMessage.NavigationInfo(turnType, distToTurn, "", nextRoad)
    }

    private fun parseImageData(payload: ByteArray): DecodedMessage.ImageData? {
        if (payload.size < 4) return null
        val width = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val height = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        val imgBytes = payload.copyOfRange(4, payload.size)
        return DecodedMessage.ImageData(imgBytes, width, height)
    }

    /**
     * Ánh xạ mã maneuver XFc từ VietMap Live H50 sang TurnType của WazeHUD.
     */
    fun mapH50Maneuver(code: Int): TurnType = when (code) {
        0x04 -> TurnType.CONTINUE       // departStraight
        0x02 -> TurnType.LEFT           // departLeft
        0x03 -> TurnType.RIGHT          // departRight
        0x25 -> TurnType.CONTINUE
        0x1F -> TurnType.LEFT
        0x20 -> TurnType.RIGHT
        0x23 -> TurnType.SLIGHT_LEFT
        0x24 -> TurnType.SLIGHT_RIGHT
        0x21 -> TurnType.SHARP_LEFT
        0x22 -> TurnType.SHARP_RIGHT
        0x27 -> TurnType.U_TURN
        0x1D -> TurnType.ROUNDABOUT_STRAIGHT
        0x17 -> TurnType.ROUNDABOUT_LEFT
        0x18 -> TurnType.ROUNDABOUT_RIGHT
        0x1E -> TurnType.ROUNDABOUT_U_TURN
        0x19, 0x1B -> TurnType.ROUNDABOUT_LEFT
        0x1A, 0x1C -> TurnType.ROUNDABOUT_RIGHT
        0x08, 0x0A -> TurnType.KEEP_LEFT  // forkLeft / forkSlightLeft
        0x09, 0x0B -> TurnType.KEEP_RIGHT // forkRight / forkSlightRight
        0x0C -> TurnType.CONTINUE         // forkStraight
        0x0D, 0x0F -> TurnType.KEEP_LEFT  // mergeLeft / mergeSlightLeft
        0x0E, 0x10 -> TurnType.KEEP_RIGHT // mergeRight / mergeSlightRight
        0x11 -> TurnType.CONTINUE         // mergeStraight
        0x05 -> TurnType.LEFT
        0x06 -> TurnType.RIGHT
        0x01 -> TurnType.ARRIVE
        0x28 -> TurnType.LEFT              // roadIconLeft
        0x29 -> TurnType.RIGHT             // roadIconRight
        else -> TurnType.NONE
    }

    /** Maps the recovered H50 alert enum. F0 is a generated next-limit sign, while 0A is
     * speedLimitCamera. Distances are u24 BE; the leading zero seen on short routes is not
     * padding. */
    fun mapH50Alert(item: DecodedMessage.UpcomingAlert): HudAlert? {
        if (item.alertType == 0 || item.distanceM >= 10000 || item.distanceM == 0x86A0) {
            return null
        }
        val (mappedType, vmlIcon) = when (item.alertType) {
            0x01 -> Pair(12, "VML_alert/toll.png")
            0x02 -> Pair(75, "VML_alert/tunnel.png")
            0x03 -> Pair(12, "VML_alert/toll_etc.png")
            0x04 -> Pair(11, "VML_alert/railway.png")
            0x05 -> Pair(23, "VML_alert/residential_area_in.png")
            0x06 -> Pair(24, "VML_alert/residential_area_out.png")
            0x07 -> Pair(20, "VML_alert/highway_rest_area.png")
            0x08 -> Pair(40, "VML_alert/penalty_camera.png")
            0x09 -> Pair(4,  "VML_alert/traffic_camera.png")
            0x0A -> Pair(2,  "VML_alert/speed_limit_camera.png")
            0x0B -> Pair(9,  "VML_alert/no_passing_in.png")
            0x0C -> Pair(10, "VML_alert/no_passing_out.png")
            0x0D -> Pair(2,  "VML_alert/speed_limit_camera.png")
            0x0E -> Pair(17, "VML_alert/stop_lane.png")
            0xF0 -> {
                val lim = item.speedLimitKmh
                val icon = if (lim in 10..120 && lim % 10 == 0) "VML_alert/speed_limit_$lim.png" else null
                Pair(8, icon)
            }
            else -> {
                try {
                    Log.w("WazeHudReceiver", "H50: Cảnh báo chưa được ánh xạ: type=${item.alertType}, dist=${item.distanceM}m")
                } catch (_: Throwable) {}
                Pair(4, null) // Fallback -> bigpin_hazard.png
            }
        }
        val alertValue = if (item.alertType == 0xF0 || item.alertType == 0x0A || item.alertType == 0x0D) {
            item.speedLimitKmh.takeIf { it in 10..150 }
        } else {
            null
        }
        return HudAlert(
            type = mappedType,
            distanceMeters = item.distanceM,
            value = alertValue,
            iconPath = vmlIcon,
        )
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }

    private fun u24(value: ByteArray, offset: Int): Int =
        ((value[offset].toInt() and 0xFF) shl 16) or
            ((value[offset + 1].toInt() and 0xFF) shl 8) or
            (value[offset + 2].toInt() and 0xFF)

    private fun u32(value: ByteArray): Long =
        ((value[0].toLong() and 0xFF) shl 24) or
            ((value[1].toLong() and 0xFF) shl 16) or
            ((value[2].toLong() and 0xFF) shl 8) or
            (value[3].toLong() and 0xFF)
}
