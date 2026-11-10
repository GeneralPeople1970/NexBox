package com.nexbox.app.view.apps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.nexbox.app.R
import com.nexbox.app.data.UsersRepository
import com.nexbox.app.ui.components.AlertDialog
import com.nexbox.app.ui.components.DestructiveButton
import com.nexbox.app.ui.components.FilledTonalButton
import com.nexbox.app.ui.components.MiuixDialogButtonRole
import com.nexbox.app.ui.components.LocalMiuixDialogButtonRole
import com.nexbox.app.ui.components.OutlinedTextField
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.components.TextButton
import com.nexbox.app.ui.theme.LocalNexBoxExtendedColors
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.core.core.system.user.BUserInfo

@Composable
fun UserActionsDialog(user: BUserInfo, busy: Boolean, onDismiss: () -> Unit,
    onRename: (String) -> Unit, onDelete: () -> Unit) {
    val displayName = UsersRepository.displayName(user)
    var mode by rememberSaveable(user.id, user.createTime) { mutableStateOf("menu") }
    var name by rememberSaveable(user.id, user.createTime) { mutableStateOf(displayName) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                displayName,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center
            )
        },
        text = {
            when (mode) {
                "rename" -> OutlinedTextField(value = name,
                    onValueChange = { if (!busy && it.length <= 32) name = it }, enabled = !busy,
                    singleLine = true, label = { Text(stringResource(R.string.user_name)) })
                "delete" -> Text(stringResource(R.string.user_delete_warning, displayName),
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                else -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    TextButton(modifier = Modifier.fillMaxWidth(), enabled = !busy,
                        onClick = { if (!busy) mode = "rename" }) {
                        Text(stringResource(R.string.user_rename), color = if (busy)
                            LocalNexBoxExtendedColors.current.disabledContent else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
        confirmButton = {
            if (mode == "menu") {
                UserActionButtonRow(busy = busy,
                    onDelete = { if (!busy) mode = "delete" }, onCancel = { if (!busy) onDismiss() })
            } else if (mode == "delete") {
                UserActionButtonRow(busy = busy,
                    onDelete = { if (!busy) onDelete() }, onCancel = { if (!busy) onDismiss() })
        } else if (mode == "rename") {
            TextButton(enabled = !busy && name.isNotBlank(),
                onClick = { if (!busy && name.isNotBlank()) onRename(name.trim()) }) {
                    Text(stringResource(R.string.done))
                }
            }
        },
        dismissButton = if (mode == "rename") {
            {
                FilledTonalButton(enabled = !busy, onClick = { if (!busy) onDismiss() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        } else null
    )
}

/** The management dialog keeps the destructive action on the left and Cancel on the right. */
@Composable
private fun UserActionButtonRow(busy: Boolean, onDelete: () -> Unit, onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NexBoxSpacing.sm)
    ) {
        CompositionLocalProvider(LocalMiuixDialogButtonRole provides MiuixDialogButtonRole.Destructive) {
            DestructiveButton(
                modifier = Modifier.weight(1f),
                enabled = !busy,
                onClick = onDelete
            ) {
                Text(stringResource(R.string.user_delete))
            }
        }
        CompositionLocalProvider(LocalMiuixDialogButtonRole provides MiuixDialogButtonRole.Secondary) {
            FilledTonalButton(
                modifier = Modifier.weight(1f),
                enabled = !busy,
                onClick = onCancel
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}
