package com.nexbox.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import com.nexbox.app.ui.theme.LocalNexBoxDarkTheme
import com.nexbox.core.utils.compat.SystemPropertiesCompat

internal enum class MiuixDialogButtonRole { Primary, Secondary, Destructive }

internal val LocalMiuixDialogButtonRole = staticCompositionLocalOf<MiuixDialogButtonRole?> { null }

/** The public facade keeps every action/enable guard; only its dialog-slot presentation changes. */
@Composable
internal fun MiuixDialogButton(
    role: MiuixDialogButtonRole,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    content: @Composable RowScope.() -> Unit
) {
    val dark = LocalNexBoxDarkTheme.current
    val primary = role == MiuixDialogButtonRole.Primary
    val colors = MaterialTheme.colorScheme
    val background = when {
        primary && enabled -> colors.primary
        primary -> colors.primary.copy(alpha = if (dark) 0.2f else 0.1f)
        dark -> Color(0x26FFFFFF)
        else -> Color(0x0F000000)
    }
    val foreground = when {
        primary && !dark -> Color.White
        !enabled && dark -> Color(0x4DFFFFFF)
        !enabled -> Color(0x4D000000)
        role == MiuixDialogButtonRole.Destructive -> colors.error
        primary -> Color(0xE6FFFFFF)
        dark -> Color(0xB3FFFFFF)
        else -> Color(0xCC000000)
    }
    val shape = remember { RoundedRectangle(if (DialogRom.miuiVersion >= 15) 16.dp else 36.dp) }
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val overlayAlpha = when {
        !enabled -> 0f
        pressed -> 0.08f
        hovered -> 0.06f
        else -> 0f
    }
    val overlay = if (dark) Color.White else Color.Black
    Row(
        modifier = modifier
            .heightIn(min = 50.dp)
            .clip(shape)
            .background(background)
            .drawWithContent {
                drawContent()
                if (overlayAlpha > 0f) drawRect(overlay.copy(alpha = overlayAlpha))
            }
            .clickable(interactionSource = interactions, indication = null, enabled = enabled,
                role = Role.Button, onClick = onClick)
            // Both reference layer-list backgrounds specify this 4dp content-safe padding.
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CompositionLocalProvider(LocalTextStyle provides MiuixDialogTypography.Button,
            LocalMiuixDialogButtonRole provides null) {
            NexBoxContentColor(foreground) { content() }
        }
    }
}

/** Empty slots emit no measurables. Two actions stay equal-width or move together to a column. */
@Composable
internal fun MiuixDialogButtons(
    modifier: Modifier,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)?
) {
    Layout(modifier = modifier, content = {
        CompositionLocalProvider(LocalMiuixDialogButtonRole provides MiuixDialogButtonRole.Secondary) {
            dismissButton?.invoke()
        }
        CompositionLocalProvider(LocalMiuixDialogButtonRole provides MiuixDialogButtonRole.Primary) {
            confirmButton()
        }
    }) { actions, constraints ->
        val width = constraints.maxWidth
        val gap = 12.dp.roundToPx()
        if (actions.isEmpty()) {
            layout(width, 0) {}
        } else {
            val slotWidth = ((width - gap * (actions.size - 1)) / actions.size).coerceAtLeast(0)
            val vertical = actions.size >= 3 || (actions.size == 2 &&
                actions.any { it.maxIntrinsicWidth(Constraints.Infinity) > slotWidth })
            if (vertical) {
                // The reference panel has measureWithLargestChild=true in either orientation.
                val buttonHeight = actions.maxOf { it.minIntrinsicHeight(width) }
                    .coerceAtLeast(50.dp.roundToPx())
                val buttons = actions.map { it.measure(Constraints.fixed(width, buttonHeight)) }
                val height = buttonHeight * buttons.size + gap * (buttons.size - 1)
                layout(width, height) {
                    var top = 0
                    buttons.forEach { button ->
                        button.placeRelative(0, top)
                        top += button.height + gap
                    }
                }
            } else {
                val buttonHeight = actions.maxOf { it.minIntrinsicHeight(slotWidth) }
                    .coerceAtLeast(50.dp.roundToPx())
                val buttons = actions.map { it.measure(Constraints.fixed(slotWidth, buttonHeight)) }
                layout(width, buttonHeight) {
                    buttons.forEachIndexed { index, button -> button.placeRelative(index * (slotWidth + gap), 0) }
                }
            }
        }
    }
}

internal object DialogRom {
    val miuiVersion: Int by lazy { SystemPropertiesCompat.getInt("ro.miui.ui.version.code", 0) }
    val characteristics: Set<String> by lazy {
        SystemPropertiesCompat.get("ro.build.characteristics", "").split(',').map { it.trim() }.toSet()
    }
}
