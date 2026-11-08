package com.raavivi.sysmon.core.term

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CoderResult
import java.nio.charset.CodingErrorAction

/**
 * Decodes a byte stream split into arbitrary frames as UTF-8. An incomplete
 * trailing sequence is held back and completed by the next frame, so a
 * multi-byte character straddling two frames is not turned into U+FFFD.
 * Malformed input is replaced. Not thread-safe; use one instance per connection.
 */
class Utf8StreamDecoder {
    private val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    /** Bytes of an incomplete sequence carried over from the previous frame. */
    private var carry = ByteArray(0)

    fun decode(bytes: ByteArray): String {
        val input = ByteBuffer.wrap(carry + bytes)
        val out = CharBuffer.allocate(input.remaining() + 1)
        while (true) {
            val r: CoderResult = decoder.decode(input, out, false)
            if (r.isUnderflow) break
            // Overflow cannot happen (one char per byte at most), but never spin on it.
            if (r.isOverflow) break
        }
        carry = ByteArray(input.remaining()).also { input.get(it) }
        out.flip()
        return out.toString()
    }

    /** Drop any held-back bytes, e.g. on reconnect. */
    fun reset() {
        decoder.reset()
        carry = ByteArray(0)
    }
}
