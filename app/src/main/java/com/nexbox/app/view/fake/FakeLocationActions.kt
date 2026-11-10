package com.nexbox.app.view.fake

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import com.nexbox.app.R
import com.nexbox.app.ui.components.Button
import com.nexbox.app.ui.components.OutlinedButton
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.components.usesMiuix
import com.nexbox.app.ui.theme.MiuixActionLabelStyle
import com.nexbox.app.ui.theme.NexBoxSpacing

/** Native themed buttons with full, single-line action names at ordinary and large font scales. */
@Composable
internal fun FakeLocationActions(
    simulationEnabled: Boolean,
    enabled: Boolean,
    onApply: () -> Unit,
    onSecondary: () -> Unit,
    secondaryLabel: String,
    showSecondary: Boolean,
    secondaryEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    applyLabelOverride: String? = null
) {
    val applyLabel = applyLabelOverride ?: stringResource(
        if (simulationEnabled) R.string.location_update_position else R.string.location_enable_simulation
    )
    val miuix = usesMiuix()
    val labelStyle = if (miuix) MiuixActionLabelStyle else MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widestLabel = maxOf(
        measurer.measure(AnnotatedString(applyLabel), labelStyle, softWrap = false, maxLines = 1).size.width,
        measurer.measure(AnnotatedString(secondaryLabel), labelStyle, softWrap = false, maxLines = 1).size.width
    )
    val horizontalPadding = if (miuix) NexBoxSpacing.buttonHorizontalPadding else NexBoxSpacing.xxl
    val minimumButtonWidth = with(density) { widestLabel.toDp() } + horizontalPadding * 2
    val apply: @Composable (Modifier) -> Unit = { buttonModifier ->
        Button(onClick = onApply, enabled = enabled,
            modifier = buttonModifier) {
            Text(applyLabel, style = labelStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
    val secondary: @Composable (Modifier) -> Unit = { buttonModifier ->
        OutlinedButton(onClick = onSecondary, enabled = enabled && secondaryEnabled,
            modifier = buttonModifier) {
            Text(secondaryLabel, style = labelStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
    BoxWithConstraints(modifier) {
        if (!showSecondary) {
            apply(Modifier.fillMaxWidth())
        } else if ((maxWidth - NexBoxSpacing.sm) / 2 >= minimumButtonWidth) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(NexBoxSpacing.sm)) {
                apply(Modifier.weight(1f).fillMaxHeight())
                secondary(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            // Narrow windows and accessibility fonts get two full-width buttons, not wrapped labels.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.sm)) {
                apply(Modifier.fillMaxWidth())
                secondary(Modifier.fillMaxWidth())
            }
        }
    }
}
