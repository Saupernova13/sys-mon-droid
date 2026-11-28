package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.WaConvResult

/**
 * What the card sheet needs for "More like this": the shown card's [list] (null: not asked for yet), what
 * the buttons do, and whether the sheet was opened from a row ([canGoBack]).
 */
internal class SimilarUi(
    val list: SimilarList?,
    val canGoBack: Boolean,
    val onMore: () -> Unit,
    val onRetry: () -> Unit,
    val onOpen: (WaConvResult) -> Unit,
    val onBack: () -> Unit,
)

/**
 * The end of the card sheet's body: the "More like this" button, then "Finding similar…", then up to 8 rows
 * (or "Nothing like this yet", or the reason it failed with "Try again"). Card text is never logged.
 */
@Composable
internal fun SimilarSection(vm: WhatsAppViewModel, ui: SimilarUi, c: ChatColors, nowSec: Double) {
    val list = ui.list
    when {
        list == null -> OutlinedButton(
            onClick = ui.onMore,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            border = BorderStroke(1.dp, c.hairline),
        ) {
            Text("More like this", style = chatSecondaryStyle(c.accent, FontWeight.SemiBold))
        }
        list.phase == SimilarPhase.LOADING -> Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Finding similar…", style = chatSecondaryStyle(c.meta))
        }
        list.phase == SimilarPhase.ERROR -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                list.message ?: SIMILAR_FAILED,
                style = chatSecondaryStyle(c.meta),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = ui.onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Try again", style = chatSecondaryStyle(c.accent, FontWeight.SemiBold))
            }
        }
        list.items.isEmpty() -> Text("Nothing like this yet", style = chatSecondaryStyle(c.meta))
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "More like this",
                style = chatSecondaryStyle(c.ink, FontWeight.SemiBold),
                modifier = Modifier.semantics { heading() },
            )
            for (item in list.items) SimilarRow(vm, item, c, nowSec) { ui.onOpen(item) }
        }
    }
}

/** One similar conversation: its title (or chat name), "<chat name> · <range>", and a chevron; the whole row opens its card. */
@Composable
private fun SimilarRow(vm: WhatsAppViewModel, r: WaConvResult, c: ChatColors, nowSec: Double, onOpen: () -> Unit) {
    val name = r.chatName.ifBlank { vm.searchChatName(r.chat, "") }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.inBg)
            .clickable(onClickLabel = "Open its card", role = Role.Button, onClick = onOpen)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                similarRowTitle(r, name),
                style = chatBodyStyle(c.ink).copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                similarRowMeta(name, convRange(r.startTs, r.endTs, nowSec)),
                style = chatMetaStyle(c.meta),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.meta, modifier = Modifier.size(20.dp))
    }
}

/** The sheet header's 48 dp back button, shown when the card was opened from "More like this". */
@Composable
internal fun SimilarBackButton(c: ChatColors, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the previous card", tint = c.ink)
        }
    }
}
