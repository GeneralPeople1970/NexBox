package com.nexbox.app.view.gms

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.nexbox.app.ui.components.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import com.nexbox.app.ui.components.ListItem
import androidx.compose.material3.MaterialTheme
import com.nexbox.app.ui.components.NexBoxCompactActionButton
import com.nexbox.app.ui.components.Scaffold
import com.nexbox.app.ui.components.Switch
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.components.TextButton
import com.nexbox.app.ui.components.DestructiveButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexbox.app.R
import com.nexbox.app.bean.GmsBean
import com.nexbox.app.data.CloneTarget
import com.nexbox.app.data.GmsRepository
import com.nexbox.app.data.UsersRepository
import com.nexbox.app.ui.components.NexBoxCard
import com.nexbox.app.ui.components.NexBoxLoadingOverlay
import com.nexbox.app.ui.components.LocalSettingsLayout
import com.nexbox.app.ui.components.usesMiuix
import com.nexbox.app.ui.components.NexBoxEmptyState
import com.nexbox.app.ui.components.NexBoxTopAppBar
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.app.ui.navigation.rememberPageRefreshLifecycle
import com.nexbox.app.util.NexLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GmsScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { GmsRepository() }
    val listState = rememberLazyListState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val refreshLifecycle = rememberPageRefreshLifecycle()
    val userChanges by UsersRepository.changes.collectAsStateWithLifecycle()

    var snapshot by remember { mutableStateOf(GmsScreenSnapshot(emptyList(), emptyMap())) }
    val displayedSnapshot = snapshot
    val userGmsList = displayedSnapshot.users
    var pendingDialogTarget by remember { mutableStateOf<CloneTarget?>(null) }
    val dialogTarget = pendingDialogTarget
    val pendingDialogUser = dialogTarget?.let(displayedSnapshot::findUser)
    var dialogType by remember { mutableStateOf("") } // "enable", "disable", "wipe"
    var busy by remember { mutableStateOf(false) }
    var updatingTarget by remember { mutableStateOf<CloneTarget?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var resumed by remember(refreshLifecycle) { mutableStateOf(refreshLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var refreshToken by remember { mutableLongStateOf(0L) }
    var loadGeneration by remember { mutableLongStateOf(0L) }
    var publishedUserRevision by remember { mutableLongStateOf(-1L) }

    fun requestRefresh() {
        // Invalidate immediately, before Compose gets a chance to restart the keyed effect.
        loadGeneration++
        loading = true
        refreshToken++
    }

    val ready = resumed && !busy && !loading && !loadFailed && publishedUserRevision == userChanges

    fun openDialog(target: CloneTarget, type: String) {
        if (!ready || busy || pendingDialogTarget != null ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            publishedUserRevision != UsersRepository.changes.value) return
        if (target.createTime <= 0L || snapshot.findUser(target) == null) return
        pendingDialogTarget = target
        dialogType = type
    }

    fun performAction(
        target: CloneTarget,
        failureMessage: Int = R.string.user_action_failed,
        action: (CloneTarget) -> Boolean
    ) {
        if (!ready || busy || pendingDialogTarget != target ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            publishedUserRevision != UsersRepository.changes.value || snapshot.findUser(target) == null) return
        busy = true
        updatingTarget = target
        pendingDialogTarget = null
        requestRefresh()
        scope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    synchronized(UsersRepository.mutationLock) {
                        val actual = UsersRepository.cloneGroups().firstOrNull { it.id == target.userId }
                        check(target.createTime > 0L && actual?.createTime == target.createTime) {
                            "The GMS user group changed"
                        }
                        action(target)
                    }
                }
                if (!success) Toast.makeText(context, failureMessage, Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                NexLog.e("GMS", "action=changeFromScreen", error)
                Toast.makeText(context, failureMessage, Toast.LENGTH_SHORT).show()
            } finally {
                busy = false
                // Success, partial failure and return-to-screen refresh all use the same owner.
                requestRefresh()
            }
        }
    }

    DisposableEffect(refreshLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { resumed = true; requestRefresh() }
                Lifecycle.Event.ON_PAUSE -> { resumed = false; loadGeneration++ }
                else -> Unit
            }
        }
        refreshLifecycle.addObserver(observer)
        onDispose { refreshLifecycle.removeObserver(observer); loadGeneration++ }
    }

    LaunchedEffect(repo, userChanges, refreshToken, busy, resumed) {
        if (busy || !resumed) return@LaunchedEffect
        val generation = ++loadGeneration
        val userRevision = userChanges
        loading = true
        try {
            // Directly await this effect's read. No detached child can publish after cancellation.
            val fresh = withContext(Dispatchers.IO) { readGmsScreenSnapshot(repo) }
            currentCoroutineContext().ensureActive()
            if (generation == loadGeneration && userRevision == UsersRepository.changes.value && !busy && resumed) {
                snapshot = fresh
                publishedUserRevision = userRevision
                loadFailed = false
                pendingDialogTarget?.let { target ->
                    if (fresh.findUser(target) == null) {
                        pendingDialogTarget = null
                        dialogType = ""
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (generation == loadGeneration && userRevision == UsersRepository.changes.value) {
                loadFailed = true
                pendingDialogTarget = null
                dialogType = ""
                NexLog.e("GMS", "action=refreshScreen", error)
                Toast.makeText(context, R.string.user_action_failed, Toast.LENGTH_SHORT).show()
            }
        } finally {
            if (generation == loadGeneration && userRevision == UsersRepository.changes.value) {
                loading = false
                updatingTarget = null
            }
        }
    }

    val pageInset = if (usesMiuix()) NexBoxSpacing.settingsPageInset else NexBoxSpacing.screenPadding
    CompositionLocalProvider(LocalSettingsLayout provides true) {
        Scaffold(
            topBar = {
                NexBoxTopAppBar(
                    title = stringResource(id = R.string.gms_manager),
                    onBack = onBack
                )
            }
        ) { innerPadding ->
            NexBoxLoadingOverlay(loading = loading && updatingTarget == null,
                modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = pageInset,
                        top = NexBoxSpacing.screenPadding,
                        end = pageInset,
                        bottom = NexBoxSpacing.screenPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.md)
                ) {
                    if (userGmsList.isEmpty() && !loading) {
                        item(key = "gms-empty") {
                            NexBoxEmptyState(message = stringResource(if (loadFailed) R.string.load_failed else R.string.empty_no_profile),
                                modifier = Modifier.fillParentMaxSize(), action = if (loadFailed) {{
                                    TextButton(onClick = ::requestRefresh) { Text(stringResource(R.string.location_retry)) }
                                }} else null)
                        }
                    }
                    items(userGmsList, key = { "${it.userID}:${displayedSnapshot.identities[it.userID]}" }) { item ->
                        val target = CloneTarget(item.userID, displayedSnapshot.identities.getValue(item.userID))
                        NexBoxCard {
                            NexBoxLoadingOverlay(loading = updatingTarget == target && (busy || loading),
                                status = stringResource(R.string.updating_named, item.userName)) {
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            text = item.userName,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            text = if (item.isInstalledGms) stringResource(id = R.string.gms_state_enabled)
                                            else stringResource(id = R.string.gms_state_disabled)
                                        )
                                    },
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(NexBoxSpacing.sm)) {
                                            if (item.hasGmsTraces) {
                                                NexBoxCompactActionButton(enabled = ready, destructive = true, onClick = { openDialog(target, "wipe") }) {
                                                    Text(text = stringResource(id = R.string.wipe_gms))
                                                }
                                            }
                                            Switch(
                                                checked = item.isInstalledGms,
                                                enabled = ready,
                                                onCheckedChange = { checked ->
                                                    openDialog(target, if (checked) "enable" else "disable")
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (dialogTarget != null && pendingDialogUser != null) {
        val user = pendingDialogUser
        when (dialogType) {
            "enable" -> {
                AlertDialog(
                    onDismissRequest = { pendingDialogTarget = null },
                    title = { Text(text = stringResource(id = R.string.enable_gms)) },
                    text = {
                        Text(
                            text = user.userName + "\n\n" + stringResource(id = R.string.enable_gms_hint) + "\n\n" +
                                    stringResource(id = R.string.enable_gms_warning)
                        )
                    },
                    confirmButton = {
                        TextButton(
                            enabled = ready,
                            onClick = {
                                performAction(dialogTarget) { repo.installGms(it) }
                            }
                        ) {
                            Text(text = stringResource(id = R.string.done))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDialogTarget = null }) {
                            Text(text = stringResource(id = R.string.cancel))
                        }
                    }
                )
            }
            "disable" -> {
                AlertDialog(
                    onDismissRequest = { pendingDialogTarget = null },
                    title = { Text(text = stringResource(id = R.string.disable_gms)) },
                    text = { Text(text = user.userName + "\n\n" + stringResource(id = R.string.disable_gms_hint)) },
                    confirmButton = {
                        DestructiveButton(
                            enabled = ready,
                            onClick = {
                                performAction(dialogTarget) { repo.uninstallGms(it) }
                            }
                        ) {
                            Text(text = stringResource(id = R.string.done))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDialogTarget = null }) {
                            Text(text = stringResource(id = R.string.cancel))
                        }
                    }
                )
            }
            "wipe" -> {
                AlertDialog(
                    onDismissRequest = { pendingDialogTarget = null },
                    title = { Text(text = stringResource(id = R.string.wipe_gms)) },
                    text = { Text(text = user.userName + "\n\n" + stringResource(id = R.string.wipe_gms_hint)) },
                    confirmButton = {
                        DestructiveButton(
                            enabled = ready,
                            onClick = {
                                performAction(dialogTarget, R.string.gms_reset_failed) {
                                    repo.resetGmsEnvironment(it)
                                }
                            }
                        ) {
                            Text(text = stringResource(id = R.string.wipe_gms))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDialogTarget = null }) {
                            Text(text = stringResource(id = R.string.cancel))
                        }
                    }
                )
            }
        }
    }
}

private data class GmsScreenSnapshot(val users: List<GmsBean>, val identities: Map<Int, Long>) {
    fun findUser(target: CloneTarget): GmsBean? = users.firstOrNull {
        it.userID == target.userId && identities[it.userID] == target.createTime
    }
}

/** Names, state and dialog identity form one snapshot, never independent asynchronous reads. */
private fun readGmsScreenSnapshot(repository: GmsRepository): GmsScreenSnapshot =
    synchronized(UsersRepository.mutationLock) {
        val originalIdentities = UsersRepository.cloneGroups().associate { it.id to it.createTime }
        val states = repository.getGmsInstalledListSync()
        val actualUsers = UsersRepository.cloneGroups().associateBy { it.id }
        val identities = actualUsers.mapValues { it.value.createTime }
        check(originalIdentities == identities) { "The GMS user groups changed while loading" }
        check(states.mapTo(hashSetOf()) { it.userID } == identities.keys) { "Incomplete GMS user group snapshot" }
        GmsScreenSnapshot(states.map { state ->
            actualUsers[state.userID]?.let { state.copy(userName = UsersRepository.displayName(it)) } ?: state
        }, identities)
    }
