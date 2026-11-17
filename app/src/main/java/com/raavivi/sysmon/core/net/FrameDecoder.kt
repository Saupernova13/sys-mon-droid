package com.raavivi.sysmon.core.net

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Turns WebSocket frames into JSON text. Text frames pass through; binary frames are raw DEFLATE
 * (no zlib header) on ONE compression stream per socket, each a sync flush with its trailing
 * `00 00 ff ff` removed. Keep one instance per socket and call [close] when it ends.
 */
class FrameDecoder {
    private val inflater = Inflater(true)
    private val buf = ByteArray(16 * 1024)

    fun text(text: String): String = text

    /** The frame's JSON text, or null when the frame is malformed (the stream then stays unusable). */
    @Synchronized
    fun binary(frame: ByteArray): String? = try {
        inflater.setInput(frame + TAIL)
        val out = ByteArrayOutputStream(frame.size * 8)
        while (true) {
            val n = inflater.inflate(buf)
            if (n == 0) break
            out.write(buf, 0, n)
        }
        String(out.toByteArray(), Charsets.UTF_8) // toString(Charset) is API 33; minSdk is 26
    } catch (_: Exception) {
        null
    }

    @Synchronized
    fun close() = inflater.end()

    private companion object {
        val TAIL = byteArrayOf(0, 0, 0xff.toByte(), 0xff.toByte())
    }
}
