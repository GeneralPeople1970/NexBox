package com.nexbox.app.debug

import com.nexbox.app.data.CloneTarget
import com.nexbox.app.data.UsersRepository
import com.nexbox.core.core.system.user.BUserInfo
import org.json.JSONObject

internal object DebugCommandTargets {
    fun currentUser(payload: JSONObject): BUserInfo {
        val id = payload.optInt("userId", -1)
        require(id >= 0) { "userId is required" }
        return UsersRepository.users().firstOrNull { it.id == id }
            ?: error("Virtual user $id does not exist")
    }

    fun guardedUser(payload: JSONObject): BUserInfo {
        require(payload.has("createTime")) { "createTime is required for this operation" }
        val user = currentUser(payload)
        check(payload.getLong("createTime") == user.createTime) { "User generation changed" }
        return user
    }

    fun target(payload: JSONObject, destructive: Boolean): CloneTarget {
        val user = if (destructive) guardedUser(payload) else currentUser(payload)
        if (payload.has("createTime")) check(payload.getLong("createTime") == user.createTime) { "User generation changed" }
        return CloneTarget(user.id, user.createTime)
    }

    fun guardedTarget(payload: JSONObject): CloneTarget = target(payload, destructive = true)

    fun groupTarget(payload: JSONObject, destructive: Boolean): CloneTarget {
        val owner = target(payload, destructive)
        check(UsersRepository.cloneGroups().any { it.id == owner.userId && it.createTime == owner.createTime }) {
            "A populated clone group is required"
        }
        return owner
    }
}
