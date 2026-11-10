package com.nexbox.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import com.nexbox.app.ui.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.nexbox.app.ui.theme.NexBoxSpacing
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.extra.LocalWindowListPopupState
import top.yukonga.miuix.kmp.extra.WindowListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「标题 + 当前值 + 上下箭头」的选择行，点击就地弹出选项菜单。
 *
 * 两套皮肤各用原生菜单材质与动画，锚点始终是行尾的当前值与箭头，不是整行左边。
 * Miuix 使用 WindowDropdown 的原生组件组合，无需把 PopupHost 加进 Material Scaffold；
 * 薄适配层只补充其 0.8.6 菜单项缺少的选中语义和重复选择保护。
 *
 * @param options 选项文案，调用方负责本地化。
 * @param selectedIndex [options] 中的当前下标。
 */
@Composable
fun NexBoxPickerRow(
    icon: ImageVector,
    title: String,
    value: String,
    options: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    showDivider: Boolean = true,
    modifier: Modifier = Modifier
) {
    val expanded = remember { mutableStateOf(false) }
    val rowModifier = modifier.semantics {
        role = Role.DropdownList
        stateDescription = value
    }
    val select: (Int) -> Unit = { index ->
        // Native popup exit animations keep their content alive briefly. Ignore a second
        // touch/accessibility action once closing has begun, before the new value recomposes.
        if (expanded.value) {
            expanded.value = false
            if (index != selectedIndex) onSelectedIndexChange(index)
        }
    }

    if (usesMiuix()) {
        MiuixPickerRow(icon, title, value, options, selectedIndex, expanded, select, rowModifier)
    } else {
        MaterialPickerRow(icon, title, value, options, selectedIndex, expanded, select, rowModifier)
    }
    if (showDivider && !usesMiuixSettingsLayout()) HorizontalDivider()
}

@Composable
private fun MaterialPickerRow(
    icon: ImageVector,
    title: String,
    value: String,
    options: List<String>,
    selectedIndex: Int,
    expanded: MutableState<Boolean>,
    onSelect: (Int) -> Unit,
    modifier: Modifier
) {
    val density = LocalDensity.current
    var anchorWidth by remember { mutableIntStateOf(0) }
    var menuWidth by remember(options, density) { mutableIntStateOf(0) }
    val positionedMenuWidth = menuWidth

    ListItem(
        headlineContent = { Text(text = title) },
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        trailingContent = {
            Box(Modifier.onSizeChanged { anchorWidth = it.width }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(
                        imageVector = Icons.Default.UnfoldMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = NexBoxSpacing.xs).size(NexBoxSpacing.xl)
                    )
                }
                DropdownMenu(
                    expanded = expanded.value,
                    onDismissRequest = { expanded.value = false },
                    // Align the menu's natural width to the anchor's logical end. Material mirrors
                    // this offset in RTL and still owns window-edge clamping/above-below placement.
                    // No screen coordinates, status-bar compensation or fixed negative offset.
                    offset = with(density) {
                        DpOffset(if (positionedMenuWidth == 0) 0.dp else (anchorWidth - positionedMenuWidth).toDp(), 0.dp)
                    },
                    modifier = Modifier.onSizeChanged { menuWidth = it.width }
                        .graphicsLayer {
                            // Layout can report a size before this composition's position updates.
                            alpha = if (positionedMenuWidth > 0 && positionedMenuWidth == menuWidth) 1f else 0f
                        }
                        .selectableGroup(),
                    containerColor = if (positionedMenuWidth == 0) Color.Transparent
                        else MenuDefaults.containerColor,
                    shadowElevation = if (positionedMenuWidth == 0) 0.dp else MenuDefaults.ShadowElevation
                ) {
                    options.forEachIndexed { index, label ->
                        val isSelected = index == selectedIndex
                        DropdownMenuItem(
                            text = {
                                Text(label, color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface)
                            },
                            onClick = { onSelect(index) },
                            modifier = Modifier.semantics {
                                role = Role.RadioButton
                                selected = isSelected
                                contentDescription = label
                            },
                            trailingIcon = {
                                Box(Modifier.size(NexBoxSpacing.xxl)) {
                                    if (isSelected) Icon(Icons.Default.Check, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        )
                    }
                }
            }
        },
        modifier = modifier.clickable(enabled = options.isNotEmpty(), role = Role.DropdownList) {
            expanded.value = true
        }
    )
}

@Composable
private fun MiuixPickerRow(
    icon: ImageVector,
    title: String,
    value: String,
    options: List<String>,
    selectedIndex: Int,
    expanded: MutableState<Boolean>,
    onSelect: (Int) -> Unit,
    modifier: Modifier
) {
    val haptics = LocalHapticFeedback.current
    BasicComponent(
        title = title,
        modifier = modifier,
        startAction = if (LocalSettingsLayout.current) null else { { Icon(icon, contentDescription = null) } },
        endActions = {
            // WindowListPopup measures its parent, matching WindowDropdown's endActions anchor.
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiuixText(
                    text = value,
                    modifier = Modifier.padding(end = NexBoxSpacing.sm).weight(1f, fill = false),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                DropdownArrowEndAction(MiuixTheme.colorScheme.onSurfaceVariantActions)
                WindowListPopup(
                    show = expanded,
                    alignment = PopupPositionProvider.Align.End,
                    onDismissRequest = { expanded.value = false }
                ) {
                    val dismiss = LocalWindowListPopupState.current
                    Box(Modifier.selectableGroup(), propagateMinConstraints = true) {
                        ListPopupColumn {
                            options.forEachIndexed { index, label ->
                                val isSelected = index == selectedIndex
                                val select = {
                                    if (expanded.value) {
                                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                        onSelect(index)
                                        dismiss()
                                    }
                                }
                                // Miuix 0.8.6's DropdownImpl has no item Modifier/selected semantics.
                                // Keep its native visuals and pointer feedback; expose exactly one
                                // selectable, keyboard-focusable target to accessibility services.
                                Box(
                                    Modifier.selectable(selected = isSelected, role = Role.RadioButton, onClick = select)
                                        .semantics { contentDescription = label },
                                    propagateMinConstraints = true
                                ) {
                                    Box(Modifier.clearAndSetSemantics {}.focusProperties { canFocus = false },
                                        propagateMinConstraints = true) {
                                        DropdownImpl(
                                            text = label,
                                            optionSize = options.size,
                                            isSelected = isSelected,
                                            index = index,
                                            onSelectedIndexChange = { select() }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        onClick = {
            expanded.value = !expanded.value
            if (expanded.value) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        },
        holdDownState = expanded.value,
        enabled = options.isNotEmpty()
    )
}
