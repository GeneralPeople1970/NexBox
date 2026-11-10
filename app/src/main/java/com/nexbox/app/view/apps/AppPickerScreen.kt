package com.nexbox.app.view.apps

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.nexbox.app.ui.icons.filled.Android
import com.nexbox.app.ui.components.AlertDialog
import com.nexbox.app.ui.components.Button
import com.nexbox.app.ui.components.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import com.nexbox.app.ui.components.Icon
import com.nexbox.app.ui.components.NexBoxLoadingOverlay
import com.nexbox.app.ui.components.ListItem
import com.nexbox.app.ui.components.Scaffold
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexbox.app.R
import com.nexbox.app.bean.InstalledAppBean
import com.nexbox.app.data.CloneTarget
import com.nexbox.app.data.AppPickerPreferences
import com.nexbox.app.data.sortHostApps
import com.nexbox.app.data.filterHostApps
import com.nexbox.app.ui.components.NexBoxCompactActionButton
import com.nexbox.app.ui.components.rememberUserDisplayName
import com.nexbox.app.ui.components.NexBoxScrollableEmptyState
import com.nexbox.app.ui.components.NexBoxTopAppBar
import com.nexbox.app.ui.components.NexBoxSearchField
import com.nexbox.app.ui.components.NexBoxPullToRefresh
import com.nexbox.app.ui.components.rememberNexBoxSearchState
import com.nexbox.app.ui.components.dismissSearchFocusOnBlankTap
import com.nexbox.app.ui.theme.NexBoxSpacing
import androidx.compose.ui.unit.dp
import com.nexbox.app.util.InjectionUtil
import com.nexbox.app.util.NexLog
import com.nexbox.app.util.AppListPermissionState
import com.nexbox.app.util.MiuiAppListPermission
import com.nexbox.app.util.rememberHostAppListPermission
import com.nexbox.app.view.list.ListViewModel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 应用选择器：把宿主已安装的应用克隆进指定分身。
 *
 * 这是「多开」的入口。此前该界面在 Compose 重构中被整体删除，导致主界面的
 * 悬浮按钮只弹一个 Toast，用户无法克隆任何应用。
 *
 * @param userId 目标分身。新分身不必预先创建——Bcore 在首次安装时会按需建用户。
 * @param onInstalled 安装成功时回调目标分身，供主界面切换选中项。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppPickerScreen(
    userId: Int,
    onBack: () -> Unit = {},
    onInstalled: (Int) -> Unit = {},
    viewModel: ListViewModel = viewModel(factory = InjectionUtil.getListFactory())
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val search = rememberNexBoxSearchState()
    val query = search.query
    val preferences = remember(context.applicationContext) { AppPickerPreferences.get(context) }
    val options by preferences.options.collectAsStateWithLifecycle()
    LaunchedEffect(preferences) {
        preferences.saveErrors.collect {
            Toast.makeText(context, R.string.app_picker_preferences_failed, Toast.LENGTH_LONG).show()
        }
    }
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    var appToUninstall by remember { mutableStateOf<Pair<InstalledAppBean, CloneTarget>?>(null) }
    val listState = rememberLazyListState()
    var activeUserId by rememberSaveable(userId) { mutableStateOf(userId) }
    val pendingClone by viewModel.pendingClone.observeAsState()
    val installing by viewModel.installing.observeAsState(false)
    val updatingPackage by viewModel.updatingPackage.collectAsStateWithLifecycle()

    val installed by viewModel.installedLiveData.observeAsState(emptyList())
    val loading by viewModel.loadingLiveData.observeAsState(true)
    val installResult by viewModel.installResultLiveData.observeAsState()
    val installedUser by viewModel.installedUserLiveData.observeAsState()
    val loadError by viewModel.loadError.observeAsState()
    val accessBlocked by viewModel.hostAccessBlocked.observeAsState(false)
    val permission = rememberHostAppListPermission(loadKey = activeUserId,
        onReady = { viewModel.loadForUser(activeUserId, forceRefreshHost = it) },
        onUnavailable = { viewModel.blockHostAccess() })

    // Errors are explicit; successful installs switch to the newly populated group.
    LaunchedEffect(installResult) {
        val message = installResult ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.consumeInstallResult()
    }

    // 新分身是随首次安装才被 Bcore 创建的，主界面需要据此切过去
    LaunchedEffect(installedUser) {
        val target = installedUser ?: return@LaunchedEffect
        viewModel.consumeInstalledUser()
        Toast.makeText(context, R.string.clone_success, Toast.LENGTH_SHORT).show()
        val previousUserId = activeUserId
        activeUserId = target
        onInstalled(target)
        // A changed user causes the permission gate to load the new target itself.
        if (target == previousUserId && permission.access.canEnumerate) viewModel.reloadForUser(target)
    }

    val apkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        viewModel.installDocument(uri?.toString())
    }

    fun chooseApk() {
        val targetId = activeUserId
        scope.launch {
            if (viewModel.beginDocumentSelection(targetId)) {
                try {
                    apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/zip", "application/octet-stream"))
                } catch (error: RuntimeException) {
                    viewModel.installDocument(null)
                    NexLog.e("AppPicker", "action=openDocumentPicker", error)
                    Toast.makeText(context, R.string.clone_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val sorted = remember(installed, options.sort, options.reverse, options.showSystemApps, locale) {
        sortHostApps(installed, options, locale)
    }
    val visible = remember(sorted, query) { filterHostApps(sorted, query) }

    Scaffold(
        modifier = Modifier.dismissSearchFocusOnBlankTap(search),
        topBar = {
          Column {
            NexBoxTopAppBar(
                onBack = onBack,
                title = stringResource(id = R.string.app_picker_title),
                actions = {
                    AppPickerMoreMenu(options, preferences::update, onOpen = search::clearFocus,
                        onImport = ::chooseApk, onRefresh = permission.refresh,
                        importEnabled = !installing && pendingClone == null,
                        refreshEnabled = !loading && !permission.requesting && !installing)
                }
            )
            NexBoxSearchField(search, stringResource(R.string.search_apps_hint),
                Modifier.padding(horizontal = NexBoxSpacing.screenPadding, vertical = NexBoxSpacing.sm))
          }
        }
    ) { innerPadding ->
        NexBoxPullToRefresh(
            refreshing = loading && !permission.requesting && visible.isNotEmpty(),
            onRefresh = { search.clearFocus(); permission.refresh() },
            enabled = !permission.requesting && !installing,
            modifier = Modifier.fillMaxSize().padding(innerPadding)
        ) {
            NexBoxLoadingOverlay(loading = installing && visible.none { it.packageName == updatingPackage },
                modifier = Modifier.fillMaxSize()) {
                when {
                    permission.requesting -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    !permission.access.canEnumerate || accessBlocked -> {
                        AppListPermissionEmptyState(
                            permission = if (!permission.access.canEnumerate) permission.access.state else AppListPermissionState.DENIED,
                            onOpenSettings = permission.openSettings,
                            onRetry = permission.refresh, onInstallFile = ::chooseApk,
                            onRequest = if (permission.access.miui == MiuiAppListPermission.DENIED && !permission.permanentlyDenied)
                                permission.retry else null)
                    }
                    loadError != null -> {
                        NexBoxScrollableEmptyState(
                            message = stringResource(R.string.load_failed),
                            action = { Button(onClick = permission.refresh) { Text(stringResource(R.string.xp_refresh)) } })
                    }
                    visible.isEmpty() && loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    visible.isEmpty() -> {
                        NexBoxScrollableEmptyState(
                            message = stringResource(if (query.isNotBlank()) R.string.search_no_result else R.string.app_picker_empty)
                        )
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                            items(visible, key = { it.packageName }) { bean ->
                                NexBoxLoadingOverlay(loading = installing && bean.packageName == updatingPackage,
                                    status = stringResource(R.string.updating_named, bean.name)) {
                                    AppPickerRow(
                                        bean = bean,
                                        showPackageName = options.showPackageName,
                                        onClick = { viewModel.install(bean.packageName, activeUserId) },
                                        onLongClick = {
                                            if (bean.isInstalled) {
                                                viewModel.targetForAction(activeUserId)?.let { appToUninstall = bean to it }
                                            }
                                        },
                                        enabled = !loading && !installing && pendingClone == null,
                                        loadIcon = { viewModel.loadHostIcon(bean) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingClone?.let { request ->
        // Dialog/LazyColumn subcompositions may still read content while their parent
        // is being removed. Capture this request before confirm/cancel clears the state.
        AlertDialog(
            onDismissRequest = viewModel::cancelNewUser,
            contentScrollable = false,
            title = { Text(stringResource(R.string.clone_choose_group)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(request.groups, key = { "${it.target.userId}/${it.target.createTime}" }) { group ->
                        ListItem(
                            headlineContent = { Text(rememberUserDisplayName(group.target.userId)) },
                            modifier = Modifier.clickable { viewModel.chooseCloneGroup(group.target) }
                        )
                    }
                    item(key = "new-group") {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.clone_create_group)) },
                            modifier = Modifier.clickable(onClick = viewModel::confirmNewUser)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::cancelNewUser) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (appToUninstall != null) {
        val (app, target) = appToUninstall!!
        AlertDialog(
            onDismissRequest = { appToUninstall = null },
            title = { Text(text = stringResource(id = R.string.app_remove)) },
            text = { Text(text = stringResource(id = R.string.uninstall_app_hint, app.name)) },
            confirmButton = {
                com.nexbox.app.ui.components.DestructiveButton(
                    onClick = {
                        appToUninstall = null
                        viewModel.uninstall(app.packageName, target)
                    }
                ) {
                    Text(text = stringResource(id = R.string.done))
                }
            },
            dismissButton = {
                TextButton(onClick = { appToUninstall = null }) {
                    Text(text = stringResource(id = R.string.cancel))
                }
            }
        )
    }
}

@Composable
internal fun AppListPermissionEmptyState(
    permission: AppListPermissionState,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onInstallFile: () -> Unit,
    onRequest: (() -> Unit)? = null
) {
    val denied = permission == AppListPermissionState.DENIED
    NexBoxScrollableEmptyState(
        modifier = modifier,
        message = stringResource(if (denied) R.string.app_list_permission_required_title else R.string.app_list_permission_check_title),
        action = {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.sm)) {
                Button(onClick = onRequest ?: onOpenSettings) {
                    Text(stringResource(if (onRequest != null) R.string.app_list_permission_request else R.string.app_list_permission_settings))
                }
                if (onRequest != null) TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.app_list_permission_settings)) }
                else TextButton(onClick = onRetry) { Text(stringResource(R.string.xp_refresh)) }
                TextButton(onClick = onInstallFile) { Text(stringResource(R.string.install_from_file)) }
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppPickerRow(
    bean: InstalledAppBean,
    showPackageName: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    enabled: Boolean = true,
    loadIcon: (suspend () -> Bitmap?)? = null
) {
    ListItem(
        headlineContent = {
            Text(
                text = bean.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = if (showPackageName) {{
            Text(
                text = bean.packageName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }} else null,
        leadingContent = { AppIcon(bean, enabled, loadIcon) },
        trailingContent = {
            // Keep the same capsule and label even when this app already has other clones.
            NexBoxCompactActionButton(onClick = onClick, enabled = enabled) {
                Text(stringResource(R.string.clone_action), maxLines = 1, softWrap = false)
            }
        },
        modifier = Modifier.combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick
        )
    )
}

/**
 * 应用图标。宿主图标可能很大，统一缩到列表尺寸再上屏，
 * 解码失败时静默降级为不显示图标（不阻断列表）。
 */
@Composable
private fun AppIcon(bean: InstalledAppBean, enabled: Boolean, loadIcon: (suspend () -> Bitmap?)?) {
    val bitmap = rememberAppBitmap(bean.iconKey, bean.icon, enabled, loadIcon)
    Box(Modifier.size(NexBoxSpacing.listIcon).clip(RoundedCornerShape(NexBoxSpacing.sm)),
        contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), bean.name, Modifier.fillMaxSize())
        else Icon(Icons.Default.Android, null, Modifier.size(NexBoxSpacing.xl))
    }
}
