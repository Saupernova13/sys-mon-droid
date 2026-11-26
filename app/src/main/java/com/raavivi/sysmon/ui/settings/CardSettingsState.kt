package com.raavivi.sysmon.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.raavivi.sysmon.core.data.whatsapp.WhatsAppRepository
import com.raavivi.sysmon.core.model.WaCardChat
import com.raavivi.sysmon.core.model.WaCardSettings
import com.raavivi.sysmon.core.model.WaChat
import com.raavivi.sysmon.core.net.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where the Topic cards screen is: reading, editing, the read failed, or chat search is off on the server. */
enum class CardSettingsPhase { LOADING, READY, ERROR, OFF }

/** How many chats the picker reads: the whole list, not just the recent ones the chat screen shows. */
const val PICKER_CHAT_LIMIT = 500

/** The picker's chat list: not asked for yet, being read, read, or failed. */
enum class PickerChatsPhase { IDLE, LOADING, READY, ERROR }

/** What carding one picked chat would take: reading it ([chat] null, [failed] false), read, or failed. */
data class AddProbe(val jid: String, val name: String, val chat: WaCardChat? = null, val failed: Boolean = false)

/**
 * The Topic cards screen's state: the saved settings, the draft being edited, the chat picker (its chat list is
 * read here, on first use) and its probe, and the save. [onMessage] shows the snackbar. Chat names are private
 * and never logged.
 */
class CardSettingsState(
    private val repo: WhatsAppRepository,
    private val scope: CoroutineScope,
    private val onMessage: (String) -> Unit,
) {
    var phase by mutableStateOf(CardSettingsPhase.LOADING)
        private set
    var loadError by mutableStateOf("")
        private set

    /** The settings as the server holds them; null until read. */
    var saved by mutableStateOf<WaCardSettings?>(null)
        private set
    var draft by mutableStateOf<CardDraft?>(null)
        private set
    var saving by mutableStateOf(false)
        private set

    /** The server's reason a save was refused, or a network failure; cleared by the next edit. */
    var saveError by mutableStateOf<String?>(null)
        private set

    /** The chat picker is showing. */
    var picking by mutableStateOf(false)
        private set

    /** The picked chat being read for its estimate, then confirmed or left. */
    var probe by mutableStateOf<AddProbe?>(null)
        private set

    /** The chats the picker lists, read from the WhatsApp chat list the first time the picker opens. */
    var chats by mutableStateOf<List<WaChat>>(emptyList())
        private set
    var chatsPhase by mutableStateOf(PickerChatsPhase.IDLE)
        private set

    /** Why the last theme add was refused, or null; cleared by the next add or by typing. */
    var themeError by mutableStateOf<String?>(null)
        private set

    /** The latest removed theme (index and name, for "Removed · Undo") and a counter that moves with each removal. */
    private var removedTheme: Pair<Int, String>? = null
    var removalSeq by mutableStateOf(0)
        private set

    private var epoch = 0
    private var probeEpoch = 0
    private var chatsEpoch = 0

    /** The Save button: something changed and the draft is valid. */
    val canSave: Boolean
        get() {
            val s = saved ?: return false
            val d = draft ?: return false
            return !saving && canSave(s, d)
        }

    /** "Use the built-in list" is on offer: the draft's themes differ from a non-empty built-in list. */
    val offersBuiltIn: Boolean
        get() = draft?.let { offersBuiltIn(it.facets, saved?.defaultFacets.orEmpty()) } == true

    /** Something was edited and not saved yet (leaving asks first). */
    val dirty: Boolean
        get() {
            val s = saved ?: return false
            val d = draft ?: return false
            return !patchOf(s, d).isEmpty()
        }

    /** The problem with the draft as it stands, shown above Save; the server's refusal comes first. */
    val problem: String?
        get() = saveError ?: draft?.let(::draftProblem)

    fun load() {
        val mine = ++epoch
        phase = CardSettingsPhase.LOADING
        loadError = ""
        scope.launch {
            val r = repo.cardSettings()
            if (mine != epoch) return@launch
            when (r) {
                is ApiResult.Ok -> {
                    saved = r.value
                    draft = draftOf(r.value)
                    saveError = null
                    phase = CardSettingsPhase.READY
                }
                is ApiResult.Err -> {
                    loadError = r.message
                    phase = if (isSearchOff(r.code)) CardSettingsPhase.OFF else CardSettingsPhase.ERROR
                }
            }
        }
    }

    fun retry() = load()

    private fun edit(f: (CardDraft) -> CardDraft) {
        val d = draft ?: return
        draft = f(d)
        saveError = null
    }

    fun setNight(on: Boolean) = edit { it.copy(night = on) }
    fun setFrom(minutes: Int) = edit { it.copy(from = minutes) }
    fun setTo(minutes: Int) = edit { it.copy(to = minutes) }
    fun stepMin(delta: Int) = edit { it.copy(minMsgs = stepMinMsgs(it.minMsgs, delta)) }
    fun removeChat(jid: String) = edit { removeChat(it, jid) }

    // -- themes --------------------------------------------------------------------------------

    /** Add [raw] to the draft's themes; false (with [themeError] set) when the server's rules refuse it. */
    fun addTheme(raw: String): Boolean {
        val d = draft ?: return false
        val (list, error) = addTheme(d.facets, raw)
        themeError = error
        if (error != null) return false
        edit { it.copy(facets = list) }
        return true
    }

    fun clearThemeError() {
        themeError = null
    }

    /** Take [name] out of the draft; [undoRemoveTheme] puts it back where it was. */
    fun removeTheme(name: String) {
        val d = draft ?: return
        val at = d.facets.indexOfFirst { it.equals(name, ignoreCase = true) }
        if (at < 0) return
        removedTheme = at to d.facets[at]
        removalSeq++
        edit { it.copy(facets = removeTheme(it.facets, name)) }
    }

    fun undoRemoveTheme() {
        val (at, name) = removedTheme ?: return
        removedTheme = null
        edit { d ->
            if (d.facets.any { it.equals(name, ignoreCase = true) }) d
            else d.copy(facets = d.facets.toMutableList().also { it.add(at.coerceAtMost(it.size), name) })
        }
    }

    /** "Use the built-in list": the draft's themes become the server's built-in ones. */
    fun useBuiltInThemes() {
        val defaults = saved?.defaultFacets.orEmpty()
        themeError = null
        edit { useBuiltIn(it, defaults) }
    }

    // -- chat picker ---------------------------------------------------------------------------

    fun openPicker() {
        picking = true
        probe = null
        if (chatsPhase == PickerChatsPhase.IDLE || chatsPhase == PickerChatsPhase.ERROR) loadChats()
    }

    fun loadChats() {
        val mine = ++chatsEpoch
        chatsPhase = PickerChatsPhase.LOADING
        scope.launch {
            val r = repo.chats(limit = PICKER_CHAT_LIMIT)
            if (mine != chatsEpoch) return@launch
            when (r) {
                is ApiResult.Ok -> {
                    chats = r.value.sortedByDescending { it.lastTs }
                    chatsPhase = PickerChatsPhase.READY
                }
                is ApiResult.Err -> chatsPhase = PickerChatsPhase.ERROR
            }
        }
    }

    fun closePicker() {
        probeEpoch++
        picking = false
        probe = null
    }

    /** A chat was tapped in the picker: read what carding it would take. */
    fun pick(jid: String, name: String) {
        if (draft?.chats?.any { it.jid == jid } == true) return
        val mine = ++probeEpoch
        probe = AddProbe(jid, name)
        scope.launch {
            val r = repo.cardSettings(probe = jid)
            if (mine != probeEpoch) return@launch
            probe = when (r) {
                is ApiResult.Ok -> AddProbe(jid, name, chat = r.value.probe ?: WaCardChat(jid = jid, name = name))
                is ApiResult.Err -> AddProbe(jid, name, failed = true)
            }
        }
    }

    /** "Back" on the confirm card: pick another chat. */
    fun clearProbe() {
        probeEpoch++
        probe = null
    }

    /** "Add" on the confirm card: the chat joins the draft (saved with Save) and the picker closes. */
    fun confirmAdd() {
        val p = probe ?: return
        val chat = p.chat ?: return
        edit { addChat(it, chat.copy(name = chat.name.ifBlank { p.name })) }
        closePicker()
    }

    // -- save ----------------------------------------------------------------------------------

    fun save() {
        val s = saved ?: return
        val d = draft ?: return
        if (saving || !canSave(s, d)) return
        val patch = patchOf(s, d)
        val mine = epoch
        saving = true
        saveError = null
        scope.launch {
            val r = repo.saveCardSettings(patch)
            if (mine != epoch) return@launch
            saving = false
            when (r) {
                is ApiResult.Ok -> {
                    saved = r.value
                    draft = draftOf(r.value)
                    onMessage("Card settings saved")
                }
                is ApiResult.Err -> saveError = r.message
            }
        }
    }
}
