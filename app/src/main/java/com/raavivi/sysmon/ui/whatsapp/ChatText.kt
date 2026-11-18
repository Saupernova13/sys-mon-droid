package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raavivi.sysmon.ui.common.formatTime
import kotlin.math.max

/** Message body style: 15sp on a 20sp line, weight 400 (spec section 2). */
fun chatBodyStyle(color: Color): TextStyle = TextStyle(
    color = color,
    fontSize = 15.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.Normal,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** Secondary 13sp text: sender names, quote text, day pills, previews. */
fun chatSecondaryStyle(color: Color, weight: FontWeight = FontWeight.Normal): TextStyle = TextStyle(
    color = color,
    fontSize = 13.sp,
    lineHeight = 17.sp,
    fontWeight = weight,
)

/** Meta 11sp with tabular numerals: times, "edited", counts. */
fun chatMetaStyle(color: Color): TextStyle = TextStyle(
    color = color,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    fontFeatureSettings = "tnum",
)

/** How the parsed spans look. Quote bars are drawn separately (see [quoteRanges]). */
data class FormatTheme(val codeBg: Color, val link: Color, val quoteInk: Color)

private fun listIndent(marker: String) = (if (marker == "•") 12 else 6 + 8 * marker.length).sp

/** Visible text plus its [FmtSpan]s as an [AnnotatedString]; links become clickable [LinkAnnotation.Url]. */
fun WaFormatted.toAnnotatedString(theme: FormatTheme): AnnotatedString = buildAnnotatedString {
    append(text)
    for (sp in spans) {
        if (sp.end <= sp.start) continue
        when (sp.kind) {
            FmtKind.BOLD -> addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), sp.start, sp.end)
            FmtKind.ITALIC -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), sp.start, sp.end)
            FmtKind.STRIKE -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), sp.start, sp.end)
            FmtKind.CODE -> addStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, background = theme.codeBg),
                sp.start, sp.end,
            )
            FmtKind.CODE_BLOCK -> {
                addStyle(ParagraphStyle(textIndent = TextIndent(4.sp, 4.sp)), sp.start, sp.end)
                addStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, background = theme.codeBg), sp.start, sp.end)
            }
            FmtKind.QUOTE -> {
                addStyle(ParagraphStyle(textIndent = TextIndent(11.sp, 11.sp)), sp.start, sp.end)
                addStyle(SpanStyle(color = theme.quoteInk), sp.start, sp.end)
            }
            FmtKind.LIST -> addStyle(ParagraphStyle(textIndent = TextIndent(0.sp, listIndent(sp.data))), sp.start, sp.end)
            FmtKind.LINK -> addLink(
                LinkAnnotation.Url(
                    sp.data,
                    TextLinkStyles(SpanStyle(color = theme.link, textDecoration = TextDecoration.Underline)),
                ),
                sp.start, sp.end,
            )
        }
    }
}

/** The quote paragraphs of [f], as inclusive ranges over [WaFormatted.text], for drawing their left bar. */
fun WaFormatted.quoteRanges(): List<IntRange> =
    spans.filter { it.kind == FmtKind.QUOTE && it.end > it.start }.map { it.start until it.end }

/** Receives the text's layout during measure so the meta can read the last line's right edge. */
class TextLayoutHolder {
    var result: TextLayoutResult? = null
}

/**
 * Message text with its time/edited/star meta on the same line as the last line of text when it fits,
 * bottom-right, else on its own right-aligned line (spec section 3, "inline meta"). The decision is the
 * pure [waPlanMeta]; the bubble is as wide as the text or the meta requires.
 */
@Composable
fun TextWithMeta(
    text: AnnotatedString,
    style: TextStyle,
    quoteRanges: List<IntRange>,
    quoteBar: Color,
    modifier: Modifier = Modifier,
    meta: @Composable () -> Unit,
) {
    val holder = remember { TextLayoutHolder() }
    val density = LocalDensity.current
    val policy = remember(density) { MetaTextPolicy(holder, with(density) { 8.dp.roundToPx() }) }
    Layout(
        content = {
            Text(
                text = text,
                style = style,
                onTextLayout = { holder.result = it },
                modifier = Modifier.drawBehind {
                    val r = holder.result ?: return@drawBehind
                    for (q in quoteRanges) {
                        if (q.first >= r.layoutInput.text.length) continue
                        val top = r.getLineTop(r.getLineForOffset(q.first))
                        val bottom = r.getLineBottom(r.getLineForOffset(q.last))
                        drawRoundRect(
                            quoteBar,
                            topLeft = Offset(0f, top + 1.dp.toPx()),
                            size = Size(3.dp.toPx(), (bottom - top - 2.dp.toPx()).coerceAtLeast(1f)),
                            cornerRadius = CornerRadius(1.5.dp.toPx()),
                        )
                    }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) { meta() }
        },
        modifier = modifier,
        measurePolicy = policy,
    )
}

private class MetaTextPolicy(private val holder: TextLayoutHolder, private val gap: Int) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val maxW = constraints.maxWidth
        val metaP = measurables[1].measure(Constraints())
        val textP = measurables[0].measure(Constraints(minWidth = constraints.minWidth.coerceAtMost(maxW), maxWidth = maxW))
        val r = holder.result
        val lastRight = r?.takeIf { it.lineCount > 0 }?.let { it.getLineRight(it.lineCount - 1) }
        val plan = waPlanMeta(textP.width, textP.height, lastRight, metaP.width, metaP.height, maxW, gap)
        val w = plan.width.coerceIn(constraints.minWidth, maxW)
        return layout(w, plan.height) {
            textP.placeRelative(0, 0)
            metaP.placeRelative(w - metaP.width, plan.metaY)
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables[0].maxIntrinsicWidth(height) + gap + measurables[1].maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        max(measurables[0].minIntrinsicWidth(height), measurables[1].minIntrinsicWidth(height))

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables[0].minIntrinsicHeight(width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables[0].maxIntrinsicHeight(width) + measurables[1].maxIntrinsicHeight(width)
}

/** Star (if starred), "edited" (if edited) and the clock time: one 11sp line. */
@Composable
fun MetaContent(ts: Double, edited: Boolean, starred: Boolean, color: Color) {
    val style = chatMetaStyle(color)
    if (starred) {
        Icon(Icons.Filled.Star, contentDescription = "Starred", tint = color, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(3.dp))
    }
    if (edited) {
        Text("edited", style = style)
        Spacer(Modifier.width(4.dp))
    }
    Text(formatTime(ts), style = style)
}
