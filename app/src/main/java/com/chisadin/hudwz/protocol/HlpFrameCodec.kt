package com.chisadin.hudwz.protocol

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class HlpFrameCodec(private val maxFrameBytes: Int = 512) {
    sealed interface Result {
        data class Frame(val value: String) : Result
        data class Error(val reason: String) : Result
    }

    private val payload = ByteArray(maxFrameBytes - 1)
    private var length = 0
    private var overflow = false

    @Synchronized
    fun feed(bytes: ByteArray): List<Result> {
        val output = ArrayList<Result>()
        bytes.forEach { byte ->
            if (byte == '\n'.code.toByte()) {
                if (overflow) {
                    output += Result.Error("FRAME_TOO_LARGE")
                } else {
                    var end = length
                    if (end > 0 && payload[end - 1] == '\r'.code.toByte()) end--
                    decode(payload, end)?.let { output += Result.Frame(it) }
                        ?: run { output += Result.Error("MALFORMED_UTF8") }
                }
                length = 0
                overflow = false
            } else if (!overflow) {
                if (length >= payload.size) overflow = true else payload[length++] = byte
            }
        }
        return output
    }

    @Synchronized
    fun reset() {
        length = 0
        overflow = false
    }

    private fun decode(bytes: ByteArray, size: Int): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, size))
            .toString()
    }.getOrNull()
}
