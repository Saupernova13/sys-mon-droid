package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

/** [this] with a [bg] background over each of [spans] (indices into its text); the other styles and links stay. */
fun AnnotatedString.withHighlights(spans: List<Span>, bg: Color): AnnotatedString {
    if (spans.isEmpty()) return this
    return buildAnnotatedString {
        append(this@withHighlights)
        for (s in spans) addStyle(SpanStyle(background = bg), s.start.coerceIn(0, length), s.end.coerceIn(0, length))
    }
}

/** A snippet's text with its marked ranges highlighted (the server's markers are already stripped). */
fun Snippet.toAnnotated(bg: Color, ink: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    for (m in marks) addStyle(SpanStyle(background = bg, color = ink, fontWeight = FontWeight.SemiBold), m.start, m.end)
}

/** [text] with the content words of [q] highlighted, for a conversation preview or summary line. */
fun queryMarked(text: String, q: String, bg: Color): AnnotatedString =
    AnnotatedString(text).withHighlights(markQueryWords(text, q), bg)
