package com.nexbox.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import com.nexbox.app.ui.icons.filled.ChevronRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import com.nexbox.app.ui.theme.NexBoxSpacing
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.SpinnerDefaults
import top.yukonga.miuix.kmp.basic.SpinnerEntry
import top.yukonga.miuix.kmp.basic.SpinnerItemImpl
import top.yukonga.miuix.kmp.extra.LocalWindowListPopupState
import top.yukonga.miuix.kmp.extra.WindowListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Place beside the action anchor inside a Box. Both skins retain their native popup. */
@Composable
fun NexBoxMenuPopup(
    show: MutableState<Boolean>,
    modifier: Modifier = Modifier,
    selectable: Boolean = false,
    content: @Composable (dismiss: () -> Unit) -> Unit
) {
    val groupModifier = modifier.then(if (selectable) Modifier.selectableGroup() else Modifier)
    if (usesMiuix()) {
        WindowListPopup(show = show, alignment = PopupPositionProvider.Align.End,
            onDismissRequest = { show.value = false }) {
            val dismiss = LocalWindowListPopupState.current
            NexBoxContentColor(MiuixTheme.colorScheme.onSurfaceContainer) {
                Box(groupModifier, propagateMinConstraints = true) {
                    ListPopupColumn { content { show.value = false; dismiss() } }
                }
            }
        }
    } else {
        DropdownMenu(expanded = show.value, onDismissRequest = { show.value = false },
            modifier = groupModifier) { content { show.value = false } }
    }
}

/** Shared native rows for app-picker, log filters and other overflow menus. */
@Composable
fun NexBoxMenuEntry(
    label: String,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    checked: Boolean? = null,
    toggle: Boolean = false,
    forward: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val activate = { if (enabled) onClick() }
    val entryRole = if (checked == null) Role.Button else if (toggle) Role.Checkbox else Role.RadioButton
    if (usesMiuix()) {
        val foreground = if (enabled) MiuixTheme.colorScheme.onSurfaceContainer
            else MiuixTheme.colorScheme.onSurfaceVariantSummary
        val interaction = when {
            checked == null -> Modifier.clickable(enabled = enabled, role = entryRole, onClick = activate)
            toggle -> Modifier.toggleable(checked, enabled = enabled, role = entryRole) { activate() }
            else -> Modifier.selectable(checked, enabled = enabled, role = entryRole, onClick = activate)
        }
        // MIUIx 0.8.6 rows do not accept Modifier/selection semantics. Expose one target
        // around the native typography, checkmark, spacing and pressed-state rendering.
        Box(modifier.then(interaction).semantics { contentDescription = label }, propagateMinConstraints = true) {
            Box(Modifier.clearAndSetSemantics {}.focusProperties { canFocus = false }, propagateMinConstraints = true) {
                if (icon != null) {
                    SpinnerItemImpl(
                        // SpinnerItemImpl colors its text but does not provide the icon slot's tint.
                        entry = SpinnerEntry(icon = { Icon(icon, null, it, tint = foreground) }, title = label),
                        entryCount = count, isSelected = false, index = index,
                        spinnerColors = SpinnerDefaults.spinnerColors(
                            contentColor = foreground
                        ), onSelectedIndexChange = { activate() }
                    )
                } else {
                    DropdownImpl(text = label, optionSize = count, isSelected = checked == true, index = index,
                        dropdownColors = DropdownDefaults.dropdownColors(
                            contentColor = foreground,
                            selectedContentColor = if (enabled) MiuixTheme.colorScheme.primary else foreground
                        ), onSelectedIndexChange = { activate() })
                }
            }
        }
    } else {
        DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = activate,
            leadingIcon = icon?.let { vector -> { Icon(vector, null) } },
            modifier = modifier.semantics {
                role = entryRole
                if (checked != null) {
                    if (toggle) toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                    else selected = checked
                }
            }, trailingIcon = {
                Box(Modifier.size(NexBoxSpacing.xxl)) {
                    if (checked == true) Icon(Icons.Default.Check, null)
                    else if (forward) Icon(Icons.Default.ChevronRight, null)
                }
            })
    }
}
