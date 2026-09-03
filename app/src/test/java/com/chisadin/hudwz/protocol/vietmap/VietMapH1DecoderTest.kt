package com.chisadin.hudwz.protocol.vietmap

import com.chisadin.hudwz.data.HudRepository
import com.chisadin.hudwz.domain.TurnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VietMapH1DecoderTest {

    @Test
    fun testCrc16Modbus() {
        val testData = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val crc = VietMapH1Decoder.crc16Modbus(testData)
        // Ensure CRC16 is in 16-bit range
        assertTrue(crc in 0..0xFFFF)
    }

    @Test
    fun testParseC5CameraAlert() {
        // Payload: 00 00 00 09, 01, type=1 (speed cam), dist=200m (0x00C8), speed=80kmh (0x50)
        val payload = byteArrayOf(
            0x00, 0x00, 0x00, 0x09,
            0x01,
            0x01,
            0x00, 0xC8.toByte(),
            0x50,
        )
        val crc = VietMapH1Decoder.crc16Modbus(payload)
        val frame = ByteBuffer.allocate(4 + payload.size + 2)
            .order(ByteOrder.BIG_ENDIAN)
            .put(VietMapH1Decoder.MAGIC_C5)
            .put(payload)
            .putShort(crc.toShort())
            .array()

        val decoded = VietMapH1Decoder.parseFrame(frame)
        assertNotNull(decoded)
        assertTrue(decoded is VietMapH1Decoder.DecodedMessage.CameraList)
        val camList = decoded as VietMapH1Decoder.DecodedMessage.CameraList
        assertEquals(1, camList.alerts.size)
        assertEquals(1, camList.alerts[0].type)
        assertEquals(200, camList.alerts[0].distanceM)
        assertEquals(80, camList.alerts[0].speedLimitKmh)

        val alert = VietMapH1Decoder.mapCameraAlert(camList.alerts[0])
        assertNotNull(alert)
        assertEquals(40, alert!!.type) // mapped to Phone Camera (type 40 = bigpin_phone_camera.png)
        assertEquals(200, alert.distanceMeters)
        assertNull(alert.value)
    }

    @Test
    fun testParseC5BadCrc() {
        val payload = byteArrayOf(0x00, 0x00, 0x00, 0x09, 0x01, 0x01, 0x00, 0x64, 0x3C)
        val frame = ByteBuffer.allocate(4 + payload.size + 2)
            .order(ByteOrder.BIG_ENDIAN)
            .put(VietMapH1Decoder.MAGIC_C5)
            .put(payload)
            .putShort(0x1234.toShort()) // bad CRC
            .array()

        val decoded = VietMapH1Decoder.parseFrame(frame)
        assertNotNull(decoded)
        assertTrue(decoded is VietMapH1Decoder.DecodedMessage.UnsupportedPayload)
        val unsupported = decoded as VietMapH1Decoder.DecodedMessage.UnsupportedPayload
        assertEquals("C5-BadCRC", unsupported.frameKind)
    }

    @Test
    fun testParseC3CurrentSpeed() {
        // Opcode 0x02, speed 65 km/h (0x0041)
        val payload = byteArrayOf(0x02, 0x00, 0x41)
        val declaredLen = payload.size + 12
        val frame = ByteBuffer.allocate(declaredLen + 8)
            .order(ByteOrder.BIG_ENDIAN)
            .put(VietMapH1Decoder.MAGIC_C3)
            .putInt(declaredLen)
            .putInt(1700000000) // timestamp
            .put(payload)
            .putInt(0) // session auth
            .putInt(0) // fixed auth
            .array()

        val decoded = VietMapH1Decoder.parseFrame(frame)
        assertNotNull(decoded)
        assertTrue(decoded is VietMapH1Decoder.DecodedMessage.CurrentSpeed)
        val speedMsg = decoded as VietMapH1Decoder.DecodedMessage.CurrentSpeed
        assertEquals(65, speedMsg.speedKmh)
    }

    @Test
    fun testParseC3Navigation() {
        val nextRoad = "Nguyễn Huệ".toByteArray(Charsets.UTF_8)
        val curRoad = "Lê Lợi".toByteArray(Charsets.UTF_8)

        // Build navigation payload (opcode 0x40)
        val payloadBuf = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN)
        payloadBuf.put(0x40.toByte())
        payloadBuf.put(1.toByte()) // direction = 1 (LEFT)
        payloadBuf.putInt(0) // image len = 0
        payloadBuf.putInt(150) // turn dist = 150m
        payloadBuf.put(nextRoad.size.toByte())
        payloadBuf.put(nextRoad)
        payloadBuf.put(curRoad.size.toByte())
        payloadBuf.put(curRoad)
        payloadBuf.putShort(0) // hours = 0
        payloadBuf.put(5.toByte()) // mins = 5
        payloadBuf.put(30.toByte()) // secs = 30 -> 330s
        payloadBuf.putInt(2500) // remain distance = 2500m
        payloadBuf.putShort(45) // current speed = 45 km/h

        val payload = ByteArray(payloadBuf.position())
        payloadBuf.flip()
        payloadBuf.get(payload)

        val declaredLen = payload.size + 12
        val frame = ByteBuffer.allocate(declaredLen + 8)
            .order(ByteOrder.BIG_ENDIAN)
            .put(VietMapH1Decoder.MAGIC_C3)
            .putInt(declaredLen)
            .putInt(1700000000)
            .put(payload)
            .putInt(0)
            .putInt(0)
            .array()

        val decoded = VietMapH1Decoder.parseFrame(frame)
        assertNotNull(decoded)
        assertTrue(decoded is VietMapH1Decoder.DecodedMessage.Navigation)
        val nav = decoded as VietMapH1Decoder.DecodedMessage.Navigation
        assertEquals(1, nav.direction)
        assertEquals(TurnType.CONTINUE, nav.turnType)
        assertEquals(150, nav.distanceToTurnM)
        assertEquals("Nguyễn Huệ", nav.nextRoad)
        assertEquals("Lê Lợi", nav.currentRoad)
        assertEquals(330, nav.remainTimeS)
        assertEquals(2500, nav.remainDistanceM)
        assertEquals(45, nav.currentSpeedKmh)
        assertTrue(nav.isNavigating)
    }

    @Test
    fun testNavigationIdleDoesNotTriggerNavigationOrOverwriteSpeed() {
        val repo = HudRepository()
        val session = VietMapH1ReceiverSession(repo)
        session.onConnected()

        // 1. Send Heartbeat (Opcode 0x02 with 0xFFFF)
        val speedPayload = byteArrayOf(0x02, 0xFF.toByte(), 0xFF.toByte())
        val sLen = speedPayload.size + 12
        val speedFrame = ByteBuffer.allocate(sLen + 8)
            .order(ByteOrder.BIG_ENDIAN)
            .put(VietMapH1Decoder.MAGIC_C3)
            .putInt(sLen)
            .putInt(1700000000)
            .put(speedPayload)
            .putInt(0).putInt(0)
            .array()
        session.feed(speedFrame)
        assertEquals(null, repo.hudState.value.speed) // Heartbeat does not force 0

        // 2. Send idle navigation packet with mock driving speed 124 km/h (Opcode 0x40 with dir=0xFE, turnDist=-1, speed=124)
        // 40 FE 00000001 01 FFFFFFFF 00 00 0000 00 00 00000000 007C
        val navPayloadBuf = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN)
        navPayloadBuf.put(0x40.toByte())
        navPayloadBuf.put(0xFE.toByte()) // direction = 0xFE (idle)
        navPayloadBuf.putInt(1) // img len = 1
        navPayloadBuf.put(0x01.toByte()) // img dummy
        navPayloadBuf.putInt(-1) // turn dist = -1
        navPayloadBuf.put(0.toByte()) // next road len = 0
        navPayloadBuf.put(0.toByte()) // cur road len = 0
        navPayloadBuf.putShort(0) // hours = 0
        navPayloadBuf.put(0.toByte()) // mins = 0
        navPayloadBuf.put(0.toByte()) // secs = 0
        navPayloadBuf.putInt(0) // remain distance = 0
        navPayloadBuf.putShort(124) // mock driving speed = 124 km/h

        val navPayload = ByteArray(navPayloadBuf.position())
        navPayloadBuf.flip()
        navPayloadBuf.get(navPayload)

        val nLen = navPayload.size + 12
        val navFrame = ByteBuffer.allocate(nLen + 8)
            .order(ByteOrder.BIG_ENDIAN)
            .put(VietMapH1Decoder.MAGIC_C3)
            .putInt(nLen)
            .putInt(1700000000)
            .put(navPayload)
            .putInt(0).putInt(0)
            .array()

        session.feed(navFrame)

        val state = repo.hudState.value
        assertEquals("Tốc độ xe phải là 124 km/h", 124, state.speed)
        assertEquals("Không được bật chế độ dẫn đường", false, state.navigating)
        assertEquals("Không có rẽ", TurnType.NONE, state.turn)
        assertEquals(null, state.distanceMeters)
    }

    @Test
    fun testNavigationActiveStraightFromVmlLog() {
        val repo = HudRepository()
        val session = VietMapH1ReceiverSession(repo)
        session.onConnected()

        // Exact 72B frame from user log:
        // A55A37C3000000406A98F06F40FE0000000101000014B41048E1BB932056C4836E2043E1BB916E670D4CC3AA204368C3AD2044C3A26E000330100002F9A3007C7263E6E062503272
        val hex = "A55A37C3000000406A98F06F40FE0000000101000014B41048E1BB932056C4836E2043E1BB916E670D4CC3AA204368C3AD2044C3A26E000330100002F9A3007C7263E6E062503272"
        val bytes = ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

        session.feed(bytes)

        val state = repo.hudState.value
        assertEquals("Phải ở trạng thái dẫn đường", true, state.navigating)
        assertEquals("Hướng rẽ là chếch phải", TurnType.SLIGHT_RIGHT, state.turn)
        assertEquals("Khoảng cách rẽ là 5300m", 5300, state.distanceMeters)
        assertEquals("Đường kế tiếp là Hồ Văn Cống", "Hồ Văn Cống", state.nextStreet)
        assertEquals("Đường hiện tại là Lê Chí Dân", "Lê Chí Dân", state.street)
        assertEquals("Tốc độ xe là 124 km/h", 124, state.speed)
        assertEquals("Quãng đường còn lại là 194979m", 194979, state.remainingMeters)
        assertEquals("Thời gian còn lại là 228 phút (3h48m)", 228, state.remainingMinutes)
    }

    @Test
    fun testAlertFilteringFromVmlLog() {
        val repo = HudRepository()
        val session = VietMapH1ReceiverSession(repo)
        session.onConnected()

        // Exact 29B alert frame from user log:
        // A55A37C3000000156A98F191010186A00000006478A72987B2A9895314
        val hex = "A55A37C3000000156A98F191010186A00000006478A72987B2A9895314"
        val bytes = ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

        session.feed(bytes)

        val state = repo.hudState.value
        // Out-of-range sentinel (0x86A0) and type 0 (none) must be filtered out:
        assertTrue("Không được có cảnh báo ma nào trên màn hình", state.alerts.isEmpty())
        assertEquals("Giới hạn tốc độ phải được cập nhật thành 120 km/h", 120, state.speedLimit)
    }

    @Test
    fun testPenaltyCameraFromVmlLog() {
        val repo = HudRepository()
        val session = VietMapH1ReceiverSession(repo)
        session.onConnected()

        // Exact 29B penalty camera frame from user log:
        // A55A37C3000000156A98F1C20101018700000064788176E73204416B49
        val hex = "A55A37C3000000156A98F1C20101018700000064788176E73204416B49"
        val bytes = ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

        session.feed(bytes)

        val state = repo.hudState.value
        assertEquals("Phải nhận đúng 1 camera cảnh báo", 1, state.alerts.size)
        val cam = state.alerts[0]
        assertEquals("Loại camera phải là Phone Camera (type 40)", 40, cam.type)
        assertEquals("Khoảng cách tới camera là 391m (0x0187)", 391, cam.distanceMeters)
        assertNull("Không hiển thị số tốc độ trên badge camera", cam.value)
        assertEquals("Giới hạn tốc độ đường là 120 km/h", 120, state.speedLimit)
    }

    @Test
    fun testParseC3UnsupportedOpcode() {
        // Unknown opcode 0x99 (e.g. OBD or custom vendor command)
        val payload = byteArrayOf(0x99.toByte(), 0x11, 0x22, 0x33)
        val declaredLen = payload.size + 12
        val frame = ByteBuffer.allocate(declaredLen + 8)
            .order(ByteOrder.BIG_ENDIAN)
            .put(VietMapH1Decoder.MAGIC_C3)
            .putInt(declaredLen)
            .putInt(1700000000)
            .put(payload)
            .putInt(0)
            .putInt(0)
            .array()

        val decoded = VietMapH1Decoder.parseFrame(frame)
        assertNotNull(decoded)
        assertTrue(decoded is VietMapH1Decoder.DecodedMessage.UnsupportedPayload)
        val unsupported = decoded as VietMapH1Decoder.DecodedMessage.UnsupportedPayload
        assertEquals("C3", unsupported.frameKind)
        assertEquals(0x99, unsupported.opcode)
        assertEquals(4, unsupported.length)
        assertTrue(unsupported.payloadHex.startsWith("99112233"))
    }

    @Test
    fun testReceiverSessionStreaming() {
        val repo = HudRepository()
        val session = VietMapH1ReceiverSession(repo)
        session.onConnected()

        // Send speed in two separate chunks to test streaming re-assembly
        val payload = byteArrayOf(0x02, 0x00, 0x50) // 80 km/h
        val declaredLen = payload.size + 12
        val frame = ByteBuffer.allocate(declaredLen + 8)
            .order(ByteOrder.BIG_ENDIAN)
            .put(VietMapH1Decoder.MAGIC_C3)
            .putInt(declaredLen)
            .putInt(1700000000)
            .put(payload)
            .putInt(0)
            .putInt(0)
            .array()

        val part1 = frame.copyOfRange(0, 10)
        val part2 = frame.copyOfRange(10, frame.size)

        session.feed(part1)
        assertEquals(null, repo.hudState.value.speed) // not yet complete

        session.feed(part2)
        assertEquals(80, repo.hudState.value.speed) // processed!
    }

    @Test
    fun testReceiverSessionLogsUnsupportedPayload() {
        val repo = HudRepository()
        val session = VietMapH1ReceiverSession(repo)
        session.onConnected()

        // Send an unsupported frame opcode 0x7E
        val payload = byteArrayOf(0x7E.toByte(), 0xAA.toByte(), 0xBB.toByte())
        val declaredLen = payload.size + 12
        val frame = ByteBuffer.allocate(declaredLen + 8)
            .order(ByteOrder.BIG_ENDIAN)
            .put(VietMapH1Decoder.MAGIC_C3)
            .putInt(declaredLen)
            .putInt(1700000000)
            .put(payload)
            .putInt(0)
            .putInt(0)
            .array()

        session.feed(frame)
        val events = repo.events.value
        val hasUnsupportedLog = events.any { it.category == "VML-Unsupported" && it.message.contains("0x7E") }
        assertTrue("Cần có log category VML-Unsupported cho opcode 0x7E", hasUnsupportedLog)
    }

    @Test
    fun testDeviceInfoHandshakeQuery() {
        val repo = HudRepository()
        val session = VietMapH1ReceiverSession(repo, "2.2.2")
        session.onConnected()

        // Test querying 0x0E 0x00 from VML
        val query = byteArrayOf(0x0E.toByte(), 0x00)
        val replies = session.feed(query)
        assertEquals(1, replies.size)

        val replyFrame = replies[0]
        assertTrue(replyFrame.size > 20)
        // Verify frame starts with MAGIC_C3
        assertTrue(replyFrame[0] == 0xA5.toByte() && replyFrame[1] == 0x5A.toByte() && replyFrame[2] == 0x37.toByte() && replyFrame[3] == 0xC3.toByte())

        // Verify payload starts with opcode 0x0E and contains PROTOCOL:2.2.2
        val payload = replyFrame.copyOfRange(12, replyFrame.size - 8)
        assertEquals(0x0E.toByte(), payload[0])
        val text = String(payload, 1, payload.size - 1, Charsets.UTF_8)
        assertTrue(text.contains("MODEL:H1N"))
        assertTrue(text.contains("PROTOCOL:2.2.2"))
    }

    @Test
    fun testSmoothRoadName() {
        assertEquals("Cao Tốc Phan Thiết - Dầu Giây", VietMapH1Decoder.smoothRoadName("Cao Tốc Phan Thiết D"))
        assertEquals("Cao Tốc Phan Thiết - Dầu Giây", VietMapH1Decoder.smoothRoadName("Cao Tốc Phan Thiết Dầu"))
        assertEquals("Cao Tốc TP.HCM - Long Thành - Dầu Giây", VietMapH1Decoder.smoothRoadName("Cao Tốc Hồ Chí Minh "))
        assertEquals("Đi Phan Thiết", VietMapH1Decoder.smoothRoadName("Đi Phan Thiết"))
        assertEquals("Lê Chí Dân", VietMapH1Decoder.smoothRoadName("Lê Chí Dân"))
    }

    @Test
    fun testNavigationRoadNameSmoothingFromVmlLog() {
        val repo = HudRepository()
        val session = VietMapH1ReceiverSession(repo)
        session.onConnected()

        // Exact 112B stream containing 29B camera frame + 83B navigation frame:
        // A55A37C30000004B6A98F54E40FE00000001010001531010C49069205068616E20546869E1BABF741843616F2054E1BB9163205068616E20546869E1BABF7420440001182D00019ACD006928C924F3B8B8F098
        val hex = "A55A37C30000004B6A98F54E40FE00000001010001531010C49069205068616E20546869E1BABF741843616F2054E1BB9163205068616E20546869E1BABF7420440001182D00019ACD006928C924F3B8B8F098"
        val bytes = ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

        session.feed(bytes)

        val state = repo.hudState.value
        assertEquals("Hướng rẽ là SLIGHT_RIGHT", TurnType.SLIGHT_RIGHT, state.turn)
        assertEquals("Khoảng cách rẽ là 86800m (0x00015310)", 86800, state.distanceMeters)
        assertEquals("Đường kế tiếp là 'Đi Phan Thiết'", "Đi Phan Thiết", state.nextStreet)
        assertEquals(
            "Đường hiện tại phải được mở rộng đầy đủ thành 'Cao Tốc Phan Thiết - Dầu Giây'",
            "Cao Tốc Phan Thiết - Dầu Giây",
            state.street,
        )
    }
}
