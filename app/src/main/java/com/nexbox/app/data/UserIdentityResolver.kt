package com.nexbox.app.data

import com.nexbox.app.R
import com.nexbox.app.util.NexLog
import com.nexbox.app.util.getString
import com.nexbox.core.fake.frameworks.BUserManager

/** Read-only user identity access, independent of installation and location preferences. */
internal object UserIdentityResolver {
    fun captureTarget(userId: Int): CloneTarget {
        require(userId >= 0)
        val user = UsersRepository.users().firstOrNull { it.id == userId }
        return CloneTarget(userId, user?.createTime ?: 0L)
    }

    fun captureDefaultTarget(): CloneTarget = synchronized(UsersRepository.mutationLock) {
        val userZero = captureTarget(0)
        if (userZero.createTime > 0L) userZero else CloneTarget(UsersRepository.nextId(), 0L)
    }

    fun validateTarget(target: CloneTarget) {
        val actual = captureTarget(target.userId)
        if (target != actual) NexLog.w("Install", "action=validateTarget userId=${target.userId} expectedGeneration=${target.createTime} actualGeneration=${actual.createTime}")
        check(target == actual) { getString(R.string.clone_user_changed) }
    }

    /** Null means an absent user; Binder failures must remain visible. Call on IO. */
    fun userIdentity(userId: Int): Long? {
        if (userId < 0) return null
        val service = checkNotNull(BUserManager.get().service) { "User service unavailable" }
        return service.getUserInfo(userId)?.createTime?.takeIf { it > 0 }
    }
}
