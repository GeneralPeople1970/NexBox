package com.nexbox.app.view.gallery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import com.nexbox.app.ui.icons.automirrored.filled.InsertDriveFile
import com.nexbox.app.ui.icons.filled.Apps
import com.nexbox.app.ui.icons.filled.ChevronRight
import com.nexbox.app.ui.icons.filled.Folder
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import com.nexbox.app.R
import com.nexbox.app.ui.components.*
import com.nexbox.app.ui.theme.NexBoxAppearanceHost
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.app.util.NexLog
import com.nexbox.core.NexBoxCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PrivateDocumentPickerActivity : ComponentActivity() {
    private val candidate = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) { setResult(result.resultCode, result.data); finish() }
    }
    private suspend fun call(action: String, request: Bundle = Bundle()): Bundle = withContext(Dispatchers.IO) {
        val uri = Uri.parse("content://" + NexBoxCore.getHostPkg() + ".nexbox.Documents")
        checkNotNull(contentResolver.call(uri, "picker.$action", intent.getStringExtra("session"), request))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        if (intent.getStringExtra("session").isNullOrEmpty()) { finish(); return }
        enableEdgeToEdge()
        setContent { NexBoxAppearanceHost {
            val scope = rememberCoroutineScope()
            var info by remember { mutableStateOf<Bundle?>(null) }
            var entries by remember { mutableStateOf<List<Bundle>>(emptyList()) }
            var path by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
            var selected by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
            var name by rememberSaveable { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var failed by remember { mutableStateOf(false) }
            var refresh by remember { mutableIntStateOf(0) }
            val original = info?.let { BundleCompat.getParcelable(it, "intent", Intent::class.java) }
            val request = if (original?.action == Intent.ACTION_CHOOSER)
                IntentCompat.getParcelableExtra(original, Intent.EXTRA_INTENT, Intent::class.java) else original
            val tree = request?.action == Intent.ACTION_OPEN_DOCUMENT_TREE
            val create = request?.action == Intent.ACTION_CREATE_DOCUMENT
            val multiple = request?.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) == true && !tree && !create
            val candidates = info?.let { BundleCompat.getParcelableArrayList(it, "candidates", Bundle::class.java) }.orEmpty()
            fun back() { if (!busy) { if (path.isEmpty()) finish() else path = ArrayList(path.dropLast(1)) } }
            BackHandler { back() }
            LaunchedEffect(path, refresh) {
                busy = true; failed = false
                try {
                    if (info == null) {
                        info = call("info")
                        val loaded = info?.let { BundleCompat.getParcelable(it, "intent", Intent::class.java) }
                        val target = if (loaded?.action == Intent.ACTION_CHOOSER)
                            IntentCompat.getParcelableExtra(loaded, Intent.EXTRA_INTENT, Intent::class.java) else loaded
                        name = name.ifEmpty { target?.getStringExtra(Intent.EXTRA_TITLE).orEmpty() }
                    }
                    val result = call("list", Bundle().apply { putString("id", path.lastOrNull()) })
                    entries = BundleCompat.getParcelableArrayList(result, "entries", Bundle::class.java).orEmpty()
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { failed = true; NexLog.w("DocumentPicker", "Cannot load files", failure) }
                finally { busy = false }
            }
            fun choose(ids: ArrayList<String>) {
                if (busy) return
                scope.launch {
                    busy = true; failed = false
                    try {
                        val result = call("choose", Bundle().apply { putStringArrayList("ids", ids); putString("name", name) })
                        setResult(RESULT_OK, BundleCompat.getParcelable(result, "result", Intent::class.java)); finish()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Exception) { failed = true; NexLog.w("DocumentPicker", "Cannot select files", failure) }
                    finally { busy = false }
                }
            }
            Scaffold(topBar = {
                NexBoxTopAppBar(title = stringResource(R.string.private_documents_title), onBack = { back() }, actions = {
                    if (tree || create || multiple) TextButton(enabled = !busy && (if (tree || create) path.isNotEmpty() && (!create || name.isNotBlank()) else selected.isNotEmpty()),
                        onClick = { choose(if (tree || create) arrayListOf(path.last()) else selected) }) { Text(stringResource(R.string.done)) }
                })
            }) { padding ->
                NexBoxLoadingOverlay(busy, Modifier.fillMaxSize().padding(padding)) {
                    Column(Modifier.fillMaxSize().padding(horizontal =
                        if (usesMiuix()) NexBoxSpacing.settingsPageInset else NexBoxSpacing.screenPadding)) {
                        if (failed) {
                            Text(stringResource(R.string.private_documents_failed))
                            TextButton(onClick = { refresh++ }, enabled = !busy) { Text(stringResource(R.string.nexbox_gallery_refresh)) }
                        }
                        if (create) OutlinedTextField(value = name, onValueChange = { name = it }, enabled = !busy,
                            label = { Text(stringResource(R.string.private_documents_name)) }, modifier = Modifier.fillMaxWidth())
                        LazyColumn(Modifier.weight(1f)) {
                            if (path.isEmpty()) items(candidates.indices.toList()) { index ->
                                ListItem(
                                    headlineContent = { Text(candidates[index].getString("name").orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    leadingContent = { Icon(Icons.Default.Apps, null) },
                                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                        scope.launch {
                                            busy = true
                                            try {
                                                val result = call("candidate", Bundle().apply { putInt("index", index) })
                                                val launch = BundleCompat.getParcelable(result, "launch", Intent::class.java)
                                                candidate.launch(checkNotNull(launch))
                                            }
                                            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                            catch (failure: Exception) { failed = true; NexLog.w("DocumentPicker", "Cannot launch candidate", failure) }
                                            finally { busy = false }
                                        }
                                    }
                                )
                            }
                            items(entries, key = { it.getString("id").orEmpty() }) { entry ->
                                val id = entry.getString("id").orEmpty()
                                val directory = entry.getBoolean("directory")
                                val rawName = entry.getString("name").orEmpty()
                                val label = if (path.isEmpty()) stringResource(when (rawName) {
                                    "documents" -> R.string.private_documents_root
                                    "downloads" -> R.string.private_documents_downloads
                                    "images" -> R.string.private_documents_images
                                    "videos" -> R.string.private_documents_videos
                                    else -> R.string.private_documents_app
                                }) else rawName
                                val enabled = !busy && (directory || !tree && !create)
                                ListItem(
                                    headlineContent = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    leadingContent = { Icon(if (directory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                                    trailingContent = {
                                        if (directory) Icon(Icons.Default.ChevronRight, null)
                                        else if (multiple) Checkbox(checked = id in selected, onCheckedChange = null, enabled = enabled)
                                    },
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) {
                                        if (directory) path = ArrayList(path).apply { add(id) }
                                        else if (multiple) selected = ArrayList(selected).apply { if (!remove(id)) add(id) }
                                        else choose(arrayListOf(id))
                                    }
                                )
                            }
                        }
                    }
                }
            }
        } }
    }
}
