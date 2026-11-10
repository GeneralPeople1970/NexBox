package com.nexbox.app.view.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.nexbox.app.R
import com.nexbox.app.ui.components.AlertDialog
import com.nexbox.app.ui.components.CircularProgressIndicator
import com.nexbox.app.ui.components.NexBoxTopAppBar
import com.nexbox.app.ui.components.Scaffold
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.components.TextButton
import com.nexbox.app.ui.components.DestructiveButton
import com.nexbox.app.ui.components.Surface
import com.nexbox.app.ui.theme.NexBoxAlpha
import com.nexbox.app.ui.theme.NexBoxAppearanceHost
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.app.util.NexLog
import com.nexbox.app.util.toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.launch
import com.nexbox.core.media.NexBoxMediaEntry

class NexBoxGalleryPreviewActivity : ComponentActivity() {
    private val mediaId by lazy { intent.getLongExtra(EXTRA_MEDIA_ID, -1L) }
    private val model by viewModels<GalleryPreviewViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            NexBoxAppearanceHost(glassAllowed = false) {
                GalleryPreviewContent(state.entry, state.busy, onBack = { finish() }, onDelete = model::delete,
                    onExport = model::export)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    state.message?.let { toast(it); model.consumeMessage() }
                    if (state.close) {
                        if (state.deleted) setResult(RESULT_OK)
                        finish()
                    }
                }
            }
        }
        model.load(mediaId)
    }

    companion object {
        private const val EXTRA_MEDIA_ID = "extra_media_id"
        fun intentFor(context: Context, mediaId: Long): Intent =
            Intent(context, NexBoxGalleryPreviewActivity::class.java).putExtra(EXTRA_MEDIA_ID, mediaId)
    }
}

@Composable
private fun GalleryPreviewContent(entry: NexBoxMediaEntry?, busy: Boolean, onBack: () -> Unit, onDelete: () -> Unit,
    onExport: () -> Unit) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    var imageFailed by remember(entry?.id, entry?.filePath, entry?.dateModified, entry?.size) { mutableStateOf(false) }
    BackHandler(enabled = busy) { }
    Scaffold(
        topBar = { NexBoxTopAppBar(onBack = if (busy) null else onBack, titleContent = {
            Text(entry?.displayName.orEmpty(), modifier = Modifier.fillMaxWidth().basicMarquee(),
                style = MaterialTheme.typography.titleLarge, maxLines = 1, softWrap = false,
                overflow = TextOverflow.Clip)
        }) },
        bottomBar = {
            if (entry != null) Surface(Modifier.fillMaxWidth()) {
                Column(Modifier.navigationBarsPadding().padding(NexBoxSpacing.screenPadding), verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.sm)) {
                    Text(SimpleDateFormat("HH:mm", locale).format(Date(entry.displayTimeMillis)),
                        style = MaterialTheme.typography.bodyMedium)
                    GalleryMediaActions(enabled = !busy, onExport = onExport, onDelete = { confirmDelete = true })
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (entry != null) {
                if (entry.isVideo) GalleryVideo(entry, Modifier.fillMaxSize().padding(padding))
                else {
                    AsyncImage(File(entry.filePath), null, Modifier.fillMaxSize().alpha(NexBoxAlpha.mediaBackdrop), contentScale = ContentScale.Crop)
                    AsyncImage(File(entry.filePath), entry.displayName, Modifier.fillMaxSize().padding(padding),
                        contentScale = ContentScale.Fit, onSuccess = { imageFailed = false }, onError = {
                            if (!imageFailed) {
                                imageFailed = true
                                NexLog.w("Gallery", "action=previewImage mediaId=${entry.id} reason=${it.result.throwable.javaClass.simpleName}")
                            }
                        })
                    if (imageFailed) Text(stringResource(R.string.gallery_preview_failed), Modifier.padding(padding))
                }
            }
            if (busy) CircularProgressIndicator()
        }
    }
    if (confirmDelete && entry != null) AlertDialog(
        onDismissRequest = { confirmDelete = false }, title = { Text(stringResource(R.string.nexbox_gallery_delete)) },
        text = { Text(stringResource(R.string.gallery_delete_message)) },
        confirmButton = { DestructiveButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.nexbox_gallery_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun GalleryVideo(entry: NexBoxMediaEntry, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var position by rememberSaveable(entry.id) { mutableIntStateOf(0) }
    var playWhenReady by rememberSaveable(entry.id) { mutableStateOf(true) }
    var playbackFailed by remember(entry.id, entry.filePath, entry.dateModified, entry.size) { mutableStateOf(false) }
    fun reportPlaybackFailure(reason: String) {
        if (!playbackFailed) NexLog.w("Gallery", "action=playVideo mediaId=${entry.id} $reason")
        playbackFailed = true
        playWhenReady = false
    }
    val videoView = remember(context, entry.id, entry.filePath, entry.dateModified, entry.size) {
        VideoView(context).apply {
            setMediaController(MediaController(context).also { it.setAnchorView(this) })
            setOnPreparedListener {
                NexLog.i("Gallery", "action=playVideo mediaId=${entry.id} stage=prepared")
                if (position > 0) seekTo(position)
                if (playWhenReady && !playbackFailed) start()
            }
            setOnCompletionListener {
                position = 0
                playWhenReady = false
                NexLog.i("Gallery", "action=playVideo mediaId=${entry.id} stage=completed")
            }
            setOnErrorListener { _, what, extra ->
                reportPlaybackFailure("stage=failed what=$what extra=$extra")
                true
            }
            try {
                setVideoURI(Uri.fromFile(File(entry.filePath)))
            } catch (failure: Exception) {
                reportPlaybackFailure("stage=failed reason=${failure.javaClass.simpleName}")
            }
        }
    }
    DisposableEffect(videoView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> if (!playbackFailed) {
                    position = videoView.currentPosition; playWhenReady = videoView.isPlaying; videoView.pause()
                }
                Lifecycle.Event.ON_RESUME -> if (playWhenReady && !playbackFailed) videoView.start()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(factory = { videoView }, modifier = Modifier.fillMaxSize(), onRelease = {
            it.setOnPreparedListener(null)
            it.setOnCompletionListener(null)
            it.setOnErrorListener(null)
            it.setMediaController(null)
            it.stopPlayback()
        })
        if (playbackFailed) Text(stringResource(R.string.gallery_video_failed), Modifier.padding(NexBoxSpacing.screenPadding))
    }
}
