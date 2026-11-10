package com.nexbox.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import com.nexbox.app.ui.icons.filled.Apps
import com.nexbox.app.ui.icons.filled.ChevronRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexbox.app.R
import com.nexbox.app.data.ScopeApp
import com.nexbox.app.data.AppIconLoader
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.app.view.apps.rememberAppBitmap

/** Scope emptiness is a status, never an invitation to leave settings and clone an app. */
@Composable
fun ScopeAppEmptyState(modifier: Modifier = Modifier) {
    NexBoxEmptyState(message = stringResource(R.string.scope_apps_empty), modifier = modifier)
}

/** Shared layout, with buttons/surfaces/feedback supplied by the active theme. */
@Composable
fun ScopeAppRow(
    app: ScopeApp,
    checked: Boolean,
    enabled: Boolean = true,
    updating: Boolean = false,
    onOpen: (() -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    onToggle: (Boolean) -> Unit
) {
    val actionModifier = if (onOpen != null) {
        Modifier.clickable(enabled = enabled && !updating, role = Role.Button, onClick = onOpen)
    } else {
        Modifier.toggleable(checked, enabled = enabled && !updating, role = Role.Switch, onValueChange = onToggle)
    }
    NexBoxCard {
        NexBoxLoadingOverlay(updating, status = stringResource(R.string.updating_named, app.label)) {
            Row(
                Modifier.fillMaxWidth().then(actionModifier).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val bitmap = rememberAppBitmap(app.iconKey, null, true) { AppIconLoader.load(app.iconKey) }
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(44.dp))
                } else {
                    Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(app.label, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    app.versionName?.takeIf { it.isNotBlank() }?.let { version ->
                        Text(version, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                badge?.invoke()
                Switch(
                    checked = checked,
                    onCheckedChange = if (onOpen != null) onToggle else null,
                    enabled = enabled && !updating,
                    modifier = if (onOpen != null) Modifier.semantics { contentDescription = app.label } else Modifier
                )
                if (onOpen != null) {
                    Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun ScopeGroupHeader(title: String) {
    val modifier = Modifier.padding(start = NexBoxSpacing.lg, top = NexBoxSpacing.sm)
        .semantics { heading() }
    if (usesMiuix()) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f), modifier = modifier)
    } else {
        NexBoxSectionHeader(title, modifier)
    }
}
