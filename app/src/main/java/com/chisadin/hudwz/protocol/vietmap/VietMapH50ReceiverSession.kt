package com.chisadin.hudwz.protocol.vietmap

import com.chisadin.hudwz.data.HudRepository
import com.chisadin.hudwz.domain.HudState
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

    private fun handleDecodedMessage(msg: VietMapH50Decoder.DecodedMessage, replies: MutableList<ByteArray>) {
        when (msg) {
            is VietMapH50Decoder.DecodedMessage.SpeedTwo -> {
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
                repository.log("VietMap", "H50: Điều hướng: ${msg.turnType}, còn ${msg.distanceToTurnM}m tới '${msg.nextRoad}', đường hiện tại '${msg.currentRoad}'")
                repository.updateHudState { cur ->
                    cur.copy(
                        navigating = msg.distanceToTurnM >= 0,
                        turn = msg.turnType,
                        distanceMeters = if (msg.distanceToTurnM >= 0) msg.distanceToTurnM else null,
                        street = msg.currentRoad.ifBlank { cur.street },
                        nextStreet = msg.nextRoad.ifBlank { cur.nextStreet },
                        connected = true,
                    )
                }
            }

            is VietMapH50Decoder.DecodedMessage.UpcomingAlerts -> {
                val alerts = msg.alerts.mapNotNull(VietMapH50Decoder::mapH50Alert)
                repository.log(
                    "VietMap",
                    "H50: Nhận ${alerts.size} cảnh báo: " + msg.alerts.joinToString { "type=${it.alertType}/${it.distanceM}m/${it.speedLimitKmh}" },
                )
                repository.updateHudState { cur ->
                    cur.copy(
                        alerts = alerts,
                        connected = true,
                    )
                }
            }

            is VietMapH50Decoder.DecodedMessage.UpcomingAlert -> Unit

            is VietMapH50Decoder.DecodedMessage.EstimatedNavigation -> {
                val remainKm = msg.remainingDistanceMeters / 1000.0
                val eta = "%02d:%02d".format(msg.etaHour, msg.etaMinute)
                repository.log("VietMap", "H50: Lộ trình còn ${msg.remainingDistanceMeters}m (~$remainKm km), ETA $eta, progress=${msg.progress}")
                repository.updateHudState { cur ->
                    cur.copy(
                        eta = eta,
                        remainingMinutes = msg.etaHour * 60 + msg.etaMinute,
                        remainingMeters = msg.remainingDistanceMeters.toInt(),
                        remainingKm = remainKm,
                        connected = true,
                    )
                }
            }

            is VietMapH50Decoder.DecodedMessage.TimeSync -> {
                repository.log("VietMap", "H50: Đồng bộ Unix timestamp: ${msg.timestampSeconds}")
            }

            is VietMapH50Decoder.DecodedMessage.QueryCommand -> {
                repository.log("VietMap", "H50: Nhận truy vấn từ app: ${msg.name} (0x${msg.cmd.toString(16).uppercase()})")
                when (msg.cmd) {
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
