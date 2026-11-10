package com.nexbox.app.view.list

import com.nexbox.app.data.CloneAppActions
import com.nexbox.app.data.UserIdentityResolver
import com.nexbox.app.util.NexLog

import android.graphics.Bitmap
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.nexbox.app.bean.InstalledAppBean
import com.nexbox.app.data.AppsRepository
import com.nexbox.app.data.UsersRepository
import com.nexbox.app.data.CloneInstallSource
import com.nexbox.app.data.prepareCloneSourceCancellable
import com.nexbox.app.data.CloneTarget
import com.nexbox.app.data.CloneInstallReceipt
import com.nexbox.app.data.CloneInstallCoordinator
import com.nexbox.app.data.CloneGroupChoice
import com.nexbox.app.data.CurrentUserSelection
import com.nexbox.app.R
import com.nexbox.app.util.getString
import com.nexbox.app.util.HostAppListAccessException
import com.nexbox.app.util.AppListPermission
import com.nexbox.app.util.awaitNexBoxServices
import com.nexbox.app.util.requireNexBoxServices
import com.nexbox.app.view.base.BaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns picker state and routes host/document sources through the checked clone coordinator. */
class ListViewModel(
    private val repo: AppsRepository,
    private val captureDocumentTarget: (Int) -> CloneTarget = {
        requireNexBoxServices()
        UserIdentityResolver.captureTarget(it)
    }
) : BaseViewModel() {

    val pendingClone = MutableLiveData<PendingCloneRequest?>()
    val installing = MutableLiveData(false)
    private val mutableUpdatingPackage = MutableStateFlow<String?>(null)
    val updatingPackage = mutableUpdatingPackage.asStateFlow()
    private var loadedTarget: CloneTarget? = null
    private var documentTarget: CloneTarget? = null

    val installedLiveData = MutableLiveData<List<InstalledAppBean>>()
    val loadError = MutableLiveData<String?>()
    val hostAccessBlocked = MutableLiveData(false)
    private var preparingDocument = false

    /**
     * 安装/卸载结果资源；显示时解析当前语言，null 表示没有待处理的事件。
     * 消费后请调用 [consumeInstallResult] 置空，避免旋转屏幕后重复弹 Toast。
     */
    val installResultLiveData = MutableLiveData<Int?>()

    /**
     * 最近一次安装成功所落到哪个分身；null 表示无待处理事件。
     *
     * 确认创建新用户时，Bcore 在安装时创建目标环境，
     * 主界面需要据此把选中项切过去。消费后调用 [consumeInstalledUser]。
     */
    val installedUserLiveData = MutableLiveData<Int?>()

    /**
     * 宿主应用列表的扫描标志放在 [AppsRepository] 里（进程级共享），
     * 这里的锁只用来保证「检查—扫描—置位」这一步是原子的。
     *
     * 仓储内部同步保护包版本缓存和图标发布；这个锁避免同一个选择器的重复扫描。
     * ABI 回退按包版本读取，不再依赖上游仅以文件路径为键的全局缓存。
     */
    private val hostScanMutex = Mutex()
    private val loadRequests = HostLoadRequests()
    private var loadJob: Job? = null

    /**
     * @param forceRefreshHost 强制重新扫描宿主应用列表。宿主安装了新应用后才需要，
     *   普通的「切换分身 / 安装完成后刷新」不需要重扫。
     */
    fun loadForUser(userId: Int, forceRefreshHost: Boolean = false) {
        val request = loadRequests.begin(forceRefreshHost)
        loadJob?.cancel()
        // Publish before starting the slow host/ABI scan, not only before badge lookup.
        loadingLiveData.value = true
        loadError.value = null
        hostAccessBlocked.value = false
        loadJob = viewModelScope.launch {
            try {
                val (target, apps) = withContext(Dispatchers.IO) {
                    awaitNexBoxServices()
                    val target = UserIdentityResolver.captureTarget(userId)
                    ensureHostPreviewLoaded(request.forceHostRefresh)
                    currentCoroutineContext().ensureActive()
                    val apps = repo.getInstalledAppList(userId)
                    UserIdentityResolver.validateTarget(target)
                    target to apps
                }
                if (loadRequests.isCurrent(request)) {
                    loadRequests.scanCompleted(request)
                    loadedTarget = target
                    installedLiveData.value = apps
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HostAppListAccessException) {
                if (loadRequests.isCurrent(request)) blockHostAccess()
            } catch (e: Exception) {
                NexLog.e("ListViewModel", "Could not load apps for user $userId", e)
                if (loadRequests.isCurrent(request)) {
                    loadedTarget = null
                    installedLiveData.value = emptyList()
                    loadError.value = e.message.orEmpty()
                }
            } finally {
                // A replaced request may finish its blocking I/O later. It cannot hide
                // the current request's progress or publish a list for the previous user.
                if (loadRequests.isCurrent(request)) loadingLiveData.value = false
            }
        }
    }

    /** Refresh target identity after an install without rescanning host metadata. */
    fun reloadForUser(userId: Int) = loadForUser(userId, forceRefreshHost = false)

    suspend fun loadHostIcon(app: InstalledAppBean): Bitmap? = withContext(Dispatchers.IO) {
        try {
            repo.loadAppIcon(app.iconKey)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NexLog.w("ListViewModel", "action=loadHostIcon package=${app.packageName}", error)
            null
        }
    }

    fun blockHostAccess() {
        loadRequests.begin(false)
        loadJob?.cancel()
        repo.invalidateHostPreview()
        loadedTarget = null
        installedLiveData.value = emptyList()
        loadingLiveData.value = false
        loadError.value = null
        hostAccessBlocked.value = true
    }

    /** An installed package is a request for another copy, never an implicit launch/update. */
    fun install(source: String, userId: Int) {
        val target = targetForAction(userId) ?: return
        install(source, target)
    }

    fun targetForAction(userId: Int): CloneTarget? {
        val target = loadedTarget
        if (loadingLiveData.value == true || target == null || target.userId != userId) {
            installResultLiveData.value = R.string.clone_user_changed
            return null
        }
        return target
    }

    /** Capture before opening DocumentsUI, not when its asynchronous result comes back. */
    suspend fun beginDocumentSelection(userId: Int): Boolean {
        if (preparingDocument || documentTarget != null || installing.value == true || pendingClone.value != null) return false
        preparingDocument = true
        return try {
            // DocumentsUI is independent of host enumeration, including on permission denial.
            val target = runInterruptible(Dispatchers.IO) { captureDocumentTarget(userId) }
            if (installing.value == true || pendingClone.value != null) return false
            documentTarget = target
            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            NexLog.e("ListViewModel", "action=beginDocumentSelection userId=$userId", e)
            installResultLiveData.value = R.string.clone_user_changed
            false
        } finally { preparingDocument = false }
    }

    fun installDocument(source: String?) {
        val target = documentTarget
        documentTarget = null
        if (source == null) return
        if (target == null) {
            installResultLiveData.value = R.string.clone_user_changed
            return
        }
        install(source, target)
    }

    private fun install(source: String, target: CloneTarget) {
        if (installing.value == true || pendingClone.value != null) return
        installing.value = true
        mutableUpdatingPackage.value = installedLiveData.value?.firstOrNull { it.packageName == source }?.packageName
        viewModelScope.launch {
            var prepared: CloneInstallSource? = null
            try {
                val choices = withContext(Dispatchers.IO) {
                    val request = prepareCloneSourceCancellable(source).also { prepared = it }
                    mutableUpdatingPackage.value = request.packageName
                    synchronized(UsersRepository.mutationLock) {
                        UserIdentityResolver.validateTarget(target)
                        val groups = CloneInstallCoordinator.availableGroups(request)
                        when {
                            // No group can accept this package: create exactly one new group.
                            groups.available.isEmpty() -> {
                                publishInstalled(CloneInstallCoordinator.installInNewUser(request))
                                emptyList()
                            }
                            groups.groupCount < 2 -> {
                                publishInstalled(CloneInstallCoordinator.install(request, groups.available.single().target))
                                emptyList()
                            }
                            else -> groups.available
                        }
                    }
                }
                if (choices.isNotEmpty()) {
                    pendingClone.value = PendingCloneRequest(checkNotNull(prepared), target, choices)
                    prepared = null
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                NexLog.e("ListViewModel", "action=clone userId=${target.userId}", e)
                installResultLiveData.value = R.string.clone_failed
            }
            finally { prepared?.close(); installing.value = false; mutableUpdatingPackage.value = null }
        }
    }

    fun confirmNewUser() = chooseCloneGroup(null)

    fun chooseCloneGroup(target: CloneTarget?) {
        val request = pendingClone.value ?: return
        if (installing.value == true) return
        if (target != null && request.groups.none { it.target == target }) return
        pendingClone.value = null
        installing.value = true
        mutableUpdatingPackage.value = request.source.packageName
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    synchronized(UsersRepository.mutationLock) {
                        if (target == null) {
                            publishInstalled(CloneInstallCoordinator.installInNewUser(request.source))
                        } else {
                            // Never turn a stale selection into an update of an existing clone.
                            check(!CloneInstallCoordinator.requiresNewUser(request.source, target)) {
                                getString(R.string.clone_user_changed)
                            }
                            publishInstalled(CloneInstallCoordinator.install(request.source, target))
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                NexLog.e("ListViewModel", "action=cloneNewUser sourceUserId=${request.target.userId}", e)
                installResultLiveData.value = R.string.clone_failed
            }
            finally { request.source.close(); installing.value = false; mutableUpdatingPackage.value = null }
        }
    }

    fun cancelNewUser() {
        if (installing.value == true) return
        pendingClone.value?.source?.close()
        pendingClone.value = null
    }

    override fun onCleared() {
        pendingClone.value?.source?.close()
        super.onCleared()
    }

    fun uninstall(packageName: String, target: CloneTarget) {
        if (installing.value == true) return
        installing.value = true
        mutableUpdatingPackage.value = packageName
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { CloneAppActions.uninstall(packageName, target) }
                if (loadedTarget == target) reloadForUser(target.userId)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                NexLog.e("ListViewModel", "action=uninstall package=$packageName userId=${target.userId}", e)
                installResultLiveData.value = R.string.user_action_failed
            }
            finally { installing.value = false; mutableUpdatingPackage.value = null }
        }
    }

    private fun publishInstalled(receipt: CloneInstallReceipt) {
        CurrentUserSelection.select(receipt.target())
        installedUserLiveData.postValue(receipt.userId)
    }

    fun consumeInstallResult() {
        installResultLiveData.value = null
    }

    fun consumeInstalledUser() {
        installedUserLiveData.value = null
    }

    private suspend fun ensureHostPreviewLoaded(force: Boolean) {
        if (!force && repo.isHostPreviewLoaded()) return
        hostScanMutex.withLock {
            if (force || !repo.isHostPreviewLoaded()) {
                check(repo.previewInstallList()) { "Could not scan installed host applications" }
                // MIUI can expose the grant before its package-list filtering catches up.
                // Retry only an empty first snapshot, not a populated list or every resume.
                if (AppListPermission.isMiuiDevice()) {
                    for (waitMillis in longArrayOf(120, 300, 600)) {
                        if (!repo.isHostPreviewEmpty()) break
                        delay(waitMillis)
                        currentCoroutineContext().ensureActive()
                        check(repo.previewInstallList()) { "Could not scan installed host applications" }
                    }
                }
            }
        }
    }
}

data class PendingCloneRequest(
    val source: CloneInstallSource,
    val target: CloneTarget,
    val groups: List<CloneGroupChoice> = emptyList()
)

internal data class HostLoadRequest(val generation: Long, val forceHostRefresh: Boolean)

/** Main-thread request ownership, including a forced refresh carried across cancellations. */
internal class HostLoadRequests {
    private var generation = 0L
    private var refreshPending = false

    fun begin(forceRefresh: Boolean): HostLoadRequest {
        refreshPending = refreshPending || forceRefresh
        return HostLoadRequest(++generation, refreshPending)
    }

    fun isCurrent(request: HostLoadRequest): Boolean = request.generation == generation

    fun scanCompleted(request: HostLoadRequest) {
        if (isCurrent(request) && request.forceHostRefresh) refreshPending = false
    }
}
