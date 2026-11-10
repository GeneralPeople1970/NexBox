package com.nexbox.app.view.gallery

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import com.nexbox.app.ui.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nexbox.app.ui.navigation.rememberPageRefreshLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexbox.app.R
import com.nexbox.app.ui.components.AlertDialog
import com.nexbox.app.ui.components.FloatingActionButton
import com.nexbox.app.ui.components.Icon
import com.nexbox.app.ui.components.NexBoxLoadingOverlay
import com.nexbox.app.ui.components.homeActionBottomInset
import com.nexbox.app.ui.components.NexBoxCard
import com.nexbox.app.ui.components.NexBoxEmptyState
import com.nexbox.app.ui.components.NexBoxEmptyStateArrangement
import com.nexbox.app.ui.components.NexBoxPullToRefresh
import com.nexbox.app.ui.components.NexBoxTopAppBar
import com.nexbox.app.ui.components.Scaffold
import com.nexbox.app.ui.components.Surface
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.components.TextButton
import com.nexbox.app.ui.components.DestructiveButton
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.app.util.NexLog
import java.time.LocalDate
import java.time.ZoneId
import com.nexbox.core.media.NexBoxMediaContract

/** Host-only management UI; guests reach the same private library through MediaStore. */
@Composable
fun GalleryScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val refreshLifecycle = rememberPageRefreshLifecycle()
    val model: GalleryViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val actionBottomInset = homeActionBottomInset()
    var confirmDelete by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val dayGroups = remember(state.entries, zone) { state.entries.groupBy { it.galleryDay(zone) } }
    val preview = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        NexLog.i("Gallery", "action=previewResult resultCode=${it.resultCode}")
        model.refresh()
    }
    // Android Photo Picker, OEM system fallback, then ACTION_OPEN_DOCUMENT on older systems.
    // This never enumerates the real gallery or asks for broad storage access.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) {
        NexLog.i("Gallery", "action=systemPickerResult count=${it.size} cancelled=${it.isEmpty()}")
        model.importMedia(it)
    }

    BackHandler(enabled = state.busy || state.selecting) { if (!state.busy) model.endSelection() }
    DisposableEffect(refreshLifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refresh() }
        refreshLifecycle.addObserver(observer)
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = model.refresh()
        }
        context.contentResolver.registerContentObserver(
            NexBoxMediaContract.buildPrivateCollectionUri(MediaStore.Files.FileColumns.MEDIA_TYPE_NONE, NexBoxMediaContract.SHARED_USER_ID),
            true, contentObserver)
        onDispose {
            refreshLifecycle.removeObserver(observer)
            context.contentResolver.unregisterContentObserver(contentObserver)
        }
    }
    LaunchedEffect(state.failureMessage) {
        state.failureMessage?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); model.consumeFailure() }
    }

    Scaffold(
        topBar = {
            NexBoxTopAppBar(
                title = if (state.selecting) stringResource(R.string.gallery_selected_count, state.selected.size)
                    else stringResource(R.string.nexbox_gallery_title),
                onBack = if (state.busy) null else if (state.selecting) model::endSelection else onBack,
                actions = {
                    if (state.selecting) {
                        TextButton(onClick = model::selectAll, enabled = !state.busy) {
                            Text(stringResource(if (state.selected.size == state.entries.size) R.string.gallery_clear_selection else R.string.gallery_select_all))
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (!state.selecting) {
                FloatingActionButton(onClick = {
                    if (state.busy) return@FloatingActionButton
                    val operation = NexLog.operation("Gallery", "openSystemPicker")
                    try {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        operation.finish(detail = "pickerLaunched=true")
                    }
                    catch (failure: Exception) {
                        operation.fail(failure)
                        Toast.makeText(context, R.string.nexbox_gallery_import_failed, Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.padding(bottom = actionBottomInset)
                    .alpha(if (state.busy) 0.38f else 1f).semantics { if (state.busy) disabled() }) {
                    Icon(Icons.Default.Add, stringResource(R.string.gallery_add_media))
                }
            }
        },
        bottomBar = {
            if (state.selecting) {
                Surface {
                    GalleryMediaActions(enabled = !state.busy && state.selected.isNotEmpty(),
                        onExport = model::exportSelected, onDelete = { confirmDelete = true },
                        modifier = Modifier.navigationBarsPadding().padding(NexBoxSpacing.screenPadding))
                }
            }
        }
    ) { padding ->
        val emptyContent = state.entries.isEmpty()
        val emptyArrangement = emptyContent && (state.loadFailed || !state.loading)
        NexBoxPullToRefresh(refreshing = state.loading, onRefresh = model::refresh,
            enabled = !state.busy, modifier = Modifier.fillMaxSize().padding(padding)) {
            NexBoxLoadingOverlay(loading = state.busy, modifier = Modifier.fillMaxSize(),
                progress = { state.processed.toFloat() / state.total.coerceAtLeast(1) }) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(128.dp), state = gridState, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = NexBoxSpacing.screenPadding, top = NexBoxSpacing.screenPadding,
                        end = NexBoxSpacing.screenPadding, bottom = if (state.selecting || emptyContent)
                            NexBoxSpacing.screenPadding else 96.dp + actionBottomInset),
                    horizontalArrangement = Arrangement.spacedBy(NexBoxSpacing.sm),
                    verticalArrangement = if (emptyArrangement) {
                        NexBoxEmptyStateArrangement(setOf(0), NexBoxSpacing.sm)
                    } else Arrangement.spacedBy(NexBoxSpacing.sm)
                ) {
                    if (state.loadFailed) item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                        NexBoxEmptyState(message = stringResource(R.string.gallery_load_failed), action = {
                            TextButton(onClick = model::refresh) { Text(stringResource(R.string.nexbox_gallery_refresh)) }
                        })
                    }
                    if (!state.loading && !state.loadFailed && state.entries.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        NexBoxEmptyState(message = stringResource(R.string.gallery_empty_hint))
                    }
                    dayGroups.forEach { (day, entries) ->
                        item(key = "day:$day", span = { GridItemSpan(maxLineSpan) }) {
                            Text(galleryDateLabel(day, today, locale, zone),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(vertical = NexBoxSpacing.sm))
                        }
                        items(entries, key = { it.id }) { entry ->
                            val selected = entry.id in state.selected
                            NexBoxCard(modifier = Modifier.combinedClickable(
                                enabled = !state.busy, onLongClickLabel = stringResource(R.string.gallery_select),
                                onLongClick = { model.toggleSelection(entry.id) },
                                onClick = {
                                    if (state.selecting) model.toggleSelection(entry.id)
                                    else {
                                        val operation = NexLog.operation("Gallery", "openPreview", "mediaId=${entry.id}")
                                        try {
                                            preview.launch(NexBoxGalleryPreviewActivity.intentFor(context, entry.id))
                                            operation.finish(detail = "previewLaunched=true")
                                        } catch (failure: Exception) {
                                            operation.fail(failure)
                                            Toast.makeText(context, R.string.gallery_preview_failed, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )) {
                                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainer)) {
                                    GalleryThumbnail(entry, Modifier.fillMaxSize())
                                    if (entry.isVideo) GalleryThumbnailBadge(Icons.Default.PlayArrow,
                                        stringResource(R.string.nexbox_gallery_video_badge),
                                        Modifier.align(Alignment.BottomEnd).padding(NexBoxSpacing.sm))
                                    if (state.selecting) {
                                        GalleryThumbnailBadge(
                                            if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                            stringResource(if (selected) R.string.gallery_selected else R.string.gallery_select),
                                            Modifier.align(Alignment.TopEnd).padding(NexBoxSpacing.sm),
                                            selected = selected, size = 28.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.gallery_delete_count, state.selected.size)) },
            text = { Text(stringResource(R.string.gallery_delete_message)) },
            confirmButton = { DestructiveButton(onClick = { confirmDelete = false; model.deleteSelected() }) { Text(stringResource(R.string.nexbox_gallery_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } })
    }
}
