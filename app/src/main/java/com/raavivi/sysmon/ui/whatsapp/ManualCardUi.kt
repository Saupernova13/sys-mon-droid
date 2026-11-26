package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.WaConvCard
import com.raavivi.sysmon.core.model.WaConvResult
import com.raavivi.sysmon.core.model.WaManualJob

/**
 * The bar that replaces the composer in selection mode: how many messages are picked, Cancel, and Make card
 * (the primary action, in the thumb zone; off when nothing is picked or more than [MAX_PICKS] are).
 */
@Composable
internal fun SelectionBar(count: Int, c: ChatColors, onCancel: () -> Unit, onMake: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(c.inBg).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            barText(count),
            style = chatBodyStyle(if (count > MAX_PICKS) MaterialTheme.colorScheme.error else c.ink).copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Cancel", style = chatSecondaryStyle(c.accent, FontWeight.SemiBold))
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onMake,
            enabled = canMake(count),
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
        ) {
            Text("Make card", style = chatSecondaryStyle(c.onAccent, FontWeight.SemiBold))
        }
    }
}

/**
 * The Make card sheet: the form (count and range, "What should this card describe?" with a live counter, Write
 * card) and then the job's progress (Queued, what it waits for, Writing, Done with Open card, Failed with Try
 * again, Cancelled, with Cancel card while it runs). Closing it while the job runs hands it to a watcher.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MakeCardSheetHost(m: ManualCardsState, s: MakeCardSheet, c: ChatColors) {
    val nowSec = remember(s.req) { System.currentTimeMillis() / 1000.0 }
    val summary = remember(s.req, nowSec) { manualSummary(s.req.ids.size, s.req.first, s.req.last, nowSec) }
    ModalBottomSheet(
        onDismissRequest = m::dismissSheet,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.wall,
        contentColor = c.ink,
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Make a card", style = chatBodyStyle(c.ink).copy(fontWeight = FontWeight.SemiBold))
                Text(summary, style = chatSecondaryStyle(c.meta))
            }
            val job = s.job
            if (job == null) CardForm(m, s, c) else CardProgress(m, s, job, c)
        }
    }
}

@Composable
private fun CardForm(m: ManualCardsState, s: MakeCardSheet, c: ChatColors) {
    Text("What should this card describe?", style = chatSecondaryStyle(c.ink, FontWeight.SemiBold))
    OutlinedTextField(
        value = s.text,
        onValueChange = m::setText,
        enabled = !s.busy,
        minLines = 3,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("e.g. the plan for the pottery class", style = chatSecondaryStyle(c.meta)) },
        textStyle = chatBodyStyle(c.ink),
        supportingText = { Text(instructionCounter(s.text.length), style = chatMetaStyle(c.meta)) },
        isError = !instructionOk(s.text),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, cursorColor = c.accent),
    )
    s.error?.let { ErrorLine(it) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = m::dismissSheet, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Cancel", style = chatSecondaryStyle(c.accent, FontWeight.SemiBold))
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = m::submit,
            enabled = !s.busy && instructionOk(s.text),
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
        ) {
            Text(if (s.busy) "Sending..." else "Write card", style = chatSecondaryStyle(c.onAccent, FontWeight.SemiBold))
        }
    }
}

@Composable
private fun CardProgress(m: ManualCardsState, s: MakeCardSheet, job: WaManualJob, c: ChatColors) {
    val running = !isFinished(job)
    val done = job.state == "done"
    val failed = job.state == "failed"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.accent)
            done -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = c.accent, modifier = Modifier.size(24.dp))
        }
        if (running || done) Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(jobProgressText(job), style = chatBodyStyle(c.ink).copy(fontWeight = FontWeight.SemiBold))
            if (done) Text("Your card is ready", style = chatSecondaryStyle(c.meta))
        }
    }
    if (running) Text("You can close this: you will be told when the card is ready.", style = chatSecondaryStyle(c.meta))
    if (failed) ErrorLine(jobFailureText(job.error))
    s.error?.let { ErrorLine(it) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = m::dismissSheet, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Close", style = chatSecondaryStyle(c.accent, FontWeight.SemiBold))
        }
        Spacer(Modifier.width(8.dp))
        when {
            running -> OutlinedButton(
                onClick = m::cancelJob,
                enabled = !s.busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Cancel card", style = chatSecondaryStyle(c.accent, FontWeight.SemiBold)) }
            done && job.cardId != null -> Button(
                onClick = m::openSheetCard,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
            ) { Text("Open card", style = chatSecondaryStyle(c.onAccent, FontWeight.SemiBold)) }
            failed -> Button(
                onClick = m::tryAgain,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
            ) { Text("Try again", style = chatSecondaryStyle(c.onAccent, FontWeight.SemiBold)) }
        }
    }
}

/** A refusal or failure in plain words, announced when it appears; the error colour is never the only cue. */
@Composable
private fun ErrorLine(text: String) {
    Text(
        text,
        style = chatSecondaryStyle(MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

/**
 * Everything "your own cards" shows above the screen: the snackbar for a card finished while its sheet was
 * closed ("Your card is ready", Open), the Make card sheet, and the card view of one of the owner's cards.
 */
@Composable
internal fun ManualCardOverlays(vm: WhatsAppViewModel, modifier: Modifier = Modifier) {
    val m = vm.manual
    val c = rememberChatColors()
    val reduced = rememberReducedMotion()
    val snackbar = remember { SnackbarHostState() }
    val ready = m.ready
    LaunchedEffect(ready?.job?.id) {
        if (ready == null) return@LaunchedEffect
        val r = snackbar.showSnackbar(ready.text, actionLabel = if (ready.ref != null) "Open" else null, duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) m.openReady() else m.consumeReady()
    }
    SnackbarHost(snackbar, modifier.navigationBarsPadding())
    m.sheet?.let { MakeCardSheetHost(m, it, c) }
    m.cardView?.let { YourCardHost(vm, it, c, reduced) }
}

/** The card view of one of the owner's cards: the shared card sheet with "Your card", "You asked: …" and Delete card. */
@Composable
private fun YourCardHost(vm: WhatsAppViewModel, view: YourCardView, c: ChatColors, reduced: Boolean) {
    val m = vm.manual
    val ref = view.ref
    val nowSec = remember(ref.cardId) { System.currentTimeMillis() / 1000.0 }
    val actions = CardSheetActions(
        q = "",
        onClose = m::closeCard,
        onRetry = m::retryCard,
        onOpenChat = { vm.showYourCard(ref) },
        onOpenMoment = { jid, ts, msgId -> vm.openChatAt(jid, vm.searchChatName(jid, ref.chatName), ts, focusId = msgId) },
        onSearchFor = {
            m.closeCard()
            if (vm.currentJid != null) vm.closeChat()
            vm.search.openFor(it)
        },
    )
    val mine = MineCard(
        instruction = view.detail?.instruction?.trim().orEmpty().ifBlank { ref.instruction },
        confirming = view.confirming,
        deleting = view.deleting,
        error = view.deleteError,
        onDelete = m::askDelete,
        onKeep = m::keepCard,
        onConfirm = m::confirmDelete,
    )
    ConvCardSheet(vm, CardSheet(0, view.phase, view.detail), ref.asConv(), actions, c, reduced, nowSec, mine)
}

/** A card of the owner's own as the result the card sheet shows; its title and summary come from the card itself. */
internal fun YourCardRef.asConv(): WaConvResult = WaConvResult(
    nodeId = 0, chat = chat, family = listOf(chat), chatName = chatName, startTs = firstTs, endTs = lastTs, nMsgs = nMsgs,
    card = title.takeIf { it.isNotBlank() }?.let { WaConvCard(id = cardId, title = it, summary = summary) },
)

/** The small "Your card" chip on a result and on the card view. */
@Composable
internal fun YourCardChip(c: ChatColors, modifier: Modifier = Modifier) {
    Text(
        "Your card",
        style = chatMetaStyle(c.ink).copy(fontWeight = FontWeight.SemiBold),
        modifier = modifier.clip(CircleShape).background(c.accent.copy(alpha = 0.3f)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** The small "Edited" chip on the card view of a card the owner has edited. */
@Composable
internal fun EditedCardChip(c: ChatColors, modifier: Modifier = Modifier) {
    Text(
        "Edited",
        style = chatMetaStyle(c.ink).copy(fontWeight = FontWeight.SemiBold),
        modifier = modifier.clip(CircleShape).background(c.accent.copy(alpha = 0.3f)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
