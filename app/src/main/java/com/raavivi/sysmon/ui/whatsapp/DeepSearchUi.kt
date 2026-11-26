package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The Deep switch in the controls row: a Filters-style pill that TalkBack reads as a switch and explains. */
@Composable
internal fun DeepPill(conv: ConvSearchState, c: ChatColors) {
    SearchPill(
        "Deep", on = conv.deep, c = c, onClick = { conv.setDeep(!conv.deep) }, icon = Icons.AutoMirrored.Filled.ManageSearch,
        semanticsOverride = {
            role = Role.Switch
            toggleableState = ToggleableState(conv.deep)
            stateDescription = if (conv.deep) "On" else "Off"
            contentDescription = "Deep search. $DEEP_DESCRIPTION"
        },
    )
}

/** The line over the skeleton while a deep search runs; announced politely. */
@Composable
internal fun DeepSearchingLine(c: ChatColors) {
    Text(
        DEEP_SEARCHING,
        style = chatSecondaryStyle(c.meta),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * What the server read from the query: "Read as" and one outlined tag per reading (not tappable, no fill and no
 * accent, so they never look like the theme chips), then the imagined text on a line of its own. One TalkBack
 * node. Nothing read: a single plain line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReadAsBlock(conv: ConvSearchState, c: ChatColors, modifier: Modifier = Modifier) {
    val rewrite = conv.rewrite ?: return
    val tags = readAsTags(rewrite)
    val imagined = readAsImagined(rewrite)
    val note = modelNote(rewrite.model)
    Column(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).clearAndSetSemantics { contentDescription = readAsDescription(rewrite) },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (tags.isEmpty() && imagined == null) {
            Text(READ_AS_NOTHING, style = chatMetaStyle(c.meta))
            if (note != null) Text(note, style = chatMetaStyle(c.meta).copy(fontStyle = FontStyle.Italic))
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // The same 4 dp (plus the 1 dp border) as the tags, so the label sits on their line.
            Text("Read as", style = chatMetaStyle(c.meta), modifier = Modifier.padding(vertical = 5.dp))
            for (t in tags) {
                Text(
                    t,
                    style = chatMetaStyle(c.meta),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 280.dp).border(BorderStroke(1.dp, c.hairline), CircleShape).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        if (imagined != null) {
            Text("imagined: $imagined", style = chatMetaStyle(c.meta).copy(fontStyle = FontStyle.Italic), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (note != null) Text(note, style = chatMetaStyle(c.meta).copy(fontStyle = FontStyle.Italic))
    }
}
