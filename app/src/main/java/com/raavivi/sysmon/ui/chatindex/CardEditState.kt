package com.raavivi.sysmon.ui.chatindex

import com.raavivi.sysmon.core.model.CiCardEdit
import com.raavivi.sysmon.core.model.CiCardFull
import com.raavivi.sysmon.core.model.CiEditAct
import com.raavivi.sysmon.core.model.CiEditDetail
import com.raavivi.sysmon.core.model.CiEditFacet
import com.raavivi.sysmon.core.model.CiEditLex
import com.raavivi.sysmon.core.model.CiEditSub
import com.raavivi.sysmon.core.model.CiEditTopic
import com.raavivi.sysmon.core.model.WaCardAct
import com.raavivi.sysmon.core.model.WaCardDetailItem
import com.raavivi.sysmon.core.model.WaCardFacet
import com.raavivi.sysmon.core.model.WaTopicCard

/**
 * The card editor's draft, with no UI. A draft is plain immutable data built from the card as read: every change
 * returns the next draft, so "changed?" is an equality check and "Undo" puts one item back. Items carry an [id]
 * (stable while they are edited, used as the lazy list key). Card text is private: nothing here logs.
 */

/** An editable thing with a stable id. */
sealed interface DItem {
    val id: Long
}

/** One line of text (said, meant, a recall query) or one chip (a topic, an entity). */
data class Line(override val id: Long, val text: String) : DItem

data class ActDraft(override val id: Long, val kind: String, val intensity: Int, val settled: Boolean) : DItem

data class FacetDraft(override val id: Long, val name: String, val confidence: Double) : DItem

data class DetailDraft(override val id: Long, val kinds: List<String>, val who: String, val text: String) : DItem

data class WordDraft(override val id: Long, val term: String, val meaning: String) : DItem

/** The text a topic card and a sub-topic card share; [id] is [MAIN_ID] for the topic card itself. */
data class Part(
    override val id: Long,
    val title: String,
    val summary: String,
    val said: List<Line>,
    val meant: List<Line>,
    val acts: List<ActDraft>,
    val facets: List<FacetDraft>,
    val details: List<DetailDraft>,
) : DItem

/** A key moment: it can only be removed ([removed]); [index] is its place in the card as read. */
data class MomentDraft(val index: Int, val kind: String, val oneLine: String, val ts: Long?, val removed: Boolean)

const val MAIN_ID = 0L

/** The intensity choices of an act. */
val INTENSITIES = 1..5

/** The lists of a draft; only [SUBS] and the card-level lists live on the main part's [Loc]. */
enum class ListKey { SAID, MEANT, RECALL, TOPICS, ENTITIES, ACTS, FACETS, DETAILS, WORDS, SUBS }

/** Where a list is: its part ([MAIN_ID] or a sub-topic's id) and which list. */
data class Loc(val part: Long, val key: ListKey) {
    companion object {
        fun main(key: ListKey) = Loc(MAIN_ID, key)
    }
}

data class CardDraft(
    val main: Part,
    val topics: List<Line>,
    val entities: List<Line>,
    val recall: List<Line>,
    val words: List<WordDraft>,
    val moments: List<MomentDraft>,
    val subs: List<Part>,
    val nextId: Long,
)

/** What a removal took out, so Undo can put it back where it was. */
data class Removal(val loc: Loc, val index: Int, val item: DItem)

// -- building a draft -------------------------------------------------------------------------------

/** The draft of [card] as read. A card without a body (a failed one) starts empty. */
fun draftOf(card: CiCardFull): CardDraft {
    var n = 1L
    fun next() = n++
    fun lines(l: List<String>) = l.map { Line(next(), it) }
    fun acts(l: List<WaCardAct>) = l.map { ActDraft(next(), it.kind, it.intensity, it.resolved) }
    fun facets(l: List<WaCardFacet>) = l.map { FacetDraft(next(), it.name, it.confidence) }
    fun details(l: List<WaCardDetailItem>) = l.map { DetailDraft(next(), it.kinds, it.who, it.text) }
    val c = card.card ?: WaTopicCard()
    val main = Part(MAIN_ID, c.title, c.summary, lines(c.said), lines(c.meant), acts(c.acts), facets(c.facets), details(c.details))
    val topics = lines(c.topics)
    val entities = lines(c.entities)
    val recall = lines(c.recallQueries)
    val words = c.lexicon.map { WordDraft(next(), it.term, it.meaning) }
    val subs = card.subtopics.map { s ->
        Part(next(), s.title, s.summary, lines(s.said), lines(s.meant), acts(s.acts), facets(s.facets), details(s.details))
    }
    val moments = card.moments.mapIndexed { i, m ->
        MomentDraft(i, m.kind, m.oneLine, m.message?.ts?.takeIf { it > 0 }, removed = false)
    }
    return CardDraft(main, topics, entities, recall, words, moments, subs, nextId = n)
}

// -- reading and changing a list --------------------------------------------------------------------

private fun CardDraft.part(id: Long): Part? = if (id == MAIN_ID) main else subs.firstOrNull { it.id == id }

private fun CardDraft.mapPart(id: Long, f: (Part) -> Part): CardDraft =
    if (id == MAIN_ID) copy(main = f(main)) else copy(subs = subs.map { if (it.id == id) f(it) else it })

/** The items of the list at [loc]; empty when its sub-topic is gone. */
fun CardDraft.items(loc: Loc): List<DItem> = when (loc.key) {
    ListKey.TOPICS -> topics
    ListKey.ENTITIES -> entities
    ListKey.RECALL -> recall
    ListKey.WORDS -> words
    ListKey.SUBS -> subs
    ListKey.SAID -> part(loc.part)?.said.orEmpty()
    ListKey.MEANT -> part(loc.part)?.meant.orEmpty()
    ListKey.ACTS -> part(loc.part)?.acts.orEmpty()
    ListKey.FACETS -> part(loc.part)?.facets.orEmpty()
    ListKey.DETAILS -> part(loc.part)?.details.orEmpty()
}

@Suppress("UNCHECKED_CAST")
private fun CardDraft.withItems(loc: Loc, list: List<DItem>): CardDraft = when (loc.key) {
    ListKey.TOPICS -> copy(topics = list as List<Line>)
    ListKey.ENTITIES -> copy(entities = list as List<Line>)
    ListKey.RECALL -> copy(recall = list as List<Line>)
    ListKey.WORDS -> copy(words = list as List<WordDraft>)
    ListKey.SUBS -> copy(subs = list as List<Part>)
    ListKey.SAID -> mapPart(loc.part) { it.copy(said = list as List<Line>) }
    ListKey.MEANT -> mapPart(loc.part) { it.copy(meant = list as List<Line>) }
    ListKey.ACTS -> mapPart(loc.part) { it.copy(acts = list as List<ActDraft>) }
    ListKey.FACETS -> mapPart(loc.part) { it.copy(facets = list as List<FacetDraft>) }
    ListKey.DETAILS -> mapPart(loc.part) { it.copy(details = list as List<DetailDraft>) }
}

/** Add the item [make] builds (it is given the new id) at the end of the list at [loc]. */
fun CardDraft.addItem(loc: Loc, make: (Long) -> DItem): CardDraft =
    copy(nextId = nextId + 1).withItems(loc, items(loc) + make(nextId))

/** Change the item [id] of the list at [loc] with [f]. */
fun CardDraft.updateItem(loc: Loc, id: Long, f: (DItem) -> DItem): CardDraft =
    withItems(loc, items(loc).map { if (it.id == id) f(it) else it })

fun CardDraft.setTitle(part: Long, text: String): CardDraft = mapPart(part) { it.copy(title = text) }

fun CardDraft.setSummary(part: Long, text: String): CardDraft = mapPart(part) { it.copy(summary = text) }

/** Remove the item [id] and say what went, or null when it is not there. */
fun CardDraft.removeItem(loc: Loc, id: Long): Pair<CardDraft, Removal>? {
    val list = items(loc)
    val at = list.indexOfFirst { it.id == id }
    if (at < 0) return null
    return withItems(loc, list.filterIndexed { i, _ -> i != at }) to Removal(loc, at, list[at])
}

/** Put a removed item back at its place (or the end when the list got shorter); nothing when it is back already. */
fun CardDraft.undo(r: Removal): CardDraft {
    val list = items(r.loc)
    if (list.any { it.id == r.item.id }) return this
    val at = r.index.coerceIn(0, list.size)
    return withItems(r.loc, list.toMutableList().apply { add(at, r.item) })
}

/** Remove a moment, or put it back. */
fun CardDraft.toggleMoment(index: Int): CardDraft =
    copy(moments = moments.map { if (it.index == index) it.copy(removed = !it.removed) else it })

/** A new, empty sub-topic card. */
fun CardDraft.addSub(): CardDraft =
    addItem(Loc.main(ListKey.SUBS)) { Part(it, "", "", emptyList(), emptyList(), emptyList(), emptyList(), emptyList()) }

// -- the PUT body -----------------------------------------------------------------------------------

private fun List<Line>.texts(): List<String> = map { it.text.trim() }.filter { it.isNotEmpty() }

private fun List<ActDraft>.actsWire() =
    filter { it.kind.isNotBlank() }.map { CiEditAct(it.kind.trim(), it.intensity, it.settled) }

private fun List<FacetDraft>.facetsWire() = map { CiEditFacet(it.name.trim(), it.confidence) }

private fun List<DetailDraft>.detailsWire() = map { CiEditDetail(it.kinds, it.who.trim(), it.text.trim()) }

/** The moments to keep: their indexes in the card as read, each once, ascending. */
fun CardDraft.keptMoments(): List<Int> = moments.filter { !it.removed }.map { it.index }.sorted()

/**
 * The body of `PUT .../cards/{id}` for this draft; [updatedAt] is the card's, as read. Blank list lines and
 * acts without a kind are left out (the server drops them too); the rest goes as typed.
 */
fun CardDraft.toEdit(updatedAt: Long): CiCardEdit = CiCardEdit(
    card = CiEditTopic(
        title = main.title.trim(),
        summary = main.summary.trim(),
        said = main.said.texts(),
        meant = main.meant.texts(),
        topics = topics.texts(),
        acts = main.acts.actsWire(),
        facets = main.facets.facetsWire(),
        entities = entities.texts(),
        recallQueries = recall.texts(),
        details = main.details.detailsWire(),
        lexicon = words.map { CiEditLex(it.term.trim(), it.meaning.trim()) },
    ),
    subtopics = subs.map {
        CiEditSub(it.title.trim(), it.summary.trim(), it.said.texts(), it.meant.texts(), it.acts.actsWire(), it.facets.facetsWire(), it.details.detailsWire())
    },
    keepMoments = keptMoments(),
    updatedAt = updatedAt,
)
