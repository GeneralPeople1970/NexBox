package com.nexbox.app.view.setting

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import com.nexbox.app.ui.icons.filled.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexbox.app.R
import com.nexbox.app.ui.components.AlertDialog
import com.nexbox.app.ui.components.DestructiveButton
import com.nexbox.app.ui.components.HorizontalDivider
import com.nexbox.app.ui.components.Icon
import com.nexbox.app.ui.components.IconButton
import com.nexbox.app.ui.components.LocalSettingsLayout
import com.nexbox.app.ui.components.NexBoxCard
import com.nexbox.app.ui.components.NexBoxCompactActionButton
import com.nexbox.app.ui.components.NexBoxEmptyState
import com.nexbox.app.ui.components.NexBoxEmptyStateArrangement
import com.nexbox.app.ui.components.NexBoxTopAppBar
import com.nexbox.app.ui.components.NexBoxSearchField
import com.nexbox.app.ui.components.rememberNexBoxSearchState
import com.nexbox.app.ui.components.dismissSearchFocusOnBlankTap
import com.nexbox.app.ui.components.Scaffold
import com.nexbox.app.ui.components.Surface
import com.nexbox.app.ui.components.Switch
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.components.TextButton
import com.nexbox.app.ui.components.usesMiuix
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.app.ui.theme.LocalNexBoxExtendedColors
import com.nexbox.app.util.LogSettings
import com.nexbox.app.util.LogQuery
import com.nexbox.app.util.NexLog
import com.nexbox.app.util.newestLogsFirst
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val entries by NexLog.entries.collectAsStateWithLifecycle()
    val settings by NexLog.settings.collectAsStateWithLifecycle()
    val storageError by NexLog.storageError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    fun reportSaveResult(saved: Boolean) {
        if (!saved) Toast.makeText(context, R.string.logs_settings_failed, Toast.LENGTH_SHORT).show()
    }
    fun export(diagnostics: Boolean, filter: LogQuery = LogQuery()) {
        if (exporting) return
        val capture = NexLog.capture()
        val currentFilter = filter.withSettings(capture.configuration.settings)
        val snapshot = if (diagnostics) capture.entries else capture.entries.filter(currentFilter::matches)
        exporting = true
        NexLog.i("Diagnostics", "action=export diagnostics=$diagnostics snapshotEntries=${snapshot.size}")
        scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    if (diagnostics) NexLog.exportDiagnosticsForShare() else NexLog.exportForShare(snapshot, "filtered session logs")
                } ?: error("Log export unavailable")
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = if (diagnostics) "application/zip" else "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(context.getString(R.string.logs_title), uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, context.getString(R.string.logs_share_title)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                NexLog.e("Diagnostics", "action=export diagnostics=$diagnostics failed", error)
                Toast.makeText(context, R.string.logs_export_failed, Toast.LENGTH_SHORT).show()
            } finally { exporting = false }
        }
    }
    LogScreenContent(
        entries = entries,
        settings = settings,
        onRecordingChange = { reportSaveResult(NexLog.setRecordingEnabled(it)) },
        onAdbChange = { reportSaveResult(NexLog.setAdbEnabled(it)) },
        onDetailedChange = { reportSaveResult(NexLog.setDetailedEnabled(it)) },
        onClear = { reportSaveResult(NexLog.clear()) },
        onExportDiagnostics = { export(true) },
        storageError = storageError,
        exporting = exporting,
        onExport = { export(false, it) },
        onBack = onBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogScreenContent(
    entries: List<NexLog.Entry>,
    settings: LogSettings,
    onRecordingChange: (Boolean) -> Unit,
    onAdbChange: (Boolean) -> Unit,
    onDetailedChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    onExport: (LogQuery) -> Unit,
    exporting: Boolean = false,
    onExportDiagnostics: (() -> Unit)? = null,
    storageError: String? = null,
    onBack: () -> Unit = {}
) {
    var minLevel by rememberSaveable { mutableStateOf<NexLog.Level?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val search = rememberNexBoxSearchState()
    val query = search.query
    // Retained popup/lazy subcompositions read this render's immutable filter snapshot.
    val selectedLevel = settings.displayLevel(minLevel)
    val defaultLevel = settings.displayLevel(null)
    val newest = remember(entries) { entries.newestLogsFirst(null) }
    val filter = remember(settings, selectedLevel, query) { LogQuery.forDisplay(settings, selectedLevel, query) }
    val visible = remember(newest, filter) {
        newest.filter(filter::matches)
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val actionsEnabled = visible.isNotEmpty() && !exporting
    val clearEnabled = !exporting && (entries.isNotEmpty() || onExportDiagnostics != null)

    Scaffold(modifier = Modifier.dismissSearchFocusOnBlankTap(search), topBar = {
        Column {
            NexBoxTopAppBar(
                title = stringResource(R.string.logs_title),
                onBack = onBack,
                actions = { LogLevelMenu(selectedLevel, settings.minimumDisplayLevel) { minLevel = it } }
            )
            NexBoxSearchField(search, stringResource(R.string.logs_search),
                Modifier.padding(horizontal = NexBoxSpacing.screenPadding, vertical = NexBoxSpacing.sm),
                maxQueryLength = 160)
        }
    }) { innerPadding ->
        // A single scrolling surface stays usable in landscape and at large font scales.
        // Stable entry IDs retain the reading position when new logs arrive.
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(NexBoxSpacing.screenPadding),
            verticalArrangement = if (visible.isEmpty()) NexBoxEmptyStateArrangement(setOf(2)) else Arrangement.Top
        ) {
            item(key = "controls", contentType = "controls") {
                CompositionLocalProvider(LocalSettingsLayout provides true) {
                    NexBoxCard {
                        LogSwitchRow(
                            title = stringResource(R.string.logs_recording_title),
                            checked = settings.recordingEnabled,
                            onCheckedChange = onRecordingChange
                        )
                        HorizontalDivider(Modifier.padding(horizontal = NexBoxSpacing.cardPadding))
                        LogSwitchRow(
                            title = stringResource(R.string.logs_adb_title),
                            checked = settings.adbEnabled,
                            onCheckedChange = onAdbChange
                        )
                        HorizontalDivider(Modifier.padding(horizontal = NexBoxSpacing.cardPadding))
                        LogSwitchRow(
                            title = stringResource(R.string.logs_detailed_title),
                            checked = settings.detailedEnabled,
                            onCheckedChange = onDetailedChange
                        )
                    }
                }
                storageError?.let { detail ->
                    Text(stringResource(R.string.logs_storage_error, detail),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = NexBoxSpacing.sm))
                }
                onExportDiagnostics?.let { exportDiagnostics ->
                    NexBoxCompactActionButton(onClick = exportDiagnostics, enabled = !exporting,
                        modifier = Modifier.padding(top = NexBoxSpacing.sm)) {
                        Text(stringResource(R.string.logs_export_diagnostics))
                    }
                }
            }
            item(key = "toolbar", contentType = "toolbar") {
                Row(Modifier.padding(top = NexBoxSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.logs_session_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    NexBoxCompactActionButton(onClick = { onExport(filter) }, enabled = actionsEnabled) {
                        Text(stringResource(if (exporting) R.string.logs_exporting else R.string.logs_export))
                    }
                    IconButton(onClick = { confirmClear = true }, enabled = clearEnabled,
                        modifier = Modifier.semantics {
                            // Miuix IconButton removes its click action when disabled, but does
                            // not expose Disabled. Retain an explicit accessible state here.
                            if (!clearEnabled) disabled()
                        }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.logs_clear),
                            tint = if (clearEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                                else LocalNexBoxExtendedColors.current.disabledContent)
                    }
                }
                Text(
                    text = stringResource(
                        if (settings.recordingEnabled) R.string.logs_recording_status else R.string.logs_stopped_status,
                        visible.size, entries.size
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (selectedLevel == null) logLevelLabel(null)
                        else stringResource(R.string.logs_filter_threshold, logLevelLabel(selectedLevel)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = NexBoxSpacing.xs, bottom = NexBoxSpacing.md)
                )
            }
            if (visible.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    val filteredOut = entries.isNotEmpty()
                    NexBoxEmptyState(
                        message = stringResource(when {
                            filteredOut && query.isNotBlank() -> R.string.logs_search_empty
                            filteredOut && selectedLevel == defaultLevel && !settings.detailedEnabled -> R.string.logs_no_warnings
                            filteredOut -> R.string.logs_filtered_empty
                            !settings.recordingEnabled -> R.string.logs_recording_off
                            else -> R.string.logs_empty
                        }),
                        action = when {
                            filteredOut && (query.isNotBlank() || selectedLevel != defaultLevel) -> {{ NexBoxCompactActionButton(onClick = { minLevel = null; search.close() }) {
                                Text(stringResource(R.string.logs_reset_filter))
                            } }}
                            !settings.recordingEnabled -> {{ NexBoxCompactActionButton(onClick = { onRecordingChange(true) }) {
                                Text(stringResource(R.string.logs_enable_recording))
                            } }}
                            else -> null
                        }
                    )
                }
            } else {
                items(visible, key = { it.id }, contentType = { "log" }) { entry ->
                    LogEntryRow(entry, timeFormat.format(Date(entry.timeMillis)))
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.logs_clear_title)) },
            text = { Text(stringResource(R.string.logs_clear_message)) },
            confirmButton = { DestructiveButton(onClick = { confirmClear = false; onClear() }) {
                Text(stringResource(R.string.logs_clear)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun LogSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(NexBoxSpacing.cardPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NexBoxSpacing.md)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun LogEntryRow(entry: NexLog.Entry, time: String) {
    Surface(color = if (usesMiuix()) MaterialTheme.colorScheme.surfaceContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(NexBoxSpacing.md),
            verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.xs)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NexBoxSpacing.sm)) {
                Text(logLevelLabel(entry.level), color = logLevelColor(entry.level),
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text(time, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.xs)) {
                    Text(entry.tag, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    entry.origin?.let { origin ->
                        Text(origin.label(), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                    }
                    Text(entry.message, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
    }
    HorizontalDivider()
}
