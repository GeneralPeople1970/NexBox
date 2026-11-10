package com.nexbox.app.view.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexbox.app.R
import com.nexbox.app.bean.AppInfo
import com.nexbox.app.data.CloneTarget
import com.nexbox.app.ui.components.AlertDialog
import com.nexbox.app.ui.components.DestructiveButton
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.components.TextButton
import com.nexbox.app.ui.theme.NexBoxSpacing

internal data class MissingAppSourceGroup(
    val target: CloneTarget,
    val name: String,
    val apps: List<AppInfo>
)

@Composable
internal fun MissingAppSourcesDialog(
    groups: List<MissingAppSourceGroup>,
    actionsEnabled: Boolean,
    onKeep: () -> Unit,
    onDelete: (AppInfo, CloneTarget) -> Unit
) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.clone_source_unavailable)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.sm)) {
                Text(stringResource(R.string.clone_source_restore_hint))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    groups.forEach { group ->
                        val groupKey = "${group.target.userId}:${group.target.createTime}"
                        item(key = "$groupKey:heading") {
                            Text(group.name, style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth().padding(vertical = NexBoxSpacing.sm)
                                    .semantics { heading() })
                        }
                        items(group.apps, key = { "$groupKey:${it.packageName}" }) { app ->
                            Row(Modifier.fillMaxWidth().padding(bottom = NexBoxSpacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(NexBoxSpacing.sm)) {
                                Column(Modifier.weight(1f)) {
                                    Text(app.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (app.name != app.packageName) {
                                        Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                DestructiveButton(onClick = { onDelete(app, group.target) }, enabled = actionsEnabled) {
                                    Text(stringResource(R.string.clone_source_delete))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.clone_source_keep)) } },
        contentScrollable = false
    )
}
