package com.nexbox.app.view.install

import com.nexbox.app.data.UserIdentityResolver
import com.nexbox.app.util.NexLog

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexbox.app.R
import com.nexbox.app.data.CloneInstallCoordinator
import com.nexbox.app.data.CloneInstallReceipt
import com.nexbox.app.data.CloneInstallSource
import com.nexbox.app.data.CloneTarget
import com.nexbox.app.data.CurrentUserSelection
import com.nexbox.app.data.prepareCloneSource
import com.nexbox.app.data.prepareCloneSourceCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ApkCloneImportFailure(val itemNumber: Int, @param:StringRes val message: Int)

data class ApkCloneImportState(
    val total: Int = 0,
    val processed: Int = 0,
    val successful: Int = 0,
    val skipped: Int = 0,
    val failures: List<ApkCloneImportFailure> = emptyList(),
    val target: CloneTarget? = null,
    val importing: Boolean = false,
    val pendingPackageName: String? = null,
    val completed: Boolean = false
) {
    val busy: Boolean get() = importing || pendingPackageName != null
}

/** Injectable synchronous operations keep regression fixtures away from real sandbox users. */
internal interface ApkCloneImportOperations {
    fun captureTarget(userId: Int): CloneTarget
    fun captureDefaultTarget(): CloneTarget
    fun validateTarget(target: CloneTarget)
    fun prepare(source: String): CloneInstallSource
    suspend fun prepareCancellable(source: String): CloneInstallSource = prepare(source)
    fun requiresNewUser(source: CloneInstallSource, target: CloneTarget): Boolean
    fun install(source: CloneInstallSource, target: CloneTarget): CloneInstallReceipt
    fun installInNewUser(source: CloneInstallSource): CloneInstallReceipt
    fun selectTarget(target: CloneTarget)
}

private object DefaultApkCloneImportOperations : ApkCloneImportOperations {
    override fun captureTarget(userId: Int) = UserIdentityResolver.captureTarget(userId)
    override fun captureDefaultTarget() = UserIdentityResolver.captureDefaultTarget()
    override fun validateTarget(target: CloneTarget) = UserIdentityResolver.validateTarget(target)
    override fun prepare(source: String) = prepareCloneSource(source)
    override suspend fun prepareCancellable(source: String) = prepareCloneSourceCancellable(source)
    override fun requiresNewUser(source: CloneInstallSource, target: CloneTarget) =
        CloneInstallCoordinator.requiresNewUser(source, target)
    override fun install(source: CloneInstallSource, target: CloneTarget) =
        CloneInstallCoordinator.install(source, target)
    override fun installInNewUser(source: CloneInstallSource) =
        CloneInstallCoordinator.installInNewUser(source)
    override fun selectTarget(target: CloneTarget) = CurrentUserSelection.select(target)
}

/**
 * One retained APK batch owns its target identity and each staged document. In particular,
 * an Activity recreation cannot restart an install or substitute the currently selected user.
 */
class ApkCloneImportViewModel internal constructor(
    private val operations: ApkCloneImportOperations,
    private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {
    constructor() : this(DefaultApkCloneImportOperations, Dispatchers.IO)

    private val mutableState = MutableStateFlow(ApkCloneImportState())
    val state = mutableState.asStateFlow()
    private var sources = emptyList<String>()
    private var pendingSource: CloneInstallSource? = null

    /** Called on the main thread; only an explicitly new Intent can replace a completed batch. */
    fun start(sources: List<String>, selectedTarget: CloneTarget?, replace: Boolean = false): Boolean {
        if (mutableState.value.total > 0 && !replace) return true
        if (mutableState.value.busy || sources.isEmpty()) return false
        this.sources = sources.distinct()
        NexLog.i("Import", "action=startApkBatch count=${this.sources.size} userId=${selectedTarget?.userId} generation=${selectedTarget?.createTime} replace=$replace")
        mutableState.value = ApkCloneImportState(
            total = this.sources.size,
            target = selectedTarget,
            importing = true
        )
        viewModelScope.launch {
            if (selectedTarget == null) {
                try {
                    // Retain an existing user 0; otherwise select genuinely unused storage,
                    // never an arbitrary existing group or an orphaned user-0 directory.
                    val target = withContext(ioDispatcher) { operations.captureDefaultTarget() }
                    mutableState.value = mutableState.value.copy(target = target)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failWithoutTarget(e)
                    return@launch
                }
            }
            processRemaining()
        }
        return true
    }

    fun confirmNewUser() {
        if (mutableState.value.importing) return
        val request = pendingSource ?: return
        val origin = mutableState.value.target
        NexLog.i("Import", "action=confirmNewUser package=${request.packageName} previousUser=${origin?.userId} previousGeneration=${origin?.createTime}")
        pendingSource = null
        mutableState.value = mutableState.value.copy(importing = true, pendingPackageName = null)
        viewModelScope.launch {
            try {
                val receipt = withContext(ioDispatcher) {
                    currentCoroutineContext().ensureActive()
                    operations.validateTarget(checkNotNull(origin) { "Target user unavailable" })
                    currentCoroutineContext().ensureActive()
                    operations.installInNewUser(request)
                }
                recordSuccess(receipt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordFailure(e)
            } finally {
                request.close()
            }
            processRemaining()
        }
    }

    /** Cancelling a duplicate does not allocate a user, change selection, or touch the package. */
    fun skipDuplicate() {
        if (mutableState.value.importing) return
        val request = pendingSource ?: return
        NexLog.i("Import", "action=skipDuplicate package=${request.packageName} userId=${mutableState.value.target?.userId}")
        pendingSource = null
        request.close()
        val current = mutableState.value
        mutableState.value = current.copy(
            processed = current.processed + 1,
            skipped = current.skipped + 1,
            importing = true,
            pendingPackageName = null
        )
        viewModelScope.launch { processRemaining() }
    }

    private suspend fun processRemaining() {
        while (mutableState.value.processed < sources.size) {
            currentCoroutineContext().ensureActive()
            val current = mutableState.value
            val target = checkNotNull(current.target)
            var prepared: CloneInstallSource? = null
            try {
                val receipt = withContext(ioDispatcher) {
                    val request = operations.prepareCancellable(sources[current.processed]).also { prepared = it }
                    currentCoroutineContext().ensureActive()
                    if (operations.requiresNewUser(request, target)) null
                    else {
                        currentCoroutineContext().ensureActive()
                        operations.install(request, target)
                    }
                }
                if (receipt == null) {
                    NexLog.i("Import", "action=awaitDuplicateConfirmation package=${prepared?.packageName} userId=${target.userId} generation=${target.createTime}")
                    pendingSource = checkNotNull(prepared)
                    prepared = null
                    mutableState.value = mutableState.value.copy(
                        importing = false,
                        pendingPackageName = pendingSource!!.packageName
                    )
                    return
                }
                recordSuccess(receipt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordFailure(e, preparingSource = prepared == null)
            } finally {
                // Covers parse/install failure and cancellation while switching back from I/O.
                prepared?.close()
            }
        }
        mutableState.value = mutableState.value.copy(importing = false, completed = true)
        NexLog.i("Import", "action=finishApkBatch total=${mutableState.value.total} successful=${mutableState.value.successful} skipped=${mutableState.value.skipped} failed=${mutableState.value.failures.size}")
    }

    private fun recordSuccess(receipt: CloneInstallReceipt) {
        NexLog.i("Import", "action=itemConfirmed package=${receipt.packageName} userId=${receipt.userId} generation=${receipt.createTime}")
        val target = CloneTarget(receipt.userId, receipt.createTime)
        operations.selectTarget(target)
        val current = mutableState.value
        mutableState.value = current.copy(
            target = target,
            processed = current.processed + 1,
            successful = current.successful + 1
        )
    }

    private fun recordFailure(error: Exception, preparingSource: Boolean = false) {
        NexLog.e("Import", "action=itemFailed index=${mutableState.value.processed + 1} userId=${mutableState.value.target?.userId}", error)
        val current = mutableState.value
        mutableState.value = current.copy(
            processed = current.processed + 1,
            failures = current.failures + ApkCloneImportFailure(current.processed + 1, when {
                error is SecurityException -> R.string.apk_clone_permission_denied
                preparingSource -> R.string.apk_clone_source_failed
                else -> R.string.install_fail
            })
        )
    }

    private fun failWithoutTarget(error: Exception) {
        NexLog.e("Import", "action=batchRejected reason=target_unavailable count=${sources.size}", error)
        val current = mutableState.value
        mutableState.value = current.copy(
            processed = current.total,
            failures = sources.indices.map { ApkCloneImportFailure(it + 1, R.string.apk_clone_user_unavailable) },
            importing = false,
            completed = true
        )
    }

    override fun onCleared() {
        if (!mutableState.value.completed) NexLog.i("Import", "action=batchCancelled processed=${mutableState.value.processed} total=${mutableState.value.total}")
        pendingSource?.close()
        pendingSource = null
        super.onCleared()
    }
}
