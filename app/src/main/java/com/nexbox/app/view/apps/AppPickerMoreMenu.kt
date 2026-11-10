package com.nexbox.app.view.apps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.nexbox.app.ui.icons.automirrored.filled.Sort
import com.nexbox.app.ui.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nexbox.app.R
import com.nexbox.app.data.AppPickerOptions
import com.nexbox.app.data.AppPickerSort
import com.nexbox.app.ui.components.Icon
import com.nexbox.app.ui.components.IconButton
import com.nexbox.app.ui.components.NexBoxMenuEntry
import com.nexbox.app.ui.components.NexBoxMenuPopup
import com.nexbox.app.ui.theme.NexBoxSpacing

@Composable
internal fun AppPickerMoreMenu(
    options: AppPickerOptions,
    update: ((AppPickerOptions) -> AppPickerOptions) -> Unit,
    onOpen: () -> Unit = {},
    onImport: () -> Unit = {},
    onRefresh: () -> Unit = {},
    importEnabled: Boolean = true,
    refreshEnabled: Boolean = true
) {
    val expanded = remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    val menu: @Composable (() -> Unit) -> Unit = { dismiss ->
        fun closeThen(action: () -> Unit) {
            // Native exit animations retain the menu briefly; an action commits only once.
            if (expanded.value) {
                expanded.value = false
                dismiss()
                action()
            }
        }
        if (sorting) {
            val count = AppPickerSort.entries.size + 1
            NexBoxMenuEntry(stringResource(R.string.app_picker_sort), 0, count,
                icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = { if (expanded.value) sorting = false })
            AppPickerSort.entries.forEachIndexed { index, sort ->
                NexBoxMenuEntry(stringResource(sort.label()), index + 1, count,
                    checked = options.sort == sort,
                    onClick = { closeThen { update { it.copy(sort = sort) } } })
            }
        } else {
            NexBoxMenuEntry(stringResource(R.string.install_from_file), 0, 6,
                icon = Icons.Default.FolderOpen,
                enabled = importEnabled, onClick = { closeThen(onImport) })
            NexBoxMenuEntry(stringResource(R.string.xp_refresh), 1, 6,
                icon = Icons.Default.Refresh,
                enabled = refreshEnabled, onClick = { closeThen(onRefresh) })
            NexBoxMenuEntry(stringResource(R.string.app_picker_sort), 2, 6,
                icon = Icons.AutoMirrored.Filled.Sort, forward = true,
                onClick = { if (expanded.value) sorting = true })
            NexBoxMenuEntry(stringResource(R.string.app_picker_reverse), 3, 6,
                checked = options.reverse, toggle = true,
                onClick = { if (expanded.value) update { it.copy(reverse = !it.reverse) } })
            NexBoxMenuEntry(stringResource(R.string.app_picker_show_package), 4, 6,
                checked = options.showPackageName, toggle = true,
                onClick = { if (expanded.value) update { it.copy(showPackageName = !it.showPackageName) } })
            NexBoxMenuEntry(stringResource(R.string.app_picker_show_system), 5, 6,
                checked = options.showSystemApps, toggle = true,
                onClick = { if (expanded.value) update { it.copy(showSystemApps = !it.showSystemApps) } })
        }
    }
    Box {
        IconButton(onClick = { onOpen(); sorting = false; expanded.value = !expanded.value },
            modifier = Modifier.size(NexBoxSpacing.touchTarget)) {
            Icon(Icons.Default.MoreVert, stringResource(R.string.app_picker_more))
        }
        NexBoxMenuPopup(show = expanded, selectable = true, content = menu)
    }
}

private fun AppPickerSort.label(): Int = when (this) {
    AppPickerSort.NAME -> R.string.app_picker_sort_name
    AppPickerSort.PACKAGE_NAME -> R.string.app_picker_sort_package
    AppPickerSort.APK_SIZE -> R.string.app_picker_sort_size
    AppPickerSort.FIRST_INSTALL_TIME -> R.string.app_picker_sort_first_install
    AppPickerSort.LAST_UPDATE_TIME -> R.string.app_picker_sort_last_update
}
