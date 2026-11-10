package com.nexbox.app.debug

import com.nexbox.app.app.AppFreezeManager
import com.nexbox.app.BuildConfig
import android.os.Build
import android.os.Process
import com.nexbox.app.data.RuntimeSettingsChanges
import com.nexbox.app.data.RuntimeSettingsRepository
import com.nexbox.app.debug.DebugCommandJson.optionalPackage
import com.nexbox.app.debug.DebugCommandTargets.target
import com.nexbox.app.debug.DebugCommandTargets.guardedTarget
import com.nexbox.app.debug.DebugCommandJson.requiredPackage
import com.nexbox.app.data.UsersRepository
import com.nexbox.app.data.UserIdentityResolver
import com.nexbox.core.NexBoxCore
import com.nexbox.core.utils.ConfigurationAppStopper
import org.json.JSONObject

internal class DebugRuntimeCommands {
    fun runtimeStatus(): JSONObject = JSONObject()
        .put("applicationId", BuildConfig.APPLICATION_ID).put("versionName", BuildConfig.VERSION_NAME)
        .put("versionCode", BuildConfig.VERSION_CODE).put("buildType", BuildConfig.BUILD_TYPE)
        .put("sdk", Build.VERSION.SDK_INT).put("is64Bit", Process.is64Bit())
        .put("pid", Process.myPid()).put("serviceState", NexBoxCore.get().serviceConnectionState.name)
        .put("debugProviderAvailable", BuildConfig.DEBUG)

    fun runtimeGet(payload: JSONObject = JSONObject()): JSONObject {
        val result = JSONObject()
            .put("hideRoot", RuntimeSettingsRepository.isHideRootEnabled())
            .put("autoFreeze", AppFreezeManager.isGlobalAutoFreezeEnabled())
            .put("instantFreezeButton", AppFreezeManager.isInstantFreezeButtonEnabled())
        val packageName = optionalPackage(payload)
        require(!payload.has("userId") || packageName != null) { "packageName is required with userId" }
        require(!payload.has("createTime") || packageName != null) { "packageName is required with createTime" }
        if (packageName != null) {
            val owner = target(payload, destructive = false)
            requireInstalled(packageName, owner)
            result.put("freezeExcluded", AppFreezeManager.isExcludedFromAutoFreeze(packageName, owner.userId))
                .put("packageName", packageName).put("userId", owner.userId).put("createTime", owner.createTime)
            UserIdentityResolver.validateTarget(owner)
        }
        return result
    }

    fun runtimeSet(payload: JSONObject): JSONObject {
        val owner = if (payload.has("userId")) guardedTarget(payload) else null
        val packageName = optionalPackage(payload)
        require((packageName != null) == payload.has("freezeExcluded")) { "packageName is only used with freezeExcluded" }
        require((owner != null) == (packageName != null)) { "App runtime settings require userId/createTime and packageName" }
        require(owner != null || !payload.has("createTime")) { "createTime requires userId" }
        if (packageName != null) requireNotNull(owner) { "userId is required for app runtime settings" }
        if (packageName != null) requireInstalled(packageName, requireNotNull(owner))
        RuntimeSettingsRepository.apply(RuntimeSettingsChanges(
            hideRoot = if (payload.has("hideRoot")) payload.getBoolean("hideRoot") else null,
            autoFreeze = if (payload.has("autoFreeze")) payload.getBoolean("autoFreeze") else null,
            instantFreezeButton = if (payload.has("instantFreezeButton")) payload.getBoolean("instantFreezeButton") else null,
            freezeExcluded = if (payload.has("freezeExcluded")) payload.getBoolean("freezeExcluded") else null,
            excludeUser = owner,
            excludePackage = packageName
        ))
        return runtimeGet(payload)
    }

    fun retryConfigurationStops(payload: JSONObject): JSONObject = synchronized(UsersRepository.mutationLock) {
        val requested = payload.getJSONArray("targets")
        require(requested.length() in 1..5000) { "1..5000 captured targets are required" }
        val targets = (0 until requested.length()).map { index ->
            val target = requested.getJSONObject(index)
            DebugCommandContract(DebugCommandContract.words(DebugCommandContract.APP),
                DebugCommandContract.words(DebugCommandContract.APP)).validate(target)
            val owner = guardedTarget(target)
            ConfigurationAppStopper.Target(requiredPackage(target), owner.userId, owner.createTime)
        }
        ConfigurationAppStopper.stop(targets)
        JSONObject().put("stopBatchConfirmed", true).put("targetCount", targets.distinct().size)
            .put("launched", false).put("configurationRewritten", false)
    }

    private fun requireInstalled(packageName: String, owner: com.nexbox.app.data.CloneTarget) {
        UserIdentityResolver.validateTarget(owner)
        check(checkNotNull(NexBoxCore.getBPackageManager().service).isInstalled(packageName, owner.userId)) {
            "Runtime settings app is no longer installed"
        }
    }
}
