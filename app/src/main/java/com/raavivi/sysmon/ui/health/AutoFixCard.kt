package com.raavivi.sysmon.ui.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.AutoFixStates
import com.raavivi.sysmon.core.model.AutoFixTurn
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.whatsapp.rememberReducedMotion
import kotlinx.coroutines.delay

/**
 * The Auto fix card on the Health screen (Minimal theme). All behaviour lives in [AutoFixState]; this binds it.
 * Spacing is 4/8/12/16 dp, text is bodyMedium + bodySmall in Normal/SemiBold, targets are at least 48 dp, and
 * the primary action sits at the card's bottom.
 */
@Composable
fun AutoFixCard(state: AutoFixState) {
    val haptic = LocalHapticFeedback.current
    // A fix that ends "done" is the peak moment: one light tick, tied to the state flipping, not to composition.
    LaunchedEffect(state.finished) {
        if (state.finished) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            state.consumeFinished()
        }
    }

    SectionCard(title = "Auto fix") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state.phase) {
                AutoFixPhase.Idle -> IdleBody(state)
                AutoFixPhase.Running -> RunningBody(state)
                AutoFixPhase.Done, AutoFixPhase.Failed -> FinishedBody(state)
            }
        }
    }
}

@Composable
private fun IdleBody(state: AutoFixState) {
    Text(
        "Let an agent check what is down and fix it",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ErrorLine(state.error)
    PrimaryAction("Auto fix", busy = state.busy, onClick = state::start)
}

@Composable
private fun RunningBody(state: AutoFixState) {
    val reduced = rememberReducedMotion()
    val startedAt = state.data?.startedAt
    // A one-second clock for the elapsed time; it only reads the wall clock, it animates nothing.
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(startedAt) {
        while (true) {
            elapsed = AutoFixText.elapsedSeconds(startedAt, System.currentTimeMillis() / 1000)
            delay(1_000)
        }
    }
    Column(
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Reduced motion: no endlessly sliding bar, the text alone says it is working.
        if (!reduced) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            "Checking and fixing... this can take a few minutes",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            AutoFixText.clock(elapsed),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    ErrorLine(state.error)
}

@Composable
private fun FinishedBody(state: AutoFixState) {
    val failed = state.phase == AutoFixPhase.Failed
    val data = state.data
    var draft by rememberSaveable { mutableStateOf("") }

    if (failed) {
        Text(
            AutoFixText.failure(data?.error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    } else {
        AutoFixText.tookSeconds(data?.startedAt, data?.finishedAt)?.let {
            Text(
                "Finished in ${AutoFixText.clock(it)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (state.turns.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.turns.forEach { Message(it) }
        }
    }

    if (state.canFollowUp) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(AUTOFIX_MAX_REPLY) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Add error detail (optional)") },
            supportingText = {
                Text(
                    "${draft.length}/$AUTOFIX_MAX_REPLY",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            minLines = 2,
            maxLines = 5,
            enabled = !state.busy,
        )
    }
    ErrorLine(state.error)

    // The primary actions sit at the card's bottom, in the thumb zone.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = state::start,
            enabled = !state.busy,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
        ) { Text(if (failed) "Try again" else "Start over") }
        if (state.canFollowUp) {
            Button(
                onClick = {
                    state.reply(draft)
                    draft = ""
                },
                enabled = !state.busy && draft.isNotBlank(),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) { Text("Send") }
        }
    }
}

/** One reply: the agent on the left in a tonal block, the user on the right in an accent tint. */
@Composable
private fun Message(turn: AutoFixTurn) {
    val mine = turn.role == AutoFixStates.ROLE_USER
    val tint = if (mine) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .fillMaxWidth(0.9f)
                .background(tint, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (mine) "You" else "Agent",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(turn.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ErrorLine(text: String?) {
    if (text == null) return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun PrimaryAction(label: String, busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) { Text(label) }
}
