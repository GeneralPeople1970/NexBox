package com.nexbox.app.view.base

import com.nexbox.app.util.NexLog

import android.app.Application
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexbox.app.R
import com.nexbox.app.ui.components.CircularProgressIndicator
import com.nexbox.app.ui.components.NexBoxTopAppBar
import com.nexbox.app.ui.components.Scaffold
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.app.data.SharedMediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.nexbox.core.media.NexBoxMediaContract

enum class NexBoxImportKind { MEDIA, APK }

data class NexBoxImportResult(val userId: Int, val successful: Int)

data class NexBoxImportState(
    val kind: NexBoxImportKind = NexBoxImportKind.MEDIA,
    val uris: List<Uri> = emptyList(),
    val userId: Int? = null,
    val importing: Boolean = false,
    val processed: Int = 0,
    val result: NexBoxImportResult? = null,
    val completed: Boolean = false
)

/** Retains the existing import work across host recreation, without retaining an Activity. */
class NexBoxImportViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(NexBoxImportState())
    val state = mutableState.asStateFlow()

    fun prepare(kind: NexBoxImportKind, uris: List<Uri>, replace: Boolean = false): Boolean {
        require(kind == NexBoxImportKind.MEDIA) { "APK imports must use the checked clone import workflow" }
        if (mutableState.value.importing) return false
        if (mutableState.value.uris.isNotEmpty() && !replace) return true
        mutableState.value = NexBoxImportState(kind = kind, uris = uris.distinct())
        NexLog.i("MediaImport", "action=prepare count=${uris.distinct().size} kind=$kind replace=$replace")
        return true
    }

    fun importSharedMedia() {
        val request = mutableState.value
        if (request.importing || request.completed || request.uris.isEmpty()) return
        val userId = NexBoxMediaContract.SHARED_USER_ID
        mutableState.value = request.copy(userId = userId, importing = true)
        viewModelScope.launch {
            val operation = NexLog.operation("MediaImport", "importBatch", "userId=$userId count=${request.uris.size} kind=${request.kind}")
            var successful = 0
            try {
                successful = SharedMediaRepository.importBatch(getApplication(), request.uris) { processed ->
                    mutableState.value = mutableState.value.copy(processed = processed)
                }
            } catch (e: CancellationException) {
                operation.fail(e)
                throw e
            } catch (e: Exception) {
                operation.fail(e, "successful=$successful")
            } finally {
                operation.finish(successful == request.uris.size, "successful=$successful total=${request.uris.size}")
                mutableState.value = mutableState.value.copy(
                    importing = false, completed = true,
                    result = NexBoxImportResult(userId, successful)
                )
            }
        }
    }

    fun consumeResult(): NexBoxImportResult? {
        val result = mutableState.value.result ?: return null
        mutableState.value = mutableState.value.copy(result = null)
        return result
    }
}

@Composable
fun NexBoxImportScreen(state: NexBoxImportState, onCancelled: () -> Unit) {
    BackHandler(enabled = state.importing) { /* Importing must not be restarted by dismissing the window. */ }
    val title = stringResource(if (state.kind == NexBoxImportKind.MEDIA)
        R.string.nexbox_gallery_share_label else R.string.apk_install_share_label)
    Scaffold(topBar = { NexBoxTopAppBar(title = title, onBack = if (state.importing) null else onCancelled) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(NexBoxSpacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.lg, Alignment.CenterVertically)) {
            Text(stringResource(if (state.kind == NexBoxImportKind.MEDIA) R.string.nexbox_gallery_importing
                else R.string.apk_install_importing), style = MaterialTheme.typography.titleMedium)
            if (state.importing) {
                CircularProgressIndicator(modifier = Modifier.size(40.dp),
                    progress = { state.processed.toFloat() / state.uris.size.coerceAtLeast(1) })
                Text(stringResource(R.string.appearance_import_progress, state.processed, state.uris.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
