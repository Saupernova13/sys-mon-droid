package com.raavivi.sysmon.ui.whatsapp

import java.time.Instant
import java.util.Locale

/** Pure helpers for the WhatsApp screen, kept free of Android types so they unit-test on the JVM. */

/** Epoch seconds (fractional) as the RFC3339 instant the server's `before=` cursor expects. */
fun waBeforeCursor(tsSeconds: Double): String =
    Instant.ofEpochSecond(tsSeconds.toLong()).toString()

/** Whether a message's media type is a voice note / audio clip. */
fun isAudioMedia(mediaType: String?): Boolean {
    val t = mediaType ?: return false
    return t.contains("audio", true) || t.contains("ptt", true) || t.contains("voice", true)
}

/** Milliseconds as `m:ss` (minutes keep counting past 59). Negative values clamp to 0:00. */
fun formatClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
}

val VOICE_SPEEDS = listOf(1f, 1.5f, 2f)

/** The next playback speed in the 1x -> 1.5x -> 2x -> 1x cycle; unknown values restart at 1x. */
fun nextSpeed(current: Float): Float {
    val i = VOICE_SPEEDS.indexOfFirst { it == current }
    return if (i < 0) VOICE_SPEEDS[0] else VOICE_SPEEDS[(i + 1) % VOICE_SPEEDS.size]
}

fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"
