package com.raavivi.sysmon.ui.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.DriveInfo
import com.raavivi.sysmon.core.model.Favorite
import com.raavivi.sysmon.ui.common.Metric
import com.raavivi.sysmon.ui.common.UsageBar
import com.raavivi.sysmon.ui.common.band
import com.raavivi.sysmon.ui.common.formatBytes
import com.raavivi.sysmon.ui.theme.SysMon

/**
 * Collapsible "Places" block above the listing: a scrolling row of drive cards
 * (capacity meter in the drive hue) and a row of favourites. Every entry is one tap.
 */
@Composable
fun PlacesSection(
    vm: FilesViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (vm.drives.isEmpty() && vm.favorites.isEmpty()) return
    val palette = SysMon.palette
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Places",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = palette.quietInk,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse places" else "Expand places",
                tint = palette.quietInk,
            )
        }
        if (!expanded) return@Column
        if (vm.drives.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                vm.drives.forEachIndexed { i, d ->
                    DriveCard(d, i, current = vm.currentDriveIndex == i, onClick = { vm.open(d.mountpoint) })
                }
            }
        }
        if (vm.favorites.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                vm.favorites.forEach { f ->
                    FavoriteChip(
                        f,
                        canRemove = vm.isAdmin,
                        onOpen = { vm.open(f.path) },
                        onRemove = { vm.removeFavorite(f.path) },
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun DriveCard(d: DriveInfo, index: Int, current: Boolean, onClick: () -> Unit) {
    val palette = SysMon.palette
    val hue = palette.driveHues[index % palette.driveHues.size]
    val hueBg = palette.driveHueBgs[index % palette.driveHueBgs.size]
    val shape = RoundedCornerShape(palette.cardRadius)
    val pct = driveUsedPct(d)
    Column(
        Modifier
            .width(168.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, if (current) hue else palette.hairline, shape)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(if (palette.isInstrument) 2.dp else 8.dp))
                    .background(hueBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Storage, contentDescription = null, tint = hue, modifier = Modifier.size(18.dp))
            }
            Text(
                driveName(d),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        if (pct != null) {
            UsageBar(
                fraction = (pct / 100.0).toFloat(),
                color = hue,
                barHeight = 6,
                state = band(Metric.DiskUsed, pct),
            )
            Text(
                "${formatBytes(d.free)} free of ${formatBytes(d.total)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = if (palette.monoNumbers) FontFamily.Monospace else null,
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Text(
                d.fstype.ifBlank { "unreadable" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteChip(f: Favorite, canRemove: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    val palette = SysMon.palette
    val shape = RoundedCornerShape(if (palette.isInstrument) 2.dp else 16.dp)
    Row(
        Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, palette.hairline, shape)
            .combinedClickable(onClick = onOpen, onLongClick = if (canRemove) onRemove else null)
            .padding(start = 10.dp, end = if (canRemove) 4.dp else 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Star, contentDescription = null, tint = palette.fsStar, modifier = Modifier.size(16.dp))
        Text(
            f.name.ifBlank { f.path },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 6.dp)
                .widthIn(max = 160.dp),
        )
        if (canRemove) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove from favorites",
                tint = palette.quietInk,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onRemove)
                    .padding(4.dp)
                    .size(16.dp),
            )
        }
    }
}

