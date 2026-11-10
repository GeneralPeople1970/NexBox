package com.nexbox.app.view.spoof

import com.nexbox.app.util.NexLog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexbox.app.R
import com.nexbox.app.ui.components.*
import com.nexbox.app.ui.theme.NexBoxSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nexbox.core.fake.device.DeviceCatalog
import com.nexbox.app.data.DeviceRepository
import com.nexbox.core.fake.device.DeviceSpoofProfile

@Composable
fun DeviceModelControls(profile: DeviceSpoofProfile, onChange: (DeviceSpoofProfile) -> Unit) {
    var showCatalog by rememberSaveable { mutableStateOf(false) }
    val mode = when (profile.buildMode) {
        DeviceSpoofProfile.MODE_HOST -> R.string.device_mode_host
        DeviceSpoofProfile.MODE_TEMPLATE -> R.string.device_mode_template
        DeviceSpoofProfile.MODE_BASIC -> R.string.device_mode_basic
        else -> R.string.device_mode_legacy
    }
    Text(stringResource(mode), style = MaterialTheme.typography.titleSmall)
    if (profile.isBasicOnly) Text(stringResource(R.string.device_basic_warning), style = MaterialTheme.typography.bodySmall)
    Button(onClick = { showCatalog = true }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.device_catalog_open))
    }
    if (showCatalog) DeviceCatalogDialog(
        onDismiss = { showCatalog = false },
        onSelected = { entry ->
            onChange(DeviceRepository.selectCatalogEntry(profile, entry))
            showCatalog = false
        }
    )
}

@Composable
internal fun DeviceCatalogDialog(onDismiss: () -> Unit, onSelected: (DeviceCatalog.Entry) -> Unit) {
    val context = LocalContext.current
    var catalog by remember { mutableStateOf<DeviceCatalog?>(null) }
    var failed by remember { mutableStateOf(false) }
    val search = rememberNexBoxSearchState()
    val query = search.query
    var compatibleOnly by rememberSaveable { mutableStateOf(true) }
    var matches by remember { mutableStateOf<List<DeviceCatalog.Entry>>(emptyList()) }
    var selected by remember { mutableStateOf<DeviceCatalog.Entry?>(null) }
    LaunchedEffect(Unit) {
        try { catalog = withContext(Dispatchers.IO) { DeviceCatalog.get(context) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { NexLog.e("DeviceCatalog", "action=load failed", error); failed = true }
    }
    LaunchedEffect(catalog, query, compatibleOnly) {
        selected = null
        matches = emptyList()
        matches = withContext(Dispatchers.Default) { catalog?.search(query, compatibleOnly).orEmpty() }
        NexLog.d("DeviceCatalog", "action=filter queryLength=${query.length} compatibleOnly=$compatibleOnly matches=${matches.size}")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        searchState = search,
        contentScrollable = false,
        title = { Text(stringResource(R.string.device_catalog_open)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).dismissSearchFocusOnBlankTap(search)) {
                NexBoxSearchField(search, stringResource(R.string.device_catalog_search))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.device_catalog_compatible_only), modifier = Modifier.weight(1f))
                    Switch(checked = compatibleOnly, onCheckedChange = { compatibleOnly = it })
                }
                when {
                    failed -> Text(stringResource(R.string.device_catalog_failed))
                    catalog == null -> CircularProgressIndicator()
                    else -> {
                        Text(stringResource(R.string.device_catalog_count, matches.size))
                        LazyColumn(Modifier.heightIn(min = 80.dp, max = 220.dp).fillMaxWidth()) {
                            items(matches, key = { it.id }) { entry ->
                                if (usesMiuix()) NexBoxChoiceRow(
                                    text = entry.manufacturer + " " + entry.modelName,
                                    selected = selected?.id == entry.id, onClick = { selected = entry },
                                    modifier = Modifier.padding(vertical = NexBoxSpacing.xs),
                                    supporting = entry.device)
                                else Column(Modifier.fillMaxWidth().clickable { selected = entry }.padding(vertical = NexBoxSpacing.sm)) {
                                    Text((if (selected?.id == entry.id) "✓ " else "") + entry.manufacturer + " " + entry.modelName)
                                    Text(entry.device, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Button(onClick = { selected = matches.randomOrNull() }, enabled = matches.isNotEmpty()) {
                            Text(stringResource(R.string.device_catalog_random))
                        }
                    }
                }
                selected?.let { entry ->
                    Text(entry.details(), style = MaterialTheme.typography.bodySmall, maxLines = 4)
                    Text(stringResource(R.string.device_basic_warning),
                        style = MaterialTheme.typography.bodySmall)
                }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected != null, onClick = { selected?.let(onSelected) }) {
                Text(stringResource(R.string.device_catalog_use))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
