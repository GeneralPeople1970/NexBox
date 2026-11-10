package com.nexbox.app.data

import com.nexbox.app.app.AppManager
import com.nexbox.app.util.NexLog
import com.nexbox.core.NexBoxCore

/** The picker, imports and Debug CLI share the same confirmed clone lifecycle. */
internal object CloneAppOrder {
    private const val TAG = "AppsRepository"

    fun positions(target: CloneTarget): Map<String, Int> = synchronized(UsersRepository.mutationLock) {
        UserIdentityResolver.validateTarget(target)
        if (target.createTime == 0L) emptyMap()
        else read(target.userId).withIndex().associate { it.value to it.index }
    }

    /** Capture before installation, so an incomplete legacy order cannot put new clones first. */
    fun installedPackages(target: CloneTarget): Set<String> = synchronized(UsersRepository.mutationLock) {
        UserIdentityResolver.validateTarget(target)
        if (target.createTime == 0L) return@synchronized emptySet()
        val packages = checkNotNull(checkNotNull(NexBoxCore.getBPackageManager().service)
            .getInstalledPackages(0, target.userId)) { "Virtual application list unavailable" }
        val names = validPackageEntries(packages) { it.packageName }.mapTo(linkedSetOf()) { it.packageName }
        UserIdentityResolver.validateTarget(target)
        names
    }

    fun installed(target: CloneTarget, packageName: String, newClone: Boolean,
                  installedBefore: Set<String>) =
        synchronized(UsersRepository.mutationLock) {
            require(target.createTime > 0L && packageName.isNotBlank())
            UserIdentityResolver.validateTarget(target)
            val previous = read(target.userId)
            val next = cloneOrderAfterInstall(previous, packageName, newClone, installedBefore)
            if (next != previous) save(target.userId, next)
            NexLog.d(TAG) {
                "action=updateCloneOrder userId=${target.userId} generation=${target.createTime}" +
                    " package=$packageName reason=${if (newClone) "new_clone" else "install_or_update"}" +
                    " previousIndex=${previous.indexOf(packageName)} index=${next.indexOf(packageName)}" +
                    " changed=${next != previous} persistence=asynchronous"
            }
        }

    /** Called only after the generation-checked core uninstall has succeeded. */
    fun uninstalled(target: CloneTarget, packageName: String) =
        synchronized(UsersRepository.mutationLock) {
            require(target.createTime > 0L && packageName.isNotBlank())
            // The legacy preference key has no generation. A concurrently recreated
            // user must never lose its own order to an earlier user's completion.
            val currentGeneration = UserIdentityResolver.userIdentity(target.userId)
            if (currentGeneration != target.createTime) {
                NexLog.d(TAG) {
                    "action=updateCloneOrder userId=${target.userId} generation=${target.createTime}" +
                        " package=$packageName reason=uninstall skipped=generation_changed"
                }
                return@synchronized
            }
            val previous = read(target.userId)
            val next = previous.filterNot { it == packageName }
            if (next != previous) save(target.userId, next)
            NexLog.d(TAG) {
                "action=updateCloneOrder userId=${target.userId} generation=${target.createTime}" +
                    " package=$packageName reason=uninstall removed=${next != previous}" +
                    " persistence=asynchronous"
            }
        }

    fun replace(target: CloneTarget, packageNames: List<String>) =
        synchronized(UsersRepository.mutationLock) {
            require(target.createTime > 0L)
            UserIdentityResolver.validateTarget(target)
            val next = packageNames.map(String::trim).filter(String::isNotEmpty).distinct()
            if (next != read(target.userId)) {
                save(target.userId, next)
                UsersRepository.notifyChanged()
            }
        }

    private fun read(userId: Int): List<String> = parseCloneAppOrder(
        AppManager.mRemarkSharedPreferences.getString("AppList$userId", "").orEmpty()
    )

    private fun save(userId: Int, packageNames: List<String>) {
        AppManager.mRemarkSharedPreferences.edit()
            .putString("AppList$userId", packageNames.joinToString(",")).apply()
    }
}

internal fun parseCloneAppOrder(saved: String): List<String> = saved.split(',')
    .map(String::trim).filter(String::isNotEmpty).distinct()

/** A new clone has a new position even when an older uninstall left a stale entry. */
internal fun cloneOrderAfterInstall(
    previous: List<String>, packageName: String, newClone: Boolean,
    installedBefore: Set<String> = emptySet()
): List<String> {
    // Unsaved existing packages use the same package-name fallback as the home grid.
    val order = (previous + installedBefore.sorted()).distinct().toMutableList()
    if (newClone) order.remove(packageName)
    if (packageName !in order) order.add(packageName)
    return order
}
