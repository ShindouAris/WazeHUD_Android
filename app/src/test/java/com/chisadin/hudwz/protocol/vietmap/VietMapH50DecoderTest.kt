package com.chisadin.hudwz.protocol.vietmap

import com.chisadin.hudwz.data.HudRepository
import com.chisadin.hudwz.domain.TurnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VietMapH50DecoderTest {

    @Test
    fun testBuildAndFindFrame() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val frame = VietMapH50Decoder.buildFrame(VietMapH50Decoder.CMD_SEND_SPEED_TWO, payload)

        assertTrue(frame.size >= 10)
        assertEquals(0xA6.toByte(), frame[0])
        assertEquals(0x6A.toByte(), frame[1])
        assertEquals(0x0D.toByte(), frame[frame.size - 2])
        assertEquals(0x0A.toByte(), frame[frame.size - 1])

        // Thêm vài byte rác trước và sau frame để kiểm tra findFrame
        val noiseData = byteArrayOf(0x00, 0xFF.toByte()) + frame + byteArrayOf(0x11, 0x22)
        val pos = VietMapH50Decoder.findFrame(noiseData, 0, noiseData.size)

        assertNotNull(pos)
        assertEquals(2, pos!!.first)
        assertEquals(2 + frame.size - 1, pos.second)
    }

    @Test
    fun testSpeedTwo() {
        val repo = HudRepository()
        val session = VietMapH50ReceiverSession(repo)

        val road = "QL1".toByteArray(Charsets.UTF_8)
        val payload = byteArrayOf(80, 0, 0, 1, road.size.toByte()) + road
        val frame = VietMapH50Decoder.buildFrame(VietMapH50Decoder.CMD_SEND_SPEED_TWO, payload)

        val decoded = VietMapH50Decoder.parseFrame(frame)
        assertNotNull(decoded)
        assertTrue(decoded is VietMapH50Decoder.DecodedMessage.SpeedTwo)
        val speedMsg = decoded as VietMapH50Decoder.DecodedMessage.SpeedTwo
        assertEquals(80, speedMsg.currentLimitKmh)
        assertEquals("QL1", speedMsg.currentRoad)

        session.feed(frame)
        val state = repo.hudState.value
        assertNull(state.speed)
        assertEquals(80, state.speedLimit)
        assertEquals("QL1", state.street)
    }

    @Test
    fun testLaneInfoGuidance() {
        val repo = HudRepository()
        val session = VietMapH50ReceiverSession(repo)

        // visible + laneCount + 9 centered H50 lane-type slots.
        val payload = byteArrayOf(
            1, 3,
            0, 0, 0,
            1, 5, 4,
            0, 0, 0,
        )
        val frame = VietMapH50Decoder.buildFrame(VietMapH50Decoder.CMD_SEND_LANE_INFO, payload)

        val decoded = VietMapH50Decoder.parseFrame(frame)
        assertNotNull(decoded)
        assertTrue(decoded is VietMapH50Decoder.DecodedMessage.LaneInfo)
        val laneMsg = decoded as VietMapH50Decoder.DecodedMessage.LaneInfo
        assertEquals(3, laneMsg.lanes.size)
        assertTrue(laneMsg.visible)
        assertEquals(4, laneMsg.lanes[0].directionsMask)  // left = canonical bit2
        assertEquals(1, laneMsg.lanes[1].directionsMask)  // straight = canonical bit0
        assertEquals(32, laneMsg.lanes[2].directionsMask) // right = canonical bit5

        session.feed(frame)
        val state = repo.hudState.value
        assertEquals(3, state.lanes.size)
        assertEquals(4, state.lanes[0].selectedMask)

        val withEmptyMiddle = byteArrayOf(
            1, 3,
            0, 0, 0,
            1, 0, 4,
            0, 0, 0,
        )
        val positional = VietMapH50Decoder.parseLaneInfo(withEmptyMiddle)
        assertEquals(3, positional.lanes.size)
        assertEquals(0, positional.lanes[1].directionsMask)
    }

    @Test
    fun testNavigationInfoFullRoadNames() {
        val repo = HudRepository()
        val session = VietMapH50ReceiverSession(repo)

        val nextRoad = "Cao Tốc Phan Thiết - Dầu Giây" // Tên đường dài > 24 bytes UTF-8!
        val nextBytes = nextRoad.toByteArray(Charsets.UTF_8)
        val payload = byteArrayOf(0x24, 0x00, 0x01, 0xF4.toByte(), 0x01, nextBytes.size.toByte()) + nextBytes
        val frame = VietMapH50Decoder.buildFrame(VietMapH50Decoder.CMD_NAVIGATION_INFO, payload)

        val decoded = VietMapH50Decoder.parseFrame(frame)
        assertNotNull(decoded)
        assertTrue(decoded is VietMapH50Decoder.DecodedMessage.NavigationInfo)
        val navMsg = decoded as VietMapH50Decoder.DecodedMessage.NavigationInfo
        assertEquals(TurnType.SLIGHT_RIGHT, navMsg.turnType)
        assertEquals(500, navMsg.distanceToTurnM)
        assertEquals("", navMsg.currentRoad)
        assertEquals(nextRoad, navMsg.nextRoad)

        session.feed(frame)
        val state = repo.hudState.value
        assertTrue("Đang dẫn đường", state.navigating)
        assertEquals(TurnType.SLIGHT_RIGHT, state.turn)
        assertEquals(500, state.distanceMeters)
        assertEquals("Tên đường kế tiếp đầy đủ không bị cắt", nextRoad, state.nextStreet)
    }

    @Test
    fun testUpcomingAlertsMapping() {
        val repo = HudRepository()
        val session = VietMapH50ReceiverSession(repo)

        // 1. Camera phạt nguội (H50 type 0x08) -> Phone Camera (40)
        val alert1 = VietMapH50Decoder.DecodedMessage.UpcomingAlert(alertType = 0x08, distanceM = 300)
        val hudAlert1 = VietMapH50Decoder.mapH50Alert(alert1)
        assertNotNull(hudAlert1)
        assertEquals(40, hudAlert1!!.type)
        assertEquals(300, hudAlert1.distanceMeters)
        assertNull(hudAlert1.value)

        // 2. Railway (H50 type 0x04) -> railroad (11)
        val alert4 = VietMapH50Decoder.DecodedMessage.UpcomingAlert(alertType = 4, distanceM = 200)
        val hudAlert4 = VietMapH50Decoder.mapH50Alert(alert4)
        assertNotNull(hudAlert4)
        assertEquals(11, hudAlert4!!.type)
        assertEquals(200, hudAlert4.distanceMeters)
        assertNull(hudAlert4.value)

        // 3. Camera bắn tốc độ (H50 type 0x0A) -> Speed Camera (2)
        val alert6 = VietMapH50Decoder.DecodedMessage.UpcomingAlert(alertType = 0x0A, distanceM = 450)
        val hudAlert6 = VietMapH50Decoder.mapH50Alert(alert6)
        assertNotNull(hudAlert6)
        assertEquals(2, hudAlert6!!.type)
        assertEquals(450, hudAlert6.distanceMeters)
        assertNull(hudAlert6.value)

        // 4. Trạm thu phí (H50 type 0x01) -> Toll booth (12)
        val alert10 = VietMapH50Decoder.DecodedMessage.UpcomingAlert(alertType = 0x01, distanceM = 1000)
        val hudAlert10 = VietMapH50Decoder.mapH50Alert(alert10)
        assertNotNull(hudAlert10)
        assertEquals(12, hudAlert10!!.type)

        // 5. Cảnh báo lạ chưa rõ -> Fallback Hazard (4)
        val alertUnknown = VietMapH50Decoder.DecodedMessage.UpcomingAlert(alertType = 99, distanceM = 150)
        val hudAlertUnknown = VietMapH50Decoder.mapH50Alert(alertUnknown)
        assertNotNull(hudAlertUnknown)
        assertEquals(4, hudAlertUnknown!!.type)

        // 6. Sentinel distance >= 10000 -> null
        val alertSentinel = VietMapH50Decoder.DecodedMessage.UpcomingAlert(alertType = 0x08, distanceM = 10000)
        assertNull(VietMapH50Decoder.mapH50Alert(alertSentinel))

        val nextLimit = VietMapH50Decoder.DecodedMessage.UpcomingAlert(0xF0, 868, 70)
        val nextLimitAlert = VietMapH50Decoder.mapH50Alert(nextLimit)
        assertEquals(8, nextLimitAlert?.type)
        assertEquals(70, nextLimitAlert?.value)
    }

    @Test
    fun testHandshakeQueriesResponse() {
        val repo = HudRepository()
        val session = VietMapH50ReceiverSession(repo, serialNumber = "H50-TEST12345", firmwareVersion = "H50-V2.0.1")

        // Gửi querySeri (0xFB)
        val querySeriFrame = VietMapH50Decoder.buildFrame(VietMapH50Decoder.CMD_QUERY_SERI, byteArrayOf())
        val repliesSeri = session.feed(querySeriFrame)
        assertEquals(1, repliesSeri.size)

        val seriMsg = VietMapH50Decoder.parseFrame(repliesSeri[0])
        assertNotNull(seriMsg)
        assertTrue(seriMsg is VietMapH50Decoder.DecodedMessage.SeriResponse)
        val rawSeri = (seriMsg as VietMapH50Decoder.DecodedMessage.SeriResponse)
        assertEquals("H50-TEST12345", rawSeri.seri)

        // Gửi queryVersion (0xFC)
        val queryVerFrame = VietMapH50Decoder.buildFrame(VietMapH50Decoder.CMD_QUERY_VERSION, byteArrayOf())
        val repliesVer = session.feed(queryVerFrame)
        assertEquals(1, repliesVer.size)

        val verMsg = VietMapH50Decoder.parseFrame(repliesVer[0])
        assertNotNull(verMsg)
        assertTrue(verMsg is VietMapH50Decoder.DecodedMessage.VersionResponse)
        val rawVer = (verMsg as VietMapH50Decoder.DecodedMessage.VersionResponse)
        assertEquals("H50-V2.0.1", rawVer.version)

        // VML completes discovery with queryOBDVersion after serial and firmware.
        val queryObdFrame = VietMapH50Decoder.buildEncryptedResponse(
            VietMapH50Decoder.CMD_QUERY_OBD_VERSION,
        )
        val repliesObd = session.feed(queryObdFrame)
        assertEquals(1, repliesObd.size)
        val obdPlain = VietMapH50Decoder.decrypt(repliesObd[0])
        assertNotNull(obdPlain)
        val obdMsg = VietMapH50Decoder.parseFrame(obdPlain!!)
        assertTrue(obdMsg is VietMapH50Decoder.DecodedMessage.RawCommand)
        assertEquals(
            "NONE;0.0",
            String((obdMsg as VietMapH50Decoder.DecodedMessage.RawCommand).payload, Charsets.UTF_8),
        )
    }

    @Test
    fun testEstimatedNavigation() {
        val repo = HudRepository()
        val session = VietMapH50ReceiverSession(repo)

        // 45,000m, ETA 01:38, progress 1.
        val payload = byteArrayOf(0x00, 0xAF.toByte(), 0xC8.toByte(), 0x01, 0x26, 0x01)
        val frame = VietMapH50Decoder.buildFrame(VietMapH50Decoder.CMD_EST_NAV_INFO, payload)
        session.feed(frame)

        val state = repo.hudState.value
        assertEquals(45.0, state.remainingKm ?: 0.0, 0.1)
        assertEquals(45000, state.remainingMeters)
        assertEquals("01:38", state.eta)
    }

    @Test
    fun testCapturedVmlNavigationAndEtaPayloads() {
        val navigationCiphertext = hexToBytes(
            "9088833E16B3B0FFEA2CCAFB826AC3C92B76713A84BC32D4ADD14975B8A4E26B986D4DD55DE7D3777561E95FC2462F2A",
        )
        val navigation = VietMapH50Decoder.parseFrame(navigationCiphertext)
        assertTrue(navigation is VietMapH50Decoder.DecodedMessage.NavigationInfo)
        navigation as VietMapH50Decoder.DecodedMessage.NavigationInfo
        assertEquals(TurnType.SLIGHT_RIGHT, navigation.turnType)
        assertEquals(550, navigation.distanceToTurnM)
        assertEquals("Đi Quốc Lộ 5", navigation.nextRoad)

        val etaCiphertext = hexToBytes(
            "96CB254655CBA5039CFBD7AC9EF802A4F3D5D614DAAA4E6CB9F852A11C6B8919",
        )
        val eta = VietMapH50Decoder.parseFrame(etaCiphertext)
        assertTrue(eta is VietMapH50Decoder.DecodedMessage.EstimatedNavigation)
        eta as VietMapH50Decoder.DecodedMessage.EstimatedNavigation
        assertEquals(33528L, eta.remainingDistanceMeters)
        assertEquals(1, eta.etaHour)
        assertEquals(11, eta.etaMinute)
        assertEquals(1, eta.progress)

        val forkRightCiphertext = hexToBytes(
            "820DE5990814B688641A89DF064282A3D98E697E91A9AE5B40171DDA7FEB1DB57FF2B1258822CBDD1A703599A1ECEFF9",
        )
        val forkRight = VietMapH50Decoder.parseFrame(forkRightCiphertext)
        assertTrue(forkRight is VietMapH50Decoder.DecodedMessage.NavigationInfo)
        forkRight as VietMapH50Decoder.DecodedMessage.NavigationInfo
        assertEquals(TurnType.KEEP_RIGHT, forkRight.turnType)
        assertEquals(4200, forkRight.distanceToTurnM)
        assertEquals("Đường Không Tên", forkRight.nextRoad)
    }

    @Test
    fun testCapturedLongRouteAndMultiAlertFrames() {
        val multiAlertCiphertext = hexToBytes(
            "C110ACFDD5495C91DF02FC20223960EB745632E218E0806215DB79B2C9BCB95A",
        )
        val decodedAlerts = VietMapH50Decoder.parseFrame(multiAlertCiphertext)
        assertTrue(decodedAlerts is VietMapH50Decoder.DecodedMessage.UpcomingAlerts)
        decodedAlerts as VietMapH50Decoder.DecodedMessage.UpcomingAlerts
        assertEquals(2, decodedAlerts.alerts.size)
        assertEquals(0xF0, decodedAlerts.alerts[0].alertType)
        assertEquals(1475, decodedAlerts.alerts[0].distanceM)
        assertEquals(100, decodedAlerts.alerts[0].speedLimitKmh)
        assertEquals(0x0A, decodedAlerts.alerts[1].alertType)
        assertEquals(391, decodedAlerts.alerts[1].distanceM)

        val nextLimit = VietMapH50Decoder.mapH50Alert(decodedAlerts.alerts[0])
        val speedCamera = VietMapH50Decoder.mapH50Alert(decodedAlerts.alerts[1])
        assertEquals(8, nextLimit?.type)
        assertEquals(100, nextLimit?.value)
        assertEquals(2, speedCamera?.type)
        assertNull(speedCamera?.value)

        val longRouteCiphertext = hexToBytes(
            "A2E3739EF4855206688643BB9C218E27980DD6E681466CBF5F5553E014AEBF57",
        )
        val longRoute = VietMapH50Decoder.parseFrame(longRouteCiphertext)
        assertTrue(longRoute is VietMapH50Decoder.DecodedMessage.EstimatedNavigation)
        longRoute as VietMapH50Decoder.DecodedMessage.EstimatedNavigation
        assertEquals(736032L, longRoute.remainingDistanceMeters)
        assertEquals(12, longRoute.etaHour)
        assertEquals(2, longRoute.etaMinute)

        val navigationCiphertext = hexToBytes(
            "70A8D7842950D37A05F7300658CE9B0D2CB0E2DC3E4F5116D2B12C9EACE4EB6F40B1A00E7F103C3B02648B131707FB1B",
        )
        val navigation = VietMapH50Decoder.parseFrame(navigationCiphertext)
        assertTrue(navigation is VietMapH50Decoder.DecodedMessage.NavigationInfo)
        navigation as VietMapH50Decoder.DecodedMessage.NavigationInfo
        assertEquals(TurnType.CONTINUE, navigation.turnType)
        assertEquals(21500, navigation.distanceToTurnM)
        assertEquals("Cao Tốc Cầu Giẽ Ninh B", navigation.nextRoad)
    }

    @Test
    fun testRealIPhoneEncryptedHandshakePackets() {
        val repo = HudRepository()
        val session = VietMapH50ReceiverSession(repo)

        // 1. Packet 1 captured from iPhone 14 Pro Max in Logcat: C0EAB7B660E181E4189F77B2B3306074
        val rawP1 = hexToBytes("C0EAB7B660E181E4189F77B2B3306074")
        val repliesP1 = session.feed(rawP1)

        assertEquals("Phải sinh ra đúng 1 frame phản hồi bắt tay", 1, repliesP1.size)
        val reply1 = repliesP1[0]
        assertEquals(32, reply1.size) // 2 block AES 16 bytes

        // Kiểm tra giải mã phản hồi
        val decReply1 = VietMapH50Decoder.decrypt(reply1)
        assertNotNull(decReply1)
        val parsedReply1 = VietMapH50Decoder.parseFrame(decReply1!!)
        assertNotNull(parsedReply1)
        assertTrue(parsedReply1 is VietMapH50Decoder.DecodedMessage.SeriResponse)
        assertEquals("CHISADIN-H50-0001", (parsedReply1 as VietMapH50Decoder.DecodedMessage.SeriResponse).seri)

        // 2. Packet 2 captured from iPhone 14 Pro Max in Logcat: 3A767990E6EE3046F99D206506C1DD60
        val rawP2 = hexToBytes("3A767990E6EE3046F99D206506C1DD60")
        val repliesP2 = session.feed(rawP2)
        assertEquals(1, repliesP2.size)
        val decReply2 = VietMapH50Decoder.decrypt(repliesP2[0])
        assertNotNull(decReply2)
        val parsedReply2 = VietMapH50Decoder.parseFrame(decReply2!!)
        assertTrue(parsedReply2 is VietMapH50Decoder.DecodedMessage.SeriResponse)
        assertEquals("CHISADIN-H50-0001", (parsedReply2 as VietMapH50Decoder.DecodedMessage.SeriResponse).seri)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            val index = i * 2
            result[i] = hex.substring(index, index + 2).toInt(16).toByte()
        }
        return result
    }
}
