package com.nexbox.app.view.fake

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nexbox.app.R
import com.nexbox.app.data.MapTileProvider
import com.nexbox.app.data.MapTileSettings
import com.nexbox.app.data.XyzMapSource
import com.nexbox.app.ui.components.AlertDialog
import com.nexbox.app.ui.components.Icon
import com.nexbox.app.ui.components.OutlinedTextField
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.components.TextButton
import com.nexbox.app.ui.components.ListItem
import com.nexbox.app.ui.components.usesMiuix
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex

internal fun XyzMapSource.asTileSource(): XYTileSource = object : XYTileSource(
    cacheName, 0, maxZoom, 256, ".png", arrayOf(template.substringBefore("/{z}")), attribution
) {
    override fun getTileURLString(pMapTileIndex: Long): String = tileUrl(
        MapTileIndex.getZoom(pMapTileIndex), MapTileIndex.getX(pMapTileIndex), MapTileIndex.getY(pMapTileIndex)
    )
}

@Composable
internal fun MapSourceDialog(
    settings: MapTileSettings,
    saving: Boolean,
    saveFailed: Boolean,
    onSave: (MapTileSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var provider by rememberSaveable { mutableStateOf(settings.provider) }
    var template by rememberSaveable { mutableStateOf(settings.customTemplate) }
    var attribution by rememberSaveable { mutableStateOf(settings.customAttribution) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        contentScrollable = false,
        title = { Text(stringResource(R.string.map_tile_source)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MapTileProvider.entries.forEach { choice ->
                    val label = stringResource(when (choice) {
                        MapTileProvider.OPEN_STREET_MAP_DE -> R.string.map_source_osm_de
                        MapTileProvider.OPEN_STREET_MAP -> R.string.map_source_osm
                        MapTileProvider.CUSTOM -> R.string.map_source_custom
                    })
                    if (usesMiuix()) ListItem(
                        headlineContent = {
                            Text(label, color = if (choice == provider) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface)
                        },
                        modifier = Modifier.fillMaxWidth().selectable(
                            selected = choice == provider, enabled = !saving, role = Role.RadioButton,
                            onClick = { provider = choice; invalid = false }
                        )
                    )
                    else TextButton(
                        onClick = { provider = choice; invalid = false }, enabled = !saving,
                        modifier = Modifier.fillMaxWidth().semantics { selected = choice == provider }
                    ) {
                        Text(label, modifier = Modifier.weight(1f))
                        if (choice == provider) Icon(Icons.Default.Check, null)
                    }
                }
                if (provider == MapTileProvider.CUSTOM) {
                    OutlinedTextField(
                        template, { template = it; invalid = false }, Modifier.fillMaxWidth(), enabled = !saving,
                        label = { Text(stringResource(R.string.map_source_url)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true
                    )
                    OutlinedTextField(
                        attribution, { attribution = it; invalid = false }, Modifier.fillMaxWidth(), enabled = !saving,
                        label = { Text(stringResource(R.string.map_source_attribution)) }, singleLine = true
                    )
                    Text(stringResource(R.string.map_source_help), style = MaterialTheme.typography.bodySmall)
                }
                if (invalid || saveFailed) Text(
                    stringResource(if (invalid) R.string.map_source_invalid else R.string.map_source_save_failed),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                val candidate = MapTileSettings(provider, template.trim(), attribution.trim())
                if (candidate.isValid()) onSave(candidate) else invalid = true
            }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) } }
    )
}
