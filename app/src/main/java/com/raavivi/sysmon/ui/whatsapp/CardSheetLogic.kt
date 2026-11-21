package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.ui.geometry.Offset
import com.raavivi.sysmon.core.model.WaCardAct
import com.raavivi.sysmon.core.model.WaCardDetail
import com.raavivi.sysmon.core.model.WaCardFacet
import com.raavivi.sysmon.core.model.WaCardMoment

/** The sections of the full-card sheet, in the order they are shown. */
enum class CardSection { MOMENTS, SAID, MEANT, DETAILS, ENTITIES, TOPICS, MOOD, SUBTOPICS, LEXICON, RECALL }

/** The sections that have something to show for [d], in display order; none for a missing card. */
fun visibleSections(d: WaCardDetail?): List<CardSection> {
    if (d == null) return emptyList()
    val card = d.card
    return buildList {
        if (d.moments.isNotEmpty()) add(CardSection.MOMENTS)
        if (card.said.isNotEmpty()) add(CardSection.SAID)
        if (card.meant.isNotEmpty()) add(CardSection.MEANT)
        if (card.details.isNotEmpty()) add(CardSection.DETAILS)
        if (card.entities.isNotEmpty()) add(CardSection.ENTITIES)
        if (card.topics.isNotEmpty()) add(CardSection.TOPICS)
        if (card.acts.isNotEmpty() || card.facets.isNotEmpty()) add(CardSection.MOOD)
        if (d.subtopics.isNotEmpty()) add(CardSection.SUBTOPICS)
        if (card.lexicon.isNotEmpty()) add(CardSection.LEXICON)
        if (card.recallQueries.isNotEmpty()) add(CardSection.RECALL)
    }
}

/** A moment opens its chat only when it names the chat, the message and the time. */
fun momentOpenable(m: WaCardMoment): Boolean = !m.chatJid.isNullOrBlank() && !m.msgId.isNullOrBlank() && m.ts != null

/** How many of the five intensity dots are filled. */
const val INTENSITY_DOTS = 5

fun intensityDots(act: WaCardAct): Int = act.intensity.coerceIn(0, INTENSITY_DOTS)

/** A server kind string (`plan`, `small_talk`) as a label ("Plan", "Small talk"). */
fun kindLabel(kind: String): String =
    kind.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }

/** "Plan · settled" or "Plan · unsettled": the text part of a mood row (the dots are drawn beside it). */
fun actText(act: WaCardAct): String = "${kindLabel(act.kind)} · ${if (act.resolved) "settled" else "unsettled"}"

/** Facets, the most confident first. */
fun sortedFacets(facets: List<WaCardFacet>): List<WaCardFacet> = facets.sortedByDescending { it.confidence }

/** "1 message" / "N messages". */
fun messagesLabel(n: Int): String = if (n == 1) "1 message" else "$n messages"

/** A user-driven drag or scroll should drop the keyboard; programmatic scrolls and flings should not. */
fun dismissesKeyboard(available: Offset, userInput: Boolean): Boolean = userInput && available != Offset.Zero

/** The idle guide's rows: a bold-ish line and its hint. */
data class GuideRow(val title: String, val hint: String)

val ConversationGuide = listOf(
    GuideRow("Describe it like you'd tell a friend", "e.g. the night we planned the trip, not just “trip”"),
    GuideRow("Know the exact words? Use Messages", "Messages matches words; Conversations matches meaning"),
    GuideRow("Tap a result to read its full card", "Check it's the right one, then Open chat here"),
    GuideRow("Teach it with Found it / Not here", "Marking the right result (or none) improves future searches"),
)

val ConversationExamples = listOf("when we planned a trip", "a recommendation for a show", "an argument we sorted out")

val MessageTips = listOf(
    "Use \"quotes\" for a phrase",
    "-word leaves a word out",
    "Filters narrow to photos, voice notes and more",
)
