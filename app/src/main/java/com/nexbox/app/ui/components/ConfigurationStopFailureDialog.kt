package com.nexbox.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.nexbox.app.R
import com.nexbox.core.utils.ConfigurationStopException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A committed setting is retained. Retrying never rotates identifiers or writes the setting again. */
@Composable
fun ConfigurationStopFailureDialog(failure: ConfigurationStopException?, onDismiss: () -> Unit) {
    if (failure == null) return
    var remaining by remember(failure) { mutableStateOf<ConfigurationStopException>(failure) }
    var busy by remember(failure) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        text = { Text(stringResource(R.string.config_saved_stop_failed)) },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { remaining.retry() }
                        onDismiss()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: ConfigurationStopException) {
                        remaining = failure
                    } finally { busy = false }
                }
            }) { Text(stringResource(R.string.config_retry_stop)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
