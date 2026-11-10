package com.nexbox.app.view.gallery

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.ThumbnailUtils
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.request.ImageRequest
import coil.request.Options
import coil.size.Scale
import com.nexbox.app.ui.components.Icon
import com.nexbox.app.ui.theme.NexBoxAlpha
import com.nexbox.app.ui.theme.NexBoxShapes
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.app.util.NexLog
import com.nexbox.app.data.withMediaCancellationSignal
import com.nexbox.core.media.NexBoxMediaEntry
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Management and picker grids share Coil's bounded memory cache and request lifecycle. */
@Composable
internal fun GalleryThumbnail(entry: NexBoxMediaEntry, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var active by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var measuredSize by remember { mutableStateOf(IntSize.Zero) }
    var version by remember(entry) { mutableStateOf<GalleryFileVersion?>(null) }
    var failureReported by remember(entry) { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            active = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(entry, active) {
        version = null
        if (!active) return@LaunchedEffect
        try {
            version = withContext(Dispatchers.IO) { GalleryFileVersion.read(entry.filePath) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!failureReported) {
                failureReported = true
                NexLog.w("Gallery", "action=thumbnail mediaId=${entry.id} reason=${failure.javaClass.simpleName}")
            }
        }
    }
    Box(modifier.onSizeChanged { measuredSize = it }) {
        val fileVersion = version
        if (active && fileVersion != null && measuredSize.width > 0 && measuredSize.height > 0) {
            val size = IntSize(measuredSize.width.coerceAtMost(1024), measuredSize.height.coerceAtMost(1024))
            val request = remember(context, entry.id, entry.isVideo, fileVersion, size) {
                val source = GalleryThumbnailSource(fileVersion, size)
                ImageRequest.Builder(context)
                    .data(if (entry.isVideo) source else fileVersion.file)
                    .memoryCacheKey("gallery/${entry.id}/${entry.mediaType}/${fileVersion.cacheIdentity}/" +
                        "${size.width}x${size.height}")
                    .size(size.width, size.height)
                    .scale(Scale.FILL)
                    .apply {
                        if (entry.isVideo) fetcherFactory(GalleryVideoFetcherFactory, GalleryThumbnailSource::class.java)
                    }
                    .build()
            }
            AsyncImage(model = request, contentDescription = entry.displayName,
                modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop,
                onError = {
                    if (!failureReported) {
                        failureReported = true
                        NexLog.w("Gallery", "action=thumbnail mediaId=${entry.id} " +
                            "reason=${it.result.throwable.javaClass.simpleName}")
                    }
                })
        }
    }
}

@Composable
internal fun GalleryThumbnailBadge(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    size: Dp = NexBoxSpacing.xxl
) {
    val colors = MaterialTheme.colorScheme
    val background = if (selected) colors.primary else colors.scrim.copy(alpha = NexBoxAlpha.mediaBadgeScrim)
    val foreground = if (selected) colors.onPrimary else Color.White
    Icon(icon, contentDescription,
        modifier.background(background, NexBoxShapes.pill).size(size), tint = foreground)
}

internal data class GalleryFileVersion(
    val path: String,
    val device: Long,
    val inode: Long,
    val modifiedSeconds: Long,
    val modifiedNanos: Long,
    val length: Long
) {
    val file: File get() = File(path)
    val cacheIdentity: String get() = "$path/$device/$inode/$modifiedSeconds/$modifiedNanos/$length"

    fun requireUnchanged() {
        if (read(path) != this) {
            throw IOException("Media changed while loading its thumbnail")
        }
    }

    companion object {
        fun read(path: String): GalleryFileVersion {
            val file = File(path).canonicalFile
            val state = try { Os.lstat(file.path) }
            catch (error: ErrnoException) { throw IOException("Media file is unavailable", error) }
            if (!OsConstants.S_ISREG(state.st_mode)) throw IOException("Media file is unavailable")
            return GalleryFileVersion(file.path, state.st_dev, state.st_ino,
                state.st_mtim.tv_sec, state.st_mtim.tv_nsec, state.st_size)
        }
    }
}

private data class GalleryThumbnailSource(val version: GalleryFileVersion, val size: IntSize)

private val videoDecodePermits = Semaphore(2)

private object GalleryVideoFetcherFactory : Fetcher.Factory<GalleryThumbnailSource> {
    override fun create(data: GalleryThumbnailSource, options: Options, imageLoader: ImageLoader): Fetcher =
        GalleryVideoFetcher(data, options)
}

private class GalleryVideoFetcher(private val source: GalleryThumbnailSource, private val options: Options) : Fetcher {
    override suspend fun fetch(): DrawableResult = videoDecodePermits.withPermit {
        var bitmap: Bitmap? = null
        try {
            withMediaCancellationSignal { cancellation ->
                runInterruptible {
                    source.version.requireUnchanged()
                    val frame = ThumbnailUtils.createVideoThumbnail(source.version.file,
                        Size(source.size.width, source.size.height), cancellation)
                    bitmap = frame
                    cancellation.throwIfCanceled()
                    // Store the final crop size so Coil can reuse it for this exact request size.
                    val cropped = ThumbnailUtils.extractThumbnail(frame, source.size.width, source.size.height)
                        ?: throw IOException("Video thumbnail is unavailable")
                    bitmap = cropped
                    if (cropped !== frame) frame.recycle()
                    cancellation.throwIfCanceled()
                    source.version.requireUnchanged()
                }
            }
            currentCoroutineContext().ensureActive()
            val decoded = bitmap ?: throw IOException("Video thumbnail is unavailable")
            DrawableResult(BitmapDrawable(options.context.resources, decoded), isSampled = true,
                dataSource = DataSource.DISK).also { bitmap = null }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            throw failure
        } finally {
            // Only unsuccessful requests still own this bitmap; Coil owns a returned drawable.
            bitmap?.recycle()
        }
    }
}
