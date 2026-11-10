package com.nexbox.app.view.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.rememberNavController
import com.nexbox.app.R
import com.nexbox.app.data.CloneTarget
import com.nexbox.app.data.XpModuleDataSource
import com.nexbox.app.data.XpModuleRepository
import com.nexbox.app.ui.components.*
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.app.ui.navigation.rememberPageRefreshLifecycle
import com.nexbox.app.ui.navigation.NexBoxBackGuard
import com.nexbox.app.ui.navigation.nexBoxComposable
import com.nexbox.app.ui.NexBoxNavHost
import com.nexbox.app.util.NexLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nexbox.core.utils.ConfigurationStopException
import java.util.concurrent.atomic.AtomicBoolean

/** Installed modules -> per-user app switches. userId only prefers a module-management copy. */
@Composable
fun XpModulesScreen(userId: Int = 0, onBack: () -> Unit = {}, repository: XpModuleDataSource? = null) {
    val repo = remember(repository) { repository ?: XpModuleRepository() }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val refreshLifecycle = rememberPageRefreshLifecycle()
    val active = remember { AtomicBoolean(true) }
    var profiles by remember(repo) { mutableStateOf<Map<Int, XpModuleRepository.Snapshot>>(emptyMap()) }
    val displayedProfiles = profiles
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val moduleNavigation = rememberNavController()
    var refreshVersion by remember { mutableIntStateOf(0) }
    var loadGeneration by remember { mutableLongStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var updatingKey by remember { mutableStateOf<String?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var showFailure by remember { mutableStateOf(false) }
    var failureMessage by remember { mutableIntStateOf(R.string.xp_change_failed) }
    var stopFailure by remember { mutableStateOf<ConfigurationStopException?>(null) }
    var configurationTarget by remember(selectedPackage) { mutableStateOf<CloneTarget?>(null) }
    val modules = remember(displayedProfiles) { groupInstalledModules(displayedProfiles) }
    val modulePackages = remember(modules) { modules.mapTo(hashSetOf()) { it.module.packageName } }
    val selectedModule = modules.firstOrNull { it.module.packageName == selectedPackage }
    val moduleListState = rememberLazyListState()
    val scopeListState = key(selectedPackage) { rememberLazyListState() }

    suspend fun load(): Map<Int, XpModuleRepository.Snapshot> = withContext(Dispatchers.IO) {
        repo.loadProfiles(includeTargets = selectedPackage != null)
    }

    fun canHandleInput() = active.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    fun mutate(key: String, after: (() -> Unit)? = null, block: suspend () -> Unit) {
        if (busy || loading || !canHandleInput()) return
        busy = true
        updatingKey = key
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                profiles = load()
                loadFailed = false
                after?.invoke()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ConfigurationStopException) {
                stopFailure = failure
                try {
                    profiles = load()
                    loadFailed = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (reloadFailure: Exception) {
                    loadFailed = true
                    NexLog.w("XpModulesScreen", "Could not reload saved module configuration", reloadFailure)
                }
            } catch (failure: Exception) {
                NexLog.w("XpModulesScreen", "Could not update module configuration", failure)
                failureMessage = R.string.xp_change_failed
                showFailure = true
                // A multi-user change can partially succeed. Always reconcile with server state.
                try { profiles = load() } catch (cancelled: CancellationException) { throw cancelled }
                catch (reloadFailure: Exception) {
                    loadFailed = true
                    NexLog.w("XpModulesScreen", "Could not reload module configuration", reloadFailure)
                }
            } finally { busy = false; updatingKey = null }
        }
    }

    val busyNow by rememberUpdatedState(busy)
    DisposableEffect(refreshLifecycle) {
        active.set(true)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !busyNow) { loading = true; refreshVersion++ }
        }
        refreshLifecycle.addObserver(observer)
        onDispose { active.set(false); refreshLifecycle.removeObserver(observer) }
    }
    LaunchedEffect(repo, refreshVersion, selectedPackage) {
        loadGeneration++
        val generation = loadGeneration
        loading = true
        try {
            profiles = load()
            loadFailed = false
            if (selectedPackage != null && groupInstalledModules(profiles).none { it.module.packageName == selectedPackage }) {
                selectedPackage = null
                moduleNavigation.popBackStack("modules", inclusive = false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            loadFailed = true
            NexLog.w("XpModulesScreen", "Could not load installed modules", failure)
        } finally { if (generation == loadGeneration) loading = false }
    }

    // A settings entry is optional. Resolve only installed copies, preferring the current group;
    // never expose a launch button for modules without a management activity.
    LaunchedEffect(repo, selectedPackage, displayedProfiles, userId) {
        val module = selectedModule
        configurationTarget = null
        configurationTarget = if (module == null) null else try {
            withContext(Dispatchers.IO) {
                module.profiles.keys.sortedWith(compareBy<Int> { it != userId }.thenBy { it })
                    .map { id -> displayedProfiles.getValue(id).owner }
                    .firstOrNull { owner ->
                        currentCoroutineContext().ensureActive()
                        repo.configurationIntent(module.module.packageName, owner) != null
                    }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            NexLog.w("XpModulesScreen", "Could not resolve module settings", failure)
            null
        }
    }

    fun back() {
        if (busy || !canHandleInput()) return
        if (moduleNavigation.currentDestination?.route == "scope") moduleNavigation.popBackStack() else onBack()
    }
    fun openModule(packageName: String) {
        if (!canHandleInput() || moduleNavigation.currentDestination?.route != "modules") return
        selectedPackage = packageName
        loading = true
        moduleNavigation.navigate("scope") { launchSingleTop = true }
    }
    val ready = !busy && !loading && !loadFailed
    val launchButtonInset = homeActionBottomInset()

    fun openConfiguration() {
        val modulePackage = selectedModule?.module?.packageName ?: return
        val owner = configurationTarget ?: return
        if (!ready || !canHandleInput() || profiles[owner.userId]?.owner != owner) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    repo.openConfiguration(modulePackage, owner)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                NexLog.w("XpModulesScreen", "Could not open module settings", failure)
                failureMessage = R.string.xp_module_launch_failed
                showFailure = true
                refreshVersion++
            } finally { busy = false }
        }
    }

    CompositionLocalProvider(LocalSettingsLayout provides true) {
        NexBoxNavHost(moduleNavigation, Modifier.fillMaxSize(), startDestination = "modules") {
            listOf("modules", "scope").forEach { route ->
                nexBoxComposable(route) { entry ->
                    // Keep the module overview behind its scope during a cancellable back.
                    val selected = if (route == "scope") selectedModule else null
                    NexBoxBackGuard(blocked = busy, allowPreview = false, onBlocked = {})
                    Scaffold(topBar = {
                        NexBoxTopAppBar(title = selected?.module?.name ?: stringResource(R.string.module_setting), onBack = {
                            if (moduleNavigation.currentBackStackEntry == entry &&
                                entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) back()
                        })
                    }, floatingActionButton = {
                        if (selected != null && configurationTarget != null) {
                            FloatingActionButton(onClick = ::openConfiguration,
                                modifier = Modifier.padding(bottom = launchButtonInset)
                                    .alpha(if (ready) 1f else 0.38f).semantics { if (!ready) disabled() }) {
                                if (busy && updatingKey == null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                                else Icon(Icons.Default.PlayArrow, stringResource(R.string.xp_open_module_settings))
                            }
                        }
                    }) { padding ->
                        val scopeProfiles = remember(selected, displayedProfiles, modulePackages) {
                            if (selected == null) emptyMap() else displayedProfiles.filterValues { snapshot ->
                                snapshot.targets.any { !it.isModule && it.packageName !in modulePackages } ||
                                    moduleScopeTargets(snapshot, selected.module, modulePackages).isNotEmpty()
                            }
                        }
                        val scopeApps = remember(selected, scopeProfiles, modulePackages) {
                            selected?.let { group ->
                                scopeProfiles.mapValues { (id, snapshot) ->
                                    moduleScopeTargets(snapshot, group.profiles[id] ?: group.module, modulePackages)
                                }
                            }.orEmpty()
                        }
                        val scopeEmpty = scopeApps.values.all { it.isEmpty() }
                        val centeredEmptyIndices = remember(selected, modules, scopeApps, loadFailed) {
                            when {
                                selected == null && modules.isEmpty() -> setOf(0)
                                selected != null && scopeEmpty -> {
                                    val policyRows = if (selected.module.staticScope) 1 else 0
                                    if (scopeApps.isEmpty()) setOf(policyRows)
                                    else {
                                        val leadingRows = policyRows + if (loadFailed) 1 else 0
                                        scopeApps.keys.indices.mapTo(mutableSetOf()) { leadingRows + it * 2 + 1 }
                                    }
                                }
                                else -> emptySet()
                            }
                        }
                        NexBoxPullToRefresh(refreshing = loading, onRefresh = {
                            if (!busy && canHandleInput()) { loading = true; refreshVersion++ }
                        },
                            enabled = !busy, modifier = Modifier.fillMaxSize().padding(padding)) {
                            LazyColumn(Modifier.fillMaxSize(),
                                state = if (selected == null) moduleListState else scopeListState,
                                contentPadding = PaddingValues(
                                    start = if (usesMiuix()) NexBoxSpacing.settingsPageInset else NexBoxSpacing.screenPadding,
                                    end = if (usesMiuix()) NexBoxSpacing.settingsPageInset else NexBoxSpacing.screenPadding,
                                    top = NexBoxSpacing.lg,
                                    bottom = if (selected != null && configurationTarget != null && !scopeEmpty)
                                        96.dp + launchButtonInset else NexBoxSpacing.lg),
                                verticalArrangement = if (centeredEmptyIndices.isNotEmpty()) {
                                    NexBoxEmptyStateArrangement(centeredEmptyIndices, 12.dp)
                                } else Arrangement.spacedBy(12.dp)) {
                                if (selected?.module?.staticScope == true) item(key = "static-scope-policy") {
                                    Text(stringResource(R.string.xp_static_scope), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = NexBoxSpacing.lg))
                                }
                                if (loadFailed) item(key = "error") {
                                    NexBoxEmptyState(message = stringResource(R.string.xp_load_failed_retry))
                                }
                                if (selected == null) {
                                    if (!loading && !loadFailed && modules.isEmpty()) item(key = "empty") {
                                        NexBoxEmptyState(message = stringResource(R.string.xp_installed_empty))
                                    }
                                    items(modules, key = { it.module.packageName }) { group ->
                                        val owners = group.profiles.keys.associateWith { displayedProfiles.getValue(it).owner }
                                        XpModuleRow(group.module, ready,
                                            updating = updatingKey == "module:${group.module.packageName}",
                                            onOpen = { openModule(group.module.packageName) },
                                            onToggle = { enabled ->
                                                if (enabled && group.profiles.values.all { it.targets.isEmpty() } && canHandleInput()) {
                                                    openModule(group.module.packageName)
                                                } else {
                                                    var needsScope = false
                                                    mutate("module:${group.module.packageName}",
                                                        after = { if (needsScope) openModule(group.module.packageName) }) {
                                                        // Overview loads only module metadata. Fetch target lists only for
                                                        // the groups whose switches are actually being changed.
                                                        val selections = group.profiles.mapValues { (id, module) ->
                                                            currentCoroutineContext().ensureActive()
                                                            val available = moduleScopeTargets(repo.load(owners.getValue(id)), module, modulePackages)
                                                                .mapTo(hashSetOf()) { it.packageName }
                                                            module.targets.intersect(available)
                                                        }
                                                        if (enabled && selections.values.all { it.isEmpty() }) {
                                                            needsScope = true
                                                            return@mutate
                                                        }
                                                        val stopFailures = mutableListOf<ConfigurationStopException>()
                                                        group.profiles.forEach { (id, module) ->
                                                            currentCoroutineContext().ensureActive()
                                                            val targets = selections[id].orEmpty()
                                                            try {
                                                                repo.save(owners.getValue(id), module.packageName, enabled && targets.isNotEmpty(), targets)
                                                            } catch (failure: ConfigurationStopException) {
                                                                stopFailures += failure
                                                            }
                                                        }
                                                        if (stopFailures.isNotEmpty()) {
                                                            throw ConfigurationStopException(stopFailures.flatMap { it.failedTargets }.distinct()).also { combined ->
                                                                stopFailures.forEach(combined::addSuppressed)
                                                            }
                                                        }
                                                    }
                                                }
                                            })
                                    }
                                } else {
                                    if (scopeProfiles.isEmpty() && !loading && !loadFailed) item(key = "empty-scope") {
                                        NexBoxEmptyState(message = stringResource(R.string.xp_group_no_apps))
                                    }
                                    scopeProfiles.forEach { (id, snapshot) ->
                                        val owner = snapshot.owner
                                        val profileModule = selected.profiles[id]
                                        val module = profileModule ?: selected.module
                                        val apps = scopeApps.getValue(id)
                                        item(key = "user:$id") { XpUserGroupHeader(id) }
                                        if (apps.isEmpty()) item(key = "empty:$id") {
                                            NexBoxEmptyState(message = stringResource(
                                                if (module.staticScope) R.string.xp_static_scope_no_clones else R.string.xp_group_no_apps
                                            ))
                                        }
                                        items(apps, key = { "app:$id:${it.packageName}" }) { app ->
                                            XpTargetRow(app,
                                                updating = updatingKey == "app:$id:${app.packageName}",
                                                checked = profileModule?.isEnabled == true && app.packageName in profileModule.targets,
                                                enabled = ready && (profileModule?.isEnabled == true && app.packageName in profileModule.targets
                                                    || module.supported && (!module.staticScope || app.packageName in module.recommendedScope)),
                                                onToggle = { checked ->
                                                    val available = apps.mapTo(hashSetOf()) { it.packageName }
                                                    // Disabled modules retain selections for the overview switch. An app
                                                    // switch enables only the visible active selection in this profile.
                                                    val activeTargets = if (profileModule?.isEnabled == true) profileModule.targets.intersect(available) else emptySet()
                                                    val targets = if (checked) activeTargets + app.packageName else activeTargets - app.packageName
                                                    mutate("app:$id:${app.packageName}") {
                                                        if (profileModule == null) repo.installExisting(selected.module.packageName, owner)
                                                        currentCoroutineContext().ensureActive()
                                                        repo.save(owner, selected.module.packageName, targets.isNotEmpty(), targets)
                                                    }
                                                })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showFailure) AlertDialog(onDismissRequest = { showFailure = false },
        title = { Text(stringResource(failureMessage)) },
        confirmButton = { TextButton(onClick = { showFailure = false }) { Text(stringResource(R.string.done)) } })
    ConfigurationStopFailureDialog(stopFailure, onDismiss = { stopFailure = null })
}
