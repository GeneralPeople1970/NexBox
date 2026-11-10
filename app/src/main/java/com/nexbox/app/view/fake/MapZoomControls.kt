package com.nexbox.app.view.fake

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nexbox.app.R
import com.nexbox.app.ui.components.HorizontalDivider
import com.nexbox.app.ui.components.IconButton
import com.nexbox.app.ui.components.Surface
import com.nexbox.app.ui.components.usesMiuix
import com.nexbox.app.ui.theme.LocalNexBoxExtendedColors
import com.nexbox.app.ui.theme.NexBoxShapes
import com.nexbox.app.ui.theme.NexBoxSpacing

@Composable
internal fun MapZoomControls(
    canZoomIn: Boolean,
    canZoomOut: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.width(NexBoxSpacing.touchTarget),
        shape = if (usesMiuix()) NexBoxShapes.control else MaterialTheme.shapes.medium,
        color = if (usesMiuix()) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            MapZoomButton(true, canZoomIn, onZoomIn)
            HorizontalDivider(Modifier.padding(horizontal = NexBoxSpacing.sm))
            MapZoomButton(false, canZoomOut, onZoomOut)
        }
    }
}

@Composable
private fun MapZoomButton(zoomIn: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(if (zoomIn) R.string.map_zoom_in else R.string.map_zoom_out)
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else LocalNexBoxExtendedColors.current.disabledContent
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(NexBoxSpacing.touchTarget).semantics {
            contentDescription = description
            if (!enabled) disabled()
        }) {
        Canvas(Modifier.size(20.dp)) {
            drawLine(color, Offset(0f, center.y), Offset(size.width, center.y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            if (zoomIn) drawLine(color, Offset(center.x, 0f), Offset(center.x, size.height), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}
