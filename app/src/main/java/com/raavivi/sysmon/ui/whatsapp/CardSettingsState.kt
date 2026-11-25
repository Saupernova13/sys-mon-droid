package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.raavivi.sysmon.core.data.whatsapp.WhatsAppRepository
import com.raavivi.sysmon.core.model.WaCardChat
import com.raavivi.sysmon.core.model.WaCardSettings
import com.raavivi.sysmon.core.net.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where the Card settings sheet is: reading the settings, editing them, or the read failed. */
enum class CardSettingsPhase { LOADING, READY, ERROR }

/** What carding one picked chat would take: reading it ([chat] null, [failed] false), read, or failed. */
data class AddProbe(val jid: String, val name: String, val chat: WaCardChat? = null, val failed: Boolean = false)

/**
 * The Card settings sheet's state: the saved settings, the draft being edited, the chat picker and its probe,
 * and the save. [onSaved] runs after a successful save (the status line reads the new numbers); [onMessage]
 * shows the toast. Chat names are private and never logged.
 */
class CardSettingsState(
    private val repo: WhatsAppRepository,
    private val scope: CoroutineScope,
    private val onSaved: () -> Unit,
    private val onMessage: (String) -> Unit,
) {
    var open by mutableStateOf(false)
        private set
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

    private var epoch = 0
    private var probeEpoch = 0

    /** The Save button: something changed and the draft is valid. */
    val canSave: Boolean
        get() {
            val s = saved ?: return false
            val d = draft ?: return false
            return !saving && canSave(s, d)
        }

    /** The problem with the draft as it stands, shown above Save; the server's refusal comes first. */
    val problem: String?
        get() = saveError ?: draft?.let(::draftProblem)

    fun show() {
        open = true
        load()
    }

    fun close() {
        epoch++
        probeEpoch++
        open = false
        picking = false
        probe = null
        saving = false
        saveError = null
    }

    fun retry() = load()

    private fun load() {
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
                    phase = CardSettingsPhase.ERROR
                }
            }
        }
    }

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

    // -- chat picker ---------------------------------------------------------------------------

    fun openPicker() {
        picking = true
        probe = null
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
                    close()
                    onMessage("Card settings saved")
                    onSaved()
                }
                is ApiResult.Err -> saveError = r.message
            }
        }
    }
}
