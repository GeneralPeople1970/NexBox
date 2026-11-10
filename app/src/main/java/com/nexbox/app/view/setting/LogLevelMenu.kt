package com.nexbox.app.view.setting

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import com.nexbox.app.ui.icons.filled.FilterList
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.nexbox.app.R
import com.nexbox.app.ui.components.Icon
import com.nexbox.app.ui.components.IconButton
import com.nexbox.app.ui.components.usesMiuix
import com.nexbox.app.ui.components.NexBoxMenuEntry
import com.nexbox.app.ui.components.NexBoxMenuPopup
import com.nexbox.app.util.NexLog

/** Native menus in both skins, anchored to the top-right filter action. */
@Composable
internal fun LogLevelMenu(
    selected: NexLog.Level?,
    minimumLevel: NexLog.Level = NexLog.Level.VERBOSE,
    onSelect: (NexLog.Level?) -> Unit
) {
    val expanded = remember { mutableStateOf(false) }
    val levels = remember(minimumLevel) {
        if (minimumLevel == NexLog.Level.VERBOSE) listOf(null) + NexLog.Level.entries
        else NexLog.Level.entries.filter { it.priority >= minimumLevel.priority }
    }
    val labels = levels.map { logLevelLabel(it) }
    val selection = levels.indexOf(selected)
    val description = stringResource(R.string.logs_filter_action)
    Box {
        IconButton(onClick = { expanded.value = !expanded.value },
            modifier = Modifier.semantics {
                contentDescription = description
                stateDescription = labels[selection]
            }) {
            Icon(Icons.Default.FilterList, contentDescription = null,
                tint = if (selected == null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary)
        }
        NexBoxMenuPopup(show = expanded, selectable = true) { dismiss ->
            labels.forEachIndexed { index, label ->
                NexBoxMenuEntry(label, index, labels.size,
                    checked = selection == index,
                    onClick = {
                        // The native exit animation may retain rows; commit the choice once.
                        if (expanded.value) { dismiss(); onSelect(levels[index]) }
                    })
            }
        }
    }
}

@Composable
internal fun logLevelLabel(level: NexLog.Level?): String = stringResource(when (level) {
    null -> R.string.logs_filter_all
    NexLog.Level.VERBOSE -> R.string.logs_level_verbose
    NexLog.Level.DEBUG -> R.string.logs_level_debug
    NexLog.Level.INFO -> R.string.logs_level_information
    NexLog.Level.WARN -> R.string.logs_level_warning
    NexLog.Level.ERROR -> R.string.logs_level_error
})

@Composable
internal fun logLevelColor(level: NexLog.Level): Color = when (level) {
    NexLog.Level.VERBOSE, NexLog.Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    NexLog.Level.INFO -> MaterialTheme.colorScheme.primary
    // Miuix's bridged tertiary is a container-like secondary, not a text accent:
    // on its light log surface it is indistinguishable from the background.
    NexLog.Level.WARN -> if (usesMiuix()) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.tertiary
    NexLog.Level.ERROR -> MaterialTheme.colorScheme.error
}
