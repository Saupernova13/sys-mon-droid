package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.raavivi.sysmon.core.data.whatsapp.WhatsAppRepository
import com.raavivi.sysmon.core.data.whatsapp.manualErrorOf
import com.raavivi.sysmon.core.model.ManualCardError
import com.raavivi.sysmon.core.model.WaCardDetail
import com.raavivi.sysmon.core.model.WaManualJob
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.core.net.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Selection mode in the open conversation: which messages are picked. Cleared when the chat changes. */
class MessageSelection {
    var active by mutableStateOf(false)
        private set
    var state by mutableStateOf(Selection())
        private set

    val count: Int get() = state.count

    /** "Select messages" from a message's menu: selection mode starts with that message picked. */
    fun enter(m: WaMessage) {
        if (!waCanSelect(m)) return
        active = true
        state = Selection().toggled(m.id, m.ts)
    }

    /** A tap on a row. */
    fun tap(m: WaMessage) {
        if (active && waCanSelect(m)) state = state.toggled(m.id, m.ts)
    }

    /** A long-press on a row: the range from the last one toggled. */
    fun range(m: WaMessage, order: List<PickRow>) {
        if (active && waCanSelect(m)) state = state.withRange(order, m.id, m.ts)
    }

    fun leave() {
        active = false
        state = Selection()
    }
}

/** The messages a Make card sheet was opened for: [chat], their [ids] (oldest first) and the first and last time. */
data class ManualReq(val chat: String, val ids: List<String>, val first: Long?, val last: Long?)

/**
 * The Make card sheet: the description [text], whether a request is in flight ([busy]), the server's reason
 * for a refusal ([error]) and, once accepted, the [job] it follows.
 */
data class MakeCardSheet(
    val req: ManualReq,
    val text: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val job: WaManualJob? = null,
)

/** A job that finished while its sheet was closed: the snackbar says it; [ref] opens a finished card. */
data class ManualReady(val job: WaManualJob, val ref: YourCardRef?) {
    val text: String get() = readyText(job)
}

/**
 * The open card view of one of the owner's own cards: its [detail] once [phase] is DONE, and the delete flow
 * ([confirming], [deleting], [deleteError]).
 */
data class YourCardView(
    val ref: YourCardRef,
    val phase: CardPhase = CardPhase.LOADING,
    val detail: WaCardDetail? = null,
    val confirming: Boolean = false,
    val deleting: Boolean = false,
    val deleteError: String? = null,
)

/**
 * Everything "your own cards" keeps for the open conversation and the Search view, owned by the view model:
 * selection mode, the Make card sheet and its job (polled every [pollMs] while the sheet is up, watched every
 * [watchMs] once it is dismissed), the card view with its delete, and the messages a card was written for
 * (tinted in the chat). Port of the web whatsapp-select.js / whatsapp-manual-*.js. Descriptions, message ids and
 * card text are private and never logged.
 *
 * [currentChat] is the open chat's jid; [chatName] the name to show for a jid; [onMessage] shows a toast;
 * [onDeleted] runs after a card was deleted, so the search results drop it.
 */
class ManualCardsState(
    private val repo: WhatsAppRepository,
    private val scope: CoroutineScope,
    private val currentChat: () -> String?,
    private val chatName: (String) -> String,
    private val onMessage: (String) -> Unit,
    private val onDeleted: (Long) -> Unit,
    private val pollMs: Long = MANUAL_POLL_MS,
    private val watchMs: Long = MANUAL_WATCH_MS,
) {
    val selection = MessageSelection()

    /** The Make card sheet, or null. */
    var sheet by mutableStateOf<MakeCardSheet?>(null)
        private set

    /** Jobs that finished while their sheet was closed, oldest first; the screen shows the first as a snackbar. */
    var pending by mutableStateOf<List<ManualReady>>(emptyList())
        private set

    /** The open card view of one of the owner's cards, or null. */
    var cardView by mutableStateOf<YourCardView?>(null)
        private set

    /** The messages a card was written for, tinted in the open chat; empty when none. */
    var picked by mutableStateOf<Set<String>>(emptySet())
        private set

    val ready: ManualReady? get() = pending.firstOrNull()

    private var sheetToken = 0
    private var viewToken = 0
    private var pollJob: Job? = null
    private val watchers = HashMap<Long, Job>()

    // -- the Make card sheet -------------------------------------------------------------------------

    /** "Make card" on the selection bar: open the sheet for what is picked (1 to 400 messages). */
    fun makeCard() {
        val chat = currentChat() ?: return
        if (!selection.active || !canMake(selection.count)) return
        val span = selection.state.span()
        sheetToken++
        pollJob?.cancel()
        sheet = MakeCardSheet(ManualReq(chat, selection.state.ids(), span?.first, span?.second))
    }

    /** The description is optional and at most [MAX_INSTRUCTION] characters (a longer paste is cut). */
    fun setText(text: String) {
        val s = sheet ?: return
        if (s.busy || s.job != null) return
        sheet = s.copy(text = text.take(MAX_INSTRUCTION), error = null)
    }

    /** "Write card": send the request; on success selection mode ends and the sheet follows the job. */
    fun submit() {
        val s = sheet ?: return
        if (s.busy || s.job != null || !instructionOk(s.text)) return
        val token = sheetToken
        sheet = s.copy(busy = true, error = null)
        scope.launch {
            val r = repo.createManualCard(s.req.chat, s.req.ids, s.text.trim().ifBlank { null })
            when (r) {
                is ApiResult.Ok -> {
                    selection.leave()
                    val cur = sheet
                    if (token == sheetToken && cur != null) {
                        sheet = cur.copy(job = r.value, busy = false)
                        if (!isFinished(r.value)) startPoll(token)
                    } else if (!isFinished(r.value)) {
                        watch(r.value, s.req)   // the sheet was dismissed while the request was in flight
                    }
                }
                is ApiResult.Err -> {
                    val cur = sheet
                    if (token == sheetToken && cur != null) {
                        sheet = cur.copy(busy = false, error = manualErrorText(manualErrorOf(r)))
                    }
                }
            }
        }
    }

    /** "Cancel card" while the job runs. A job that already ended is simply shown as it ended. */
    fun cancelJob() {
        val s = sheet ?: return
        val job = s.job ?: return
        if (s.busy || isFinished(job)) return
        val token = sheetToken
        sheet = s.copy(busy = true)
        pollJob?.cancel()
        scope.launch {
            val r = repo.cancelManualJob(job.id)
            // 409 (finished) and 404 (gone): the next read tells how it ended.
            if (r is ApiResult.Err && r.code != 409 && r.code != 404) onMessage("Could not cancel the card")
            if (token != sheetToken) return@launch
            refresh(token)
            if (token == sheetToken && sheet?.job?.let { !isFinished(it) } == true) startPoll(token)
        }
    }

    /** "Try again" after a failure: back to the form with the same messages and description. */
    fun tryAgain() {
        val s = sheet ?: return
        pollJob?.cancel()
        sheetToken++
        sheet = s.copy(job = null, busy = false, error = null)
    }

    /** Close the sheet. A job still running is handed to a watcher that announces it when it ends. */
    fun dismissSheet() {
        val s = sheet ?: return
        sheetToken++
        pollJob?.cancel()
        sheet = null
        s.job?.takeIf { !isFinished(it) }?.let { watch(it, s.req) }
    }

    /** "Open card" on a finished job in the sheet. */
    fun openSheetCard() {
        val s = sheet ?: return
        val job = s.job ?: return
        val ref = refFor(job, s.req) ?: return
        dismissSheet()
        openCard(ref)
    }

    private fun startPoll(token: Int) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                delay(pollMs)
                if (token != sheetToken) return@launch
                val job = sheet?.job ?: return@launch
                if (isFinished(job)) return@launch
                refresh(token)
                if (sheet?.job?.let { isFinished(it) } != false) return@launch
            }
        }
    }

    /** Read the sheet's job once: a 404 (finished jobs are kept 50 deep) shows as failed; a blip changes nothing. */
    private suspend fun refresh(token: Int) {
        val job = sheet?.job ?: return
        val r = repo.manualJob(job.id)
        if (token != sheetToken) return
        val cur = sheet ?: return
        sheet = when {
            r is ApiResult.Ok -> cur.copy(job = r.value, busy = false)
            r is ApiResult.Err && r.code == 404 -> cur.copy(job = job.copy(state = "failed", error = null, waiting = null), busy = false)
            else -> cur.copy(busy = false)
        }
    }

    // -- a dismissed sheet's job ----------------------------------------------------------------------

    private fun watch(job: WaManualJob, req: ManualReq) {
        if (job.id in watchers) return
        watchers[job.id] = scope.launch {
            try {
                while (true) {
                    delay(watchMs)
                    val r = repo.manualJob(job.id)
                    if (r is ApiResult.Err && r.code == 404) return@launch   // gone: nothing left to tell
                    if (r is ApiResult.Ok && isFinished(r.value)) {
                        pending = pending + ManualReady(r.value, if (r.value.state == "done") refFor(r.value, req) else null)
                        return@launch
                    }
                }
            } finally {
                watchers.remove(job.id)
            }
        }
    }

    /** How many dismissed jobs are being watched. */
    val watching: Int get() = watchers.size

    /** The snackbar was shown or acted on: drop it. */
    fun consumeReady() {
        pending = pending.drop(1)
    }

    /** "Open" on the snackbar. */
    fun openReady() {
        val ref = ready?.ref
        consumeReady()
        if (ref != null) openCard(ref)
    }

    private fun refFor(job: WaManualJob, req: ManualReq): YourCardRef? {
        val id = job.cardId ?: return null
        return YourCardRef(
            cardId = id, chat = req.chat, chatName = chatName(req.chat), firstTs = req.first ?: 0, lastTs = req.last ?: 0,
            nMsgs = req.ids.size, msgIds = req.ids,
        )
    }

    // -- the card view and its delete ------------------------------------------------------------------

    fun openCard(ref: YourCardRef) {
        val token = ++viewToken
        cardView = YourCardView(ref)
        loadCard(ref, token)
    }

    fun retryCard() {
        val v = cardView ?: return
        val token = ++viewToken
        cardView = YourCardView(v.ref)
        loadCard(v.ref, token)
    }

    fun closeCard() {
        viewToken++
        cardView = null
    }

    private fun loadCard(ref: YourCardRef, token: Int) {
        scope.launch {
            val r = repo.chatSearchCardById(ref.cardId)
            val cur = cardView
            if (token != viewToken || cur == null) return@launch
            cardView = when (r) {
                is ApiResult.Ok -> cur.copy(phase = CardPhase.DONE, detail = r.value)
                is ApiResult.Err -> cur.copy(phase = if (r.code == 404) CardPhase.MISSING else CardPhase.ERROR)
            }
        }
    }

    /** "Delete card": ask first. */
    fun askDelete() {
        val v = cardView ?: return
        if (!v.deleting) cardView = v.copy(confirming = true, deleteError = null)
    }

    /** "Keep" in the confirm. */
    fun keepCard() {
        val v = cardView ?: return
        if (!v.deleting) cardView = v.copy(confirming = false)
    }

    /** "Delete" in the confirm: on success (or a 404: already gone) the card leaves the view and the search results. */
    fun confirmDelete() {
        val v = cardView ?: return
        if (v.deleting) return
        val token = viewToken
        cardView = v.copy(confirming = false, deleting = true, deleteError = null)
        scope.launch {
            val r = repo.deleteCard(v.ref.cardId)
            if (r is ApiResult.Err && r.code != 404) {
                val cur = cardView
                if (token == viewToken && cur != null) {
                    cardView = cur.copy(deleting = false, deleteError = deleteErrorText(manualErrorOf(r)))
                }
                return@launch
            }
            if (token == viewToken) cardView = null
            if (picked.isNotEmpty() && picked == pickedSet(v.ref.msgIds)) picked = emptySet()
            onDeleted(v.ref.cardId)
            onMessage("Card deleted")
        }
    }

    // -- the messages a card was written for --------------------------------------------------------------

    /** Tint exactly these messages (by id) in the open chat. */
    fun setPicked(ids: List<String>) {
        picked = pickedSet(ids)
    }

    /** Another chat opened (or the chat closed): nothing picked, marked or being asked for carries over. */
    fun onChatChanged() {
        selection.leave()
        picked = emptySet()
        dismissSheet()
        closeCard()
    }
}
