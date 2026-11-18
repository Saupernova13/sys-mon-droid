package com.raavivi.sysmon.ui.whatsapp

import kotlin.math.floor

/**
 * Pure text helpers for the WhatsApp screen (no Android or Compose types, so they unit-test on the
 * JVM): the message formatting parser, emoji-only detection and the two stable hashes (sender colour,
 * voice-note waveform). Mirrors sys-mon's frontend/js/features/whatsapp-format.js rule for rule, so the
 * web widget and this app agree on what a message looks like.
 */

enum class FmtKind {
    BOLD, ITALIC, STRIKE, CODE,

    /** A whole paragraph rendered as a monospace block. */
    CODE_BLOCK,

    /** A whole paragraph: a "> " quote line. */
    QUOTE,

    /** A whole paragraph: a bullet or numbered line. [FmtSpan.data] is its marker ("•", "12."). */
    LIST,

    /** [FmtSpan.data] is the absolute URL to open. */
    LINK,
}

/** A styled range of [WaFormatted.text]. Paragraph kinds (CODE_BLOCK, QUOTE, LIST) cover whole lines. */
data class FmtSpan(val kind: FmtKind, val start: Int, val end: Int, val data: String = "")

/**
 * The visible text (markers removed, bullets inserted, URLs verbatim) and the spans over it.
 * A newline is only emitted between two plain lines; paragraph kinds start and end their own line.
 */
data class WaFormatted(val text: String, val spans: List<FmtSpan>)

// A private-use char standing in for one URL while the styles are scanned, so nothing inside a URL is
// ever formatted (snake_case_urls) and a style may still wrap a link.
private const val PH = ''

// A link never ends on a style marker, so "*see http://x.com*" keeps the `*` outside the link.
private val URL_RE = Regex(
    """(https?://[^\s<]+[^\s<.,;:!?)\]}'"*_~])|(\bwww\.[^\s<]+[^\s<.,;:!?)\]}'"*_~])""",
    RegexOption.IGNORE_CASE,
)

private const val OPEN_BEFORE = "([{\"'"
private const val CLOSE_AFTER = ".,!?;:)]}\"'"
private const val MARKERS = "*_~`"

private fun isSpace(ch: Char?): Boolean = ch == null || ch.isWhitespace()

private val STYLE_OF = mapOf('*' to FmtKind.BOLD, '_' to FmtKind.ITALIC, '~' to FmtKind.STRIKE)

private class OpenMarker(val c: Char, val n: Int, val at: Int)

private fun hrefOf(raw: String): String = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw

/** One line: URLs, then styles, appended to [sb] / [spans] (absolute positions). */
private fun formatInline(line: String, sb: StringBuilder, spans: MutableList<FmtSpan>) {
    val links = ArrayList<String>()
    val plain = URL_RE.replace(line) { links.add(it.value); PH.toString() }
    var li = 0
    val stack = ArrayList<OpenMarker>()
    var openEnd = -1

    fun put(ch: Char, asLink: Boolean) {
        if (ch != PH) { sb.append(ch); return }
        val url = links[li++]
        val s = sb.length
        sb.append(url)
        if (asLink) spans.add(FmtSpan(FmtKind.LINK, s, sb.length, hrefOf(url)))
    }

    var i = 0
    while (i < plain.length) {
        val c = plain[i]
        if (!MARKERS.contains(c)) { put(c, true); i++; continue }
        var n = 1
        while (i + n < plain.length && plain[i + n] == c) n++
        val prev = plain.getOrNull(i - 1)
        val next = plain.getOrNull(i + n)
        val canOpen = next != null && !isSpace(next) &&
            (i == 0 || isSpace(prev) || OPEN_BEFORE.contains(prev!!) || i == openEnd)
        val canClose = prev != null && !isSpace(prev) &&
            (next == null || isSpace(next) || CLOSE_AFTER.contains(next) || MARKERS.contains(next))

        if (c == '`') {
            // Inline code: raw inside, one backtick each side.
            if (n == 1 && canOpen) {
                var k = i + 1
                var end = -1
                while (k < plain.length) {
                    if (plain[k] != '`') { k++; continue }
                    if (plain.getOrNull(k + 1) == '`') {   // a longer run is not a closer
                        while (plain.getOrNull(k + 1) == '`') k++
                        k++
                        continue
                    }
                    val p = plain[k - 1]
                    val q = plain.getOrNull(k + 1)
                    if (!isSpace(p) && (q == null || isSpace(q) || CLOSE_AFTER.contains(q) || MARKERS.contains(q))) {
                        end = k
                        break
                    }
                    k++
                }
                if (end > i + 1) {
                    val s = sb.length
                    for (j in i + 1 until end) put(plain[j], false)
                    spans.add(FmtSpan(FmtKind.CODE, s, sb.length))
                    i = end + 1
                    continue
                }
            }
            repeat(n) { sb.append('`') }
            i += n
            continue
        }

        if (n > 2) { repeat(n) { sb.append(c) }; i += n; continue }   // "***": rendered as typed

        var k = stack.lastIndex
        while (k >= 0 && !(stack[k].c == c && stack[k].n == n)) k--
        if (canClose && k >= 0 && sb.length > stack[k].at + stack[k].n) {
            val open = stack[k]
            sb.delete(open.at, open.at + open.n)   // the literal opener goes; everything after shifts left
            for (idx in spans.indices) {
                val sp = spans[idx]
                if (sp.start >= open.at) spans[idx] = sp.copy(start = sp.start - open.n, end = sp.end - open.n)
            }
            spans.add(FmtSpan(STYLE_OF.getValue(c), open.at, sb.length))
            stack.subList(k, stack.size).clear()
        } else if (canOpen) {
            stack.add(OpenMarker(c, n, sb.length))   // literal until (unless) it is closed
            repeat(n) { sb.append(c) }
            openEnd = i + n
        } else {
            repeat(n) { sb.append(c) }
        }
        i += n
    }
}

private val BULLET_RE = Regex("""^[-*] +(?=\S)""")
private val NUMBER_RE = Regex("""^(\d{1,3}[.)]) +(?=\S)""")

/** Appends one line; true when it became a paragraph kind (quote / list). */
private fun formatLine(line: String, sb: StringBuilder, spans: MutableList<FmtSpan>): Boolean {
    if (line.startsWith("> ")) {
        val s = sb.length
        formatInline(line.substring(2), sb, spans)
        spans.add(FmtSpan(FmtKind.QUOTE, s, sb.length))
        return true
    }
    val bullet = BULLET_RE.find(line)
    if (bullet != null) {
        val s = sb.length
        sb.append("• ")
        formatInline(line.substring(bullet.value.length), sb, spans)
        spans.add(FmtSpan(FmtKind.LIST, s, sb.length, "•"))
        return true
    }
    val number = NUMBER_RE.find(line)
    if (number != null) {
        val s = sb.length
        val marker = number.groupValues[1]
        sb.append(marker).append(' ')
        formatInline(line.substring(number.value.length), sb, spans)
        spans.add(FmtSpan(FmtKind.LIST, s, sb.length, marker))
        return true
    }
    formatInline(line, sb, spans)
    return false
}

private val CODE_BLOCK_RE = Regex("```([\\s\\S]*?)```")

private class Run(val code: Boolean, val s: String)

/** WhatsApp-style text -> visible text plus style spans. */
fun formatWaText(input: String?): WaFormatted {
    val s = (input ?: "").replace("\r\n", "\n").replace('\r', '\n').replace(PH.toString(), "").trimEnd()
    if (s.isEmpty()) return WaFormatted("", emptyList())

    // Split off ```blocks``` first: their insides are never formatted.
    val runs = ArrayList<Run>()
    var last = 0
    for (m in CODE_BLOCK_RE.findAll(s)) {
        val inner = m.groupValues[1]
        if (inner.isBlank()) continue
        runs.add(Run(false, s.substring(last, m.range.first)))
        runs.add(Run(true, inner.removePrefix("\n").removeSuffix("\n")))
        last = m.range.last + 1
    }
    runs.add(Run(false, s.substring(last)))

    val sb = StringBuilder()
    val spans = ArrayList<FmtSpan>()
    var first = true
    var prevBlock = true

    fun joiner(block: Boolean) {
        if (!first && !prevBlock && !block) sb.append('\n')
        first = false
        prevBlock = block
    }

    runs.forEachIndexed { idx, run ->
        if (run.code) {
            joiner(true)
            val st = sb.length
            sb.append(run.s)
            spans.add(FmtSpan(FmtKind.CODE_BLOCK, st, sb.length))
            return@forEachIndexed
        }
        var t = run.s
        if (idx > 0 && runs[idx - 1].code) t = t.removePrefix("\n")   // the block already ends its line
        if (runs.getOrNull(idx + 1)?.code == true) t = t.removeSuffix("\n")
        if (t.isEmpty() && (idx > 0 || runs.size > 1)) return@forEachIndexed
        for (line in t.split('\n')) {
            val block = line.startsWith("> ") || BULLET_RE.containsMatchIn(line) || NUMBER_RE.containsMatchIn(line)
            joiner(block)
            formatLine(line, sb, spans)
        }
    }
    return WaFormatted(sb.toString(), spans.sortedWith(compareBy({ it.start }, { -it.end })))
}

// -- emoji-only messages ------------------------------------------------------

private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF
private fun isSkinTone(cp: Int) = cp in 0x1F3FB..0x1F3FF

/** Extended_Pictographic, approximated by ranges (java.lang.Character has no such property). */
private fun isPictographic(cp: Int): Boolean = when (cp) {
    0x00A9, 0x00AE, 0x203C, 0x2049, 0x2122, 0x2139, 0x24C2, 0x25B6, 0x25C0, 0x2B50, 0x2B55,
    0x3030, 0x303D, 0x3297, 0x3299, 0x2328, 0x23CF -> true
    in 0x2194..0x2199, in 0x21A9..0x21AA, in 0x231A..0x231B, in 0x23E9..0x23F3, in 0x23F8..0x23FA,
    in 0x25AA..0x25AB, in 0x25FB..0x25FE, in 0x2600..0x27BF, in 0x2934..0x2935, in 0x2B05..0x2B07,
    in 0x2B1B..0x2B1C -> true
    in 0x1F000..0x1FAFF -> !isRegionalIndicator(cp) && !isSkinTone(cp)
    else -> false
}

/** 1-3 when the text is only that many emoji (whitespace aside), else 0. */
fun waEmojiOnlyCount(text: String?): Int {
    val t = (text ?: "").filterNot { it.isWhitespace() }
    if (t.isEmpty()) return 0
    val cps = t.codePoints().toArray()
    var i = 0
    var count = 0
    while (i < cps.size) {
        val cp = cps[i]
        when {
            // flag: two regional indicators
            isRegionalIndicator(cp) -> {
                if (i + 1 < cps.size && isRegionalIndicator(cps[i + 1])) i += 2 else return 0
            }
            // keycap: [0-9#*] FE0F? 20E3
            cp == '#'.code || cp == '*'.code || cp in '0'.code..'9'.code -> {
                var j = i + 1
                if (j < cps.size && cps[j] == 0xFE0F) j++
                if (j < cps.size && cps[j] == 0x20E3) i = j + 1 else return 0
            }
            isPictographic(cp) -> {
                var j = i + 1
                var hasSelector = false
                fun modifier() {
                    if (j < cps.size && cps[j] == 0xFE0F) { j++; hasSelector = true }
                    else if (j < cps.size && isSkinTone(cps[j])) j++
                }
                modifier()
                while (j + 1 < cps.size && cps[j] == 0x200D && isPictographic(cps[j + 1])) {
                    j += 2
                    modifier()
                }
                // (c) (r) (tm) are pictographs only with the emoji selector; bare they are text.
                if (j == i + 1 && !hasSelector && (cp == 0xA9 || cp == 0xAE || cp == 0x2122)) return 0
                i = j
            }
            else -> return 0
        }
        count++
    }
    return if (count <= 3) count else 0
}

/** Jumbo font size (sp) for an emoji-only message of [count] emoji. */
fun waJumboSize(count: Int): Int = when (count) { 1 -> 48; 2 -> 40; else -> 32 }

// -- stable hashes ------------------------------------------------------------

/** Sender-name colours (ARGB hex, no alpha), index picked by [waSenderColorIndex]. */
val WA_SENDER_COLORS: List<Long> = listOf(
    0xF08A7E, 0xE8B04B, 0xA5CF5A, 0x4FC3B0, 0x5EB3F0, 0x9A9CF5, 0xC98AF0, 0xF07FB3,
)

/** Rolling hash h = h*31 + utf16 unit (int32), abs, mod 8. Identical to the web widget's. */
fun waSenderColorIndex(jid: String?): Int {
    var h = 0
    for (ch in jid ?: "") h = h * 31 + ch.code
    return (Math.abs(h.toLong()) % 8).toInt()
}

/** Voice-note waveform: [n] bar heights in [0.2, 1], the same for the same id (and the same as on the web). */
fun waWaveformBars(id: String?, n: Int = 28): List<Float> {
    var h = 2166136261L.toInt()   // FNV-1a seed
    for (ch in id ?: "") h = (h xor ch.code) * 16777619
    val mask = 0xFFFFFFFFL
    var x = h.toLong() and mask
    if (x == 0L) x = 1L
    val bars = ArrayList<Float>(n)
    for (i in 0 until n) {                       // xorshift32
        x = x xor ((x shl 13) and mask)
        x = x xor (x ushr 17)
        x = x xor ((x shl 5) and mask)
        val v = 0.2 + 0.8 * ((x % 1000) / 999.0)
        bars.add((floor(v * 100 + 0.5) / 100).toFloat())
    }
    return bars
}
