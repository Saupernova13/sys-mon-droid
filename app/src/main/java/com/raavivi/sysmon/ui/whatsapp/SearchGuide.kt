package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val GuideIcons: List<ImageVector> = listOf(Icons.Filled.ChatBubbleOutline, Icons.Filled.Search, Icons.Filled.TouchApp, Icons.Filled.ThumbUp)
private val TagsGuideIcons: List<ImageVector> = listOf(Icons.Filled.Sell, Icons.Filled.Tune, Icons.Filled.SwapVert, Icons.Filled.TouchApp)

/**
 * How to search in [mode]: Conversations gets four rows (icon, a line, a hint) and example queries that run
 * when tapped through [onExample]; Tags gets four rows of its own; Messages gets its few operators. Shared by the idle view and the help sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchGuide(mode: SearchMode, c: ChatColors, onExample: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (mode == SearchMode.CONVERSATIONS) {
            Text("How to search", style = chatBodyStyle(c.ink).copy(fontWeight = FontWeight.SemiBold))
            ConversationGuide.forEachIndexed { i, row ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(GuideIcons[i], contentDescription = null, tint = c.meta, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.title, style = chatSecondaryStyle(c.ink, FontWeight.SemiBold))
                        Text(row.hint, style = chatSecondaryStyle(c.meta))
                    }
                }
            }
            Column {
                Text("Try", style = chatMetaStyle(c.meta).copy(fontWeight = FontWeight.SemiBold))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (q in ConversationExamples) SearchPill(q, on = false, c = c, onClick = { onExample(q) })
                }
            }
        } else if (mode == SearchMode.TAGS) {
            TagsGuide.forEachIndexed { i, row ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(TagsGuideIcons[i], contentDescription = null, tint = c.meta, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.title, style = chatSecondaryStyle(c.ink, FontWeight.SemiBold))
                        Text(row.hint, style = chatSecondaryStyle(c.meta))
                    }
                }
            }
        } else {
            Text("Tips", style = chatBodyStyle(c.ink).copy(fontWeight = FontWeight.SemiBold))
            for (tip in MessageTips) Text(tip, style = chatSecondaryStyle(c.meta))
        }
    }
}

/** The help button at the end of the search field row. */
@Composable
internal fun HelpButton(c: ChatColors, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "How search works", tint = c.meta)
    }
}

/** The guide in a bottom sheet; tapping an example closes it and runs that query. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchHelpSheet(mode: SearchMode, c: ChatColors, onExample: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.wall,
        contentColor = c.ink,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SearchGuide(mode, c, { onDismiss(); onExample(it) }, Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp))
        }
    }
}
