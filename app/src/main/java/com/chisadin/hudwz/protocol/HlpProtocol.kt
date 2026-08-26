package com.chisadin.hudwz.protocol

import com.chisadin.hudwz.domain.HudAlert
import com.chisadin.hudwz.domain.HudState
import com.chisadin.hudwz.domain.LaneGuidance
import com.chisadin.hudwz.domain.TransportType
import com.chisadin.hudwz.domain.TurnType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class HlpProtocol(private val json: Json = Json { ignoreUnknownKeys = true }) {
    sealed interface Message {
        data class State(val value: HudState) : Message
        data class Hello(
            val sessionId: Long,
            val rate: Int?,
            val fields: List<String>,
            val unixSeconds: Long?,
            val timezoneMinutes: Int?,
        ) : Message
        data class Error(val code: String, val detail: String?) : Message
        data object Ping : Message
        data object Pong : Message
        data object Bye : Message
        data class Other(val type: String) : Message
    }

    fun parse(line: String, sessionId: Long? = null, receivedAtElapsedMs: Long): Message {
        val root = json.parseToJsonElement(line).jsonObject
        val version = root.int("v") ?: 1
        require(version == 1) { "UNSUPPORTED_VERSION:$version" }
        return when (val type = root.string("t") ?: if (looksLikeFriendlyState(root)) "s" else "") {
            "s" -> Message.State(parseState(root, sessionId, receivedAtElapsedMs))
            "hi" -> Message.Hello(
                sessionId = root.long("sess") ?: 0,
                rate = root.int("rate"),
                fields = root["fields"].stringList(),
                unixSeconds = root.long("unix"),
                timezoneMinutes = root.int("tz"),
            )
            "ping" -> Message.Ping
            "pong" -> Message.Pong
            "bye" -> Message.Bye
            "error" -> Message.Error(root.string("code") ?: "UNKNOWN", root.string("detail"))
            else -> Message.Other(type)
        }
    }

    fun deviceDeclaration(transport: TransportType): ByteArray {
        val transportName = when (transport) {
            TransportType.BLE -> "ble"
            TransportType.CLASSIC -> "spp"
            TransportType.AUTO -> "auto"
        }
        val objectValue = buildJsonObject {
            put("v", 1)
            put("t", "dev")
            put("name", "Android HUD")
            put("fw", "1.0.0")
            put("proto", buildJsonArray { add(JsonPrimitive(1)) })
            put("can", buildJsonArray {
                listOf("speed", "limit", "turn", "lanes", "street", "eta", "avgzone", "alerts")
                    .forEach { add(JsonPrimitive(it)) }
            })
            put("want", buildJsonObject {
                put("rate", if (transport == TransportType.BLE) 4 else 10)
                put("fields", buildJsonArray {
                    STATE_FIELDS.forEach { add(JsonPrimitive(it)) }
                })
            })
            put("transport", transportName)
        }
        return (objectValue.toString() + "\n").encodeToByteArray()
    }

    fun pong(): ByteArray = "{\"v\":1,\"t\":\"pong\"}\n".encodeToByteArray()

    fun ping(elapsedMs: Long): ByteArray =
        "{\"v\":1,\"t\":\"ping\",\"ts\":$elapsedMs}\n".encodeToByteArray()

    private fun parseState(root: JsonObject, sessionId: Long?, receivedAt: Long): HudState {
        val speed = root.int("spd") ?: root.int("speed")
        val limit = root.int("lim") ?: root.int("speedLimit")
        val turn = root.int("trn")?.let(TurnType::fromCode)
            ?: root.string("turn").toTurnType()
        val nextTurn = root.int("trn2")?.let(TurnType::fromCode)
            ?: root.string("nextTurn").toTurnType()
        val remainingKm = root.double("rkm") ?: root.double("remainingKm")
        val remainingMeters = root.int("rm")
            ?: remainingKm?.let { (it * 1000.0).toInt() }
        val alerts = parseAlerts(root)
        return HudState(
            navigating = root.bool("nav") ?: (root.int("nav") == 1),
            speed = speed?.coerceAtLeast(0),
            speedLimit = limit?.takeIf { it > 0 },
            overspeed = root.bool("overspeed") ?: root.bool("over") ?: (root.int("over") == 1),
            distanceMeters = (root.int("dst") ?: root.int("distance"))?.takeIf { it >= 0 },
            turn = turn,
            nextTurn = nextTurn,
            roundaboutExit = root.int("exit")?.takeIf { it > 0 },
            lanes = parseLanes(root["lan"]),
            street = root.string("st") ?: root.string("street"),
            nextStreet = root.string("st2") ?: root.string("nextStreet"),
            eta = root.string("eta"),
            remainingMinutes = root.int("rmin") ?: root.int("remainingMinutes"),
            remainingMeters = remainingMeters?.takeIf { it >= 0 },
            remainingKm = remainingKm,
            gpsAvailable = root.bool("gps") ?: true,
            noPassingZone = root.bool("avg") ?: (root.int("avg") == 1),
            zoneRemainingMeters = root.int("avgL")?.takeIf { it > 0 },
            zoneRecommendedSpeed = root.int("avgR")?.takeIf { it > 0 },
            zoneProgress = root.int("avgP")?.coerceIn(0, 100),
            alerts = alerts,
            connected = true,
            sessionId = sessionId,
            sourceTimestampMs = root.long("ts") ?: 0,
            receivedAtElapsedMs = receivedAt,
        )
    }

    private fun parseAlerts(root: JsonObject): List<HudAlert> {
        val list = root["alrs"] as? JsonArray
        if (list != null) return list.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val type = item.int("k") ?: return@mapNotNull null
            val distance = item.int("d") ?: -1
            if (distance < 0) null else HudAlert(
                type = type,
                distanceMeters = distance,
                value = item.int("v"),
                severity = item.int("s"),
                delayMinutes = item.int("m"),
            )
        }
        val type = root.int("alr") ?: 0
        val distance = root.int("alrD") ?: -1
        return if (type > 0 && distance >= 0) listOf(
            HudAlert(type, distance, root.int("alrV"), root.int("alrS"), root.int("alrM")),
        ) else emptyList()
    }

    private fun parseLanes(element: JsonElement?): List<LaneGuidance> =
        (element as? JsonArray).orEmpty().mapNotNull { lane ->
            val values = lane as? JsonArray ?: return@mapNotNull null
            if (values.size < 2) return@mapNotNull null
            val directions = values[0].jsonPrimitive.intOrNull ?: return@mapNotNull null
            val selected = values[1].jsonPrimitive.intOrNull ?: return@mapNotNull null
            LaneGuidance(directions, selected and directions)
        }.take(12)

    private fun looksLikeFriendlyState(root: JsonObject): Boolean =
        root.containsKey("speed") || root.containsKey("speedLimit") || root.containsKey("turn")

    private fun String?.toTurnType(): TurnType = when (this?.lowercase()) {
        "straight", "continue" -> TurnType.CONTINUE
        "left" -> TurnType.LEFT
        "right" -> TurnType.RIGHT
        "slight_left" -> TurnType.SLIGHT_LEFT
        "slight_right" -> TurnType.SLIGHT_RIGHT
        "sharp_left" -> TurnType.SHARP_LEFT
        "sharp_right" -> TurnType.SHARP_RIGHT
        "u_turn" -> TurnType.U_TURN
        "keep_left" -> TurnType.KEEP_LEFT
        "keep_right" -> TurnType.KEEP_RIGHT
        "exit_left" -> TurnType.EXIT_LEFT
        "exit_right" -> TurnType.EXIT_RIGHT
        "arrive" -> TurnType.ARRIVE
        else -> TurnType.NONE
    }

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
    private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
    private fun JsonElement?.stringList(): List<String> = runCatching {
        this?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    }.getOrDefault(emptyList())

    companion object {
        val STATE_FIELDS = listOf(
            "nav", "spd", "lim", "over", "trn", "trn2", "dst", "exit", "lan",
            "st", "st2", "eta", "rmin", "rm", "rkm", "avg", "avgL", "avgR",
            "avgP", "alr", "alrD", "alrV", "alrS", "alrM", "alrs",
        )
    }
}
