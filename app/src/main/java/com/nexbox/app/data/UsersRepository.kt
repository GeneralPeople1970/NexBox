package com.nexbox.app.data

import android.os.DeadObjectException
import android.os.Bundle
import com.nexbox.app.app.App
import com.nexbox.app.app.AppManager
import com.nexbox.app.app.AppFreezeManager
import com.nexbox.app.util.NexLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.nexbox.core.fake.frameworks.BUserManager
import com.nexbox.core.core.system.user.BUserInfo
import com.nexbox.core.core.system.user.UserDisplayNames

/** Host mutations share one boundary; the core validates generation and emptiness atomically. */
object UsersRepository {
    val mutationLock = Any()
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()
    private fun service() = checkNotNull(BUserManager.get().service) { "User service unavailable" }

    /** Every screen uses the persisted group name, not a separate remark or a tab index. */
    fun displayName(user: BUserInfo): String =
        UserDisplayNames.displayName(App.getContext(), user)

    /** Binder read: call on IO when the caller does not already have the user snapshot. */
    fun displayName(userId: Int): String = service().getUserInfo(userId)?.let(::displayName)
        ?: UserDisplayNames.defaultName(App.getContext(), userId)

    fun notifyChanged() { mutableChanges.update { it + 1L } }

    fun users(): List<BUserInfo> = readUserServiceWithSingleReconnect(
        read = { checkNotNull(service().users) { "User list unavailable" } },
        invalidate = {
            NexLog.w("Users", "action=listUsers stage=binderReconnect retry=1")
            BUserManager.get().clearServiceCache()
        }
    ).filterNotNull().sortedBy { it.id }.also { NexLog.d("Users", "action=listUsers count=${it.size}") }

    /**
     * Only populated, real users are shown as clone groups. An empty core user or the
     * automatic gallery alone must not invent a group in settings or scope pickers.
     * This is a read filter, never a reason to prune data; raw users() remains the
     * identity/allocation source so first installs and protected empty users stay safe.
     */
    fun cloneGroups(): List<BUserInfo> = synchronized(mutationLock) {
        val groups = readUserServiceWithSingleReconnect(
            read = { checkNotNull(service().cloneGroups) { "Clone group summary unavailable" } },
            invalidate = {
                NexLog.w("Users") { "action=cloneGroups stage=binderReconnect retry=1" }
                BUserManager.get().clearServiceCache()
            }
        ).map { value ->
            val user = checkNotNull(value) { "Incomplete clone group summary" }
            check(user.id >= 0 && user.createTime > 0L) { "Invalid clone user identity" }
            user
        }
        check(groups.map { it.id }.distinct().size == groups.size) { "Duplicate clone user identity" }
        groups.sortedBy { it.id }
    }

    fun nextId(): Int = readUserServiceWithSingleReconnect(
        read = { service().nextAvailableUserId.also { check(it >= 0) { "No free user ID is available" } } },
        invalidate = { BUserManager.get().clearServiceCache() }
    )

    fun rename(user: BUserInfo, name: String): UserRenameResult = synchronized(mutationLock) {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty() && normalizedName.length <= 32) { "Name must contain 1..32 characters" }
        NexLog.trace("Users", "rename", "userId=${user.id} generation=${user.createTime} nameLength=${normalizedName.length}") { operation ->
            val reply = checkNotNull(service().renameUserWithResult(user.id, user.createTime, normalizedName)) {
                "User rename result unavailable"
            }
            val result = UserRenameResult.fromBundle(reply, user, normalizedName)
            check(result.saved) { "User changed or rename failed: ${result.error ?: "unknown"}" }
            notifyChanged()
            operation.finish(result.refreshConfirmed,
                "nameSaved=true changed=${result.changed} refreshStatus=${result.refreshStatus}" +
                    " updatedTasks=${result.updatedTasks} unchangedTasks=${result.unchangedTasks}" +
                    " removedTasks=${result.removedTasks} remainingTasks=${result.remainingTasks}" +
                    " unverifiedTasks=${result.unverifiedTasks} stopRequested=${result.stopRequested}" +
                    " stopConfirmed=${result.stopConfirmed} requiresRelaunch=${result.requiresRelaunch}")
            result
        }
    }

    fun delete(user: BUserInfo) = synchronized(mutationLock) {
      NexLog.trace("Users", "delete", "userId=${user.id} generation=${user.createTime}") { operation ->
        check(service().deleteUserIfMatches(user.id, user.createTime, false)) { "User changed or deletion failed" }
        operation.step("coreDeleted")
        try {
            clearHostState(user.id, user.createTime)
        } finally {
            // Core deletion has already happened even when host cleanup remains unconfirmed.
            notifyChanged()
        }
      }
    }

    fun pruneEmptyUsers(candidates: List<BUserInfo>? = null) = synchronized(mutationLock) {
      NexLog.trace("Users", "pruneEmptyUsers") { operation ->
        for (user in candidates ?: users()) {
            val removed = service().deleteUserIfMatches(user.id, user.createTime, true)
            operation.step("candidate", "userId=${user.id} generation=${user.createTime} removed=$removed")
            if (removed) {
                try {
                    clearHostState(user.id, user.createTime)
                } finally {
                    notifyChanged()
                }
            }
        }
      }
    }

    fun deleteIfInventory(userId: Int, createTime: Long, token: String, onlyIfEmpty: Boolean,
                          requestId: String): Bundle = synchronized(mutationLock) {
        val result = conditionalCoreCall(requestId) {
            BUserManager.get().deleteUserIfInventory(userId, createTime, token, onlyIfEmpty, requestId)
        }
        if (result.getBoolean("deleted")) {
            try {
                clearHostState(userId, createTime)
                result.putBoolean("hostStateCleared", true)
            } catch (failure: Exception) {
                result.putBoolean("success", false)
                result.putString("stage", "clear_host_state")
                result.putString("mutationState", "unknown")
                result.putString("errorCode", "operation_unconfirmed")
                result.putString("causeCode", "host_cleanup_unconfirmed")
            }
        }
        if (result.getString("mutationState") != "none") notifyChanged()
        result
    }

    fun pruneEmptyUser(target: CloneTarget) = synchronized(mutationLock) {
      NexLog.trace("Users", "pruneEmptyUser", "userId=${target.userId} generation=${target.createTime}") { operation ->
        if (target.createTime > 0 && service().deleteUserIfMatches(target.userId, target.createTime, true)) {
            operation.step("removed")
            try {
                clearHostState(target.userId, target.createTime)
            } finally {
                notifyChanged()
            }
        } else operation.step("retained", "reason=nonempty_or_generation_changed")
      }
    }

    fun prepareInstall(userId: Int) {
        if (service().getUserInfo(userId) == null) clearHostState(userId)
    }

    private fun clearHostState(userId: Int, createTime: Long? = null) = NexLog.trace("Users", "clearHostState", "userId=$userId generation=$createTime") { operation ->
        com.nexbox.app.util.InjectionUtil.invalidateUserIcons(userId, createTime)
        check(FakeLocationPrefs.clearUser(userId, createTime)) { "Could not clear location preferences" }
        operation.step("locationPreferencesCleared")
        // Legacy host keys have no generation. Do not erase metadata already written
        // for a concurrently recreated profile after the guarded core deletion.
        val current = service().getUserInfo(userId)
        if (createTime != null && current != null && current.createTime != createTime) {
            operation.step("retainedNewGeneration", "actualGeneration=${current.createTime}")
            return@trace
        }
        check(AppManager.mRemarkSharedPreferences.edit()
            .remove("Remark$userId").remove("AppList$userId").commit()) { "Could not clear user preferences" }
        AppFreezeManager.clearUser(userId)
        operation.step("hostPreferencesCleared")
    }
}

/** Only retry reads: a dead Binder can leave a mutation's outcome unknown. */
internal fun <T> readUserServiceWithSingleReconnect(read: () -> T, invalidate: () -> Unit): T =
    try {
        read()
    } catch (_: DeadObjectException) {
        invalidate()
        // The caller obtains the service again; a second failure must remain visible.
        read()
    }
