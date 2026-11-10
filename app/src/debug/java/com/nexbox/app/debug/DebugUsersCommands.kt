package com.nexbox.app.debug

import com.nexbox.app.data.UsersRepository
import com.nexbox.app.data.CurrentUserSelection
import com.nexbox.app.data.UserIdentityResolver
import com.nexbox.app.debug.DebugCommandTargets.guardedTarget
import com.nexbox.app.debug.DebugCommandTargets.guardedUser
import com.nexbox.core.core.system.user.BUserInfo
import org.json.JSONArray
import org.json.JSONObject

internal class DebugUsersCommands {
    fun usersList(): JSONObject = JSONObject().put("users", JSONArray().apply {
        UsersRepository.users().forEach { put(userJson(it)) }
    })

    fun cloneGroups(): JSONObject = JSONObject().put("groups", JSONArray().apply {
        UsersRepository.cloneGroups().forEach { put(userJson(it).put("cloneGroup", true)) }
    })

    fun selectedUser(): JSONObject {
        val selected = CurrentUserSelection.target.value
        val current = selected?.let { owner -> UsersRepository.users().firstOrNull { it.id == owner.userId } }
        return JSONObject().put("selected", selected?.let {
            JSONObject().put("userId", it.userId).put("createTime", it.createTime)
                .put("valid", current?.createTime == it.createTime)
        } ?: JSONObject.NULL)
    }

    fun selectUser(payload: JSONObject): JSONObject = synchronized(UsersRepository.mutationLock) {
        val owner = guardedTarget(payload)
        UserIdentityResolver.validateTarget(owner)
        CurrentUserSelection.select(owner)
        selectedUser()
    }

    fun renameUser(payload: JSONObject): JSONObject {
        val user = guardedUser(payload)
        val name = payload.optString("name").trim()
        require(name.isNotEmpty() && name.length <= 32) { "name must contain 1..32 characters" }
        val result = UsersRepository.rename(user, name)
        return JSONObject()
            .put("user", userJson(user).put("name", result.name).put("displayName", result.name))
            .put("nameSaved", result.saved).put("nameChanged", result.changed)
            .put("taskRefreshConfirmed", result.refreshConfirmed)
            .put("taskRefresh", JSONObject()
                .put("status", result.refreshStatus)
                .put("updatedTasks", result.updatedTasks).put("unchangedTasks", result.unchangedTasks)
                .put("removedTasks", result.removedTasks).put("remainingTasks", result.remainingTasks)
                .put("unverifiedTasks", result.unverifiedTasks)
                .put("stopRequested", result.stopRequested).put("stopConfirmed", result.stopConfirmed)
                .put("requiresRelaunch", result.requiresRelaunch)
                .put("error", result.error ?: JSONObject.NULL))
    }

    fun deleteUser(payload: JSONObject): JSONObject {
        val user = guardedUser(payload)
        UsersRepository.delete(user)
        return JSONObject().put("deleted", true).put("userId", user.id).put("createTime", user.createTime)
    }

    fun pruneEmptyUser(payload: JSONObject): JSONObject {
        val target = guardedTarget(payload)
        val before = UsersRepository.users().any { it.id == target.userId && it.createTime == target.createTime }
        UsersRepository.pruneEmptyUser(target)
        val remains = UsersRepository.users().any { it.id == target.userId && it.createTime == target.createTime }
        return JSONObject().put("userId", target.userId).put("createTime", target.createTime)
            .put("removed", before && !remains).put("retained", remains)
    }

    fun pruneAllEmptyUsers(payload: JSONObject): JSONObject {
        require(payload.optBoolean("confirm", false)) { "confirm=true is required" }
        return synchronized(UsersRepository.mutationLock) {
            val before = UsersRepository.users().associate { it.id to it.createTime }
            UsersRepository.pruneEmptyUsers()
            val after = UsersRepository.users().associate { it.id to it.createTime }
            val removed = before.filter { (userId, createTime) -> after[userId] != createTime }.map { (userId, createTime) ->
                JSONObject().put("userId", userId).put("createTime", createTime)
            }
            JSONObject().put("removed", JSONArray().apply { removed.forEach(::put) })
                .put("removedCount", removed.size)
                .put("remaining", JSONArray().apply { after.forEach { (userId, createTime) ->
                    put(JSONObject().put("userId", userId).put("createTime", createTime))
                } })
        }
    }

    private fun userJson(user: BUserInfo): JSONObject = JSONObject()
        .put("userId", user.id).put("createTime", user.createTime)
        .put("displayName", UsersRepository.displayName(user))
        .put("name", user.name ?: "").put("status", user.status?.name ?: JSONObject.NULL)
}
