package com.raavivi.sysmon

import com.raavivi.sysmon.core.net.FrameDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/** The server's `z=1` framing: one raw-DEFLATE stream per socket, sync-flushed, tail stripped. */
class FrameDecoderTest {

    /** Mimics the server: one Deflater for the whole socket, sync flush per frame, `00 00 ff ff` removed. */
    private class Sender {
        private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        private val buf = ByteArray(4096)

        fun frame(json: String): ByteArray {
            deflater.setInput(json.toByteArray(Charsets.UTF_8))
            val out = ByteArrayOutputStream()
            while (true) {
                val n = deflater.deflate(buf, 0, buf.size, Deflater.SYNC_FLUSH)
                out.write(buf, 0, n)
                if (n < buf.size) break
            }
            val all = out.toByteArray()
            return all.copyOf(all.size - 4)
        }
    }

    @Test
    fun inflatesSeveralFramesOnOneStream() {
        val sender = Sender()
        val decoder = FrameDecoder()
        val frames = listOf(
            """{"timestamp":1,"cpu":{"overall_pct":12.34}}""",
            """{"timestamp":2,"cpu":{"overall_pct":12.35}}""",
            """{"timestamp":3,"name":"café ✓ ${"x".repeat(5000)}"}""",
        )
        frames.forEach { assertEquals(it, decoder.binary(sender.frame(it))) }
        decoder.close()
    }

    @Test
    fun textFramesPassThrough() {
        val decoder = FrameDecoder()
        val sender = Sender()
        assertEquals("""{"a":1}""", decoder.text("""{"a":1}"""))
        assertEquals("""{"b":2}""", decoder.binary(sender.frame("""{"b":2}""")))
        assertEquals("""{"c":3}""", decoder.text("""{"c":3}"""))
        decoder.close()
    }

    @Test
    fun malformedFrameIsDropped() {
        val decoder = FrameDecoder()
        assertNull(decoder.binary(byteArrayOf(0x07, 0x7f, 0x33, 0x11)))
        decoder.close()
    }
}
