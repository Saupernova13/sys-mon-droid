package com.raavivi.sysmon.ui.whatsapp

import com.raavivi.sysmon.core.model.WaMessage
import java.time.Instant
import java.util.Locale

/** Pure helpers for the WhatsApp screen, kept free of Android types so they unit-test on the JVM. */

/** Epoch seconds (fractional) as the RFC3339 instant the server's `before=` cursor expects. */
fun waBeforeCursor(tsSeconds: Double): String =
    Instant.ofEpochSecond(tsSeconds.toLong()).toString()

/** Rows per messages page. A page shorter than this means the server has no more in that direction. */
const val WA_PAGE_LIMIT = 50

/**
 * wacli compares cursors on whole seconds, strictly, so [waBeforeCursor] skips the other rows
 * sharing the boundary second. One second past it makes `before` inclusive; callers dedupe by id.
 */
fun waInclusiveBefore(tsSeconds: Double): String =
    Instant.ofEpochSecond(tsSeconds.toLong() + 1).toString()

/** Mirror of [waInclusiveBefore] for `after=`: one second before the boundary second. */
fun waInclusiveAfter(tsSeconds: Double): String =
    Instant.ofEpochSecond(tsSeconds.toLong() - 1).toString()

/** How a page fetch ended: more may follow, the end was reached, or nothing new came back (cursor stuck). */
enum class PageOutcome { MORE, END, STALLED }

/** [raw] rows returned, [added] of them new to the list. A short page is the end even if it added nothing. */
fun pageOutcome(raw: Int, added: Int, limit: Int): PageOutcome = when {
    raw < limit -> PageOutcome.END
    added == 0 -> PageOutcome.STALLED
    else -> PageOutcome.MORE
}

/**
 * [current] plus the rows of [page] not already in it, sorted by time, and how many were new.
 * Only non-blank ids count as duplicates; rows without an id are always kept.
 */
fun mergeMessages(current: List<WaMessage>, page: List<WaMessage>): Pair<List<WaMessage>, Int> {
    val seen = current.mapNotNullTo(HashSet()) { it.id.takeIf { id -> id.isNotBlank() } }
    val fresh = page.filter { it.id.isBlank() || seen.add(it.id) }
    return (current + fresh).sortedBy { it.ts } to fresh.size
}

/** WhatsApp keeps media fetchable for roughly this long. */
private const val WA_MEDIA_FRESH_SECONDS = 30 * 24 * 3600.0

/**
 * Whether a bubble may load its image on its own. Older media that is not on the PC almost always
 * fails to download, and each attempt bounces the live sync, so those wait for a tap.
 */
fun shouldAutoLoadImage(downloaded: Boolean, tsSeconds: Double, nowSeconds: Double): Boolean =
    downloaded || nowSeconds - tsSeconds <= WA_MEDIA_FRESH_SECONDS

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
