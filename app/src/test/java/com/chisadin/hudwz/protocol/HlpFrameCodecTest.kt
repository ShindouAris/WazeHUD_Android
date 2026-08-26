package com.chisadin.hudwz.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlpFrameCodecTest {
    @Test
    fun reconstructsSplitFramesAndMultipleLines() {
        val codec = HlpFrameCodec()
        assertTrue(codec.feed("{\"v\":1,".encodeToByteArray()).isEmpty())
        val results = codec.feed("\"t\":\"pong\"}\r\n{\"v\":1,\"t\":\"ping\"}\n".encodeToByteArray())
        assertEquals(2, results.size)
        assertEquals("{\"v\":1,\"t\":\"pong\"}", (results[0] as HlpFrameCodec.Result.Frame).value)
        assertEquals("{\"v\":1,\"t\":\"ping\"}", (results[1] as HlpFrameCodec.Result.Frame).value)
    }

    @Test
    fun dropsOversizedFrameAndResynchronizesAtLf() {
        val codec = HlpFrameCodec()
        val results = codec.feed(("x".repeat(512) + "\n{\"v\":1,\"t\":\"pong\"}\n").encodeToByteArray())
        assertEquals("FRAME_TOO_LARGE", (results[0] as HlpFrameCodec.Result.Error).reason)
        assertTrue(results[1] is HlpFrameCodec.Result.Frame)
    }

    @Test
    fun rejectsMalformedUtf8() {
        val codec = HlpFrameCodec()
        val results = codec.feed(byteArrayOf(0xC3.toByte(), 0x28, '\n'.code.toByte()))
        assertEquals("MALFORMED_UTF8", (results.single() as HlpFrameCodec.Result.Error).reason)
    }
}
