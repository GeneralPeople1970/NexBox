package com.nexbox.app.data

import com.nexbox.app.R
import com.nexbox.app.util.NexLog
import com.nexbox.app.util.getString
import com.nexbox.core.NexBoxCore

/** Callers retain their captured target; destructive operations revalidate it in the core. */
internal object CloneAppActions {
    /** A true result confirms dispatch only, not completion of guest startup. */
    fun start(packageName: String, target: CloneTarget): Boolean =
        synchronized(UsersRepository.mutationLock) {
            require(target.createTime > 0L)
            UserIdentityResolver.validateTarget(target)
            NexLog.trace("Install", "startApp", "package=$packageName userId=${target.userId} generation=${target.createTime}") { operation ->
                NexBoxCore.get().launchApk(packageName, target.userId, target.createTime).also { accepted ->
                    operation.finish(accepted, "requestAccepted=$accepted startupComplete=unknown")
                }
            }
        }

    fun uninstall(packageName: String, target: CloneTarget) = synchronized(UsersRepository.mutationLock) {
      NexLog.trace("Install", "uninstall", "package=$packageName userId=${target.userId} generation=${target.createTime}") { operation ->
        require(target.createTime > 0L)
        check(checkNotNull(NexBoxCore.getBPackageManager().service)
            .uninstallPackageAsUserIfMatches(packageName, target.userId, target.createTime)) {
            getString(R.string.user_action_failed)
        }
        operation.step("coreRemoved")
        try {
            CloneAppOrder.uninstalled(target, packageName)
            UsersRepository.pruneEmptyUser(target)
        } finally {
            // Do not leave the removed app on screen if host preference cleanup fails.
            UsersRepository.notifyChanged()
        }
      }
    }

    fun stop(packageName: String, target: CloneTarget) = synchronized(UsersRepository.mutationLock) {
      NexLog.trace("Install", "stopApp", "package=$packageName userId=${target.userId} generation=${target.createTime}") {
        require(target.createTime > 0L)
        // Read the service receipt directly: the legacy convenience API logs Binder
        // failures and returns void, which made the UI report a stop that never happened.
        check(checkNotNull(NexBoxCore.getBPackageManager().service)
            .stopPackageIfMatches(packageName, target.userId, target.createTime)) {
            getString(R.string.user_action_failed)
        }
      }
    }

    fun clearData(packageName: String, target: CloneTarget) = synchronized(UsersRepository.mutationLock) {
      NexLog.trace("Install", "clearAppData", "package=$packageName userId=${target.userId} generation=${target.createTime}") {
        require(target.createTime > 0L)
        check(checkNotNull(NexBoxCore.getBPackageManager().service)
            .clearPackageIfMatches(packageName, target.userId, target.createTime)) {
            getString(R.string.user_action_failed)
        }
      }
    }
}
