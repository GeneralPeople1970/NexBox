package com.nexbox.app.view.list

import com.nexbox.app.bean.XpModuleBean
import com.nexbox.app.data.XpModuleRepository
import com.nexbox.core.core.system.xposed.XposedModulePolicy

internal data class XpModuleGroup(val module: XpModuleBean, val profiles: Map<Int, XpModuleBean>)

/** An APK appears once; enabled state and selections remain isolated in their actual user. */
internal fun groupInstalledModules(profiles: Map<Int, XpModuleRepository.Snapshot>): List<XpModuleGroup> =
    profiles.toSortedMap().flatMap { (id, snapshot) -> snapshot.modules.map { id to it } }
        .groupBy { it.second.packageName }
        .values.map { entries ->
            val users = entries.associate { it.first to it.second }
            val representative = entries.firstOrNull { it.second.supported }?.second ?: entries.first().second
            XpModuleGroup(representative.copy(isEnabled = entries.any { it.second.isEnabled }), users)
        }.sortedBy { it.module.name.lowercase() }

/** Use one candidate set for display and mutation, including copies in other user groups. */
internal fun moduleScopeTargets(
    snapshot: XpModuleRepository.Snapshot,
    module: XpModuleBean,
    knownModulePackages: Set<String>
): List<XpModuleRepository.TargetApp> = snapshot.targets.filter { app ->
    XposedModulePolicy.allowsTarget(module.packageName, app.packageName,
        app.isModule || app.packageName in knownModulePackages, module.staticScope, module.recommendedScope)
}
