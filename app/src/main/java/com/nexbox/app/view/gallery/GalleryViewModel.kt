package com.nexbox.app.view.gallery

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexbox.app.R
import com.nexbox.app.data.SharedMediaRepository
import com.nexbox.app.util.NexLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import com.nexbox.core.media.NexBoxMediaEntry

data class GalleryState(
    val entries: List<NexBoxMediaEntry> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val selecting: Boolean = false,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val processed: Int = 0,
    val total: Int = 0,
    val loadFailed: Boolean = false,
    val failureMessage: Int? = null
)

/** Rotation keeps both the import job and its temporary picker grants in the host task. */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(GalleryState())
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var loadGeneration = 0L
    private var refreshRequested = false

    init { refresh() }

    fun refresh() {
        if (mutableState.value.busy || loadJob?.isActive == true) {
            refreshRequested = true
            return
        }
        refreshRequested = false
        val generation = ++loadGeneration
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true) }
            val operation = NexLog.operation("Gallery", "load", "library=shared")
            try {
                val entries = runInterruptible(Dispatchers.IO) {
                    SharedMediaRepository.refreshMediaEntries()
                        .filter { it.isPending == 0 }
                        .sortedWith(compareByDescending<NexBoxMediaEntry> { it.displayTimeMillis }.thenByDescending { it.id })
                }
                mutableState.update { it.copy(entries = entries, loadFailed = false,
                    selected = it.selected.intersect(entries.mapTo(hashSetOf()) { item -> item.id })) }
                operation.finish(detail = "count=${entries.size}")
            } catch (cancelled: CancellationException) {
                operation.fail(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                ensureActive()
                operation.fail(failure)
                mutableState.update { it.copy(loadFailed = true) }
            } finally {
                if (generation == loadGeneration) {
                    mutableState.update { it.copy(loading = false) }
                    loadJob = null
                    // A provider event can arrive after the DB snapshot but before it is
                    // delivered to Compose. Coalesce events without dropping that refresh.
                    if (refreshRequested && isActive) refresh()
                }
            }
        }
    }

    fun toggleSelection(id: Long) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(selecting = true,
            selected = if (id in it.selected) it.selected - id else it.selected + id) }
    }

    fun selectAll() {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(selected = if (it.selected.size == it.entries.size) emptySet()
            else it.entries.mapTo(linkedSetOf()) { item -> item.id }) }
    }

    fun endSelection() {
        if (!mutableState.value.busy) mutableState.update { it.copy(selecting = false, selected = emptySet()) }
    }

    fun importMedia(uris: List<Uri>) {
        val sources = uris.distinct()
        if (sources.isEmpty() || mutableState.value.busy) return
        cancelRefresh()
        mutableState.update { it.copy(busy = true, loading = false, processed = 0, total = sources.size) }
        viewModelScope.launch {
            var successful = 0
            try {
                successful = SharedMediaRepository.importBatch(getApplication(), sources) { processed ->
                    mutableState.update { it.copy(processed = processed) }
                }
            } finally {
                mutableState.update { it.copy(busy = false,
                    failureMessage = when {
                        successful == sources.size -> null
                        successful > 0 -> R.string.gallery_import_partial
                        else -> R.string.nexbox_gallery_import_failed
                    }) }
                refresh()
            }
        }
    }

    fun deleteSelected() {
        val snapshot = mutableState.value
        if (snapshot.busy || snapshot.selected.isEmpty()) return
        val entries = snapshot.entries.filter { it.id in snapshot.selected }
        cancelRefresh()
        mutableState.update { it.copy(busy = true, loading = false, processed = 0, total = entries.size) }
        viewModelScope.launch {
            val operation = NexLog.operation("Gallery", "deleteBatch", "library=shared count=${entries.size}")
            val deleted = mutableSetOf<Long>()
            try {
                withContext(Dispatchers.IO) {
                    entries.forEachIndexed { index, entry ->
                        ensureActive()
                        try {
                            if (SharedMediaRepository.deleteMedia(entry)) deleted += entry.id
                        } catch (failure: Exception) {
                            NexLog.w("Gallery", "op=${operation.id} action=deleteEntry mediaId=${entry.id}", failure)
                        }
                        mutableState.update { it.copy(processed = index + 1) }
                    }
                }
                operation.finish(deleted.size == entries.size, "deleted=${deleted.size}")
            } catch (cancelled: CancellationException) {
                operation.fail(cancelled)
                throw cancelled
            } finally {
                mutableState.update { it.copy(busy = false, selected = it.selected - deleted,
                    selecting = deleted.size != entries.size,
                    failureMessage = when {
                        deleted.size == entries.size -> null
                        deleted.isNotEmpty() -> R.string.gallery_delete_partial
                        else -> R.string.nexbox_gallery_delete_failed
                    }) }
                refresh()
            }
        }
    }

    fun exportSelected() {
        val snapshot = mutableState.value
        if (snapshot.busy || snapshot.selected.isEmpty()) return
        val entries = snapshot.entries.filter { it.id in snapshot.selected }
        cancelRefresh()
        mutableState.update { it.copy(busy = true, loading = false, processed = 0, total = entries.size) }
        viewModelScope.launch {
            val exported = mutableSetOf<Long>()
            try {
                entries.forEachIndexed { index, entry ->
                    try {
                        SharedMediaRepository.export(getApplication(), entry.id)
                        exported += entry.id
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        NexLog.w("Gallery", "action=export mediaId=${entry.id}", failure)
                    }
                    mutableState.update { it.copy(processed = index + 1) }
                }
            } finally {
                mutableState.update { it.copy(busy = false, selected = it.selected - exported,
                    selecting = exported.size != entries.size,
                    failureMessage = when {
                        exported.size == entries.size -> R.string.gallery_export_success
                        exported.isNotEmpty() -> R.string.gallery_export_partial
                        else -> R.string.gallery_export_failed
                    }) }
                refresh()
            }
        }
    }

    fun consumeFailure() { mutableState.update { it.copy(failureMessage = null) } }

    private fun cancelRefresh() {
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
    }
}
