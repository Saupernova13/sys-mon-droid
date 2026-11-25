package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The two status lines the chip stands for, in a bottom sheet: the index line (Update) and the topic-cards
 * line (Run / Stop, and Card settings). The status is polled while a run goes, so the lines stay live here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchStatusSheet(s: WhatsAppSearch, c: ChatColors, onDismiss: () -> Unit) {
    val inConv = !s.conv.available || s.mode == SearchMode.CONVERSATIONS
    val status = s.conv.status
    val index = indexView(status, inConv)
    val cards = cardsView(status, inConv)
    // Nothing left to show (the feature went off, the mode changed): close rather than sit empty.
    if (!index.visible && !cards.visible) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.wall,
        contentColor = c.ink,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Search status", style = chatBodyStyle(c.ink).copy(fontWeight = FontWeight.SemiBold))
            if (index.visible) StatusLine(Icons.Filled.Sync, index, c, busy = s.conv.indexBusy, onClick = s.conv::requestIndex)
            if (cards.visible) {
                StatusLine(
                    Icons.Filled.AutoAwesome, cards, c, busy = s.conv.cardsBusy,
                    onClick = { s.conv.toggleCards(cards.running) },
                    // The settings sheet is its own sheet: this one steps aside first.
                    onSettings = {
                        onDismiss()
                        s.conv.cardSettings.show()
                    },
                )
            }
        }
    }
}

/** One line: icon, its words (wrapping), and the button it offers; the cards line also has Card settings. */
@Composable
private fun StatusLine(icon: ImageVector, line: LineView, c: ChatColors, busy: Boolean, onClick: () -> Unit, onSettings: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = c.meta, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(line.text, style = chatSecondaryStyle(c.ink), modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        if (line.button.isNotEmpty()) {
            TextButton(onClick = onClick, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(line.button, style = chatSecondaryStyle(c.accent, FontWeight.SemiBold))
            }
        }
        if (onSettings != null) {
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Tune, contentDescription = "Card settings", tint = c.accent)
            }
        }
    }
}
