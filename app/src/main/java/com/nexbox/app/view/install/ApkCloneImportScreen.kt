package com.nexbox.app.view.install

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexbox.app.R
import com.nexbox.app.ui.components.AlertDialog
import com.nexbox.app.ui.components.Button
import com.nexbox.app.ui.components.CircularProgressIndicator
import com.nexbox.app.ui.components.NexBoxTopAppBar
import com.nexbox.app.ui.components.Scaffold
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.components.TextButton
import com.nexbox.app.ui.components.rememberUserDisplayName
import com.nexbox.app.ui.theme.NexBoxSpacing

@Composable
fun ApkCloneImportScreen(
    state: ApkCloneImportState,
    onConfirmNewUser: () -> Unit,
    onSkipDuplicate: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler(enabled = state.busy) {
        if (state.pendingPackageName != null) onSkipDuplicate()
    }
    Scaffold(topBar = {
        NexBoxTopAppBar(
            title = stringResource(R.string.apk_install_share_label),
            onBack = if (state.busy) null else onClose
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(NexBoxSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.lg)
        ) {
            item {
                Text(
                    stringResource(when {
                        state.completed -> R.string.apk_clone_import_complete
                        state.pendingPackageName != null -> R.string.apk_clone_import_waiting
                        else -> R.string.apk_install_importing
                    }),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            state.target?.let { target ->
                item { Text(rememberUserDisplayName(target.userId)) }
            }
            item {
                Text(stringResource(R.string.apk_clone_import_summary,
                    state.successful, state.skipped, state.failures.size))
            }
            if (state.busy) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NexBoxSpacing.md)) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp,
                            progress = { state.processed.toFloat() / state.total.coerceAtLeast(1) })
                        Text(stringResource(R.string.appearance_import_progress, state.processed, state.total),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(state.failures, key = { it.itemNumber }) { failure ->
                Text(stringResource(R.string.apk_clone_import_failure, failure.itemNumber, stringResource(failure.message)),
                    color = MaterialTheme.colorScheme.error)
            }
            if (state.completed) {
                item { Button(onClick = onClose) { Text(stringResource(R.string.done)) } }
            }
        }
    }

    state.pendingPackageName?.let { packageName ->
        AlertDialog(
            onDismissRequest = onSkipDuplicate,
            title = { Text(stringResource(R.string.clone_new_user_title)) },
            text = {
                Text(stringResource(R.string.apk_clone_import_duplicate_message, packageName,
                    rememberUserDisplayName(state.target?.userId ?: 0)))
            },
            confirmButton = {
                TextButton(onClick = onConfirmNewUser) { Text(stringResource(R.string.clone_create)) }
            },
            dismissButton = {
                TextButton(onClick = onSkipDuplicate) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
