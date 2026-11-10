package com.nexbox.app.data

import android.os.Bundle
import com.nexbox.core.NexBoxCore
import com.nexbox.core.fake.frameworks.BUserManager
import com.nexbox.app.bean.GmsBean
import com.nexbox.app.util.NexLog


class GmsRepository {
    fun installIfInventory(userId: Int, createTime: Long, token: String, traceFree: Boolean,
                           requestId: String): Bundle = synchronized(UsersRepository.mutationLock) {
        conditionalCoreCall(requestId) {
            NexBoxCore.getBPackageManager().installGmsIfInventory(userId, createTime, token, traceFree, requestId)
        }.also { if (it.getString("mutationState") != "none") UsersRepository.notifyChanged() }
    }

    fun removeIfInventory(userId: Int, createTime: Long, token: String, components: List<Bundle>,
                          requestId: String, reset: Boolean): Bundle = synchronized(UsersRepository.mutationLock) {
        conditionalCoreCall(requestId) {
            if (reset) BUserManager.get().resetGmsIfInventory(userId, createTime, token, components, requestId)
            else NexBoxCore.getBPackageManager().uninstallGmsIfInventory(userId, createTime, token, components, requestId)
        }.also { if (it.getString("mutationState") != "none") UsersRepository.notifyChanged() }
    }

    fun getGmsInstalledListSync(): List<GmsBean> = synchronized(UsersRepository.mutationLock) {
        val userList = UsersRepository.cloneGroups().map { user ->
            val userId = user.id
            GmsBean(userId, UsersRepository.displayName(user),
                NexBoxCore.get().isInstallGms(userId), NexBoxCore.get().hasGmsTraces(userId), user.createTime)
        }
        userList.forEach {
            UserIdentityResolver.validateTarget(CloneTarget(it.userId, it.createTime))
            NexLog.d("GMS", "action=readState userId=${it.userId} generation=${it.createTime} installed=${it.isInstalled} traces=${it.hasTraces}")
        }
        NexLog.d("GMS", "action=listProfiles count=${userList.size}")
        userList
    }

    fun installGms(target: CloneTarget): Boolean = mutate(target) { installCore(target).success }

    fun uninstallGms(target: CloneTarget): Boolean = mutate(target) { uninstallCore(target) }

    fun resetGmsEnvironment(target: CloneTarget): Boolean = mutate(target) { resetCore(target) }

    private fun <T> mutate(target: CloneTarget, action: () -> T): T = synchronized(UsersRepository.mutationLock) {
        require(target.createTime > 0L)
        UserIdentityResolver.validateTarget(target)
        try {
            action()
        } finally {
            // A failed or lost reply can follow a partial component change.
            UsersRepository.notifyChanged()
        }
    }

    private fun installCore(target: CloneTarget) = NexLog.trace("GMS", "install",
        "userId=${target.userId} generation=${target.createTime}") { operation ->
        NexBoxCore.get().installGms(target.userId, target.createTime).also {
            check(!it.success || it.userId == target.userId && it.userCreateTime == target.createTime) {
                "The Google service installation receipt does not match the requested user"
            }
            operation.finish(it.success, "reason=${it.msg}")
        }
    }
    private fun uninstallCore(target: CloneTarget) = NexLog.trace("GMS", "uninstall",
        "userId=${target.userId} generation=${target.createTime}") { operation ->
        NexBoxCore.get().uninstallGms(target.userId, target.createTime).also { operation.finish(it) }
    }
    private fun resetCore(target: CloneTarget) = NexLog.trace("GMS", "resetEnvironment",
        "userId=${target.userId} generation=${target.createTime}") { operation ->
        NexBoxCore.get().resetGmsEnvironmentIfMatches(target.userId, target.createTime).also { operation.finish(it) }
    }
}
