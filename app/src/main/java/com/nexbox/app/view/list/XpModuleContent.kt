package com.nexbox.app.view.list

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import com.nexbox.app.ui.icons.filled.ChevronRight
import com.nexbox.app.ui.icons.filled.Extension
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexbox.app.R
import com.nexbox.app.bean.XpModuleBean
import com.nexbox.app.data.ScopeApp
import com.nexbox.app.data.AppIconLoader
import com.nexbox.app.data.XpModuleRepository
import com.nexbox.app.ui.components.*
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.app.view.apps.rememberAppBitmap

@Composable
internal fun XpModuleRow(module: XpModuleBean, enabled: Boolean, updating: Boolean = false,
                         onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    NexBoxCard {
        NexBoxLoadingOverlay(updating, status = stringResource(R.string.updating_named, module.name)) {
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModuleIcon(module)
                AppLabel(module.name, module.versionName, Modifier.weight(1f))
                val toggleLabel = stringResource(R.string.xp_enable_named, module.name)
                Switch(checked = module.isEnabled, onCheckedChange = onToggle,
                    enabled = enabled && (module.supported || module.isEnabled),
                    modifier = Modifier.semantics { contentDescription = toggleLabel })
                Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun XpTargetRow(app: XpModuleRepository.TargetApp, checked: Boolean, enabled: Boolean, updating: Boolean = false,
                         onToggle: (Boolean) -> Unit) {
    ScopeAppRow(ScopeApp(app.packageName, app.label, app.versionName, app.iconKey), checked, enabled,
        updating = updating, onToggle = onToggle)
}

@Composable
internal fun XpUserGroupHeader(userId: Int) {
    ScopeGroupHeader(rememberUserDisplayName(userId))
}

@Composable
private fun AppLabel(name: String, version: String?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        version?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ModuleIcon(module: XpModuleBean) {
    val bitmap = rememberAppBitmap(module.iconKey, null, true) { AppIconLoader.load(module.iconKey) }
    if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(44.dp))
    else Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(44.dp),
        tint = MaterialTheme.colorScheme.primary)
}
