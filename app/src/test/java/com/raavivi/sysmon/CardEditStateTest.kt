package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.CiCardFull
import com.raavivi.sysmon.core.model.CiEditOptions
import com.raavivi.sysmon.core.model.CiMessageRef
import com.raavivi.sysmon.core.model.CiMoment
import com.raavivi.sysmon.core.model.CiRange
import com.raavivi.sysmon.core.model.WaCardAct
import com.raavivi.sysmon.core.model.WaCardDetailItem
import com.raavivi.sysmon.core.model.WaCardFacet
import com.raavivi.sysmon.core.model.WaCardLex
import com.raavivi.sysmon.core.model.WaSubCard
import com.raavivi.sysmon.core.model.WaTopicCard
import com.raavivi.sysmon.ui.chatindex.ActDraft
import com.raavivi.sysmon.ui.chatindex.DetailDraft
import com.raavivi.sysmon.ui.chatindex.FacetDraft
import com.raavivi.sysmon.ui.chatindex.Line
import com.raavivi.sysmon.ui.chatindex.ListKey
import com.raavivi.sysmon.ui.chatindex.Loc
import com.raavivi.sysmon.ui.chatindex.MAIN_ID
import com.raavivi.sysmon.ui.chatindex.WordDraft
import com.raavivi.sysmon.ui.chatindex.addItem
import com.raavivi.sysmon.ui.chatindex.addSub
import com.raavivi.sysmon.ui.chatindex.confidenceText
import com.raavivi.sysmon.ui.chatindex.draftOf
import com.raavivi.sysmon.ui.chatindex.editedLabel
import com.raavivi.sysmon.ui.chatindex.facetChoices
import com.raavivi.sysmon.ui.chatindex.hintsOf
import com.raavivi.sysmon.ui.chatindex.items
import com.raavivi.sysmon.ui.chatindex.keptMoments
import com.raavivi.sysmon.ui.chatindex.kindChoices
import com.raavivi.sysmon.ui.chatindex.kindsFull
import com.raavivi.sysmon.ui.chatindex.kindsLimitText
import com.raavivi.sysmon.ui.chatindex.previewOf
import com.raavivi.sysmon.ui.chatindex.removeItem
import com.raavivi.sysmon.ui.chatindex.sectionTitle
import com.raavivi.sysmon.ui.chatindex.setSummary
import com.raavivi.sysmon.ui.chatindex.setTitle
import com.raavivi.sysmon.ui.chatindex.snapConfidence
import com.raavivi.sysmon.ui.chatindex.toEdit
import com.raavivi.sysmon.ui.chatindex.toggleKind
import com.raavivi.sysmon.ui.chatindex.toggleMoment
import com.raavivi.sysmon.ui.chatindex.undo
import com.raavivi.sysmon.ui.chatindex.updateItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card editor's draft: building it, the PUT body, hints, pickers and remove with undo. Made-up cards only. */
class CardEditStateTest {

    private val opts = CiEditOptions(facets = listOf("plans", "mood"), detailKinds = listOf("plan", "joke"), detailKindsMax = 3, recallQueries = CiRange(3, 5))

    private fun message(ts: Long) = CiMessageRef(rowid = 1, chatJid = "27820000001@s.whatsapp.net", msgId = "3EB0AA", ts = ts)

    private fun card(): CiCardFull = CiCardFull(
        id = 5, nMsgs = 8, updatedAt = 1_700_000_000,
        card = WaTopicCard(
            title = "Trip plans", summary = "They chose a date.",
            said = listOf("said one", "said two"), meant = listOf("meant one"), topics = listOf("travel", "dates"),
            acts = listOf(WaCardAct("plan", 3, true), WaCardAct("joke", 1, false)),
            facets = listOf(WaCardFacet("plans", 0.8), WaCardFacet("legacy", 0.4)),
            entities = listOf("Cape Town"), recallQueries = listOf("trip", "date", "booking"),
            details = listOf(WaCardDetailItem(listOf("plan"), "Sam", "They will drive")),
            lexicon = listOf(WaCardLex("zorp", "a cuddle")),
        ),
        subtopics = listOf(
            WaSubCard(
                title = "Route", summary = "Which road.", said = listOf("sub said"), meant = emptyList(),
                acts = listOf(WaCardAct("idea", 2, false)), facets = listOf(WaCardFacet("mood", 0.5)),
                details = listOf(WaCardDetailItem(listOf("joke"), "", "A pun")),
            ),
        ),
        moments = listOf(
            CiMoment("plan", "A date", message = message(1_700_000_100)),
            CiMoment("idea", "A route"),
            CiMoment("joke", "A pun", message = message(1_700_000_300)),
        ),
    )

    // -- the draft and the body ---------------------------------------------------------------------------

    @Test
    fun `an unchanged card makes a body with the same parts`() {
        val c = card()
        val e = draftOf(c).toEdit(c.updatedAt)
        val t = c.card!!
        assertEquals(t.title, e.card.title)
        assertEquals(t.summary, e.card.summary)
        assertEquals(t.said, e.card.said)
        assertEquals(t.meant, e.card.meant)
        assertEquals(t.topics, e.card.topics)
        assertEquals(t.entities, e.card.entities)
        assertEquals(t.recallQueries, e.card.recallQueries)
        assertEquals(t.acts.map { Triple(it.kind, it.intensity, it.resolved) }, e.card.acts.map { Triple(it.kind, it.intensity, it.resolved) })
        assertEquals(t.facets.map { it.name to it.confidence }, e.card.facets.map { it.name to it.confidence })
        assertEquals(t.details.map { Triple(it.kinds, it.who, it.text) }, e.card.details.map { Triple(it.kinds, it.who, it.text) })
        assertEquals(t.lexicon.map { it.term to it.meaning }, e.card.lexicon.map { it.term to it.meaning })
        assertEquals(1, e.subtopics.size)
        assertEquals("Route", e.subtopics[0].title)
        assertEquals(listOf("sub said"), e.subtopics[0].said)
        assertEquals(listOf("joke"), e.subtopics[0].details[0].kinds)
        assertEquals(1_700_000_000L, e.updatedAt)
        assertEquals(listOf(0, 1, 2), e.keepMoments)
    }

    @Test
    fun `a failed card without a body starts empty`() {
        val d = draftOf(CiCardFull(id = 1, state = "failed"))
        assertEquals("", d.main.title)
        assertTrue(d.subs.isEmpty())
        assertTrue(d.moments.isEmpty())
    }

    @Test
    fun `blank lines and acts without a kind are dropped from the body, other text is trimmed`() {
        val d = draftOf(card())
            .addItem(Loc.main(ListKey.SAID)) { Line(it, "   ") }
            .addItem(Loc.main(ListKey.SAID)) { Line(it, "  padded  ") }
            .addItem(Loc.main(ListKey.ACTS)) { ActDraft(it, "  ", 2, false) }
            .setTitle(MAIN_ID, "  New title ")
        val e = d.toEdit(1)
        assertEquals(listOf("said one", "said two", "padded"), e.card.said)
        assertEquals(2, e.card.acts.size)
        assertEquals("New title", e.card.title)
    }

    @Test
    fun `keep_moments lists the kept indexes ascending and a removed moment can come back`() {
        var d = draftOf(card())
        d = d.toggleMoment(1)
        assertEquals(listOf(0, 2), d.keptMoments())
        assertEquals(listOf(0, 2), d.toEdit(1).keepMoments)
        d = d.toggleMoment(0)
        assertEquals(listOf(2), d.keptMoments())
        d = d.toggleMoment(1).toggleMoment(0)
        assertEquals(listOf(0, 1, 2), d.keptMoments())
    }

    @Test
    fun `a moment keeps its date only when it points at a message`() {
        val d = draftOf(card())
        assertEquals(1_700_000_100L, d.moments[0].ts)
        assertNull(d.moments[1].ts)
    }

    @Test
    fun `dirty is a comparison with the card as read`() {
        val c = card()
        val d = draftOf(c)
        assertEquals(d, draftOf(c))
        assertFalse(d.setTitle(MAIN_ID, "Trip plans") != d)
        assertTrue(d.setTitle(MAIN_ID, "Other") != d)
        assertTrue(d.toggleMoment(0) != d)
        assertEquals(d, d.toggleMoment(0).toggleMoment(0))
    }

    // -- changing lists -----------------------------------------------------------------------------------

    @Test
    fun `adding and updating an item changes only that list`() {
        val loc = Loc.main(ListKey.MEANT)
        var d = draftOf(card()).addItem(loc) { Line(it, "fresh") }
        assertEquals(listOf("meant one", "fresh"), d.toEdit(1).card.meant)
        val id = d.items(loc).last().id
        d = d.updateItem(loc, id) { (it as Line).copy(text = "changed") }
        assertEquals(listOf("meant one", "changed"), d.toEdit(1).card.meant)
        assertEquals(listOf("said one", "said two"), d.toEdit(1).card.said)
    }

    @Test
    fun `every added item gets its own id`() {
        val d = draftOf(card()).addItem(Loc.main(ListKey.TOPICS)) { Line(it, "a") }.addItem(Loc.main(ListKey.ENTITIES)) { Line(it, "b") }
        val ids = (d.topics + d.entities + d.recall + d.main.said + d.main.meant).map { it.id } +
            d.main.acts.map { it.id } + d.main.facets.map { it.id } + d.main.details.map { it.id } + d.words.map { it.id } + d.subs.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `removing an item and undoing puts it back at its place`() {
        val loc = Loc.main(ListKey.SAID)
        val d = draftOf(card())
        val first = d.main.said[0].id
        val (after, r) = d.removeItem(loc, first)!!
        assertEquals(listOf("said two"), after.toEdit(1).card.said)
        assertEquals(d, after.undo(r))
        // Edits made after the removal survive the undo.
        val typed = after.setTitle(MAIN_ID, "typed meanwhile").undo(r)
        assertEquals("typed meanwhile", typed.main.title)
        assertEquals(listOf("said one", "said two"), typed.toEdit(1).card.said)
        // Undoing twice puts nothing back twice.
        assertEquals(typed, typed.undo(r))
    }

    @Test
    fun `removing from the middle undoes to the middle, and a shorter list takes it at the end`() {
        val loc = Loc.main(ListKey.RECALL)
        val d = draftOf(card())
        val (after, r) = d.removeItem(loc, d.recall[1].id)!!
        assertEquals(listOf("trip", "booking"), after.toEdit(1).card.recallQueries)
        val (shorter, _) = after.removeItem(loc, after.recall[1].id)!!
        assertEquals(listOf("trip", "date"), shorter.undo(r).toEdit(1).card.recallQueries)
        assertEquals(listOf("trip", "date", "booking"), after.undo(r).toEdit(1).card.recallQueries)
    }

    @Test
    fun `removing something that is not there is nothing`() {
        assertNull(draftOf(card()).removeItem(Loc.main(ListKey.SAID), 9999))
    }

    @Test
    fun `a sub-topic is added empty, found by id after another is removed, and removed with undo`() {
        var d = draftOf(card()).addSub()
        assertEquals(2, d.subs.size)
        val newId = d.subs[1].id
        val (without, r) = d.removeItem(Loc.main(ListKey.SUBS), d.subs[0].id)!!
        assertEquals(1, without.subs.size)
        assertEquals(newId, without.subs[0].id)
        d = without.setTitle(newId, "Second").addItem(Loc(newId, ListKey.SAID)) { Line(it, "line") }
        assertEquals("Second", d.toEdit(1).subtopics[0].title)
        assertEquals(listOf("line"), d.toEdit(1).subtopics[0].said)
        assertEquals(listOf("Route", "Second"), d.undo(r).toEdit(1).subtopics.map { it.title })
    }

    @Test
    fun `changes inside a sub-topic leave the topic card alone`() {
        val d = draftOf(card())
        val sub = d.subs[0].id
        val changed = d.setSummary(sub, "A new road.")
        assertEquals("A new road.", changed.toEdit(1).subtopics[0].summary)
        assertEquals("They chose a date.", changed.toEdit(1).card.summary)
    }

    // -- hints, choices -----------------------------------------------------------------------------------

    @Test
    fun `a good card has no hints`() {
        assertEquals(0, hintsOf(draftOf(card()), opts).count)
    }

    @Test
    fun `an empty title or summary is hinted, on the card and on a sub-topic`() {
        val d = draftOf(card()).setTitle(MAIN_ID, " ").setSummary(MAIN_ID, "").addSub()
        val h = hintsOf(d, opts)
        assertNotNull(h.titles[MAIN_ID])
        assertNotNull(h.summaries[MAIN_ID])
        assertNotNull(h.titles[d.subs[1].id])
        assertNull(h.titles[d.subs[0].id])
    }

    @Test
    fun `a recall count outside the range is hinted and one inside is not`() {
        val d = draftOf(card())
        assertNull(hintsOf(d, opts).recall)
        val few = d.removeItem(Loc.main(ListKey.RECALL), d.recall[0].id)!!.first
        assertEquals("3 to 5 lines needed, there are 2", hintsOf(few, opts).recall)
        var many = d
        repeat(3) { many = many.addItem(Loc.main(ListKey.RECALL)) { Line(it, "more $it") } }
        assertNotNull(hintsOf(many, opts).recall)
        // A blank line is not a query.
        val blank = d.addItem(Loc.main(ListKey.RECALL)) { Line(it, " ") }
        assertNull(hintsOf(blank, opts).recall)
        assertNotNull(hintsOf(d.removeItem(Loc.main(ListKey.RECALL), d.recall[0].id)!!.first.addItem(Loc.main(ListKey.RECALL)) { Line(it, " ") }, opts).recall)
    }

    @Test
    fun `an empty detail, a half private word and an out of range confidence are hinted`() {
        var d = draftOf(card())
            .addItem(Loc.main(ListKey.DETAILS)) { DetailDraft(it, emptyList(), "", "") }
            .addItem(Loc.main(ListKey.WORDS)) { WordDraft(it, "noodle", "") }
            .addItem(Loc.main(ListKey.WORDS)) { WordDraft(it, "", "a pet name") }
            .addItem(Loc.main(ListKey.FACETS)) { FacetDraft(it, "mood", 1.4) }
        val h = hintsOf(d, opts)
        assertNotNull(h.items[d.main.details.last().id])
        assertNull(h.items[d.main.details.first().id])
        assertEquals("Add what it means", h.items[d.words[1].id])
        assertEquals("Add the word", h.items[d.words[2].id])
        assertEquals("Confidence must be between 0 and 1", h.items[d.main.facets.last().id])
        assertNull(h.items[d.words[0].id])
        d = d.updateItem(Loc.main(ListKey.DETAILS), d.main.details.last().id) { (it as DetailDraft).copy(who = "Sam") }
        assertNull(hintsOf(d, opts).items[d.main.details.last().id])
    }

    @Test
    fun `facet choices are the configured ones plus those the card holds`() {
        assertEquals(listOf("plans", "mood", "legacy"), facetChoices(opts, draftOf(card())))
    }

    @Test
    fun `detail kind choices are the configured ones plus those the card holds, sub-topics included`() {
        val d = draftOf(card()).addItem(Loc.main(ListKey.DETAILS)) { DetailDraft(it, listOf("odd"), "", "x") }
        assertEquals(listOf("plan", "joke", "odd"), kindChoices(opts, d))
    }

    @Test
    fun `kinds pick up to the maximum and a full detail takes no more`() {
        var kinds = emptyList<String>()
        for (k in listOf("a", "b", "c", "d")) kinds = toggleKind(kinds, k, 3)
        assertEquals(listOf("a", "b", "c"), kinds)
        assertTrue(kindsFull(kinds, 3))
        assertEquals(listOf("a", "c"), toggleKind(kinds, "b", 3))
        assertFalse(kindsFull(listOf("a"), 3))
        assertEquals("Up to 3 kinds", kindsLimitText(3))
        assertEquals("Up to 1 kind", kindsLimitText(1))
    }

    @Test
    fun `small labels read as the design says`() {
        assertEquals("Said · 251", sectionTitle("Said", 251))
        assertEquals("Title", sectionTitle("Title", null))
        assertEquals("first", previewOf(listOf(" ", "first", "second")))
        assertEquals("", previewOf(emptyList()))
        assertEquals("0.80", confidenceText(0.8))
        assertEquals(0.85, snapConfidence(0.8512), 1e-9)
        assertEquals(1.0, snapConfidence(4.0), 1e-9)
        assertEquals("Edited", editedLabel(null))
        assertTrue(editedLabel(1_700_000_000).startsWith("Edited · "))
    }
}
