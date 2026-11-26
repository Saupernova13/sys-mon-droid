package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.WaChatSearchResponse
import com.raavivi.sysmon.core.model.WaConvResult
import com.raavivi.sysmon.core.model.WaFacetCount
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.ui.whatsapp.ConvSearchState
import com.raavivi.sysmon.ui.whatsapp.MAX_THEMES
import com.raavivi.sysmon.ui.whatsapp.SearchPhase
import com.raavivi.sysmon.ui.whatsapp.ThemeChip
import com.raavivi.sysmon.ui.whatsapp.cleanThemeCounts
import com.raavivi.sysmon.ui.whatsapp.themeChipLabel
import com.raavivi.sysmon.ui.whatsapp.themeChips
import com.raavivi.sysmon.ui.whatsapp.themesLine
import com.raavivi.sysmon.ui.whatsapp.toggleTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The theme chips over the Conversations results: pure logic, the state's reruns and the wire shapes. Made-up themes only. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThemeChipsTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = FakeWhatsAppRepository()
    private lateinit var conv: ConvSearchState

    private fun counts(vararg c: Pair<String, Int>) = c.map { WaFacetCount(it.first, it.second) }

    private fun answer(vararg c: Pair<String, Int>, results: List<WaConvResult> = listOf(WaConvResult(nodeId = 1, nMsgs = 4))) =
        ApiResult.Ok(WaChatSearchResponse(results = results, facetCounts = counts(*c)))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        conv = ConvSearchState(repo, CoroutineScope(dispatcher), { }, { })
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // -- logic ----------------------------------------------------------------------------------

    @Test
    fun `a press adds a theme and a second press takes it off, in any case`() {
        val on = toggleTheme(emptyList(), "planning")
        assertEquals(listOf("planning"), on.selected)
        assertFalse(on.capped)
        assertEquals(emptyList<String>(), toggleTheme(on.selected, "PLANNING").selected)
        assertEquals(listOf("planning", "family"), toggleTheme(on.selected, "family").selected)
    }

    @Test
    fun `a sixth theme is refused and the selection stays`() {
        val five = List(MAX_THEMES) { "t$it" }
        val sixth = toggleTheme(five, "extra")
        assertTrue(sixth.capped)
        assertEquals(five, sixth.selected)
        // A selected theme can still be turned off at the cap.
        assertEquals(five.drop(1), toggleTheme(five, "t0").selected)
    }

    @Test
    fun `chips list the selected first, then the rest in the server's order`() {
        val chips = themeChips(listOf("weekend"), counts("planning" to 7, "weekend" to 3, "family" to 2))
        assertEquals(
            listOf(ThemeChip("weekend", 3, true), ThemeChip("planning", 7, false), ThemeChip("family", 2, false)),
            chips,
        )
    }

    @Test
    fun `a selected theme the server no longer lists stays with count 0`() {
        val chips = themeChips(listOf("gone", "Planning"), counts("planning" to 7, "family" to 2))
        assertEquals(
            listOf(ThemeChip("gone", 0, true), ThemeChip("Planning", 7, true), ThemeChip("family", 2, false)),
            chips,
        )
    }

    @Test
    fun `counts drop blanks, repeats and empty themes but keep the server's order`() {
        val raw = counts("planning" to 7, " " to 3, "Planning" to 2, "family" to 0, "weekend" to 1)
        assertEquals(counts("planning" to 7, "weekend" to 1), cleanThemeCounts(raw))
    }

    @Test
    fun `a chip reads its name and how many conversations carry it`() {
        assertEquals("planning, 7 conversations", themeChipLabel(ThemeChip("planning", 7, false)))
        assertEquals("planning, 1 conversation", themeChipLabel(ThemeChip("planning", 1, true)))
    }

    @Test
    fun `a result shows up to three of its themes on one line`() {
        assertEquals("planning · weekend · family", themesLine(listOf("planning", "weekend", "family", "money")))
        assertEquals("planning", themesLine(listOf(" planning ", " ")))
        assertNull(themesLine(emptyList()))
        assertNull(themesLine(listOf("", " ")))
    }

    // -- state ----------------------------------------------------------------------------------

    @Test
    fun `a search with no theme picked sends none and keeps the counts the answer brought`() {
        repo.chatSearchResult = answer("planning" to 7, "family" to 2)
        conv.run("cinema night")
        assertEquals(listOf(emptyList<String>()), repo.chatSearchThemes)
        assertEquals(counts("planning" to 7, "family" to 2), conv.themeCounts)
        assertTrue(conv.selectedThemes.isEmpty())
    }

    @Test
    fun `pressing a chip runs the same query with it and keeps the list until the answer lands`() {
        repo.chatSearchResult = answer("planning" to 7, "family" to 2)
        conv.run("cinema night")
        val gate = CompletableDeferred<Unit>()
        repo.chatSearchGate = gate
        repo.chatSearchResult = answer("planning" to 3, results = listOf(WaConvResult(nodeId = 9)))

        conv.pressTheme("planning")

        assertTrue(conv.filtering)
        assertEquals(SearchPhase.DONE, conv.phase)
        assertEquals(1, conv.results.size)
        assertEquals(1L, conv.results.single().nodeId)
        gate.complete(Unit)
        assertFalse(conv.filtering)
        assertEquals(9L, conv.results.single().nodeId)
        assertEquals(listOf("cinema night", "cinema night"), repo.chatSearchQueries)
        assertEquals(listOf(emptyList(), listOf("planning")), repo.chatSearchThemes)
        assertEquals(listOf("planning"), conv.selectedThemes)
        assertEquals("cinema night", conv.resultsQ)
    }

    @Test
    fun `the same query keeps the selection and a new one drops it`() {
        repo.chatSearchResult = answer("planning" to 7)
        conv.run("cinema night")
        conv.pressTheme("planning")
        conv.run("cinema night")
        assertEquals(listOf("planning"), repo.chatSearchThemes.last())
        conv.run("a different night")
        assertEquals(emptyList<String>(), repo.chatSearchThemes.last())
        assertTrue(conv.selectedThemes.isEmpty())
    }

    @Test
    fun `a sixth press does not search and shows the note, the next press hides it`() {
        repo.chatSearchResult = answer("a" to 9, "b" to 8, "c" to 7, "d" to 6, "e" to 5, "f" to 4)
        conv.run("cinema night")
        for (n in listOf("a", "b", "c", "d", "e")) conv.pressTheme(n)
        val searches = repo.chatSearchQueries.size
        val seq = conv.capNoteSeq

        conv.pressTheme("f")

        assertEquals(searches, repo.chatSearchQueries.size)
        assertEquals(5, conv.selectedThemes.size)
        assertTrue(conv.capNote)
        assertEquals(seq + 1, conv.capNoteSeq)
        conv.pressTheme("a")
        assertFalse(conv.capNote)
        assertEquals(listOf("b", "c", "d", "e"), conv.selectedThemes)
        conv.pressTheme("f")
        conv.dismissCapNote()
        assertFalse(conv.capNote)
    }

    @Test
    fun `Clear empties the selection and runs again without it`() {
        repo.chatSearchResult = answer("planning" to 7)
        conv.run("cinema night")
        conv.pressTheme("planning")
        conv.clearThemes()
        assertTrue(conv.selectedThemes.isEmpty())
        assertEquals(emptyList<String>(), repo.chatSearchThemes.last())
        assertEquals(3, repo.chatSearchQueries.size)
    }

    @Test
    fun `a filter with no results keeps the selection so it can be cleared`() {
        repo.chatSearchResult = answer("planning" to 7)
        conv.run("cinema night")
        repo.chatSearchResult = ApiResult.Ok(WaChatSearchResponse())
        conv.pressTheme("planning")
        assertEquals(SearchPhase.DONE, conv.phase)
        assertTrue(conv.results.isEmpty())
        assertEquals(listOf("planning"), conv.selectedThemes)
    }

    @Test
    fun `an old answer is dropped when a newer press has gone out`() {
        repo.chatSearchResult = answer("planning" to 7, "family" to 2)
        conv.run("cinema night")
        val slow = CompletableDeferred<Unit>()
        repo.chatSearchGate = slow
        repo.chatSearchResult = answer("planning" to 1, results = listOf(WaConvResult(nodeId = 5)))
        conv.pressTheme("planning")
        repo.chatSearchGate = null
        repo.chatSearchResult = answer("family" to 1, results = listOf(WaConvResult(nodeId = 6)))
        conv.pressTheme("family")
        slow.complete(Unit)
        assertEquals(6L, conv.results.single().nodeId)
    }

    // -- wire -----------------------------------------------------------------------------------

    @Test
    fun `the counts and each card's themes decode, and default to empty when absent`() {
        val raw = """{"results":[{"node_id":1,"card":{"id":2,"title":"t","facets":["planning","weekend"]}}],
            |"facet_counts":[{"name":"planning","count":7},{"name":"weekend","count":3}],
            |"your_cards":[{"card_id":4,"facets":["family"]}]}""".trimMargin()
        val r = SysMonJson.decodeFromString(WaChatSearchResponse.serializer(), raw)
        assertEquals(counts("planning" to 7, "weekend" to 3), r.facetCounts)
        assertEquals(listOf("planning", "weekend"), r.results.single().card?.facets)
        assertEquals(listOf("family"), r.yourCards.single().facets)

        val old = SysMonJson.decodeFromString(WaChatSearchResponse.serializer(), """{"results":[{"node_id":1,"card":{"id":2}}],"your_cards":[{"card_id":4}]}""")
        assertTrue(old.facetCounts.isEmpty())
        assertTrue(old.results.single().card!!.facets.isEmpty())
        assertTrue(old.yourCards.single().facets.isEmpty())
    }
}
