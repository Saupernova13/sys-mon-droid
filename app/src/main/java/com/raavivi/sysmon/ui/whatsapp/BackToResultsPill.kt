package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The small floating "Back to results" pill over a chat that a search result opened: a 48 dp touch target
 * around a 36 dp accent pill. Whether it shows is [backToResultsVisible]; the Search view comes back as it was.
 */
@Composable
internal fun BackToResultsPill(c: ChatColors, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.heightIn(min = 48.dp).semantics { role = Role.Button }.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.clip(CircleShape).background(c.accent).heightIn(min = 36.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Back to results", style = chatSecondaryStyle(c.onAccent, FontWeight.SemiBold))
        }
    }
}
