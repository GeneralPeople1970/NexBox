package com.nexbox.app.data

import android.os.Bundle
import com.nexbox.core.core.system.user.BUserInfo

/** Saving a name and refreshing Android's task snapshots are separate outcomes. */
@ConsistentCopyVisibility
data class UserRenameResult private constructor(
    val userId: Int,
    val createTime: Long,
    val name: String,
    val saved: Boolean,
    val changed: Boolean,
    val refreshConfirmed: Boolean,
    val refreshStatus: String,
    val updatedTasks: Int,
    val unchangedTasks: Int,
    val removedTasks: Int,
    val remainingTasks: Int,
    val unverifiedTasks: Int,
    val stopRequested: Boolean,
    val stopConfirmed: Boolean,
    val requiresRelaunch: Boolean,
    val error: String?
) {
    companion object {
        @Suppress("DEPRECATION")
        fun fromBundle(reply: Bundle, user: BUserInfo, name: String): UserRenameResult {
            fun boolean(key: String) = checkNotNull(reply.get(key) as? Boolean) { "Missing rename result: $key" }
            fun count(key: String, minimum: Int = 0) = checkNotNull(reply.get(key) as? Int) {
                "Missing rename result: $key"
            }.also { check(it >= minimum) { "Invalid rename count: $key" } }
            check(reply.get("userId") == user.id && reply.get("createTime") == user.createTime) {
                "Rename result belongs to another user generation"
            }
            val status = checkNotNull(reply.getString("refreshStatus")) { "Missing task refresh status" }
            check(status in setOf("not-run", "no-tasks", "updated", "removed", "partial", "failed")) {
                "Unknown task refresh status"
            }
            val result = UserRenameResult(
                userId = user.id,
                createTime = user.createTime,
                name = name,
                saved = boolean("saved"),
                changed = boolean("changed"),
                refreshConfirmed = boolean("refreshConfirmed"),
                refreshStatus = status,
                updatedTasks = count("updatedTasks"),
                unchangedTasks = count("unchangedTasks"),
                removedTasks = count("removedTasks"),
                remainingTasks = count("remainingTasks", -1),
                unverifiedTasks = count("unverifiedTasks", -1),
                stopRequested = boolean("stopRequested"),
                stopConfirmed = boolean("stopConfirmed"),
                requiresRelaunch = boolean("requiresRelaunch"),
                error = reply.getString("error")
            )
            check(!result.refreshConfirmed || (result.saved && result.remainingTasks == 0 &&
                result.unverifiedTasks == 0 && status in setOf("no-tasks", "updated", "removed") &&
                (!result.stopRequested || result.stopConfirmed))) { "Inconsistent task refresh result" }
            return result
        }
    }
}
