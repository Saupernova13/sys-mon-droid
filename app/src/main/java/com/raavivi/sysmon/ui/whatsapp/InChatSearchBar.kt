package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The bar under the conversation header: query field, hit counter, older / newer buttons and close. The
 * state (query, hits, selection, paging) lives in [InChatSearchState]; this only draws it. Enter on the
 * keyboard steps to the older hit.
 */
@Composable
internal fun InChatSearchBar(state: InChatSearchState, c: ChatColors, modifier: Modifier = Modifier) {
    val buttons = state.buttons
    Row(
        modifier
            .fillMaxWidth()
            .background(lerp(c.wall, c.inBg, 0.7f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatSearchField(
            value = state.query,
            onValueChange = state::onQueryChange,
            c = c,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            placeholder = "Search in this chat",
            onSearch = { state.step(1) },
            // A bar opened by a hit already has its query; do not pop the keyboard over the message.
            autoFocus = state.query.isEmpty(),
        )
        Text(
            state.countLabel,
            style = chatMetaStyle(c.meta),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 72.dp).padding(horizontal = 6.dp),
        )
        HeaderAction(Icons.Filled.KeyboardArrowUp, "Older match", c, { state.step(1) }, enabled = !buttons.olderOff)
        HeaderAction(Icons.Filled.KeyboardArrowDown, "Newer match", c, { state.step(-1) }, enabled = !buttons.newerOff)
        HeaderAction(Icons.Filled.Close, "Close search", c, { state.close() })
    }
}
