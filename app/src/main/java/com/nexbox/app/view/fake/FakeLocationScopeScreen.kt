package com.nexbox.app.view.fake

import com.nexbox.app.data.UserIdentityResolver
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexbox.app.R
import com.nexbox.app.data.FakeLocationRepository
import com.nexbox.app.data.FakeLocationScopeSnapshot
import com.nexbox.app.data.CloneInstallCoordinator
import com.nexbox.app.data.CloneTarget
import com.nexbox.app.data.UsersRepository
import com.nexbox.app.ui.components.AlertDialog
import com.nexbox.app.ui.components.ConfigurationStopFailureDialog
import com.nexbox.app.ui.components.LocalSettingsLayout
import com.nexbox.app.ui.components.NexBoxCompactBadge
import com.nexbox.app.ui.components.NexBoxEmptyState
import com.nexbox.app.ui.components.NexBoxPullToRefresh
import com.nexbox.app.ui.components.NexBoxTopAppBar
import com.nexbox.app.ui.components.Scaffold
import com.nexbox.app.ui.components.ScopeAppEmptyState
import com.nexbox.app.ui.components.ScopeAppRow
import com.nexbox.app.ui.components.ScopeGroupHeader
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.components.TextButton
import com.nexbox.app.ui.components.usesMiuix
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.app.ui.navigation.rememberPageRefreshLifecycle
import com.nexbox.app.ui.navigation.NexBoxBackGuard
import com.nexbox.core.utils.ConfigurationStopException
import com.nexbox.app.util.NexLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class LocationScopeGroup(
    val userId: Int,
    val name: String,
    val snapshot: FakeLocationScopeSnapshot
)

/** All populated clone groups use the same eligible app rows and independent profile semantics. */
@Composable
fun FakeLocationScopeScreen(
    userId: Int,
    onBack: () -> Unit = {},
    onOpenApp: (userId: Int, packageName: String, appLabel: String, generation: Long) -> Unit = { _, _, _, _ -> }
) {
    val repository = remember { FakeLocationRepository() }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val refreshLifecycle = rememberPageRefreshLifecycle()
    var groups by remember(userId) { mutableStateOf<List<LocationScopeGroup>>(emptyList()) }
    var loading by remember(userId) { mutableStateOf(true) }
    var busy by remember(userId) { mutableStateOf(false) }
    var updatingApp by remember(userId) { mutableStateOf<Triple<Int, Long, String>?>(null) }
    var failed by remember(userId) { mutableStateOf(false) }
    var saveFailed by remember(userId) { mutableStateOf(false) }
    var stopFailure by remember(userId) { mutableStateOf<ConfigurationStopException?>(null) }
    var refreshVersion by remember(userId) { mutableIntStateOf(0) }
    val busyNow by rememberUpdatedState(busy)
    val userChanges by UsersRepository.changes.collectAsStateWithLifecycle()

    suspend fun load(): List<LocationScopeGroup> = withContext(Dispatchers.IO) {
        synchronized(UsersRepository.mutationLock) {
            val users = UsersRepository.cloneGroups()
            val loaded = users.map { user ->
                val snapshot = repository.loadScope(user.id)
                check(snapshot.createTime == user.createTime) { "Location user changed" }
                LocationScopeGroup(user.id, UsersRepository.displayName(user), snapshot)
            }.filter { it.snapshot.apps.isNotEmpty() }
            users.forEach { UserIdentityResolver.validateTarget(CloneTarget(it.id, it.createTime)) }
            loaded
        }
    }

    DisposableEffect(refreshLifecycle, userId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !busyNow) refreshVersion++
        }
        refreshLifecycle.addObserver(observer)
        onDispose { refreshLifecycle.removeObserver(observer) }
    }
    LaunchedEffect(userId, refreshVersion, userChanges) {
        loading = true
        try {
            groups = load()
            failed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failed = true
            NexLog.w("Location", "action=loadScope userId=$userId", error)
        } finally { loading = false }
    }

    fun setSelected(group: LocationScopeGroup, packageName: String, checked: Boolean) {
        val current = group.snapshot
        if (busy || loading || failed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val selected = if (checked) current.selectedPackages + packageName else current.selectedPackages - packageName
        busy = true
        updatingApp = Triple(group.userId, current.createTime, packageName)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(repository.saveScope(group.userId, current.createTime, selected)) { "Could not save location scope" }
                }
                groups = load()
                failed = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ConfigurationStopException) {
                stopFailure = error
                try { groups = load(); failed = false }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (reloadError: Exception) {
                    failed = true
                    NexLog.w("Location", "action=reloadSavedScope userId=$userId", reloadError)
                }
            } catch (error: Exception) {
                NexLog.w("Location", "action=saveScope userId=${group.userId}", error)
                saveFailed = true
                // The repository rolls back, but a failed remote rollback still needs read-back.
                try { groups = load() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (reloadError: Exception) {
                    failed = true
                    NexLog.w("Location", "action=reloadScope userId=$userId", reloadError)
                }
            } finally { busy = false; updatingApp = null }
        }
    }

    NexBoxBackGuard(blocked = busy, onBlocked = {})
    CompositionLocalProvider(LocalSettingsLayout provides true) {
        Scaffold(topBar = {
            NexBoxTopAppBar(title = stringResource(R.string.location_scope_title), onBack = if (busy) null else onBack)
        }) { padding ->
            NexBoxPullToRefresh(refreshing = loading, onRefresh = { if (!busy) refreshVersion++ },
                enabled = !busy, modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = if (usesMiuix()) NexBoxSpacing.settingsPageInset else NexBoxSpacing.screenPadding,
                        vertical = NexBoxSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (failed) {
                        item(key = "error") {
                            NexBoxEmptyState(message = stringResource(R.string.location_scope_load_failed),
                                modifier = Modifier.fillParentMaxSize())
                        }
                    } else if (!loading && groups.isEmpty()) {
                        item(key = "empty") {
                            ScopeAppEmptyState(Modifier.fillParentMaxSize())
                        }
                    } else {
                        groups.forEach { group ->
                            val current = group.snapshot
                            item(key = "group-${group.userId}-${current.createTime}") { ScopeGroupHeader(group.name) }
                            items(current.apps, key = { "${group.userId}-${current.createTime}-${it.packageName}" }) { app ->
                                ScopeAppRow(app, checked = app.packageName in current.selectedPackages,
                                    enabled = !busy && !loading,
                                    updating = updatingApp == Triple(group.userId, current.createTime, app.packageName),
                                    onOpen = {
                                        if (!busy && !loading && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                            onOpenApp(group.userId, app.packageName, app.label, current.createTime)
                                        }
                                    },
                                    badge = {
                                        if (app.packageName in current.independentPackages) {
                                            NexBoxCompactBadge(stringResource(R.string.location_app_independent))
                                        }
                                    },
                                    onToggle = { setSelected(group, app.packageName, it) })
                            }
                        }
                    }
                }
            }
        }
    }
    ConfigurationStopFailureDialog(stopFailure, onDismiss = { stopFailure = null })
    if (saveFailed) AlertDialog(onDismissRequest = { saveFailed = false },
        title = { Text(stringResource(R.string.location_scope_save_failed)) },
        confirmButton = { TextButton(onClick = { saveFailed = false }) { Text(stringResource(R.string.done)) } })
}
