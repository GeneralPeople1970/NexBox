package com.nexbox.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexbox.app.ui.theme.LocalNexBoxDarkTheme
import com.nexbox.app.ui.theme.LocalNexBoxExtendedColors
import com.nexbox.app.ui.theme.NexBoxSpacing

private val CompactControlHeight = 32.dp
private val CompactControlShape = RoundedCornerShape(16.dp)
private val CompactControlLabel = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    fontWeight = FontWeight.Medium
)

/** A small, solid row action with a separate 48dp accessible touch target. */
@Composable
fun NexBoxCompactActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val dark = LocalNexBoxDarkTheme.current
    val primary = if (destructive) MaterialTheme.colorScheme.surfaceContainerHighest else compactControlColor()
    val background = if (enabled) primary else primary.copy(alpha = if (dark) 0.2f else 0.12f)
    val foreground = if (enabled) {
        if (destructive) MaterialTheme.colorScheme.error else compactControlContentColor()
    }
        else LocalNexBoxExtendedColors.current.disabledContent
    val overlayAlpha = when {
        !enabled -> 0f
        pressed -> 0.08f
        hovered -> 0.06f
        else -> 0f
    }
    val overlay = if (dark) Color.White else Color.Black
    Box(
        modifier = modifier
            .sizeIn(minWidth = NexBoxSpacing.touchTarget, minHeight = NexBoxSpacing.touchTarget)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled,
                role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.heightIn(min = CompactControlHeight)
                .clip(CompactControlShape).background(background)
                .drawWithContent {
                    drawContent()
                    if (overlayAlpha > 0f) drawRect(overlay.copy(alpha = overlayAlpha))
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompositionLocalProvider(LocalTextStyle provides CompactControlLabel) {
                NexBoxContentColor(foreground) { content() }
            }
        }
    }
}

/** Non-interactive status uses the same visual scale as compact list actions. */
@Composable
fun NexBoxCompactBadge(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.heightIn(min = CompactControlHeight)
            .clip(CompactControlShape).background(compactControlColor())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(text, color = compactControlContentColor(), style = CompactControlLabel)
    }
}

@Composable
private fun compactControlColor(): Color =
    MaterialTheme.colorScheme.primary

@Composable
private fun compactControlContentColor(): Color =
    if (usesMiuix()) Color.White else MaterialTheme.colorScheme.onPrimary
