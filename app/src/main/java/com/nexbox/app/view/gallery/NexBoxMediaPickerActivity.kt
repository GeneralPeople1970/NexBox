package com.nexbox.app.view.gallery

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import com.nexbox.app.ui.icons.filled.RadioButtonUnchecked
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexbox.app.R
import com.nexbox.app.ui.components.NexBoxLoadingOverlay
import com.nexbox.app.ui.components.NexBoxCard
import com.nexbox.app.ui.components.NexBoxEmptyState
import com.nexbox.app.ui.components.NexBoxTopAppBar
import com.nexbox.app.ui.components.Scaffold
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.components.TextButton
import com.nexbox.app.ui.theme.NexBoxAppearanceHost
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.app.ui.navigation.rememberPageRefreshLifecycle
import com.nexbox.app.util.NexLog
import com.nexbox.core.media.NexBoxMediaEntry
import com.nexbox.core.media.NexBoxMediaContract
import com.nexbox.app.data.SharedMediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Same-UID host window; Android retains the guest's original activity-result linkage. */
class NexBoxMediaPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        if (intent.getBooleanExtra("nexbox.unsupportedDocument", false)) {
            android.widget.Toast.makeText(this, R.string.gallery_document_unavailable, android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val mimeTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList()
            ?: listOfNotNull(intent.type)
        if (mimeTypes.isEmpty() || mimeTypes.any { !isVisualMime(it) }) {
            finish()
            return
        }
        val multiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        val maximumSelection = intent.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,
            if (multiple) Int.MAX_VALUE else 1)
        if (maximumSelection < 1 || (!multiple && maximumSelection != 1)) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            NexBoxAppearanceHost {
                MediaPicker(mimeTypes, multiple, maximumSelection, onCancel = { finish() }, onChoose = { entries ->
                    if (entries.size in 1..maximumSelection && !isFinishing) {
                        val resultMimeTypes = entries.mapNotNull { it.mimeType }.distinct()
                        val result = Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        result.setDataAndType(entries.first().privateUri,
                            resultMimeTypes.singleOrNull() ?: "*/*")
                        result.clipData = ClipData(ClipDescription("", resultMimeTypes.toTypedArray()),
                            ClipData.Item(entries.first().privateUri)).apply {
                            entries.drop(1).forEach { addItem(ClipData.Item(it.privateUri)) }
                        }
                        setResult(RESULT_OK, result)
                        NexLog.i("GalleryPicker", "action=result count=${entries.size}")
                        finish()
                    }
                })
            }
        }
    }
}

private fun isVisualMime(mime: String): Boolean =
    (mime.startsWith("image/") || mime.startsWith("video/")) &&
        mime.substringAfter('/').isNotEmpty() && mime.count { it == '/' } == 1

private fun acceptsMedia(entry: NexBoxMediaEntry, mimeTypes: List<String>): Boolean =
    entry.isPending == 0 && mimeTypes.any { mime -> mime == entry.mimeType ||
        (mime.endsWith("/*") && entry.mimeType?.startsWith(mime.substringBefore('/') + "/") == true) }

@Composable
private fun MediaPicker(
    mimeTypes: List<String>,
    multiple: Boolean,
    maximumSelection: Int,
    onCancel: () -> Unit,
    onChoose: (List<NexBoxMediaEntry>) -> Unit
) {
    val model: GalleryViewModel = viewModel()
    val context = LocalContext.current
    val refreshLifecycle = rememberPageRefreshLifecycle()
    val scope = rememberCoroutineScope()
    val state by model.state.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(arrayListOf<Long>()) }
    var validating by remember { mutableStateOf(false) }
    var selectionFailed by remember { mutableStateOf(false) }
    DisposableEffect(refreshLifecycle, model, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refresh()
        }
        refreshLifecycle.addObserver(observer)
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = model.refresh()
        }
        context.contentResolver.registerContentObserver(
            NexBoxMediaContract.buildPrivateCollectionUri(MediaStore.Files.FileColumns.MEDIA_TYPE_NONE,
                NexBoxMediaContract.SHARED_USER_ID), true, contentObserver)
        onDispose {
            refreshLifecycle.removeObserver(observer)
            context.contentResolver.unregisterContentObserver(contentObserver)
        }
    }
    val entries = state.entries.filter { acceptsMedia(it, mimeTypes) }
    val entryIds = entries.mapTo(hashSetOf()) { it.id }
    val chosen = entries.filter { it.id in selected }
    fun choose(snapshot: List<NexBoxMediaEntry>) {
        if (validating || state.loading || state.loadFailed || selectionFailed ||
            snapshot.size !in 1..maximumSelection) return
        validating = true
        scope.launch {
            try {
                val current = withContext(Dispatchers.IO) {
                    snapshot.map { item ->
                        val entry = requireNotNull(SharedMediaRepository.getEntry(item.id))
                        require(acceptsMedia(entry, mimeTypes))
                        // Validate readability now; the shared item may still be deleted after return.
                        SharedMediaRepository.requireReadable(entry)
                        entry
                    }
                }
                onChoose(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                NexLog.w("GalleryPicker", "action=validateSelection reason=${failure.javaClass.simpleName}")
                selected = arrayListOf()
                selectionFailed = true
                model.refresh()
            } finally {
                validating = false
            }
        }
    }
    Scaffold(topBar = {
        NexBoxTopAppBar(title = stringResource(R.string.nexbox_gallery_title), onBack = onCancel,
            actions = {
                if (multiple) TextButton(onClick = { choose(chosen) }, enabled = chosen.size in 1..maximumSelection &&
                    !validating && !state.loading && !state.loadFailed && !selectionFailed) {
                    Text(stringResource(R.string.done))
                }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (multiple && maximumSelection != Int.MAX_VALUE) {
                Text(stringResource(R.string.gallery_picker_selection_count, chosen.size, maximumSelection),
                    Modifier.padding(horizontal = NexBoxSpacing.screenPadding, vertical = NexBoxSpacing.sm))
            }
            NexBoxLoadingOverlay(loading = state.loading || validating, modifier = Modifier.fillMaxWidth().weight(1f)) {
                when {
                    state.loading && entries.isEmpty() -> Box(Modifier.fillMaxSize())
                    state.loadFailed || selectionFailed -> NexBoxEmptyState(message = stringResource(R.string.gallery_load_failed), action = {
                        TextButton(onClick = { selectionFailed = false; model.refresh() }) { Text(stringResource(R.string.nexbox_gallery_refresh)) }
                    })
                    entries.isEmpty() -> NexBoxEmptyState(message = stringResource(R.string.gallery_empty_hint))
                    else -> LazyVerticalGrid(columns = GridCells.Adaptive(128.dp),
                        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(NexBoxSpacing.screenPadding),
                        horizontalArrangement = Arrangement.spacedBy(NexBoxSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.sm)) {
                        items(entries, key = { it.id }) { entry ->
                            val checked = entry.id in selected
                            val canSelect = !state.loading && !validating && (!multiple || checked || chosen.size < maximumSelection)
                            NexBoxCard(modifier = Modifier.alpha(if (canSelect) 1f else 0.45f).clickable(enabled = canSelect) {
                                if (!multiple) choose(listOf(entry))
                                else selected = ArrayList(selected.filter { it in entryIds }).apply {
                                    // Read the current selection again: rapid taps can precede recomposition.
                                    if (!remove(entry.id) && size < maximumSelection) add(entry.id)
                                }
                            }) {
                                Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                                    GalleryThumbnail(entry, Modifier.fillMaxSize())
                                    if (entry.isVideo) GalleryThumbnailBadge(Icons.Default.PlayArrow,
                                        stringResource(R.string.nexbox_gallery_video_badge),
                                        Modifier.align(Alignment.BottomEnd).padding(NexBoxSpacing.sm))
                                    if (multiple) GalleryThumbnailBadge(if (checked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        stringResource(if (checked) R.string.gallery_selected else R.string.gallery_select),
                                        Modifier.align(Alignment.TopEnd).padding(NexBoxSpacing.sm), selected = checked)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
