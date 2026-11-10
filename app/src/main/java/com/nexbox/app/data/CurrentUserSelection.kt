package com.nexbox.app.data

import android.content.Context
import com.nexbox.app.app.App
import com.nexbox.app.util.NexLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared by host navigation and external APK intents; never silently selects another user. */
object CurrentUserSelection {
    private val preferences by lazy { App.getContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    private val mutableTarget by lazy {
        val values = preferences.all
        val id = values["clone_selected_user"] as? Int
        val created = values["clone_selected_generation"] as? Long
        MutableStateFlow(if (id != null && id >= 0 && created != null && created >= 0L)
            CloneTarget(id, created) else null)
    }
    val target: StateFlow<CloneTarget?> by lazy { mutableTarget.asStateFlow() }
    // UI and Debug Binder callers must publish in the same order as their preference writes.
    @Synchronized
    fun select(target: CloneTarget) {
        if (mutableTarget.value == target) return
        preferences.edit().putInt("clone_selected_user", target.userId)
            .putLong("clone_selected_generation", target.createTime).apply()
        mutableTarget.value = target
        NexLog.i("Users", "action=select userId=${target.userId} generation=${target.createTime}")
    }
}
