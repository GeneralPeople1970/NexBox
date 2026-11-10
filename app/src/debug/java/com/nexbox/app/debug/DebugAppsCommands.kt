package com.nexbox.app.debug

import android.content.Context
import com.nexbox.app.bean.AppInfo
import com.nexbox.app.bean.InstalledAppBean
import com.nexbox.app.data.AppPickerOptions
import com.nexbox.app.data.AppPickerPreferences
import com.nexbox.app.data.AppPickerSort
import com.nexbox.app.data.AppsRepository
import com.nexbox.app.data.CloneAppActions
import com.nexbox.app.data.CloneInstallCoordinator
import com.nexbox.app.data.CloneTarget
import com.nexbox.app.data.UserIdentityResolver
import com.nexbox.app.data.UsersRepository
import com.nexbox.app.data.prepareCloneSource
import com.nexbox.app.data.sortHostApps as sortPickerApps
import com.nexbox.app.data.filterHostApps
import com.nexbox.app.debug.DebugCommandJson.jsonArray
import com.nexbox.app.debug.DebugCommandJson.requiredPackage
import com.nexbox.app.debug.DebugCommandTargets.guardedTarget
import com.nexbox.app.debug.DebugCommandTargets.target
import com.nexbox.app.util.AppListPermission
import com.nexbox.app.util.LogPrivacy
import com.nexbox.app.util.NexLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

internal class DebugAppsCommands(private val context: Context) {
    private val apps = AppsRepository()

    @Suppress("DEPRECATION")
    fun syncHostSources(): JSONObject {
        val receipt = apps.syncHostSources()
        val packages = receipt.getParcelableArrayList<android.os.Bundle>("packages").orEmpty()
        return JSONObject().put("success", receipt.getBoolean("success"))
            .put("updated", receipt.getInt("updated")).put("failed", receipt.getInt("failed"))
            .put("scope", "shared_host_sources").put("dataRetained", true)
            .put("packages", JSONArray().apply { packages.forEach { entry ->
                put(JSONObject().put("packageName", entry.getString("packageName"))
                    .put("status", entry.getString("status"))
                    .put("sourceAvailable", entry.getBoolean("sourceAvailable"))
                    .put("versionCode", if (entry.containsKey("versionCode")) entry.getLong("versionCode") else JSONObject.NULL)
                    .put("reason", entry.getString("reason")?.let(LogPrivacy::clean) ?: JSONObject.NULL))
            } })
    }

    fun hostApps(payload: JSONObject): JSONObject {
        AppListPermission.requireHostAccess(context)
        check(runBlocking(Dispatchers.IO) { apps.previewInstallList() }) { "Host application scan failed" }
        val preview = sortHostApps(apps.hostPreviewSnapshot(), payload)
        val installedBy = linkedMapOf<String, MutableList<Int>>()
        UsersRepository.cloneGroups().forEach { user ->
            apps.getInstalledAppList(user.id).filter { it.isInstalled }.forEach {
                installedBy.getOrPut(it.packageName) { mutableListOf() }.add(user.id)
            }
            UserIdentityResolver.validateTarget(CloneTarget(user.id, user.createTime))
        }
        return JSONObject().put("apps", JSONArray().apply {
            preview.forEach { put(hostAppJson(it).put("installedUserIds", jsonArray(installedBy[it.packageName].orEmpty()))) }
        }).put("count", preview.size)
    }

    fun cloneApps(payload: JSONObject): JSONObject {
        require(!payload.has("createTime") || payload.has("userId")) { "createTime requires userId" }
        val sort = payload.optString("sort", "name").lowercase()
        require(sort in setOf("name", "package", "package_name", "clone_order")) {
            "clone-list sort supports name, package_name or clone_order"
        }
        if (payload.has("limit")) require(payload.getInt("limit") in 1..5000) { "limit must be between 1 and 5000" }
        val groups = if (payload.has("userId")) listOf(target(payload, destructive = false))
        else UsersRepository.cloneGroups().map { CloneTarget(it.id, it.createTime) }
        return JSONObject().put("groups", JSONArray().apply {
            groups.forEach { owner ->
                val appsForUser = runBlocking(Dispatchers.IO) { apps.getVmInstallList(owner) }
                UserIdentityResolver.validateTarget(owner)
                val selected = sortCloneApps(appsForUser, payload, sort)
                put(JSONObject().put("userId", owner.userId).put("createTime", owner.createTime)
                    .put("apps", JSONArray().apply { selected.forEach { put(appJson(it)) } })
                    .put("count", selected.size))
            }
        })
    }

    fun cloneCheck(payload: JSONObject): JSONObject {
        require(!payload.has("createTime") || payload.has("userId")) { "createTime requires userId" }
        val owner = if (payload.has("userId")) target(payload, destructive = false) else null
        val fromHost = payload.optBoolean("fromHost", !payload.has("path") && payload.has("packageName"))
        require(payload.has("path") != payload.has("packageName")) { "Supply exactly one of path or packageName" }
        require(fromHost == payload.has("packageName")) { "fromHost must match the supplied source" }
        val sourceValue = payload.optString(if (fromHost) "packageName" else "path").trim()
        require(sourceValue.isNotEmpty()) { "path or packageName is required" }
        val source = prepareCloneSource(sourceValue)
        return source.use {
            require(it.fromInstalledPackage == fromHost) { "Source does not match the requested source type" }
            val options = synchronized(UsersRepository.mutationLock) { CloneInstallCoordinator.availableGroups(it) }
            val result = JSONObject().put("packageName", it.packageName).put("fromHost", fromHost)
                .put("fromInstalledPackage", it.fromInstalledPackage)
                .put("groupCount", options.groupCount)
                .put("availableGroups", JSONArray().apply { options.available.forEach { group ->
                    put(JSONObject().put("userId", group.target.userId)
                        .put("createTime", group.target.createTime).put("name", group.name))
                } })
            if (owner != null) {
                UserIdentityResolver.validateTarget(owner)
                result.put("userId", owner.userId).put("createTime", owner.createTime)
                    .put("alreadyInstalled", CloneInstallCoordinator.requiresNewUser(it, owner))
            }
            result.put("newUserRecommended", options.available.isEmpty())
        }
    }

    fun appsOptionsGet(): JSONObject {
        val options = AppPickerPreferences.get(context).options.value
        return JSONObject().put("sort", options.sort.name).put("reverse", options.reverse)
            .put("showPackageName", options.showPackageName).put("showSystemApps", options.showSystemApps)
    }

    fun appsOptionsSet(payload: JSONObject): JSONObject {
        val preferences = AppPickerPreferences.get(context)
        val sort = if (payload.has("sort")) parseSort(payload.getString("sort")) else null
        check(preferences.update {
            AppPickerOptions(
                sort = sort ?: it.sort,
                reverse = if (payload.has("reverse")) payload.getBoolean("reverse") else it.reverse,
                showPackageName = if (payload.has("showPackageName")) payload.getBoolean("showPackageName") else it.showPackageName,
                showSystemApps = if (payload.has("showSystemApps")) payload.getBoolean("showSystemApps") else it.showSystemApps
            )
        }) { "Application picker settings were not saved" }
        return appsOptionsGet().put("persistence", "asynchronous")
    }

    private fun sortHostApps(apps: List<InstalledAppBean>, payload: JSONObject): List<InstalledAppBean> {
        val options = AppPickerOptions(sort = parseSort(payload.optString("sort", "name")),
            reverse = payload.optBoolean("reverse"), showSystemApps = payload.optBoolean("showSystem"))
        val ordered = sortPickerApps(apps, options, context.resources.configuration.locales[0])
        return limitApps(filterHostApps(ordered, payload.optString("query").trim()), payload)
    }

    private fun parseSort(value: String): AppPickerSort = when (value.lowercase()) {
        "name" -> AppPickerSort.NAME
        "package", "package_name" -> AppPickerSort.PACKAGE_NAME
        "size", "apk_size" -> AppPickerSort.APK_SIZE
        "first_install", "first_install_time" -> AppPickerSort.FIRST_INSTALL_TIME
        "last_update", "last_update_time" -> AppPickerSort.LAST_UPDATE_TIME
        else -> throw IllegalArgumentException("Unsupported app sort")
    }

    private fun sortCloneApps(apps: List<AppInfo>, payload: JSONObject, sort: String): List<AppInfo> {
        val query = payload.optString("query").trim()
        val reverse = payload.optBoolean("reverse", false)
        val filtered = apps.filter { query.isEmpty() || it.name.contains(query, true) || it.packageName.contains(query, true) }
        if (sort == "clone_order") {
            // Repository order is the same persisted sequence rendered by the home grid.
            return limitApps(if (reverse) filtered.asReversed() else filtered, payload)
        }
        val comparator = if (sort == "package" || sort == "package_name")
            compareBy<AppInfo> { it.packageName.lowercase() }
        else compareBy<AppInfo> { it.name.lowercase() }
        val ordered = comparator.thenBy { it.packageName.lowercase() }
        val result = filtered.sortedWith(if (reverse) ordered.reversed() else ordered)
        return limitApps(result, payload)
    }

    private fun <T> limitApps(apps: List<T>, payload: JSONObject): List<T> {
        if (!payload.has("limit")) return apps
        val limit = payload.getInt("limit")
        require(limit in 1..5000) { "limit must be between 1 and 5000" }
        return apps.take(limit)
    }

    fun clone(payload: JSONObject, host: Boolean): JSONObject {
        val newUser = payload.optBoolean("newUser", false)
        require(newUser != payload.has("userId")) { "Supply an existing userId/createTime or newUser=true, not both" }
        require(!newUser || !payload.has("createTime")) { "createTime must not be supplied for a new user" }
        val captured = if (newUser) null else guardedTarget(payload)
        val sourceValue = payload.optString(if (host) "packageName" else "path").trim()
        require(sourceValue.isNotEmpty()) { if (host) "packageName is required" else "path is required" }
        val source = prepareCloneSource(sourceValue)
        return source.use {
            require(it.fromInstalledPackage == host) { "Source does not match the requested source type" }
            val receipt = synchronized(UsersRepository.mutationLock) {
                if (newUser) {
                    CloneInstallCoordinator.installInNewUser(it)
                } else {
                    val owner = requireNotNull(captured)
                    check(!CloneInstallCoordinator.requiresNewUser(it, owner)) {
                        "Package is already cloned in this group; use -NewUser to create another group"
                    }
                    CloneInstallCoordinator.install(it, owner)
                }
            }
            val result = JSONObject().put("userId", receipt.userId).put("createTime", receipt.createTime)
                .put("packageName", receipt.packageName).put("installationConfirmed", true)
                .put("cloneOrderPersistence", "asynchronous")
                .put("launchRequested", payload.optBoolean("launch", false)).put("launchRequestAccepted", false)
            if (payload.optBoolean("launch", false)) {
                try {
                    result.put("launchRequestAccepted", CloneAppActions.start(receipt.packageName, receipt.target()))
                } catch (failure: Exception) {
                    NexLog.e("DebugCLI", "action=launchAfterClone installationConfirmed=true", failure)
                    result.put("launchError", LogPrivacy.clean(failure.message ?: "Launch failed").take(512))
                }
            }
            result
        }
    }

    fun startApp(payload: JSONObject): JSONObject {
        val (target, accepted) = synchronized(UsersRepository.mutationLock) {
            val owner = target(payload, destructive = false)
            val packageName = requiredPackage(payload)
            UserIdentityResolver.validateTarget(owner)
            owner to CloneAppActions.start(packageName, owner)
        }
        val packageName = requiredPackage(payload)
        return JSONObject().put("requestAccepted", accepted).put("packageName", packageName)
            .put("userId", target.userId).put("createTime", target.createTime)
    }

    fun stopApp(payload: JSONObject): JSONObject {
        val target = target(payload, destructive = false)
        val packageName = requiredPackage(payload)
        CloneAppActions.stop(packageName, target)
        return operationResult(packageName, target)
    }

    fun clearApp(payload: JSONObject): JSONObject {
        val target = guardedTarget(payload)
        val packageName = requiredPackage(payload)
        CloneAppActions.clearData(packageName, target)
        return operationResult(packageName, target)
    }

    fun uninstallApp(payload: JSONObject): JSONObject {
        val target = guardedTarget(payload)
        val packageName = requiredPackage(payload)
        CloneAppActions.uninstall(packageName, target)
        return operationResult(packageName, target).put("uninstallConfirmed", true)
            .put("cloneOrderPersistence", "asynchronous")
    }

    private fun hostAppJson(app: InstalledAppBean): JSONObject = JSONObject()
        .put("packageName", app.packageName).put("name", app.name)
        .put("sourcePath", app.sourceDir ?: JSONObject.NULL).put("isSystemApp", app.isSystemApp)
        .put("apkSizeBytes", app.apkSizeBytes ?: JSONObject.NULL)
        .put("firstInstallTime", app.firstInstallTime ?: JSONObject.NULL)
        .put("lastUpdateTime", app.lastUpdateTime ?: JSONObject.NULL)

    private fun appJson(app: AppInfo): JSONObject = JSONObject()
        .put("packageName", app.packageName).put("name", app.name)
        .put("sourcePath", app.sourceDir ?: JSONObject.NULL).put("isXpModule", app.isXpModule)
        .put("sourceAvailable", app.sourceAvailable)

    private fun operationResult(packageName: String, target: CloneTarget): JSONObject = JSONObject()
        .put("success", true).put("packageName", packageName)
        .put("userId", target.userId).put("createTime", target.createTime)
}
