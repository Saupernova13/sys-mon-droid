package com.raavivi.sysmon.ui.whatsapp

import com.raavivi.sysmon.core.model.WaSearchTerm
import java.text.Normalizer
import java.util.Locale

/**
 * Highlighting the searched words inside message text: the ranges of a string the server's search terms
 * match. Matching mirrors the server's index closely enough to look right: case and diacritics are folded
 * (cafe finds café), a word matches only at the start of a token ("art" is not inside "start"), a prefix
 * term may run on, a whole-word term must also end at a token boundary, and a term with no letter or digit
 * in it (an emoji) is a plain substring. Excluded (`negated`) terms never mark. Port of the web
 * whatsapp-search-marks.js.
 */

private val COMBINING = Regex("\\p{M}")
private val NON_WORD_RUN = Regex("[^\\p{L}\\p{N}]+")

private fun foldChars(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(COMBINING, "").lowercase(Locale.ROOT)

/** The folded form of a text plus, for every UTF-16 unit of it, the span of the original it came from. */
private class Folded(val str: String, val starts: IntArray, val ends: IntArray)

private fun fold(text: String): Folded {
    val sb = StringBuilder()
    val starts = ArrayList<Int>()
    val ends = ArrayList<Int>()
    var at = 0
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val len = Character.charCount(cp)
        val f = foldChars(String(Character.toChars(cp)))
        // A lone combining mark folds to nothing: it joins the letter before it.
        if (f.isEmpty() && ends.isNotEmpty()) ends[ends.lastIndex] = at + len
        repeat(f.length) { starts += at; ends += at + len }
        sb.append(f)
        at += len
        i += len
    }
    return Folded(sb.toString(), starts.toIntArray(), ends.toIntArray())
}

private fun isWordCp(cp: Int): Boolean = when (Character.getType(cp)) {
    Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(), Character.TITLECASE_LETTER.toInt(),
    Character.MODIFIER_LETTER.toInt(), Character.OTHER_LETTER.toInt(), Character.DECIMAL_DIGIT_NUMBER.toInt(),
    Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt(),
    -> true
    else -> false
}

private fun wordBefore(s: String, i: Int): Boolean = i > 0 && isWordCp(s.codePointBefore(i))
private fun wordAt(s: String, i: Int): Boolean = i < s.length && isWordCp(s.codePointAt(i))

/** Every span of [word] in [folded] that sits on a token start (and end, unless [prefix]). */
private fun wordSpans(folded: String, word: String, prefix: Boolean): List<Span> {
    val spans = mutableListOf<Span>()
    var at = folded.indexOf(word)
    while (at != -1) {
        val end = at + word.length
        if (!wordBefore(folded, at) && (prefix || !wordAt(folded, end))) spans += Span(at, end)
        at = folded.indexOf(word, at + 1)
    }
    return spans
}

private fun substringSpans(folded: String, needle: String): List<Span> {
    val spans = mutableListOf<Span>()
    var at = folded.indexOf(needle)
    while (at != -1) {
        spans += Span(at, at + needle.length)
        at = folded.indexOf(needle, at + needle.length)
    }
    return spans
}

private fun termSpans(folded: String, term: WaSearchTerm): List<Span> {
    if (term.negated) return emptyList()
    val text = foldChars(term.text)
    if (!term.indexable) return if (text.isNotEmpty()) substringSpans(folded, text) else emptyList()
    val words = text.split(NON_WORD_RUN).filter { it.isNotEmpty() }
    return words.flatMapIndexed { i, w -> wordSpans(folded, w, term.prefix && !term.phrase && i == words.lastIndex) }
}

private fun mergeSpans(spans: List<Span>): List<Span> {
    val out = mutableListOf<Span>()
    for (s in spans.sortedWith(compareBy({ it.start }, { it.end }))) {
        val last = out.lastOrNull()
        if (last != null && s.start <= last.end) out[out.lastIndex] = Span(last.start, maxOf(last.end, s.end))
        else out += s
    }
    return out
}

/** The ranges of [text] (original string indices) that [terms] match, sorted and merged. */
fun findTermRanges(text: String, terms: List<WaSearchTerm>): List<Span> {
    if (text.isEmpty() || terms.isEmpty()) return emptyList()
    val f = fold(text)
    val found = terms.flatMap { t -> termSpans(f.str, t).map { Span(f.starts[it.start], f.ends[it.end - 1]) } }
    return mergeSpans(found)
}
