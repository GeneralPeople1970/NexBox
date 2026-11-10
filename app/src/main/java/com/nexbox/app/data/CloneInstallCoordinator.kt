package com.nexbox.app.data

import com.nexbox.app.R
import com.nexbox.app.util.getString
import com.nexbox.app.util.NexLog
import com.nexbox.core.NexBoxCore
import com.nexbox.core.entity.pm.InstallOption

/** One checked installation path for the in-app picker and external APK imports. Call on IO. */
object CloneInstallCoordinator {
    /** Capture names, identities and duplicate eligibility under one mutation boundary. */
    fun availableGroups(source: CloneInstallSource): CloneGroupOptions =
        synchronized(UsersRepository.mutationLock) {
            val users = UsersRepository.cloneGroups()
            val packages = checkNotNull(NexBoxCore.getBPackageManager().service)
            CloneGroupOptions(users.size, users.filterNot {
                packages.isInstalled(source.packageName, it.id)
            }.map { CloneGroupChoice(CloneTarget(it.id, it.createTime), UsersRepository.displayName(it)) })
        }

    fun requiresNewUser(source: CloneInstallSource, target: CloneTarget): Boolean =
        NexLog.trace("Install", "checkDuplicate", "package=${source.packageName} userId=${target.userId} generation=${target.createTime}") { operation ->
          synchronized(UsersRepository.mutationLock) {
            UserIdentityResolver.validateTarget(target)
            checkNotNull(NexBoxCore.getBPackageManager().service).isInstalled(source.packageName, target.userId)
                .also { operation.step("checked", "duplicate=$it") }
          }
        }

    fun install(source: CloneInstallSource, target: CloneTarget): CloneInstallReceipt =
        NexLog.trace("Install", "clone", "package=${source.packageName} userId=${target.userId} generation=${target.createTime} source=${if (source.fromInstalledPackage) "host" else "document"}") { operation ->
          synchronized(UsersRepository.mutationLock) {
            UserIdentityResolver.validateTarget(target)
            operation.step("targetValidated")
            val installedBefore = CloneAppOrder.installedPackages(target)
            if (target.createTime == 0L) UsersRepository.prepareInstall(target.userId)
            val packages = checkNotNull(NexBoxCore.getBPackageManager().service)
            val option = if (source.fromInstalledPackage) InstallOption.installBySystem()
                else InstallOption.installByStorage()
            val result = source.preparedSession?.commit(target.userId, target.createTime)
                ?: packages.installPackageAsUserIfMatches(
                source.source, option, target.userId, target.createTime, source.packageName
            )
            operation.step("receipt", "success=${result.success} returnedUser=${result.userId} returnedGeneration=${result.userCreateTime} returnedPackage=${result.packageName} reason=${result.msg}")
            check(result.success) { result.msg ?: getString(R.string.install_not_confirmed) }
            check(result.userId == target.userId && result.userCreateTime > 0L &&
                result.packageName == source.packageName &&
                (target.createTime == 0L || result.userCreateTime == target.createTime)) {
                getString(R.string.install_not_confirmed)
            }
            val receipt = CloneInstallReceipt(result.userId, result.userCreateTime, source.packageName)
            UserIdentityResolver.validateTarget(receipt.target())
            check(packages.isInstalled(source.packageName, target.userId)) { getString(R.string.install_not_confirmed) }
            operation.step("readBackConfirmed", "generation=${receipt.createTime}")
            try {
                // The guarded installer rejects duplicates, so this is a new clone,
                // not an update. A stale entry from an older uninstall must move last.
                CloneAppOrder.installed(receipt.target(), source.packageName, newClone = true,
                    installedBefore = installedBefore)
            } finally {
                // The installation is already confirmed, even if the display preference fails.
                UsersRepository.notifyChanged()
            }
            receipt
          }
        }

    fun installInNewUser(source: CloneInstallSource): CloneInstallReceipt =
        synchronized(UsersRepository.mutationLock) {
            val userId = UsersRepository.nextId()
            NexLog.i("Install", "action=allocateCloneTarget package=${source.packageName} userId=$userId generation=0")
            install(source, CloneTarget(userId, 0L))
        }

}
