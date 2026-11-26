package com.raavivi.sysmon.ui.chatindex

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.raavivi.sysmon.core.data.chatindex.ChatIndexError
import com.raavivi.sysmon.core.data.chatindex.ChatIndexRepository
import com.raavivi.sysmon.core.data.chatindex.chatIndexErrorOf
import com.raavivi.sysmon.core.data.chatindex.isChatSearchOff
import com.raavivi.sysmon.core.model.LexEntry
import com.raavivi.sysmon.core.model.LexRejected
import com.raavivi.sysmon.core.model.LexSuggestion
import com.raavivi.sysmon.core.model.Lexicon
import com.raavivi.sysmon.core.net.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a form shows under a field after a refusal. */
data class LexFailure(val field: LexField, val text: String)

/**
 * One glossary entry form (words as chips plus a meaning), used for adding and for editing in place. A typed
 * word is only ever added whole (the Add word button or the keyboard's action): a comma is just a character.
 * The server has the last word; its reason is shown under the field it names.
 */
class LexForm(initial: LexDraft = LexDraft()) {
    var terms by mutableStateOf(initial.terms)
        private set
    var termInput by mutableStateOf("")
        private set
    var meaning by mutableStateOf(initial.meaning)
        private set
    var termsError by mutableStateOf("")
        private set
    var meaningError by mutableStateOf("")
        private set
    var formError by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)

    fun setDraft(d: LexDraft) {
        terms = d.terms
        meaning = d.meaning
        termInput = ""
        clearErrors()
    }

    fun clearErrors() {
        termsError = ""
        meaningError = ""
        formError = ""
    }

    fun onTermInput(text: String) {
        termInput = text
        termsError = ""
    }

    /** A line break typed or pasted is refused right away, with the server's reason. */
    fun onMeaning(text: String) {
        meaning = text
        meaningError = if (text.isBlank()) "" else sentence(textProblem(text, LexKind.MEANING))
    }

    /** Add the typed word whole; false when it was refused (the reason is showing). */
    fun commitTerm(): Boolean {
        val r = addTerm(terms, termInput)
        termsError = r.error
        if (r.error.isNotEmpty()) return false
        if (termInput.isNotBlank()) {
            terms = r.terms
            termInput = ""
        }
        return true
    }

    fun removeTerm(index: Int) {
        terms = com.raavivi.sysmon.ui.chatindex.removeTerm(terms, index)
    }

    /** Commit a pending word, then check the whole form; true when it may be sent. */
    fun validate(): Boolean {
        clearErrors()
        if (!commitTerm()) return false
        val bad = entryProblems(draft())
        termsError = bad.terms
        meaningError = bad.meaning
        return !bad.any
    }

    fun draft() = LexDraft(terms, meaning)

    fun show(f: LexFailure) {
        when (f.field) {
            LexField.TERMS -> termsError = f.text
            LexField.MEANING -> meaningError = f.text
            LexField.FORM -> formError = f.text
        }
    }
}

/**
 * The Lexicon tab: the glossary edited live (every action is its own request; nothing waits for a Save). Words
 * and meanings are private: never logged. A 409 `stale` reloads and says "Changed elsewhere — reloaded"; a 409
 * `duplicate` says a word is taken; a 400 shows the server's reason under the field it names.
 */
class LexiconState(
    private val repo: ChatIndexRepository,
    private val scope: CoroutineScope,
    private val onOff: () -> Unit,
) {
    var phase by mutableStateOf(LoadPhase.IDLE)
        private set
    var loadError by mutableStateOf("")
        private set
    var data by mutableStateOf(Lexicon())
        private set

    /** The filter field's text. */
    var filter by mutableStateOf("")

    /** The one-line status above the lists: a stale reload, a failed list action, a failed reload. */
    var status by mutableStateOf("")
        private set

    val addForm = LexForm()

    /** The entry being edited in place, and its form. */
    var editingId by mutableStateOf<Long?>(null)
        private set
    var editForm by mutableStateOf(LexForm())
        private set

    /** The entry whose delete confirm dialog is open. */
    var confirmDelete by mutableStateOf<LexEntry?>(null)
        private set

    /** Bumped when the add form should scroll into view and take the meaning field (Accept). */
    var acceptTick by mutableIntStateOf(0)
        private set

    private var epoch = 0

    val shown: List<LexEntry> get() = filterEntries(data.entries, filter)

    fun load() {
        val mine = ++epoch
        phase = LoadPhase.LOADING
        loadError = ""
        scope.launch {
            val r = repo.lexicon()
            if (mine != epoch) return@launch
            when (r) {
                is ApiResult.Ok -> {
                    data = r.value
                    phase = LoadPhase.READY
                }
                is ApiResult.Err -> {
                    if (isChatSearchOff(r)) onOff()
                    loadError = loadErrorText(r)
                    phase = LoadPhase.ERROR
                }
            }
        }
    }

    /** Reload after a write; a failure goes to the status line and keeps the lists that are showing. */
    private suspend fun reload() {
        when (val r = repo.lexicon()) {
            is ApiResult.Ok -> {
                data = r.value
                phase = LoadPhase.READY
            }
            is ApiResult.Err -> {
                if (isChatSearchOff(r)) onOff()
                status = loadErrorText(r)
            }
        }
    }

    /**
     * Run one write, then reload. Returns what its form should show when it failed, null when it worked
     * ([done] runs first on success). [terms] and [id] let a duplicate name the word that is taken.
     */
    private suspend fun <T> mutate(
        terms: List<String>,
        id: Long?,
        done: () -> Unit = {},
        write: suspend () -> ApiResult<T>,
    ): LexFailure? {
        status = ""
        when (val r = write()) {
            is ApiResult.Ok -> {
                done()
                reload()
                return null
            }
            is ApiResult.Err -> return when (val e = chatIndexErrorOf(r)) {
                ChatIndexError.Stale -> {
                    editingId = null
                    confirmDelete = null
                    reload()
                    status = LEX_STALE_TEXT
                    LexFailure(LexField.FORM, LEX_STALE_TEXT)
                }
                ChatIndexError.Duplicate ->
                    LexFailure(LexField.TERMS, takenText(takenTerm(terms, data.entries, data.rejected, id)))
                is ChatIndexError.Refused -> LexFailure(fieldOf(e.reason), sentence(e.reason))
                ChatIndexError.Off -> {
                    onOff()
                    LexFailure(LexField.FORM, CHAT_SEARCH_OFF_TEXT)
                }
                else -> LexFailure(LexField.FORM, r.message.ifBlank { "failed" })
            }
        }
    }

    /** A write started from a list button has no form: its failure goes to the status line. */
    private fun plain(terms: List<String>, id: Long?, done: () -> Unit = {}, write: suspend () -> ApiResult<*>) {
        scope.launch {
            mutate(terms, id, done, write)?.let { status = it.text }
        }
    }

    // -- add ---------------------------------------------------------------------------------------

    fun submitAdd() {
        val f = addForm
        if (f.busy || !f.validate()) return
        val draft = entryBody(f.draft())
        f.busy = true
        scope.launch {
            val failure = mutate(draft.terms, null) { repo.addEntry(draft.terms, draft.meaning) }
            f.busy = false
            if (failure == null) f.setDraft(LexDraft()) else f.show(failure)
        }
    }

    // -- edit in place -----------------------------------------------------------------------------

    fun startEdit(e: LexEntry) {
        editingId = e.id
        confirmDelete = null
        editForm = LexForm(draftOfEntry(e))
    }

    fun cancelEdit() {
        editingId = null
    }

    fun submitEdit(e: LexEntry) {
        val f = editForm
        if (f.busy || !f.validate()) return
        val draft = entryBody(f.draft())
        f.busy = true
        scope.launch {
            val failure = mutate(draft.terms, e.id, done = { editingId = null }) {
                repo.saveEntry(e.id, e.version, draft.terms, draft.meaning)
            }
            f.busy = false
            if (failure != null) f.show(failure)
        }
    }

    // -- delete ------------------------------------------------------------------------------------

    fun askDelete(e: LexEntry) {
        confirmDelete = e
    }

    fun keepEntry() {
        confirmDelete = null
    }

    fun confirmDeleteEntry() {
        val e = confirmDelete ?: return
        confirmDelete = null
        plain(emptyList(), e.id) { repo.deleteEntry(e.id, e.version) }
    }

    // -- suggestions and rejected ------------------------------------------------------------------

    /** Accept: the add form takes the suggested word and meaning, to confirm or edit before adding. */
    fun accept(s: LexSuggestion) {
        addForm.setDraft(draftOfSuggestion(s))
        acceptTick++
    }

    fun reject(s: LexSuggestion) {
        plain(listOf(s.term), null) { repo.reject(s.term) }
    }

    fun undoReject(r: LexRejected) {
        plain(emptyList(), null) { repo.unreject(r.term) }
    }
}
