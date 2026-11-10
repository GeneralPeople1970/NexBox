package com.nexbox.app.view.setting

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexbox.app.BuildConfig
import com.nexbox.app.R
import com.nexbox.app.data.UpdateRelease
import com.nexbox.app.data.UpdateRepository
import com.nexbox.app.data.UpdateState
import com.nexbox.app.ui.components.Button
import com.nexbox.app.ui.components.CircularProgressIndicator
import com.nexbox.app.ui.components.Icon
import com.nexbox.app.ui.components.LocalSettingsLayout
import com.nexbox.app.ui.components.NexBoxCard
import com.nexbox.app.ui.components.NexBoxNavRow
import com.nexbox.app.ui.components.NexBoxSectionHeader
import com.nexbox.app.ui.components.NexBoxTopAppBar
import com.nexbox.app.ui.components.Scaffold
import com.nexbox.app.ui.components.Switch
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.components.TextButton
import com.nexbox.app.ui.components.usesMiuix
import com.nexbox.app.ui.icons.filled.ChevronRight
import com.nexbox.app.ui.icons.filled.CloudSync
import com.nexbox.app.ui.icons.filled.Description
import com.nexbox.app.ui.icons.filled.SystemUpdate
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.app.util.ProjectBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.Instant
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSettingScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val repository = remember(context) { UpdateRepository.get(context) }
    val state by repository.state.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    val miuix = usesMiuix()
    val pageInset = if (miuix) NexBoxSpacing.settingsPageInset else NexBoxSpacing.screenPadding
    var savingPreference by remember { mutableStateOf(false) }
    val saveAutoCheck: (Boolean) -> Unit = { enabled ->
        if (!savingPreference) {
            savingPreference = true
            scope.launch {
                try {
                    val saved = withContext(Dispatchers.IO) { repository.setAutoCheckEnabled(enabled) }
                    if (!saved) {
                        Toast.makeText(context, R.string.update_setting_failed, Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    savingPreference = false
                }
            }
        }
    }

    CompositionLocalProvider(LocalSettingsLayout provides true) {
        Scaffold(topBar = {
            NexBoxTopAppBar(title = stringResource(R.string.about_check_updates), onBack = onBack)
        }) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = pageInset,
                    end = pageInset,
                    top = innerPadding.calculateTopPadding() + NexBoxSpacing.lg,
                    bottom = innerPadding.calculateBottomPadding() + NexBoxSpacing.lg
                ),
                verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.sectionGap)
            ) {
                item(key = "update_controls") {
                    NexBoxCard {
                        NexBoxNavRow(
                            icon = Icons.Default.CloudSync,
                            title = stringResource(R.string.update_auto_check),
                            subtitle = stringResource(R.string.update_auto_check_summary),
                            onClick = if (savingPreference) null else ({ saveAutoCheck(!state.autoCheckEnabled) }),
                            trailing = {
                                Switch(
                                    checked = state.autoCheckEnabled,
                                    enabled = !savingPreference,
                                    onCheckedChange = saveAutoCheck
                                )
                            }
                        )
                        NexBoxNavRow(
                            icon = Icons.Default.SystemUpdate,
                            title = stringResource(R.string.update_current_version),
                            subtitle = BuildConfig.VERSION_NAME,
                            trailing = {},
                            showDivider = false
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(NexBoxSpacing.cardPadding),
                            verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.md)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(NexBoxSpacing.md)
                            ) {
                                if (state.checking) {
                                    CircularProgressIndicator(modifier = Modifier.size(NexBoxSpacing.navIcon))
                                }
                                Text(
                                    text = updateStatusText(state),
                                    color = if (state.errorCode != null && !state.checking) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            if (state.lastCheckedAt > 0L) {
                                Text(
                                    text = stringResource(
                                        R.string.update_last_checked,
                                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
                                            .format(Date(state.lastCheckedAt))
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.checking,
                                onClick = { scope.launch { repository.check(source = "manual") } }
                            ) {
                                Text(stringResource(if (state.checking) R.string.update_checking else R.string.about_check_updates))
                            }
                        }
                    }
                }
                item(key = "history_title") {
                    NexBoxSectionHeader(text = stringResource(R.string.update_history))
                }
                if (state.releases.isEmpty()) {
                    item(key = "empty_history") {
                        NexBoxCard {
                            Text(
                                text = stringResource(R.string.update_history_empty),
                                modifier = Modifier.align(Alignment.CenterHorizontally).padding(NexBoxSpacing.xxl),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                } else {
                    itemsIndexed(state.releases, key = { _, release -> release.tag }) { index, release ->
                        UpdateHistoryCard(release = release, initiallyExpanded = index == 0)
                    }
                }
            }
        }
    }
}

@Composable
private fun updateStatusText(state: UpdateState): String = when {
    state.checking -> stringResource(R.string.update_checking)
    state.errorCode != null -> stringResource(when (state.errorCode) {
        "network_error" -> R.string.update_error_network
        "http_404" -> R.string.update_error_unavailable
        "rate_limited" -> R.string.update_error_rate_limited
        "no_stable_release" -> R.string.update_error_no_release
        "current_version_invalid" -> R.string.update_error_current_version
        "storage_error" -> R.string.update_error_storage
        else -> if (state.errorCode.startsWith("http_")) R.string.update_error_unavailable
            else R.string.update_error_response
    })
    state.latest == null -> stringResource(R.string.update_not_checked)
    state.updateAvailable -> stringResource(R.string.update_available, state.latest?.version.orEmpty())
    else -> stringResource(R.string.update_up_to_date)
}

@Composable
private fun UpdateHistoryCard(release: UpdateRelease, initiallyExpanded: Boolean) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var expanded by rememberSaveable(release.tag) { mutableStateOf(initiallyExpanded) }
    val notes = if (configuration.locales[0].language == "zh") {
        release.notesZh.ifBlank { release.notesEn }
    } else release.notesEn.ifBlank { release.notesZh }
    val publishedDate = remember(release.publishedAt, configuration) {
        runCatching {
            DateFormat.getDateInstance(DateFormat.MEDIUM, configuration.locales[0])
                .format(Date(Instant.parse(release.publishedAt).toEpochMilli()))
        }.getOrNull()
    }
    NexBoxCard {
        NexBoxNavRow(
            icon = Icons.Default.Description,
            title = release.version.ifBlank { release.tag },
            subtitle = publishedDate,
            showDivider = false,
            onClick = { expanded = !expanded },
            trailing = {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = stringResource(if (expanded) R.string.update_collapse_notes else R.string.update_expand_notes),
                    modifier = Modifier.rotate(if (expanded) 90f else 0f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = NexBoxSpacing.cardPadding)
                    .padding(bottom = NexBoxSpacing.md),
                verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.sm)
            ) {
                Text(
                    text = notes.ifBlank { stringResource(R.string.update_notes_empty) },
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = { ProjectBrowser.open(context, release.url) }) {
                    Text(stringResource(R.string.update_view_release))
                }
            }
        }
    }
}
