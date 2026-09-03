package com.chisadin.hudwz.protocol.vietmap

import com.chisadin.hudwz.data.HudRepository
import com.chisadin.hudwz.domain.HudState
import com.chisadin.hudwz.domain.TurnType
import java.io.ByteArrayOutputStream

/**
 * Quản lý phiên giao tiếp VietMap HUD H50 qua BLE GATT.
 * Nhận các luồng byte ghi vào Characteristic 00009abc..., phân tích khung tin,
 * cập nhật trạng thái lên HudRepository và tạo khung phản hồi qua Characteristic 00001234...
 */
class VietMapH50ReceiverSession(
    private val repository: HudRepository,
    private val serialNumber: String = "CHISADIN-H50-0001",
    private val firmwareVersion: String = "H50;1.0;1.0;1",
) {
    private val buffer = ByteArrayOutputStream()

    @Synchronized
    fun onConnected() {
        buffer.reset()
        repository.log("VietMap", "Phiên kết nối VietMap H50 BLE đã sẵn sàng")
    }

    @Synchronized
    fun onDisconnected() {
        buffer.reset()
        repository.log("VietMap", "Đã ngắt kết nối phiên VietMap H50 BLE")
    }

    /**
     * Nạp dữ liệu byte nhận được từ BLE GATT Characteristic Write.
     * Trả về danh sách frame phản hồi cần gửi lại qua BLE GATT Characteristic Notify.
     */
    @Synchronized
    fun feed(incoming: ByteArray): List<ByteArray> {
        val replies = mutableListOf<ByteArray>()
        // 1. Thử giải mã AES-128-CBC nếu độ dài là bội số của 16
        if (incoming.size >= 16 && incoming.size % 16 == 0) {
            val decrypted = VietMapH50Decoder.decrypt(incoming)
            if (decrypted != null && decrypted.size >= 11 &&
                decrypted[0] == VietMapH50Decoder.HEADER[0] &&
                decrypted[1] == VietMapH50Decoder.HEADER[1]
            ) {
                repository.log("VietMap", "H50: Giải mã thành công AES (${decrypted.size}B): ${VietMapH50Decoder.bytesToHex(decrypted)}")
                buffer.write(decrypted)
                replies.addAll(processBuffer())
                return replies
            }
        }

        buffer.write(incoming)
        replies.addAll(processBuffer())
        return replies
    }

    private fun processBuffer(): List<ByteArray> {
        val replies = mutableListOf<ByteArray>()
        val data = buffer.toByteArray()
        var offset = 0

        while (offset < data.size) {
            val remaining = data.size - offset
            if (remaining < 6) break // Khung H50 tối thiểu 6 byte

            val framePos = VietMapH50Decoder.findFrame(data, offset, remaining)
            if (framePos == null) {
                // Không tìm thấy khung hoàn chỉnh trong phần còn lại, giữ lại để chờ thêm byte
                break
            }

            val (startIdx, endIdx) = framePos
            val frameLen = (endIdx - startIdx) + 1
            val msg = VietMapH50Decoder.parseFrame(data, startIdx, frameLen)

            if (msg != null) {
                handleDecodedMessage(msg, replies)
            }

            offset = endIdx + 1
        }

        // Cắt bỏ phần buffer đã xử lý xong
        if (offset > 0) {
            val unread = if (offset < data.size) data.copyOfRange(offset, data.size) else byteArrayOf()
            buffer.reset()
            buffer.write(unread)
        }

        return replies
    }

    private var lastNavInfoElapsedMs: Long = 0L

    private fun currentElapsedRealtime(): Long =
        runCatching { android.os.SystemClock.elapsedRealtime() }.getOrDefault(System.currentTimeMillis())

    private fun checkNavTimeout() {
        val now = currentElapsedRealtime()
        if (lastNavInfoElapsedMs > 0 && now - lastNavInfoElapsedMs > 6000L) {
            flushNavigationState("Hết hạn dữ liệu điều hướng (>6s không nhận thêm chỉ dẫn mới)")
        }
    }

    private fun flushNavigationState(reason: String) {
        lastNavInfoElapsedMs = 0L
        repository.log("VietMap", "H50: Xoá chỉ dẫn dẫn đường ($reason)")
        repository.updateHudState { cur ->
            if (cur.navigating || cur.turn != TurnType.NONE || cur.distanceMeters != null || cur.nextStreet != null || cur.remainingKm != null) {
                cur.copy(
                    navigating = false,
                    turn = TurnType.NONE,
                    distanceMeters = null,
                    nextStreet = null,
                    eta = null,
                    remainingMinutes = null,
                    remainingMeters = null,
                    remainingKm = null,
                    zoneProgress = null,
                    lanes = emptyList(),
                )
            } else cur
        }
    }

    private fun handleDecodedMessage(msg: VietMapH50Decoder.DecodedMessage, replies: MutableList<ByteArray>) {
        when (msg) {
            is VietMapH50Decoder.DecodedMessage.SpeedTwo -> {
                checkNavTimeout()
                repository.log(
                    "VietMap",
                    "H50: Giới hạn ${msg.currentLimitKmh} km/h, đường hiện tại '${msg.currentRoad}', fields phụ=${msg.secondaryField1}/${msg.secondaryField2}",
                )
                repository.updateHudState { cur ->
                    val validLimit = msg.currentLimitKmh.takeIf { it in 10..150 }
                    cur.copy(
                        speedLimit = validLimit ?: cur.speedLimit,
                        street = msg.currentRoad.ifBlank { cur.street },
                        overspeed = validLimit?.let { limit -> cur.speed?.let { it > limit } } ?: cur.overspeed,
                        connected = true,
                    )
                }
            }

            is VietMapH50Decoder.DecodedMessage.LaneInfo -> {
                repository.log("VietMap", "H50: Nhận dữ liệu ${msg.lanes.size} làn đường")
                repository.updateHudState { cur ->
                    cur.copy(
                        lanes = msg.lanes,
                        connected = true,
                    )
                }
            }

            is VietMapH50Decoder.DecodedMessage.NavigationInfo -> {
                val hasDistance = msg.distanceToTurnM >= 0
                val hasTurn = msg.turnType != TurnType.NONE
                if (hasDistance && hasTurn) {
                    lastNavInfoElapsedMs = currentElapsedRealtime()
                    repository.log("VietMap", "H50: Điều hướng: ${msg.turnType}, còn ${msg.distanceToTurnM}m tới '${msg.nextRoad}', đường hiện tại '${msg.currentRoad}'")
                    repository.updateHudState { cur ->
                        cur.copy(
                            navigating = true,
                            turn = msg.turnType,
                            distanceMeters = msg.distanceToTurnM,
                            street = msg.currentRoad.ifBlank { cur.street },
                            nextStreet = msg.nextRoad.ifBlank { cur.nextStreet },
                            connected = true,
                        )
                    }
                } else {
                    flushNavigationState("NavigationInfo chỉ định không điều hướng (turn=${msg.turnType})")
                }
            }

            is VietMapH50Decoder.DecodedMessage.UpcomingAlerts -> {
                checkNavTimeout()
                val alerts = msg.alerts.mapNotNull(VietMapH50Decoder::mapH50Alert)
                val fallbackLimit = msg.alerts.firstOrNull { it.speedLimitKmh in 10..150 }?.speedLimitKmh
                repository.log(
                    "VietMap",
                    "H50: Nhận ${alerts.size} cảnh báo: " + msg.alerts.joinToString { "type=${it.alertType}/${it.distanceM}m/${it.speedLimitKmh}" },
                )
                repository.updateHudState { cur ->
                    val effectiveLimit = cur.speedLimit ?: fallbackLimit
                    val isOverspeed = effectiveLimit?.let { limit -> cur.speed?.let { it > limit } } ?: cur.overspeed
                    cur.copy(
                        speedLimit = effectiveLimit,
                        overspeed = isOverspeed,
                        alerts = alerts,
                        connected = true,
                    )
                }
            }

            is VietMapH50Decoder.DecodedMessage.UpcomingAlert -> Unit

            is VietMapH50Decoder.DecodedMessage.EstimatedNavigation -> {
                val isNavEnded = msg.remainingDistanceMeters == 0L && msg.progress == 0
                if (isNavEnded) {
                    flushNavigationState("Kết thúc lộ trình (estNav=0m, progress=0%)")
                } else {
                    lastNavInfoElapsedMs = currentElapsedRealtime()
                    val remainKm = msg.remainingDistanceMeters / 1000.0
                    val totalMinutes = msg.durationMinutes
                    val calculatedEta = if (totalMinutes > 0) msg.calculateEtaTime() else null
                    repository.log(
                        "VietMap",
                        "H50: Lộ trình còn ${msg.remainingDistanceMeters}m (~$remainKm km), thời gian di chuyển: ${msg.etaHour}h${msg.etaMinute}m ($totalMinutes phút) -> ETA tính toán: $calculatedEta, progress=${msg.progress}",
                    )
                    repository.updateHudState { cur ->
                        cur.copy(
                            navigating = true,
                            eta = calculatedEta,
                            remainingMinutes = totalMinutes.takeIf { it > 0 },
                            remainingMeters = msg.remainingDistanceMeters.toInt().takeIf { it > 0 },
                            remainingKm = remainKm.takeIf { it > 0.0 },
                            zoneProgress = msg.progress.takeIf { it in 1..100 },
                            connected = true,
                        )
                    }
                }
            }

            is VietMapH50Decoder.DecodedMessage.TimeSync -> {
                repository.log("VietMap", "H50: Đồng bộ Unix timestamp: ${msg.timestampSeconds}")
            }

            is VietMapH50Decoder.DecodedMessage.LeftType -> {
                repository.log("VietMap", "H50: Cấu hình chế độ hiển thị bên trái (leftType): mode=${msg.mode}")
                if (msg.mode != 3) {
                    // mode 1 = Logo (không dẫn đường), mode 0 = None, mode 2 = Compass
                    flushNavigationState("VietMap chuyển chế độ bên trái: mode=${msg.mode} (không còn maneuverInfo)")
                }
            }

            is VietMapH50Decoder.DecodedMessage.QueryCommand -> {
                repository.log("VietMap", "H50: Nhận truy vấn từ app: ${msg.name} (0x${msg.cmd.toString(16).uppercase()})")
                when (msg.cmd) {
                    VietMapH50Decoder.CMD_QUERY_OBD_DATA -> {
                        // Không kết nối cổng OBD xe thật, bỏ qua truy vấn dữ liệu OBD
                    }
                    VietMapH50Decoder.CMD_QUERY_SERI -> {
                        val reply = VietMapH50Decoder.buildEncryptedResponse(
                            VietMapH50Decoder.CMD_QUERY_SERI,
                            serialNumber.toByteArray(Charsets.UTF_8),
                            msg.timestamp,
                        )
                        replies.add(reply)
                        repository.log("VietMap", "H50: Đã gửi phản hồi Serial mã hóa: $serialNumber (${reply.size}B)")
                    }
                    VietMapH50Decoder.CMD_QUERY_VERSION -> {
                        val reply = VietMapH50Decoder.buildEncryptedResponse(
                            VietMapH50Decoder.CMD_QUERY_VERSION,
                            firmwareVersion.toByteArray(Charsets.UTF_8),
                            msg.timestamp,
                        )
                        replies.add(reply)
                        repository.log("VietMap", "H50: Đã gửi phản hồi Firmware Version mã hóa: $firmwareVersion (${reply.size}B)")
                    }
                    VietMapH50Decoder.CMD_QUERY_OBD_VERSION -> {
                        val obdVersion = "NONE;0.0"
                        val reply = VietMapH50Decoder.buildEncryptedResponse(
                            VietMapH50Decoder.CMD_QUERY_OBD_VERSION,
                            obdVersion.toByteArray(Charsets.UTF_8),
                            msg.timestamp,
                        )
                        replies.add(reply)
                        repository.log("VietMap", "H50: Đã gửi phản hồi OBD Version mã hóa: $obdVersion (${reply.size}B)")
                    }
                    VietMapH50Decoder.CMD_QUERY_BRIGHTNESS -> {
                        // Phản hồi độ sáng tự động (auto=1, level=5)
                        val reply = VietMapH50Decoder.buildEncryptedResponse(
                            VietMapH50Decoder.CMD_QUERY_BRIGHTNESS,
                            byteArrayOf(0x01, 0x05),
                            msg.timestamp,
                        )
                        replies.add(reply)
                    }
                    VietMapH50Decoder.CMD_QUERY_LAYOUT -> {
                        // Phản hồi bố cục mặc định 0
                        val reply = VietMapH50Decoder.buildEncryptedResponse(
                            VietMapH50Decoder.CMD_QUERY_LAYOUT,
                            byteArrayOf(0x00),
                            msg.timestamp,
                        )
                        replies.add(reply)
                    }
                }
            }

            is VietMapH50Decoder.DecodedMessage.SeriResponse -> {
                repository.log("VietMap", "H50: Seri: ${msg.seri}")
            }

            is VietMapH50Decoder.DecodedMessage.VersionResponse -> {
                repository.log("VietMap", "H50: Version: ${msg.version}")
            }

            is VietMapH50Decoder.DecodedMessage.ImageData -> {
                repository.log("VietMap", "H50: Nhận ảnh đồ họa nút giao ${msg.width}x${msg.height} (${msg.imageBytes.size} bytes)")
            }

            is VietMapH50Decoder.DecodedMessage.RawCommand -> {
                repository.log("VietMap", "H50: Gói thô cmd=0x${msg.cmd.toString(16).uppercase()}, size=${msg.payload.size}B")
            }
        }
    }
}
