package com.nexbox.app.debug

import android.content.Context
import android.net.Uri
import com.nexbox.app.bean.XpModuleBean
import com.nexbox.app.data.UsersRepository
import com.nexbox.app.data.XpModuleRepository
import com.nexbox.app.debug.DebugCommandJson.jsonArray
import com.nexbox.app.debug.DebugCommandJson.scopeAppJson
import com.nexbox.app.debug.DebugCommandJson.stringSet
import com.nexbox.app.debug.DebugCommandJson.packageValue
import com.nexbox.app.debug.DebugCommandTargets.target
import com.nexbox.app.debug.DebugCommandTargets.guardedTarget
import com.nexbox.app.util.LogPrivacy
import com.nexbox.core.core.system.xposed.XposedModulePolicy
import com.nexbox.core.entity.pm.ModuleLoadState
import com.nexbox.core.entity.pm.XposedRuntimeStatus
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal class DebugModulesCommands(context: Context) {
    private val modules = XpModuleRepository(context)

    fun modulesList(payload: JSONObject): JSONObject {
        val owner = target(payload, destructive = false)
        val snapshot = modules.load(owner)
        return moduleSnapshotJson(snapshot).put("userId", owner.userId).put("createTime", owner.createTime)
    }

    fun modulesHostList(): JSONObject = JSONObject().put("modules", JSONArray().apply {
        modules.hostCandidates().forEach { put(moduleJson(it)) }
    })

    fun modulesSet(payload: JSONObject): JSONObject {
        val owner = guardedTarget(payload)
        val module = packageValue(payload, "modulePackage")
        modules.save(owner, module, payload.getBoolean("enabled"), stringSet(payload, "targets"))
        return moduleSnapshotJson(modules.load(owner)).put("userId", owner.userId).put("createTime", owner.createTime)
    }

    fun modulesRestart(payload: JSONObject): JSONObject {
        val owner = guardedTarget(payload)
        val targets = stringSet(payload, "targets")
        modules.stopTargets(owner, targets)
        return JSONObject().put("stopBatchConfirmed", true).put("launched", false)
            .put("userId", owner.userId).put("createTime", owner.createTime).put("targets", jsonArray(targets))
    }

    fun modulesListAll(): JSONObject = JSONObject().put("host", modulesHostList().getJSONArray("modules"))
        .put("groups", JSONArray().apply {
            UsersRepository.cloneGroups().forEach { user ->
                put(JSONObject().put("userId", user.id).put("createTime", user.createTime)
                    .put("snapshot", moduleSnapshotJson(modules.load(com.nexbox.app.data.CloneTarget(user.id, user.createTime)))))
            }
        })

    fun modulesOpenConfig(payload: JSONObject): JSONObject = synchronized(UsersRepository.mutationLock) {
        val target = guardedTarget(payload)
        val packageName = packageValue(payload, "modulePackage")
        modules.openConfiguration(packageName, target)
        JSONObject().put("requestAccepted", true).put("userId", target.userId)
            .put("createTime", target.createTime).put("modulePackage", packageName)
    }

    fun modulesInstallExisting(payload: JSONObject): JSONObject {
        val target = guardedTarget(payload)
        val packageName = packageValue(payload, "modulePackage")
        modules.installExisting(packageName, target)
        return JSONObject().put("installed", true).put("userId", target.userId).put("createTime", target.createTime).put("packageName", packageName)
    }

    fun modulesImportHost(payload: JSONObject): JSONObject {
        val target = guardedTarget(payload)
        val packageName = packageValue(payload, "modulePackage")
        val installed = modules.importHost(packageName, target)
        return JSONObject().put("installed", true).put("userId", target.userId).put("createTime", target.createTime).put("packageName", installed)
    }

    fun modulesImportApk(payload: JSONObject): JSONObject {
        val target = guardedTarget(payload)
        val path = payload.optString("path").trim()
        require(path.isNotEmpty()) { "path is required" }
        val file = File(path)
        require(file.isAbsolute && file.isFile && file.canRead()) { "A readable absolute APK/APKS path is required" }
        val installed = modules.importApk(Uri.fromFile(file), target)
        return JSONObject().put("installed", true).put("userId", target.userId).put("createTime", target.createTime).put("packageName", installed)
    }

    private fun moduleSnapshotJson(snapshot: XpModuleRepository.Snapshot): JSONObject = JSONObject()
        .put("modules", JSONArray().apply { snapshot.modules.forEach { put(moduleJson(it)) } })
        .put("targets", JSONArray().apply { snapshot.targets.forEach {
            val eligibleModules = snapshot.modules.filter { module ->
                XposedModulePolicy.allowsTarget(module.packageName, it.packageName, it.isModule,
                    module.staticScope, module.recommendedScope)
            }.map { module -> module.packageName }
            if (!it.isModule || eligibleModules.isNotEmpty()) {
                put(scopeAppJson(it.packageName, it.label, it.versionName).put("isModule", it.isModule)
                    .put("eligibleModules", jsonArray(eligibleModules)))
            }
        } })
        .put("reports", JSONArray().apply { snapshot.reports.forEach { put(reportJson(it)) } })
        .put("status", statusJson(snapshot.status))
        .put("legacySelections", jsonArray(snapshot.legacySelections))

    private fun moduleJson(module: XpModuleBean): JSONObject = JSONObject()
        .put("packageName", module.packageName).put("name", module.name).put("versionName", module.versionName ?: JSONObject.NULL)
        .put("description", module.description ?: JSONObject.NULL).put("minXposedVersion", module.minXposedVersion ?: JSONObject.NULL)
        .put("scopeCount", module.scopeCount).put("enabled", module.isEnabled).put("userId", module.userId)
        .put("installed", module.installed).put("targets", jsonArray(module.targets))
        .put("supported", module.supported).put("error", module.error?.let(LogPrivacy::clean) ?: JSONObject.NULL)
        .put("pendingLegacySelection", module.pendingLegacySelection).put("modern", module.modern)
        .put("recommendedScope", jsonArray(module.recommendedScope)).put("staticScope", module.staticScope)

    private fun reportJson(report: ModuleLoadState): JSONObject = JSONObject()
        .put("userId", report.userId).put("pid", report.pid).put("targetPackage", report.targetPackage)
        .put("processName", report.processName).put("modulePackage", report.modulePackage)
        .put("loaded", report.loaded).put("detail", report.detail?.let(LogPrivacy::clean) ?: JSONObject.NULL)
        .put("timestamp", report.timestamp).put("processAlive", report.processAlive).put("restartRequired", report.restartRequired)

    private fun statusJson(status: XposedRuntimeStatus): JSONObject = JSONObject()
        .put("available", status.available).put("state", status.state ?: JSONObject.NULL)
        .put("message", status.message?.let(LogPrivacy::clean) ?: JSONObject.NULL).put("backend", status.backend ?: JSONObject.NULL)
        .put("apiVersion", status.apiVersion).put("configRevision", status.configRevision)
}
