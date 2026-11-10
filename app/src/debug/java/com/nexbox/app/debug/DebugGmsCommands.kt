package com.nexbox.app.debug

import com.nexbox.app.bean.GmsBean
import com.nexbox.app.data.CloneTarget
import com.nexbox.app.data.GmsRepository
import com.nexbox.app.data.UsersRepository
import com.nexbox.app.data.UserIdentityResolver
import com.nexbox.app.debug.DebugCommandTargets.groupTarget
import com.nexbox.core.NexBoxCore
import org.json.JSONArray
import org.json.JSONObject

internal class DebugGmsCommands {
    private val gms = GmsRepository()

    fun gmsList(): JSONObject = JSONObject().put("profiles", JSONArray().apply {
        gms.getGmsInstalledListSync().forEach { put(it.toJson()) }
    })

    fun gmsInstall(payload: JSONObject): JSONObject {
        return synchronized(UsersRepository.mutationLock) {
            val owner = groupTarget(payload, destructive = true)
            val success = gms.installGms(owner)
            gmsState(owner).put("success", success)
        }
    }

    fun gmsUninstall(payload: JSONObject): JSONObject {
        return synchronized(UsersRepository.mutationLock) {
            val owner = groupTarget(payload, destructive = true)
            val success = gms.uninstallGms(owner)
            gmsState(owner).put("success", success)
        }
    }

    fun gmsReset(payload: JSONObject): JSONObject {
        return synchronized(UsersRepository.mutationLock) {
            val owner = groupTarget(payload, destructive = true)
            val success = gms.resetGmsEnvironment(owner)
            gmsState(owner).put("success", success)
        }
    }

    private fun gmsState(target: CloneTarget): JSONObject {
        UserIdentityResolver.validateTarget(target)
        val result = JSONObject().put("userId", target.userId).put("createTime", target.createTime)
            .put("installed", NexBoxCore.get().isInstallGms(target.userId))
            .put("hasTraces", NexBoxCore.get().hasGmsTraces(target.userId))
        UserIdentityResolver.validateTarget(target)
        return result
    }

    private fun GmsBean.toJson(): JSONObject = JSONObject()
        .put("userId", userId).put("createTime", createTime).put("name", userName)
        .put("installed", isInstalled).put("hasTraces", hasTraces)
}
