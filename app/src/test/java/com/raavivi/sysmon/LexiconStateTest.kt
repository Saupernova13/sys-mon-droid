package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.LexSuggestion
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.ui.chatindex.CHAT_SEARCH_OFF_TEXT
import com.raavivi.sysmon.ui.chatindex.LEX_STALE_TEXT
import com.raavivi.sysmon.ui.chatindex.LexiconState
import com.raavivi.sysmon.ui.chatindex.LoadPhase
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

/** The Lexicon tab's state over the fake glossary store: add, edit, delete, suggestions, 409 and 400 handling. */
@OptIn(ExperimentalCoroutinesApi::class)
class LexiconStateTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = FakeChatIndexRepository()
    private var off = false
    private lateinit var lx: LexiconState

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        lx = LexiconState(repo, CoroutineScope(dispatcher)) { off = true }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun fill(terms: List<String>, meaning: String, form: com.raavivi.sysmon.ui.chatindex.LexForm = lx.addForm) {
        form.setDraft(com.raavivi.sysmon.ui.chatindex.LexDraft(terms, meaning))
    }

    // -- load ----------------------------------------------------------------------------------------

    @Test
    fun `loading shows the entries, suggestions and rejected words`() {
        repo.seed(listOf("zorp", "zorpy"), "a cuddle")
        repo.suggestions += LexSuggestion("noodle", "a pet name", 5)
        lx.load()
        assertEquals(LoadPhase.READY, lx.phase)
        assertEquals(1, lx.data.entries.size)
        assertEquals("noodle", lx.data.suggestions.single().term)
    }

    @Test
    fun `a 404 chat search is off marks the screen off`() {
        repo.lexiconReadResult = OFF_ERR
        lx.load()
        assertTrue(off)
        assertEquals(LoadPhase.ERROR, lx.phase)
        assertEquals(CHAT_SEARCH_OFF_TEXT, lx.loadError)
    }

    @Test
    fun `another failed load is an error with its words, not off`() {
        repo.lexiconReadResult = ApiResult.Err("Network error: down")
        lx.load()
        assertFalse(off)
        assertEquals(LoadPhase.ERROR, lx.phase)
        assertEquals("Network error: down", lx.loadError)
    }

    // -- add -----------------------------------------------------------------------------------------

    @Test
    fun `adding sends the words whole and clears the form`() {
        lx.load()
        lx.addForm.onTermInput("w/e, ok!")
        lx.addForm.commitTerm()
        lx.addForm.onTermInput("#1")
        lx.addForm.onMeaning("whatever, fine")
        lx.submitAdd()
        assertEquals(listOf(listOf("w/e, ok!", "#1") to "whatever, fine"), repo.added)
        assertEquals(1, lx.data.entries.size)
        assertTrue(lx.addForm.terms.isEmpty())
        assertEquals("", lx.addForm.meaning)
        assertEquals("", lx.addForm.formError)
    }

    @Test
    fun `a pending typed word is committed by submit`() {
        lx.load()
        lx.addForm.onTermInput("zorp")
        lx.addForm.onMeaning("a cuddle")
        lx.submitAdd()
        assertEquals(listOf(listOf("zorp") to "a cuddle"), repo.added)
    }

    @Test
    fun `the Add word action adds the typed word whole, commas and all`() {
        lx.addForm.onTermInput("a, b, c")
        assertTrue(lx.addForm.commitTerm())
        assertEquals(listOf("a, b, c"), lx.addForm.terms)
        assertEquals("", lx.addForm.termInput)
    }

    @Test
    fun `a word with a line break is refused on the field and nothing is sent`() {
        lx.load()
        lx.addForm.onTermInput("a\nb")
        assertFalse(lx.addForm.commitTerm())
        assertEquals("A word cannot contain a line break", lx.addForm.termsError)
        lx.addForm.setDraft(com.raavivi.sysmon.ui.chatindex.LexDraft(listOf("ok"), ""))
        lx.addForm.onMeaning("two\nlines")
        assertEquals("A meaning cannot contain a line break", lx.addForm.meaningError)
        lx.submitAdd()
        assertTrue(repo.added.isEmpty())
    }

    @Test
    fun `an empty form shows what is missing and sends nothing`() {
        lx.load()
        lx.submitAdd()
        assertEquals("An entry needs at least one word", lx.addForm.termsError)
        assertEquals("A meaning cannot be empty", lx.addForm.meaningError)
        assertTrue(repo.added.isEmpty())
    }

    @Test
    fun `a duplicate word names itself when the loaded lists know it`() {
        repo.seed(listOf("zorp"), "a cuddle")
        lx.load()
        fill(listOf("Fresh", "ZORP"), "something")
        lx.submitAdd()
        assertEquals("“ZORP” is already taken (in an entry or rejected)", lx.addForm.termsError)
        // The form keeps what was typed so it can be fixed.
        assertEquals(listOf("Fresh", "ZORP"), lx.addForm.terms)
        assertFalse(lx.addForm.busy)
    }

    @Test
    fun `a duplicate the lists cannot place still says a word is taken`() {
        lx.load()
        repo.failNext = ApiResult.Err("a word is already taken", 409, "duplicate")
        fill(listOf("zorp"), "a cuddle")
        lx.submitAdd()
        assertEquals("A word is already taken (in an entry or rejected)", lx.addForm.termsError)
    }

    @Test
    fun `a rejected word is taken too`() {
        repo.rejectedTerms += com.raavivi.sysmon.core.model.LexRejected("blip", 1)
        lx.load()
        fill(listOf("BLIP"), "a sound")
        lx.submitAdd()
        assertTrue(lx.addForm.termsError.contains("is already taken"))
    }

    @Test
    fun `a server 400 reason shows under the field it names`() {
        lx.load()
        fill(listOf("zorp"), "m")
        repo.failNext = ApiResult.Err("a meaning is too long (at most 300 characters)", 400)
        lx.submitAdd()
        assertEquals("A meaning is too long (at most 300 characters)", lx.addForm.meaningError)
        repo.failNext = ApiResult.Err("an entry holds at most 10 words", 400)
        lx.submitAdd()
        assertEquals("An entry holds at most 10 words", lx.addForm.termsError)
        repo.failNext = ApiResult.Err("unknown field", 400)
        lx.submitAdd()
        assertEquals("Unknown field", lx.addForm.formError)
    }

    @Test
    fun `chat search turned off while adding turns the screen off`() {
        lx.load()
        fill(listOf("zorp"), "a cuddle")
        repo.failNext = OFF_ERR
        lx.submitAdd()
        assertTrue(off)
        assertEquals(CHAT_SEARCH_OFF_TEXT, lx.addForm.formError)
    }

    // -- edit ----------------------------------------------------------------------------------------

    @Test
    fun `editing in place sends the version it read and goes back to the list`() {
        val e = repo.seed(listOf("zorp"), "a cuddle")
        lx.load()
        lx.startEdit(lx.data.entries[0])
        assertEquals(e.id, lx.editingId)
        assertEquals(listOf("zorp"), lx.editForm.terms)
        lx.editForm.onMeaning("a big cuddle")
        lx.submitEdit(lx.data.entries[0])
        assertEquals(listOf(SavedEntry(e.id, 1, listOf("zorp"), "a big cuddle")), repo.saved)
        assertNull(lx.editingId)
        assertEquals("a big cuddle", lx.data.entries[0].meaning)
        assertEquals(2L, lx.data.entries[0].version)
    }

    @Test
    fun `an entry may keep its own words when edited`() {
        repo.seed(listOf("zorp", "zorpy"), "a cuddle")
        lx.load()
        lx.startEdit(lx.data.entries[0])
        lx.editForm.removeTerm(1)
        lx.submitEdit(lx.data.entries[0])
        assertEquals(listOf("zorp"), lx.data.entries[0].terms)
    }

    @Test
    fun `a stale edit reloads, says so, closes the editor and loses nothing`() {
        val e = repo.seed(listOf("zorp"), "a cuddle")
        lx.load()
        lx.startEdit(lx.data.entries[0])
        repo.bump(e.id) // another client edited it
        lx.editForm.onMeaning("mine")
        lx.submitEdit(lx.data.entries[0])
        assertEquals(LEX_STALE_TEXT, lx.status)
        assertEquals("Changed elsewhere — reloaded", lx.editForm.formError)
        assertNull(lx.editingId)
        assertEquals(2L, lx.data.entries[0].version) // reloaded
        assertEquals("a cuddle", lx.data.entries[0].meaning) // the other client's state stands; nothing was written
    }

    @Test
    fun `an edit that collides with another entry says a word is taken and stays open`() {
        repo.seed(listOf("zorp"), "a cuddle")
        repo.seed(listOf("blip"), "a sound")
        lx.load()
        lx.startEdit(lx.data.entries[1])
        lx.editForm.setDraft(com.raavivi.sysmon.ui.chatindex.LexDraft(listOf("blip", "Zorp"), "a sound"))
        lx.submitEdit(lx.data.entries[1])
        assertEquals("“Zorp” is already taken (in an entry or rejected)", lx.editForm.termsError)
        assertEquals(lx.data.entries[1].id, lx.editingId)
    }

    @Test
    fun `cancelling an edit leaves the entry alone`() {
        repo.seed(listOf("zorp"), "a cuddle")
        lx.load()
        lx.startEdit(lx.data.entries[0])
        lx.cancelEdit()
        assertNull(lx.editingId)
        assertTrue(repo.saved.isEmpty())
    }

    // -- delete --------------------------------------------------------------------------------------

    @Test
    fun `delete asks first and sends the version it read`() {
        val e = repo.seed(listOf("zorp"), "a cuddle")
        lx.load()
        lx.askDelete(lx.data.entries[0])
        assertEquals(e.id, lx.confirmDelete?.id)
        lx.keepEntry()
        assertNull(lx.confirmDelete)
        assertTrue(repo.deletedEntries.isEmpty())
        lx.askDelete(lx.data.entries[0])
        lx.confirmDeleteEntry()
        assertEquals(listOf(e.id to 1L), repo.deletedEntries)
        assertTrue(lx.data.entries.isEmpty())
    }

    @Test
    fun `a stale delete reloads and says so instead of deleting`() {
        val e = repo.seed(listOf("zorp"), "a cuddle")
        lx.load()
        lx.askDelete(lx.data.entries[0])
        repo.bump(e.id)
        lx.confirmDeleteEntry()
        assertEquals(LEX_STALE_TEXT, lx.status)
        assertEquals(1, lx.data.entries.size)
        assertEquals(2L, lx.data.entries[0].version)
    }

    // -- suggestions and rejected --------------------------------------------------------------------

    @Test
    fun `accepting a suggestion prefills the add form, replacing what was there`() {
        repo.suggestions += LexSuggestion("noodle", "a pet name", 5)
        lx.load()
        fill(listOf("old"), "old meaning")
        val tick = lx.acceptTick
        lx.accept(lx.data.suggestions[0])
        assertEquals(listOf("noodle"), lx.addForm.terms)
        assertEquals("a pet name", lx.addForm.meaning)
        assertEquals(tick + 1, lx.acceptTick)
        assertTrue(repo.added.isEmpty()) // accepting only prefills
        lx.submitAdd()
        assertEquals(listOf(listOf("noodle") to "a pet name"), repo.added)
    }

    @Test
    fun `rejecting a suggestion moves it to the rejected list and Undo brings it back to the lists`() {
        repo.suggestions += LexSuggestion("noodle", "a pet name", 5)
        lx.load()
        lx.reject(lx.data.suggestions[0])
        assertEquals(listOf("noodle"), repo.rejectCalls)
        assertTrue(lx.data.suggestions.isEmpty())
        assertEquals("noodle", lx.data.rejected.single().term)
        lx.undoReject(lx.data.rejected[0])
        assertEquals(listOf("noodle"), repo.unrejectCalls)
        assertTrue(lx.data.rejected.isEmpty())
    }

    @Test
    fun `a failed list action goes to the status line`() {
        repo.suggestions += LexSuggestion("noodle", "a pet name", 5)
        lx.load()
        repo.failNext = ApiResult.Err("Network error: down")
        lx.reject(lx.data.suggestions[0])
        assertEquals("Network error: down", lx.status)
        assertEquals(1, lx.data.suggestions.size)
    }

    @Test
    fun `rejecting a word that is in an entry says it is taken`() {
        repo.seed(listOf("noodle"), "a pet name")
        repo.suggestions += LexSuggestion("noodle", "x", 1)
        lx.load()
        lx.reject(lx.data.suggestions[0])
        assertTrue(lx.status.contains("is already taken"))
    }

    // -- filter --------------------------------------------------------------------------------------

    @Test
    fun `the filter narrows the shown entries without touching the data`() {
        repo.seed(listOf("zorp"), "a cuddle")
        repo.seed(listOf("blip"), "a sound")
        lx.load()
        lx.filter = "sound"
        assertEquals(listOf("blip"), lx.shown.map { it.terms[0] })
        assertEquals(2, lx.data.entries.size)
        lx.filter = ""
        assertEquals(2, lx.shown.size)
    }
}
