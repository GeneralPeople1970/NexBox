package com.nexbox.app.view.gallery

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexbox.app.R
import com.nexbox.app.data.SharedMediaRepository
import com.nexbox.app.util.NexLog
import com.nexbox.core.media.NexBoxMediaEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class GalleryPreviewState(
    val entry: NexBoxMediaEntry? = null,
    val busy: Boolean = true,
    val close: Boolean = false,
    val deleted: Boolean = false,
    @param:StringRes val message: Int? = null
)

/** A rotation replaces the view only; the copy and its pending result remain owned by this task. */
internal class GalleryPreviewViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(GalleryPreviewState())
    val state = mutableState.asStateFlow()
    private var loadedMediaId: Long? = null

    fun load(mediaId: Long) {
        if (loadedMediaId != null) return
        loadedMediaId = mediaId
        viewModelScope.launch {
            val operation = NexLog.operation("Gallery", "loadEntry", "library=shared mediaId=$mediaId")
            try {
                val entry = withContext(Dispatchers.IO) {
                    SharedMediaRepository.getEntry(mediaId)
                }
                operation.finish(entry != null)
                mutableState.update { it.copy(entry = entry, close = entry == null,
                    message = if (entry == null) R.string.nexbox_gallery_item_missing else null) }
            } catch (cancelled: CancellationException) {
                operation.fail(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                operation.fail(failure)
                mutableState.update { it.copy(close = true, message = R.string.nexbox_gallery_item_missing) }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    fun delete() {
        val snapshot = mutableState.value
        val entry = snapshot.entry ?: return
        if (snapshot.busy || snapshot.close) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val operation = NexLog.operation("Gallery", "deleteEntry", "library=shared mediaId=${entry.id}")
            try {
                val deleted = withContext(Dispatchers.IO) {
                    SharedMediaRepository.deleteMedia(entry)
                }
                operation.finish(deleted)
                mutableState.update { it.copy(close = deleted, deleted = deleted,
                    message = if (deleted) null else R.string.nexbox_gallery_delete_failed) }
            } catch (cancelled: CancellationException) {
                operation.fail(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                operation.fail(failure)
                mutableState.update { it.copy(message = R.string.nexbox_gallery_delete_failed) }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    fun export() {
        val snapshot = mutableState.value
        val entry = snapshot.entry ?: return
        if (snapshot.busy || snapshot.close) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                SharedMediaRepository.export(getApplication(), entry.id)
                mutableState.update { it.copy(message = R.string.gallery_export_success) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                NexLog.w("Gallery", "action=export mediaId=${entry.id}", failure)
                mutableState.update { it.copy(message = R.string.gallery_export_failed) }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    fun consumeMessage() { mutableState.update { it.copy(message = null) } }
}
