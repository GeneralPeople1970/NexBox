package com.nexbox.app.view.spoof

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.navigation.compose.rememberNavController
import com.nexbox.app.R
import com.nexbox.app.data.CloneTarget
import com.nexbox.app.data.ScopeApp
import com.nexbox.app.data.ScopeAppRepository
import com.nexbox.app.data.UsersRepository
import com.nexbox.app.ui.components.ConfigurationStopFailureDialog
import com.nexbox.app.ui.components.NexBoxLoadingOverlay
import com.nexbox.app.ui.components.LocalSettingsLayout
import com.nexbox.app.ui.components.usesMiuix
import com.nexbox.app.ui.components.NexBoxCompactBadge
import com.nexbox.app.ui.components.NexBoxTopAppBar
import com.nexbox.app.ui.components.Scaffold
import com.nexbox.app.ui.components.ScopeAppEmptyState
import com.nexbox.app.ui.components.ScopeAppRow
import com.nexbox.app.ui.components.ScopeGroupHeader
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.components.TextButton
import com.nexbox.app.ui.components.rememberUserDisplayName
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.app.ui.NexBoxNavHost
import com.nexbox.app.ui.popFrom
import com.nexbox.app.ui.navigation.NexBoxBackGuard
import com.nexbox.app.ui.navigation.nexBoxComposable
import com.nexbox.app.util.NexLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nexbox.app.data.DeviceRepository
import com.nexbox.core.utils.ConfigurationStopException

/** Immediate, generation-checked saves, matching the module scope's switch interaction. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceScopeScreen(userId: Int, expectedGeneration: Long?, onBack: () -> Unit) {
    val repository = remember { ScopeAppRepository() }
    val coroutineScope = rememberCoroutineScope()
    val groupName = rememberUserDisplayName(userId)
    val userChanges by UsersRepository.changes.collectAsStateWithLifecycle()
    var apps by remember(userId) { mutableStateOf<List<ScopeApp>?>(null) }
    var targets by remember(userId) { mutableStateOf<Set<String>>(emptySet()) }
    var independent by remember(userId) { mutableStateOf<Set<String>>(emptySet()) }
    var target by remember(userId) { mutableStateOf<CloneTarget?>(null) }
    var loading by remember(userId) { mutableStateOf(true) }
    var saving by remember(userId) { mutableStateOf(false) }
    var updatingPackage by remember(userId) { mutableStateOf<String?>(null) }
    var stopFailure by remember(userId) { mutableStateOf<ConfigurationStopException?>(null) }
    val busy = loading || saving
    var error by remember(userId) { mutableStateOf<Int?>(null) }
    var reload by remember(userId) { mutableStateOf(0) }
    var editedPackage by rememberSaveable(userId, expectedGeneration) { mutableStateOf<String?>(null) }
    var editedLabel by rememberSaveable(userId, expectedGeneration) { mutableStateOf<String?>(null) }
    var editedGeneration by rememberSaveable(userId, expectedGeneration) { mutableStateOf<Long?>(null) }
    val scopeNavigation = rememberNavController()
    LaunchedEffect(scopeNavigation) {
        var previousRoute: String? = null
        scopeNavigation.currentBackStackEntryFlow.collect { entry ->
            // A cancelled preview never changes this flow. Toolbar, hardware and committed
            // predictive returns all refresh the independent-profile badges exactly once.
            if (previousRoute == "editor" && entry.destination.route == "scope") reload++
            previousRoute = entry.destination.route
        }
    }

    suspend fun readScope(generation: Long? = expectedGeneration) {
        val loaded = withContext(Dispatchers.IO) {
            DeviceRepository.loadScope(userId, generation, repository)
        }
        target = loaded.target
        apps = loaded.apps
        targets = loaded.selected
        independent = loaded.independent
        error = null
    }

    LaunchedEffect(userId, expectedGeneration, reload, userChanges) {
        loading = true
        try {
            readScope()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = R.string.device_scope_load_failed
            NexLog.e("DeviceScope", "action=load userId=$userId", failure)
        } finally {
            loading = false
        }
    }

    fun change(packageName: String, checked: Boolean) {
        val captured = target ?: return
        if (loading || saving) return
        val requested = if (checked) targets + packageName else targets - packageName
        saving = true
        updatingPackage = packageName
        error = null
        coroutineScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    DeviceRepository.saveScopeSelection(captured, packageName, requested, repository)
                }
                targets = result.saved
                stopFailure = result.stopFailure
                try {
                    readScope(captured.createTime)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = R.string.device_scope_load_failed
                    NexLog.e("DeviceScope", "action=reloadSavedScope userId=$userId", failure)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = R.string.device_scope_save_failed
                NexLog.e("DeviceScope", "action=save userId=$userId", failure)
            } finally {
                saving = false
                updatingPackage = null
            }
        }
    }

    CompositionLocalProvider(LocalSettingsLayout provides true) {
        NexBoxNavHost(scopeNavigation, Modifier.fillMaxSize(), startDestination = "scope") {
            nexBoxComposable("editor") { entry ->
                val openedPackage = editedPackage
                if (openedPackage != null) {
                    DeviceSpoofingScreen(
                        userId = userId,
                        packageName = openedPackage,
                        appLabel = editedLabel,
                        expectedGeneration = editedGeneration,
                        onBack = {
                            // Keep the editor's arguments alive until its exit transition completes.
                            scopeNavigation.popFrom(entry)
                        }
                    )
                }
            }
            nexBoxComposable("scope") { entry ->
                NexBoxBackGuard(blocked = busy, allowPreview = false, onBlocked = {})
                Scaffold(topBar = {
                    NexBoxTopAppBar(stringResource(R.string.device_random_scope), onBack = {
                        if (!busy && scopeNavigation.currentBackStackEntry == entry &&
                            entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onBack()
                    })
                }) { padding ->
                    NexBoxLoadingOverlay(loading, Modifier.fillMaxSize().padding(padding)) {
                        LazyColumn(Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                horizontal = if (usesMiuix()) NexBoxSpacing.settingsPageInset else NexBoxSpacing.screenPadding,
                                vertical = NexBoxSpacing.lg),
                            verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.md)) {
                            if (!apps.isNullOrEmpty() && (target?.createTime ?: 0L) > 0L) item { ScopeGroupHeader(groupName) }
                            val errorResource = error
                            if (errorResource != null) item {
                                Text(stringResource(errorResource), color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { reload++ }, enabled = !busy) {
                                    Text(stringResource(R.string.device_profile_retry))
                                }
                            }
                            val available = apps
                            if (!busy && errorResource == null && available != null && available.isEmpty()) item(key = "empty") {
                                ScopeAppEmptyState(Modifier.fillParentMaxSize())
                            }
                            items(available.orEmpty(), key = { it.packageName }) { app ->
                                ScopeAppRow(app, checked = app.packageName in targets, enabled = !busy,
                                    updating = updatingPackage == app.packageName,
                                    onOpen = {
                                        target?.let { captured ->
                                            if (!loading && !saving) {
                                                editedGeneration = captured.createTime
                                                editedLabel = app.label
                                                editedPackage = app.packageName
                                                scopeNavigation.navigate("editor") { launchSingleTop = true }
                                            }
                                        }
                                    },
                                    badge = {
                                        if (app.packageName in independent) {
                                            NexBoxCompactBadge(stringResource(R.string.device_profile_independent))
                                        }
                                    }
                                ) {
                                    change(app.packageName, it)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    ConfigurationStopFailureDialog(stopFailure, onDismiss = { stopFailure = null })
}
