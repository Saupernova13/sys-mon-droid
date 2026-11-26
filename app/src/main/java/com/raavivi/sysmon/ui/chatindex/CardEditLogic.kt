package com.raavivi.sysmon.ui.chatindex

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.raavivi.sysmon.core.data.chatindex.ChatIndexRepository
import com.raavivi.sysmon.core.data.chatindex.editErrorText
import com.raavivi.sysmon.core.data.chatindex.editNeedsReload
import com.raavivi.sysmon.core.model.CiCardFull
import com.raavivi.sysmon.core.model.CiEditOptions
import com.raavivi.sysmon.core.net.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The error banner above the bottom bar: the [text] and whether Reload card is offered. */
data class EditBanner(val text: String, val canReload: Boolean)

/**
 * One open card editor: the draft, save, reload and the leave-with-unsaved-work flow, with no UI. Nothing is
 * written until [save]; removals are immediate in the draft and [undoRemoval] puts the last one back. A reply
 * for an editor that was since closed is dropped. Card text is private: never logged.
 */
class CardEditLogic(
    private val repo: ChatIndexRepository,
    private val scope: CoroutineScope,
    card: CiCardFull,
    val options: CiEditOptions,
    /** The card was saved: the saved card, as the server answered it. */
    private val onSaved: (CiCardFull) -> Unit,
    /** The editor was left without saving. */
    private val onClose: () -> Unit,
) {
    /** The card as last read; its `updatedAt` is what Save sends. */
    var base by mutableStateOf(card)
        private set
    private var initial = draftOf(card)
    var draft by mutableStateOf(initial)
        private set
    var saving by mutableStateOf(false)
        private set
    var reloading by mutableStateOf(false)
        private set
    var banner by mutableStateOf<EditBanner?>(null)
        private set
    var confirmingDiscard by mutableStateOf(false)
        private set

    /** The latest removal (for "Removed · Undo") and a counter that moves with each, so the snackbar shows again. */
    var lastRemoval by mutableStateOf<Removal?>(null)
        private set
    var removalSeq by mutableStateOf(0)
        private set

    /** Sections and sub-topic cards opened by the user; they stay open while the editor is. */
    var expanded by mutableStateOf<Set<String>>(emptySet())
        private set

    private var closed = false

    val dirty: Boolean get() = draft != initial
    val hints: EditHints get() = hintsOf(draft, options)

    /** Apply a change to the draft. */
    fun edit(f: CardDraft.() -> CardDraft) {
        draft = draft.f()
    }

    fun remove(loc: Loc, id: Long) {
        val (next, removal) = draft.removeItem(loc, id) ?: return
        draft = next
        lastRemoval = removal
        removalSeq++
    }

    fun undoRemoval() {
        val r = lastRemoval ?: return
        draft = draft.undo(r)
        lastRemoval = null
    }

    fun toggleSection(key: String) {
        expanded = if (key in expanded) expanded - key else expanded + key
    }

    fun dismissBanner() {
        banner = null
    }

    fun save() {
        if (saving || reloading) return
        saving = true
        banner = null
        val body = draft.toEdit(base.updatedAt)
        scope.launch {
            val r = repo.editCard(base.id, body)
            if (closed) return@launch
            saving = false
            when (r) {
                is ApiResult.Ok -> {
                    closed = true
                    onSaved(r.value)
                }
                is ApiResult.Err -> banner = EditBanner(editErrorText(r), canReload = editNeedsReload(r))
            }
        }
    }

    /** Read the card again and replace the draft with it: the edits are discarded. */
    fun reload() {
        if (saving || reloading) return
        reloading = true
        scope.launch {
            val r = repo.card(base.id)
            if (closed) return@launch
            reloading = false
            when (r) {
                is ApiResult.Ok -> {
                    base = r.value
                    initial = draftOf(r.value)
                    draft = initial
                    banner = null
                    lastRemoval = null
                }
                is ApiResult.Err -> banner = EditBanner("Could not reload the card. Try again.", canReload = true)
            }
        }
    }

    /** Cancel, Close or system Back: leave at once when nothing changed, else ask. */
    fun cancel() {
        if (saving) return
        if (dirty) confirmingDiscard = true else leave()
    }

    fun keepEditing() {
        confirmingDiscard = false
    }

    fun discard() {
        confirmingDiscard = false
        leave()
    }

    private fun leave() {
        closed = true
        onClose()
    }

    /** The editor was dropped from outside (its card view was left): replies still in flight are dropped. */
    fun close() {
        closed = true
    }
}
