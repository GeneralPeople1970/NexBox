package com.nexbox.core.core.system.pm

import java.util.function.Predicate

/** Package metadata is read under the package lock; no PackageInfo, resources or IPC are needed. */
internal object CloneGroupSummary {
    @JvmStatic
    fun populatedUsers(packages: Map<String, BPackageSettings>, visiblePackage: Predicate<String>): Set<Int> {
        val result = HashSet<Int>()
        for ((packageName, settings) in packages) {
            check(packageName.isNotBlank()) { "Incomplete clone package metadata" }
            if (!visiblePackage.test(packageName)) continue
            for ((userId, state) in settings.userState) {
                if (userId >= 0 && state.installed && !state.hidden) result.add(userId)
            }
        }
        return result
    }
}
