package com.raavivi.sysmon.ui.chatindex

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.ui.theme.SysMon

// Small pieces the Chat index tabs share. Type uses four sizes (titleLarge, titleMedium, bodyLarge, bodySmall),
// spacing sits on the 4/8 grid and every tap target is at least 48 dp.

/** A non-interactive pill: a "Card" or "Yours" tag, or one word of an entry. */
@Composable
fun CiChip(text: String, modifier: Modifier = Modifier, emphasis: Boolean = false) {
    val bg = if (emphasis) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val ink = if (emphasis) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = ink,
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** The vector dot of a node row: filled when it has a vector, an outline when not; TalkBack hears which. */
@Composable
fun VectorDot(has: Boolean, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .size(12.dp)
            .clip(CircleShape)
            .then(if (has) Modifier.background(ink) else Modifier.border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
            .semantics { contentDescription = vectorLabel(has) },
    )
}

/** A centred spinner block for a first read. */
@Composable
fun CiLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * An empty or failed state that says what happened and what to do next: a bold [title], a [hint] and, when
 * there is something to try, one action in reach of the thumb.
 */
@Composable
fun CiStateBlock(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    isError: Boolean = false,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (hint.isNotBlank()) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null && onAction != null) {
            OutlinedButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(actionLabel) }
        }
    }
}

/** A short line that screen readers announce when it appears (a status or a refusal). */
@Composable
fun CiLiveLine(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.error) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** The list footer: "Load more", a spinner while it loads, or a retry after a failure. */
@Composable
fun LoadMoreRow(visible: Boolean, loading: Boolean, failed: Boolean, label: String, onClick: () -> Unit) {
    if (!visible) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (failed) CiLiveLine("Could not load more.")
        OutlinedButton(
            onClick = onClick,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(if (failed) "Try again" else label)
        }
    }
}

/** One list row as its own rounded surface, so a long list reads as separate, evenly spaced items. */
@Composable
fun RowCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(SysMon.palette.cardRadius),
        tonalElevation = 2.dp,
        content = content,
    )
}

/**
 * The Edited assist chip of a card the owner has edited, with its date. It fades and scales in (200 ms) when the
 * card appears, so a saved card shows the change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditedChip(editedAt: Long?, modifier: Modifier = Modifier) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = visible,
        modifier = modifier,
        enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.9f),
    ) {
        AssistChip(
            onClick = {},
            label = { Text(editedLabel(editedAt), style = MaterialTheme.typography.bodySmall) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            border = null,
        )
    }
}
