package com.nexbox.app.view.apps

import com.nexbox.app.data.CloneAppActions
import com.nexbox.app.data.UserIdentityResolver
import com.nexbox.app.util.NexLog

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.nexbox.app.ui.icons.automirrored.filled.Launch
import com.nexbox.app.ui.icons.filled.Stop
import androidx.compose.material.icons.filled.Add
import com.nexbox.app.ui.icons.filled.Casino
import com.nexbox.app.ui.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import com.nexbox.app.ui.components.AlertDialog
import com.nexbox.app.ui.components.Button
import com.nexbox.app.ui.components.Card
import androidx.compose.material3.CardDefaults
import com.nexbox.app.ui.components.CircularProgressIndicator
import com.nexbox.app.ui.components.ConfigurationStopFailureDialog
import com.nexbox.app.ui.components.DestructiveButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import com.nexbox.app.ui.components.FloatingActionButton
import com.nexbox.app.ui.components.Icon
import com.nexbox.app.ui.components.ListItem
import androidx.compose.material3.MaterialTheme
import com.nexbox.app.ui.components.ModalBottomSheet
import com.nexbox.app.ui.components.Scaffold
import com.nexbox.app.ui.components.Surface
import com.nexbox.app.ui.components.NexBoxUserBar
import com.nexbox.app.ui.components.UserTab
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.components.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexbox.app.ui.navigation.rememberPageRefreshLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import com.nexbox.core.core.system.user.BUserInfo
import com.nexbox.core.core.system.pm.PackageCodeAvailability
import com.nexbox.app.data.DeviceRepository
import com.nexbox.core.utils.ConfigurationStopException
import com.nexbox.app.R
import com.nexbox.app.bean.AppInfo
import com.nexbox.app.data.UsersRepository
import com.nexbox.app.data.UserRenameResult
import com.nexbox.app.util.InjectionUtil
import com.nexbox.app.util.awaitNexBoxServices
import com.nexbox.app.data.CloneTarget
import com.nexbox.app.data.CloneInstallCoordinator
import com.nexbox.app.data.CurrentUserSelection
import com.nexbox.app.ui.components.NexBoxEmptyState
import com.nexbox.app.ui.components.usesMiuix
import com.nexbox.app.ui.components.rememberNexBoxSearchState
import com.nexbox.app.ui.components.dismissSearchFocusOnBlankTap
import com.nexbox.app.ui.theme.NexBoxShapes
import com.nexbox.app.ui.theme.NexBoxSpacing

private const val TAG = "AppsScreen"

/** Rows and actions retain the captured user identity across asynchronous refreshes. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(
    selectedUserId: Int = 0,
    onSelectedUserChange: (Int) -> Unit = {},
    onNavigateToAppPicker: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val repo = remember { InjectionUtil.getAppsRepository() }
    val scope = rememberCoroutineScope()
    val currentSelectedUserId by rememberUpdatedState(selectedUserId)
    var userActionRunning by remember { mutableStateOf(false) }
    var stopFailure by remember { mutableStateOf<ConfigurationStopException?>(null) }
    var userToManage by remember { mutableStateOf<BUserInfo?>(null) }
    var userRenameNotice by remember { mutableStateOf<UserRenameResult?>(null) }
    val userChanges by UsersRepository.changes.collectAsStateWithLifecycle()

    var users by remember { mutableStateOf<List<BUserInfo>>(emptyList()) }
    var ownedApps by remember { mutableStateOf<OwnedAppList<AppInfo>?>(null) }
    var missingSourceApps by remember { mutableStateOf<Map<CloneTarget, List<AppInfo>>>(emptyMap()) }
    val appsList = ownedApps?.apps.orEmpty()
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val search = rememberNexBoxSearchState()
    val searchQuery = search.query
    val gridState = rememberLazyGridState()

    var selectedAppForMenu by remember { mutableStateOf<AppInfo?>(null) }
    var selectedAppTarget by remember { mutableStateOf<CloneTarget?>(null) }
    var showAppMenuBottomSheet by remember { mutableStateOf(false) }
    var appToDelete by remember { mutableStateOf<Pair<AppInfo, CloneTarget>?>(null) }
    var appToClearData by remember { mutableStateOf<Pair<AppInfo, CloneTarget>?>(null) }
    var showSourceNotice by remember { mutableStateOf(false) }

    fun closeAppOverlays() {
        showAppMenuBottomSheet = false
        selectedAppForMenu = null
        selectedAppTarget = null
        appToDelete = null
        appToClearData = null
    }

    fun canActOn(target: CloneTarget): Boolean =
        errorMessage == null && ownedApps?.accepts(target, currentSelectedUserId, isLoading) == true

    // A background refresh disables actions, but must not remove and recreate the sheet.
    fun canKeepOverlay(target: CloneTarget): Boolean =
        ownedApps?.accepts(target, currentSelectedUserId, loading = false) == true

    fun canKeepDeleteOverlay(app: AppInfo, target: CloneTarget): Boolean =
        if (app.sourceAvailable) canKeepOverlay(target) else
            users.any { it.id == target.userId && it.createTime == target.createTime } &&
                missingSourceApps[target].orEmpty().any { it.packageName == app.packageName }

    fun canDeleteApp(app: AppInfo, target: CloneTarget): Boolean =
        !isLoading && errorMessage == null && canKeepDeleteOverlay(app, target)

    // User-bound work runs off the UI thread, with the captured identity checked
    // inside the same host mutation boundary used by installation and deletion.
    fun <T> runAppAction(target: CloneTarget, packageName: String, action: () -> T, onSuccess: (T) -> Unit) {
        if (userActionRunning || !canActOn(target)) {
            NexLog.w(TAG, "action=appAction rejected=busy_or_stale_target userId=${target.userId} generation=${target.createTime}")
            return
        }
        userActionRunning = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    synchronized(UsersRepository.mutationLock) {
                        UserIdentityResolver.validateTarget(target)
                        action()
                    }
                }
                // A delayed read must not reopen an old user's dialog after a switch.
                if (canActOn(target)) onSuccess(result)
            } catch (e: CancellationException) { throw e }
            catch (failure: ConfigurationStopException) {
                stopFailure = failure
            }
            catch (failure: PackageCodeAvailability.SourceUnavailableException) {
                NexLog.w(TAG, "action=appAction reason=source_unavailable package=$packageName" +
                    " userId=${target.userId} generation=${target.createTime}", failure)
                if (canKeepOverlay(target)) {
                    ownedApps = ownedApps?.let { snapshot ->
                        snapshot.copy(apps = snapshot.apps.map {
                            if (it.packageName == packageName) it.copy(sourceAvailable = false) else it
                        })
                    }
                    ownedApps?.apps?.firstOrNull { it.packageName == packageName }?.let { app ->
                        missingSourceApps = missingSourceApps + (target to
                            (missingSourceApps[target].orEmpty().filterNot { it.packageName == packageName } + app))
                    }
                    showSourceNotice = true
                }
            }
            catch (error: Exception) {
                NexLog.e(TAG, "action=appAction userId=${target.userId} generation=${target.createTime} failed", error)
                Toast.makeText(context, R.string.user_action_failed, Toast.LENGTH_SHORT).show()
            }
            finally { userActionRunning = false }
        }
    }

    ConfigurationStopFailureDialog(stopFailure, onDismiss = { stopFailure = null })

    userRenameNotice?.let { result ->
        AlertDialog(
            onDismissRequest = { userRenameNotice = null },
            title = { Text(stringResource(R.string.user_rename_saved, result.name)) },
            text = { Text(stringResource(when {
                !result.refreshConfirmed && result.stopRequested -> R.string.user_rename_refresh_pending_stopped
                !result.refreshConfirmed -> R.string.user_rename_refresh_pending
                else -> R.string.user_rename_relaunch
            })) },
            confirmButton = { TextButton(onClick = { userRenameNotice = null }) {
                Text(stringResource(R.string.done))
            } }
        )
    }

    LaunchedEffect(selectedUserId, ownedApps?.owner) {
        closeAppOverlays()
        userToManage = null
    }

    LaunchedEffect(missingSourceApps, isLoading, errorMessage) {
        if (!isLoading && errorMessage == null) {
            if (missingSourceApps.values.all { it.isEmpty() }) showSourceNotice = false
            appToDelete?.takeIf { !it.first.sourceAvailable }?.let { (app, target) ->
                if (!canKeepDeleteOverlay(app, target)) appToDelete = null
            }
        }
    }

    // 「回到本页」时刷新。选中分身后首次进入由下面的 LaunchedEffect 负责，
    // 此处的 resume 只处理从选择器 / 子页面返回的情况。
    var reloadToken by remember { mutableIntStateOf(0) }
    var syncedReloadToken by remember { mutableStateOf<Int?>(null) }
    var isFirstResume by remember { mutableStateOf(true) }
    val refreshLifecycle = rememberPageRefreshLifecycle()
    DisposableEffect(refreshLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (isFirstResume) {
                    isFirstResume = false
                } else {
                    reloadToken++
                }
            }
        }
        refreshLifecycle.addObserver(observer)
        onDispose { refreshLifecycle.removeObserver(observer) }
    }

    suspend fun load(targetUserId: Int) {
        currentCoroutineContext().ensureActive()
        isLoading = true
        val requestedReloadToken = reloadToken
        val syncSources = syncedReloadToken != requestedReloadToken
        val previousOwner = ownedApps?.owner?.takeIf { it.userId == targetUserId }
        var observedOwner: CloneTarget? = null
        try {
            // 这些调用都会跨进程访问 Bcore，必须在 IO 线程执行，否则会阻塞主线程
            val (userList, snapshot, missingApps) = withContext(Dispatchers.IO) {
                awaitNexBoxServices()
                if (syncSources) repo.syncHostSources()
                UsersRepository.pruneEmptyUsers()
                val userList = UsersRepository.users()
                // 安装/卸载后会回收空分身，选中项可能已经不存在
                val effectiveUserId = if (userList.any { it.id == targetUserId }) {
                    targetUserId
                } else {
                    userList.firstOrNull()?.id ?: 0
                }
                val owner = CloneTarget(effectiveUserId,
                    userList.firstOrNull { it.id == effectiveUserId }?.createTime ?: 0L)
                observedOwner = owner
                val installedApps = repo.getVmInstallList(owner)
                val confirmedOwner = UserIdentityResolver.captureTarget(effectiveUserId)
                observedOwner = confirmedOwner
                check(confirmedOwner == owner) { context.getString(R.string.clone_user_changed) }
                val otherOwners = userList.map { CloneTarget(it.id, it.createTime) }.filterNot { it == owner }
                val otherMissingApps = repo.getMissingSourceApps(otherOwners)
                val selectedMissingApps = installedApps.filterNot { it.sourceAvailable }
                val missingApps = if (selectedMissingApps.isEmpty()) otherMissingApps
                    else otherMissingApps + (owner to selectedMissingApps)
                Triple(userList, OwnedAppList(owner, installedApps), missingApps)
            }
            currentCoroutineContext().ensureActive()
            users = userList
            ownedApps = snapshot
            missingSourceApps = missingApps
            syncedReloadToken = requestedReloadToken
            NexLog.d(TAG) {
                "action=loadUnavailableSources groups=${missingApps.size} count=${missingApps.values.sumOf { it.size }}"
            }
            CurrentUserSelection.select(snapshot.owner)
            errorMessage = null
            if (snapshot.owner.userId != targetUserId) {
                onSelectedUserChange(snapshot.owner.userId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            NexLog.e(TAG, "load failed for user $targetUserId", e)
            errorMessage = e.message ?: e.javaClass.simpleName
            // Keep a same-owner snapshot and its sheet mounted through a transient read
            // failure. Actions stay disabled until a successful retry confirms identity.
            // A newly observed generation/selection is never allowed to retain old content.
            if (previousOwner == null || (observedOwner != null && observedOwner != previousOwner)) {
                ownedApps = null
            }
        } finally {
            if (currentCoroutineContext().isActive) isLoading = false
        }
    }

    LaunchedEffect(selectedUserId, reloadToken, userChanges) {
        load(selectedUserId)
    }

    val launchApp: (String, CloneTarget) -> Unit = { packageName, target ->
        runAppAction(target, packageName, { CloneAppActions.start(packageName, target) }) { launched ->
            if (!launched) Toast.makeText(context, R.string.start_fail, Toast.LENGTH_SHORT).show()
        }
    }

    val filteredApps = remember(appsList, searchQuery) {
        if (searchQuery.isBlank()) {
            appsList
        } else {
            appsList.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                        it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    // Capture the rendered list's owner, not a target reconstructed from live selection.
    val listOwner = ownedApps?.owner
    val missingSourceGroups = remember(users, missingSourceApps) {
        users.mapNotNull { user ->
            val target = CloneTarget(user.id, user.createTime)
            missingSourceApps[target]?.takeIf { it.isNotEmpty() }?.let {
                MissingAppSourceGroup(target, UsersRepository.displayName(user), it)
            }
        }
    }
    val unavailableAppCount = missingSourceGroups.sumOf { it.apps.size }
    val appActionsEnabled = listOwner != null && canActOn(listOwner) && !userActionRunning
    val miuix = usesMiuix()
    val headerScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = rememberTopAppBarState(), canScroll = { !miuix && !search.active }
    )

    Scaffold(
        modifier = Modifier.nestedScroll(headerScrollBehavior.nestedScrollConnection).dismissSearchFocusOnBlankTap(search),
        topBar = {
            Column {
                HomeHeader(
                    searchState = search,
                    scrollBehavior = headerScrollBehavior
                )

                NexBoxUserBar(
                    users = users.map { UserTab(it.id, UsersRepository.displayName(it)) },
                    selectedUserId = selectedUserId,
                    onSelectUser = { id ->
                        users.firstOrNull { it.id == id }?.let {
                            if (id != currentSelectedUserId) {
                                isLoading = true
                                closeAppOverlays()
                                userToManage = null
                            }
                            CurrentUserSelection.select(CloneTarget(it.id, it.createTime))
                            onSelectedUserChange(id)
                        }
                    },
                    onManageUser = { id -> userToManage = users.firstOrNull { it.id == id } }
                )
                if (unavailableAppCount > 0) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = NexBoxSpacing.screenPadding),
                        shape = NexBoxShapes.control,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = NexBoxSpacing.lg, end = NexBoxSpacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NexBoxSpacing.sm)
                        ) {
                            Text(stringResource(R.string.clone_sources_unavailable, unavailableAppCount),
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showSourceNotice = true }) {
                                Text(stringResource(R.string.clone_source_review),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                if (errorMessage != null && ownedApps != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = NexBoxSpacing.screenPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NexBoxSpacing.sm)
                    ) {
                        Text(stringResource(R.string.load_failed),
                            color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        TextButton(onClick = { reloadToken++ }, enabled = !isLoading) {
                            Text(stringResource(R.string.xp_refresh))
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onNavigateToAppPicker(selectedUserId) }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(id = R.string.choose_app)
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            when {
                isLoading && appsList.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                errorMessage != null && ownedApps == null -> {
                    NexBoxEmptyState(
                        modifier = Modifier.padding(innerPadding),
                        message = stringResource(id = R.string.load_failed),
                        action = {
                            Button(onClick = { reloadToken++ }) {
                                Text(text = stringResource(id = R.string.xp_refresh))
                            }
                        }
                    )
                }

                filteredApps.isEmpty() -> {
                    if (isSearchFilteredEmpty(searchQuery, appsList)) {
                        NexBoxEmptyState(message = stringResource(id = R.string.search_no_result),
                            modifier = Modifier.padding(innerPadding))
                    } else if (users.isEmpty()) {
                        // The persistent FAB is the single add entry point, including first launch.
                        NexBoxEmptyState(
                            modifier = Modifier.padding(innerPadding),
                            message = stringResource(id = R.string.empty_no_profile)
                        )
                    } else {
                        NexBoxEmptyState(
                            modifier = Modifier.padding(innerPadding),
                            message = stringResource(id = R.string.empty_empty)
                        )
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(NexBoxSpacing.gridMinCell),
                        state = gridState,
                        contentPadding = PaddingValues(
                            start = innerPadding.calculateStartPadding(layoutDirection) + NexBoxSpacing.screenPadding,
                            top = innerPadding.calculateTopPadding() + NexBoxSpacing.screenPadding,
                            end = innerPadding.calculateEndPadding(layoutDirection) + NexBoxSpacing.screenPadding,
                            bottom = innerPadding.calculateBottomPadding() + NexBoxSpacing.screenPadding
                        ),
                        horizontalArrangement = Arrangement.spacedBy(NexBoxSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.md),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredApps, key = { it.packageName }) { appInfo ->
                            AppGridItem(
                                appInfo = appInfo,
                                enabled = appActionsEnabled,
                                loadIcon = { repo.loadAppIcon(appInfo.iconKey) },
                                onClick = {
                                    listOwner?.let { target ->
                                        launchApp(appInfo.packageName, target)
                                    }
                                },
                                onLongClick = {
                                    listOwner?.takeIf { canActOn(it) && !userActionRunning }?.let { target ->
                                        selectedAppTarget = target
                                        selectedAppForMenu = appInfo
                                        showAppMenuBottomSheet = true
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    userToManage?.let { user ->
        UserActionsDialog(user = user, busy = userActionRunning, onDismiss = { userToManage = null },
            onRename = { name ->
                if (userActionRunning) return@UserActionsDialog
                userActionRunning = true
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { UsersRepository.rename(user, name) }
                        userToManage = null
                        if (!result.refreshConfirmed || result.requiresRelaunch) {
                            userRenameNotice = result
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { Toast.makeText(context, R.string.user_action_failed, Toast.LENGTH_SHORT).show() }
                    finally { userActionRunning = false }
                }
            },
            onDelete = {
                if (userActionRunning) return@UserActionsDialog
                userActionRunning = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { UsersRepository.delete(user) }
                        userToManage = null
                        reloadToken++
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { Toast.makeText(context, R.string.user_action_failed, Toast.LENGTH_SHORT).show() }
                    finally { userActionRunning = false }
                }
            })
    }

    if (showAppMenuBottomSheet && selectedAppForMenu != null && selectedAppTarget?.let(::canKeepOverlay) == true &&
        appsList.any { it.packageName == selectedAppForMenu?.packageName }) {
        val app = selectedAppForMenu!!
        val target = selectedAppTarget!!

        ModalBottomSheet(
            onDismissRequest = { showAppMenuBottomSheet = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = NexBoxSpacing.xxl)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = NexBoxSpacing.xxl, vertical = NexBoxSpacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.md)
                ) {
                    val bitmap = rememberAppBitmap(app.iconKey, app.icon, true) { repo.loadAppIcon(app.iconKey) }
                    Box(Modifier.size(NexBoxSpacing.appIcon).clip(CircleShape)) {
                        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize())
                    }
                    Text(
                        text = app.name,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                ListItem(
                    headlineContent = { Text(text = stringResource(id = R.string.app_action_open)) },
                    leadingContent = {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Launch, contentDescription = null)
                    },
                    modifier = Modifier.combinedClickable(enabled = !userActionRunning && canActOn(target), onClick = {
                        showAppMenuBottomSheet = false
                        launchApp(app.packageName, target)
                    })
                )

                ListItem(
                    headlineContent = { Text(text = stringResource(id = R.string.app_action_stop)) },
                    leadingContent = { Icon(imageVector = Icons.Default.Stop, contentDescription = null) },
                    modifier = Modifier.combinedClickable(enabled = !userActionRunning && canActOn(target), onClick = {
                        showAppMenuBottomSheet = false
                        runAppAction(target, app.packageName, {
                            CloneAppActions.stop(app.packageName, target)
                        }) {
                            Toast.makeText(context, context.getString(R.string.is_stop, app.name), Toast.LENGTH_SHORT).show()
                        }
                    })
                )

                ListItem(
                    headlineContent = { Text(text = stringResource(id = R.string.app_action_random_device)) },
                    leadingContent = { Icon(imageVector = Icons.Default.Casino, contentDescription = null) },
                    modifier = Modifier.combinedClickable(enabled = !userActionRunning && canActOn(target), onClick = {
                        showAppMenuBottomSheet = false
                        runAppAction(target, app.packageName, {
                            DeviceRepository.randomizeAppProfile(app.packageName, target.userId, target.createTime)
                        }) {
                            Toast.makeText(context, R.string.device_spoofing_app_random_success, Toast.LENGTH_SHORT).show()
                        }
                    })
                )

                ListItem(
                    headlineContent = { Text(text = stringResource(id = R.string.app_action_clear)) },
                    leadingContent = { Icon(imageVector = Icons.Default.ClearAll, contentDescription = null) },
                    modifier = Modifier.combinedClickable(enabled = !userActionRunning && canActOn(target), onClick = {
                        showAppMenuBottomSheet = false
                        appToClearData = app to target
                    })
                )

                ListItem(
                    headlineContent = { Text(text = stringResource(id = R.string.app_action_uninstall)) },
                    leadingContent = { Icon(imageVector = Icons.Default.Delete, contentDescription = null) },
                    modifier = Modifier.combinedClickable(enabled = !userActionRunning && canActOn(target), onClick = {
                        showAppMenuBottomSheet = false
                        appToDelete = app to target
                    })
                )
            }
        }
    }


    if (appToClearData?.second?.let(::canKeepOverlay) == true) {
        val (app, target) = appToClearData!!
        AlertDialog(
            onDismissRequest = { if (!userActionRunning) appToClearData = null },
            title = { Text(text = stringResource(id = R.string.app_clear)) },
            text = { Text(text = stringResource(id = R.string.app_clear_hint, app.name)) },
            confirmButton = {
                DestructiveButton(
                    enabled = !userActionRunning && canActOn(target),
                    onClick = {
                        if (userActionRunning || !canActOn(target)) return@DestructiveButton
                        userActionRunning = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { CloneAppActions.clearData(app.packageName, target) }
                                appToClearData = null
                                Toast.makeText(context, R.string.clear_success, Toast.LENGTH_SHORT).show()
                                reloadToken++
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { Toast.makeText(context, R.string.user_action_failed, Toast.LENGTH_SHORT).show() }
                            finally { userActionRunning = false }
                        }
                    }
                ) {
                    Text(text = stringResource(id = R.string.done))
                }
            },
            dismissButton = {
                TextButton(enabled = !userActionRunning, onClick = { if (!userActionRunning) appToClearData = null }) {
                    Text(text = stringResource(id = R.string.cancel))
                }
            }
        )
    }

    if (showSourceNotice && missingSourceGroups.isNotEmpty()) {
        MissingAppSourcesDialog(
            groups = missingSourceGroups,
            actionsEnabled = !isLoading && errorMessage == null && !userActionRunning,
            onKeep = {
                NexLog.i(TAG, "action=keepUnavailableSources groups=${missingSourceGroups.size}" +
                    " count=$unavailableAppCount data=retained")
                showSourceNotice = false
            },
            onDelete = { app, target ->
                if (canDeleteApp(app, target) && !userActionRunning) {
                    showSourceNotice = false
                    appToDelete = app to target
                }
            }
        )
    }

    if (appToDelete?.let { (app, target) -> canKeepDeleteOverlay(app, target) } == true) {
        val (app, target) = appToDelete!!
        val groupName = users.firstOrNull { it.id == target.userId && it.createTime == target.createTime }
            ?.let(UsersRepository::displayName).orEmpty()
        AlertDialog(
            onDismissRequest = { if (!userActionRunning) appToDelete = null },
            title = { Text(text = stringResource(id = R.string.app_remove)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.sm)) {
                    Text(groupName, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(id = R.string.uninstall_app_hint, app.name))
                }
            },
            confirmButton = {
                DestructiveButton(
                    enabled = !userActionRunning && canDeleteApp(app, target),
                    onClick = {
                        if (userActionRunning || !canDeleteApp(app, target)) return@DestructiveButton
                        userActionRunning = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { CloneAppActions.uninstall(app.packageName, target) }
                                appToDelete = null
                                reloadToken++
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { Toast.makeText(context, R.string.user_action_failed, Toast.LENGTH_SHORT).show() }
                            finally { userActionRunning = false }
                        }
                    }
                ) {
                    Text(text = stringResource(R.string.app_action_uninstall))
                }
            },
            dismissButton = {
                TextButton(enabled = !userActionRunning, onClick = { if (!userActionRunning) appToDelete = null }) {
                    Text(text = stringResource(id = R.string.cancel))
                }
            }
        )
    }
}

/** 有原始数据但被搜索过滤掉，说明是「没搜到」而不是「什么都没装」。 */
private fun isSearchFilteredEmpty(query: String, apps: List<AppInfo>): Boolean {
    return query.isNotBlank() && apps.isNotEmpty()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppGridItem(
    appInfo: AppInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    enabled: Boolean = true,
    loadIcon: (suspend () -> Bitmap?)? = null
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier
            .fillMaxWidth()
            .clip(NexBoxShapes.tile)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(NexBoxSpacing.md)
                .fillMaxWidth()
        ) {
            val bitmap = rememberAppBitmap(appInfo.iconKey, appInfo.icon, enabled, loadIcon)
            val sourceColorFilter = remember(appInfo.sourceAvailable) {
                if (appInfo.sourceAvailable) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
            }
            Box(Modifier.size(NexBoxSpacing.appIcon).clip(CircleShape)) {
                if (bitmap != null) Image(bitmap.asImageBitmap(), appInfo.name, Modifier.fillMaxSize(),
                    colorFilter = sourceColorFilter)
            }

            Spacer(modifier = Modifier.height(NexBoxSpacing.sm))

            Text(
                text = appInfo.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            if (!appInfo.sourceAvailable) {
                Text(
                    text = stringResource(R.string.clone_source_missing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

        }
    }
}
