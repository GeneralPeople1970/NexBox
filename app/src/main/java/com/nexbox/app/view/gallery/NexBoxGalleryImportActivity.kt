package com.nexbox.app.view.gallery

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexbox.app.R
import com.nexbox.app.ui.theme.NexBoxAppearanceHost
import com.nexbox.app.util.NexLog
import com.nexbox.app.util.toast
import com.nexbox.app.view.base.NexBoxImportKind
import com.nexbox.app.view.base.NexBoxImportScreen
import com.nexbox.app.view.base.NexBoxImportViewModel

class NexBoxGalleryImportActivity : ComponentActivity() {
    private val importModel by lazy { ViewModelProvider(this)[NexBoxImportViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val operation = NexLog.operation("Gallery", "receiveSharedMedia")
        val uris = try {
            extractSharedUris(intent).also { operation.finish(it.isNotEmpty(), "count=${it.size}") }
        } catch (failure: Exception) {
            operation.fail(failure)
            toast(R.string.nexbox_gallery_import_failed)
            finish()
            return
        }
        if (uris.isEmpty()) {
            toast(R.string.nexbox_gallery_import_empty)
            finish()
            return
        }
        importModel.prepare(NexBoxImportKind.MEDIA, uris)
        importModel.importSharedMedia()
        enableEdgeToEdge()
        setContent {
            val state by importModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.result) {
                val result = importModel.consumeResult() ?: return@LaunchedEffect
                if (result.successful < state.uris.size) {
                    toast(if (result.successful > 0) R.string.gallery_import_partial else R.string.nexbox_gallery_import_failed)
                }
                if (result.successful > 0) {
                    try {
                        NexBoxGalleryActivity.start(this@NexBoxGalleryImportActivity)
                    } catch (failure: Exception) {
                        NexLog.e("Gallery", "action=openGalleryFromImport imported=${result.successful}", failure)
                        toast(R.string.gallery_load_failed)
                    }
                }
                finish()
            }
            NexBoxAppearanceHost {
                NexBoxImportScreen(state, onCancelled = { finish() })
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractSharedUris(intent: Intent): List<Uri> {
        val result = linkedSetOf<Uri>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(result::add)
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.forEach(result::add)
        } else {
            (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let(result::add)
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach(result::add)
        }
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) clip.getItemAt(index)?.uri?.let(result::add)
        }
        return result.toList()
    }
}
