package com.raavivi.sysmon.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.ui.theme.SysMon

// Rows and tiles for the hub/settings screens. Rows go inside a SectionCard(flush = true)
// so their ripple spans the card. Classic tints icons by meaning; Instrument keeps them
// neutral (interaction is brightness, never hue) and only colours warn/crit actions.

private val RowSidePadding: Dp
    @Composable get() = if (SysMon.palette.isInstrument) 12.dp else 16.dp

/** Ink for an icon or title: [tint] when given, else the theme's default accent. */
@Composable
private fun inkFor(tint: Color): Color = when {
    tint != Color.Unspecified -> tint
    SysMon.palette.isInstrument -> MaterialTheme.colorScheme.onSurface
    else -> MaterialTheme.colorScheme.primary
}

/** A 36dp icon on a soft tinted square, the leading element of every row. */
@Composable
fun IconChip(icon: ImageVector, tint: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    val palette = SysMon.palette
    val ink = inkFor(tint)
    val bg = if (palette.isInstrument && tint == Color.Unspecified) palette.track else ink.copy(alpha = 0.14f)
    Box(
        modifier
            .size(36.dp)
            .clip(RoundedCornerShape(if (palette.isInstrument) palette.cardRadius else 10.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
    }
}

/** Trailing ">" for rows that open another screen. */
@Composable
fun Chevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One list row: icon chip, title, optional subtitle and a trailing slot.
 * [onClick] null makes it static. [tint] colours the chip and, for warn/crit
 * actions, the title too ([tintTitle]). [subtitleColor] highlights a status line.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    tint: Color = Color.Unspecified,
    tintTitle: Boolean = false,
    subtitleColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val clickable = if (onClick != null) {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    RowLayout(title, modifier.then(clickable), icon, subtitle, tint, tintTitle, subtitleColor, enabled, trailing)
}

/** A row whose whole surface toggles [checked]; the switch is display-only. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    tint: Color = Color.Unspecified,
    subtitleColor: Color = Color.Unspecified,
    enabled: Boolean = true,
) {
    RowLayout(
        title = title,
        modifier = modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        icon = icon,
        subtitle = subtitle,
        tint = tint,
        tintTitle = false,
        subtitleColor = subtitleColor,
        enabled = enabled,
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

@Composable
private fun RowLayout(
    title: String,
    modifier: Modifier,
    icon: ImageVector?,
    subtitle: String?,
    tint: Color,
    tintTitle: Boolean,
    subtitleColor: Color,
    enabled: Boolean,
    trailing: @Composable (() -> Unit)?,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = RowSidePadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .alpha(if (enabled) 1f else 0.45f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                IconChip(icon, tint)
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (tintTitle && tint != Color.Unspecified) tint else MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (subtitleColor != Color.Unspecified) subtitleColor
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** A grid tile for a tool: tinted icon over a short label. */
@Composable
fun ActionTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val palette = SysMon.palette
    val ink = inkFor(tint)
    val shape = RoundedCornerShape(if (palette.isInstrument) palette.cardRadius else 16.dp)
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 88.dp)
            .then(if (palette.isInstrument) Modifier.border(1.dp, palette.hairline, shape) else Modifier),
        shape = shape,
        color = if (palette.isInstrument) MaterialTheme.colorScheme.surface else ink.copy(alpha = 0.10f),
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** One entry of an [ActionTileGrid]. */
data class Tile(val label: String, val icon: ImageVector, val tint: Color = Color.Unspecified, val onClick: () -> Unit)

/** [tiles] in rows of [columns]; a short last row keeps tile widths equal. */
@Composable
fun ActionTileGrid(tiles: List<Tile>, modifier: Modifier = Modifier, columns: Int = 3) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t ->
                    ActionTile(t.label, t.icon, t.onClick, Modifier.weight(1f), t.tint)
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
