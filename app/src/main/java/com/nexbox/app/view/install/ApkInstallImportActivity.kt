package com.nexbox.app.view.install

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexbox.app.R
import com.nexbox.app.data.CurrentUserSelection
import com.nexbox.app.ui.theme.NexBoxAppearanceHost
import com.nexbox.app.util.NexLog
import com.nexbox.app.util.toast

class ApkInstallImportActivity : ComponentActivity() {
    private val importModel by lazy { ViewModelProvider(this)[ApkCloneImportViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!handleIntent(intent)) return
        enableEdgeToEdge()
        setContent {
            val state by importModel.state.collectAsStateWithLifecycle()
            NexBoxAppearanceHost {
                ApkCloneImportScreen(
                    state = state,
                    onConfirmNewUser = importModel::confirmNewUser,
                    onSkipDuplicate = importModel::skipDuplicate,
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (importModel.state.value.busy) {
            toast(R.string.apk_clone_import_busy)
            return
        }
        if (handleIntent(intent, replace = true)) setIntent(intent)
    }

    private fun handleIntent(intent: Intent?, replace: Boolean = false): Boolean {
        val uris = try {
            extractApkUris(intent)
        } catch (failure: Exception) {
            // An exported share target can receive malformed Parcelable data. Never log its values.
            NexLog.w("Import", "action=receiveApkBatch stage=rejected error=${failure.javaClass.simpleName}")
            toast(R.string.apk_clone_source_failed)
            if (!replace) finish()
            return false
        }
        if (uris.isEmpty()) {
            toast(R.string.apk_install_import_empty)
            if (!replace) finish()
            return false
        }
        val accepted = importModel.start(
            sources = uris.map(Uri::toString),
            selectedTarget = CurrentUserSelection.target.value,
            replace = replace
        )
        if (!accepted) toast(R.string.apk_clone_import_busy)
        return accepted
    }

    @Suppress("DEPRECATION")
    private fun extractApkUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val uris = linkedSetOf<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let(uris::add)
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                uri?.let(uris::add)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                list?.forEach(uris::add)
            }
        }
        // Some document providers deliver SEND URIs only through ClipData.
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) clip.getItemAt(index)?.uri?.let(uris::add)
            }
        }
        return uris.toList()
    }
}
