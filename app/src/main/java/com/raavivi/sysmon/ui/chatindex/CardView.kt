package com.raavivi.sysmon.ui.chatindex

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.ChatOpenRequest
import com.raavivi.sysmon.core.model.CiCardFull
import com.raavivi.sysmon.core.model.WaCardAct
import com.raavivi.sysmon.core.model.WaCardDetailItem
import com.raavivi.sysmon.core.model.WaCardFacet
import com.raavivi.sysmon.core.model.WaSubCard
import com.raavivi.sysmon.ui.whatsapp.INTENSITY_DOTS
import com.raavivi.sysmon.ui.whatsapp.actText
import com.raavivi.sysmon.ui.whatsapp.intensityDots
import com.raavivi.sysmon.ui.whatsapp.kindLabel
import com.raavivi.sysmon.ui.whatsapp.sortedFacets

/**
 * A stored topic card in full, in the order the WhatsApp card sheet reads: title, the summary, key moments (each
 * with Open in chat when it points at a message), then what was said and meant, topics, mood, people and places,
 * how to recall it, details, private words and the sub-topic cards. A card the owner asked for wears a Yours tag
 * and the description they gave. The text is model output about private chats: never logged.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardView(data: CiCardFull, chatLabel: String, onOpenChat: (ChatOpenRequest) -> Unit) {
    val c = data.card
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (data.isManual) CiChip("Yours", emphasis = true)
            Text(
                c?.title?.ifBlank { null } ?: if (data.state == "failed") "Failed card" else "Untitled card",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                cardFacts(data.nMsgs, data.state, data.model, data.promptVersion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (data.isManual && !data.instruction.isNullOrBlank()) {
                Text(
                    "You asked: ${data.instruction}",
                    style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (data.state == "failed") {
                Text(
                    "This card failed${data.failReason?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            c?.summary?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
        if (data.moments.isNotEmpty()) {
            Block("Key moments") {
                for (m in data.moments) {
                    val req = momentRequest(m, chatLabel)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (m.kind.isNotBlank()) CiChip(kindLabel(m.kind))
                            m.message?.takeIf { it.ts > 0 }?.let {
                                Text(momentText(it.ts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(m.oneLine, style = MaterialTheme.typography.bodyLarge)
                        if (req != null) {
                            TextButton(onClick = { onOpenChat(req) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open in chat") }
                        }
                    }
                }
            }
        }
        if (c != null) {
            if (c.said.isNotEmpty()) Block("What was said") { c.said.forEach { Bullet(it) } }
            if (c.meant.isNotEmpty()) Block("What it meant") { c.meant.forEach { Bullet(it) } }
            if (c.topics.isNotEmpty()) Block("Topics") { Chips(c.topics) }
            if (c.acts.isNotEmpty() || c.facets.isNotEmpty()) Block("Mood") { MoodRows(c.acts, c.facets) }
            if (c.entities.isNotEmpty()) Block("People, places and things") { Chips(c.entities) }
            if (c.recallQueries.isNotEmpty()) Block("You might remember it as") { c.recallQueries.forEach { Bullet(it) } }
            if (c.details.isNotEmpty()) Block("Details") { c.details.forEach { DetailRow(it) } }
            if (c.lexicon.isNotEmpty()) {
                Block("Private words") {
                    for (lex in c.lexicon) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(lex.term) }
                                append(" — ")
                                append(lex.meaning)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
        if (data.subtopics.isNotEmpty()) {
            Block("Sub-topic cards") { data.subtopics.forEach { SubCard(it) } }
        }
    }
}

/** A small heading and its content, 8 dp apart; sections sit 24 dp apart. */
@Composable
private fun Block(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun Bullet(text: String) {
    Row {
        Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(items: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in items) CiChip(t)
    }
}

/** Each act as "Plan · settled" with its intensity dots (a spoken "Intensity 3 of 5"), then the facets as chips. */
@Composable
private fun MoodRows(acts: List<WaCardAct>, facets: List<WaCardFacet>) {
    for (act in acts) {
        val dots = intensityDots(act)
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(actText(act), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Row(
                Modifier.semantics { contentDescription = "Intensity $dots of $INTENSITY_DOTS" },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                repeat(INTENSITY_DOTS) { i ->
                    Box(
                        Modifier.size(8.dp).clip(CircleShape).background(
                            if (i < dots) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    )
                }
            }
        }
    }
    if (facets.isNotEmpty()) Chips(sortedFacets(facets).map { it.name })
}

@Composable
private fun DetailRow(item: WaCardDetailItem) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.who.isNotBlank()) {
                Text(kindLabel(item.who), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            for (k in item.kinds) CiChip(kindLabel(k))
        }
        Text(item.text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SubCard(s: WaSubCard) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            s.title.ifBlank { "Untitled" },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (s.summary.isNotBlank()) Text(s.summary, style = MaterialTheme.typography.bodyLarge)
        s.said.forEach { Bullet(it) }
        s.meant.forEach { Bullet(it) }
        s.details.forEach { DetailRow(it) }
    }
}
