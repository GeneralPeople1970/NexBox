package com.nexbox.app.view.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import com.nexbox.app.ui.icons.filled.Download
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.nexbox.app.R
import com.nexbox.app.ui.components.FilledTonalButton
import com.nexbox.app.ui.components.DestructiveButton
import com.nexbox.app.ui.components.Icon
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.components.TextButton
import com.nexbox.app.ui.components.usesMiuix
import com.nexbox.app.ui.theme.NexBoxSpacing

@Composable
internal fun GalleryMediaActions(
    enabled: Boolean,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NexBoxSpacing.md)) {
        GalleryMediaAction(Icons.Default.Download, stringResource(R.string.gallery_export), enabled,
            onExport, Modifier.weight(1f))
        GalleryMediaAction(Icons.Default.Delete, stringResource(R.string.nexbox_gallery_delete), enabled,
            onDelete, Modifier.weight(1f), destructive = true)
    }
}

@Composable
private fun GalleryMediaAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    destructive: Boolean = false
) {
    val content: @Composable RowScope.() -> Unit = {
        val tint = if (destructive && enabled) MaterialTheme.colorScheme.error else LocalContentColor.current
        Icon(icon, null, Modifier.padding(end = NexBoxSpacing.sm), tint = tint)
        Text(label, color = if (destructive && enabled) tint else Color.Unspecified)
    }
    if (destructive) DestructiveButton(onClick, modifier, enabled, content)
    else if (usesMiuix()) FilledTonalButton(onClick, modifier, enabled, content)
    else TextButton(onClick, modifier, enabled, content)
}
