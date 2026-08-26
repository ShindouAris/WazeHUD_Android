package com.chisadin.hudwz.protocol

import com.chisadin.hudwz.domain.TransportType
import com.chisadin.hudwz.domain.TurnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlpProtocolTest {
    private val protocol = HlpProtocol()

    @Test
    fun parsesHlpStateWithLanesAndAlerts() {
        val message = protocol.parse(
            """{"v":1,"t":"s","nav":1,"spd":85,"lim":80,"over":1,"trn":3,"trn2":2,"dst":350,"lan":[[5,4],[1,1]],"st":"QL1A","st2":"Nguyen Ai Quoc","eta":"09:32","rmin":24,"rm":18500,"alrs":[{"k":2,"d":300}],"ts":42}""",
            sessionId = 7,
            receivedAtElapsedMs = 100,
        ) as HlpProtocol.Message.State
        assertEquals(85, message.value.speed)
        assertEquals(80, message.value.speedLimit)
        assertEquals(TurnType.RIGHT, message.value.turn)
        assertEquals(2, message.value.lanes.size)
        assertEquals(4, message.value.lanes.first().selectedMask)
        assertEquals(2, message.value.alerts.single().type)
        assertEquals(7L, message.value.sessionId)
    }

    @Test
    fun parsesFriendlyStateWithoutEnvelope() {
        val message = protocol.parse(
            """{"speed":85,"speedLimit":80,"distance":350,"turn":"right","nextTurn":"left","street":"QL1A","remainingMinutes":24,"remainingKm":18.5,"gps":true,"overspeed":true}""",
            receivedAtElapsedMs = 100,
        ) as HlpProtocol.Message.State
        assertEquals(TurnType.RIGHT, message.value.turn)
        assertEquals(TurnType.LEFT, message.value.nextTurn)
        assertEquals(18_500, message.value.remainingMeters)
        assertTrue(message.value.overspeed)
    }

    @Test
    fun declarationFitsWireLimitAndRequestsOptionalFieldsExplicitly() {
        val frame = protocol.deviceDeclaration(TransportType.BLE)
        assertTrue(frame.size <= 512)
        assertEquals('\n'.code.toByte(), frame.last())
        val text = frame.decodeToString()
        assertTrue(text.contains("\"alrs\""))
        assertTrue(text.contains("\"lan\""))
    }
}
