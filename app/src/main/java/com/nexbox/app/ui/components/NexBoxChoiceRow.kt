package com.nexbox.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.nexbox.app.ui.theme.LocalNexBoxExtendedColors
import com.nexbox.app.ui.theme.NexBoxShapes
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.app.ui.theme.miuixChoiceLabelStyle

/** Miuix selection visual only; callers still own selection, dismissal and persistence. */
@Composable
internal fun NexBoxChoiceRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supporting: String? = null,
    role: Role = Role.RadioButton
) {
    val colors = MaterialTheme.colorScheme
    val foreground = when {
        !enabled -> LocalNexBoxExtendedColors.current.disabledContent
        selected -> colors.onPrimaryContainer
        else -> colors.onSurface
    }
    val action = if (role == Role.Checkbox) Modifier.toggleable(selected, enabled = enabled, role = role) { onClick() }
        else Modifier.selectable(selected, enabled = enabled, role = role, onClick = onClick)
    Surface(color = if (enabled && selected) colors.primaryContainer
        else colors.surfaceContainerHigh,
        contentColor = foreground, shape = NexBoxShapes.choice, modifier = modifier.fillMaxWidth().then(action)) {
        Row(Modifier.heightIn(min = NexBoxSpacing.touchTarget)
            .padding(horizontal = NexBoxSpacing.choiceHorizontalPadding, vertical = NexBoxSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NexBoxSpacing.sm)) {
            Column(Modifier.weight(1f)) {
                Text(text, style = miuixChoiceLabelStyle(selected))
                if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall,
                    color = if (enabled && !selected) colors.onSurfaceVariant else foreground)
            }
            Box(Modifier.size(NexBoxSpacing.xxl)) {
                if (role == Role.Checkbox) Checkbox(selected, null, Modifier.clearAndSetSemantics {}, enabled)
                else if (selected) Icon(Icons.Default.Check, null)
            }
        }
    }
}
