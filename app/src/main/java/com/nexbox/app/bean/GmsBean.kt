package com.nexbox.app.bean

data class GmsBean(
    val userId: Int,
    val userName: String,
    val isInstalled: Boolean,
    val hasTraces: Boolean,
    val createTime: Long = 0L
) {
    val userID: Int get() = userId
    val isInstalledGms: Boolean get() = isInstalled
    val hasGmsTraces: Boolean get() = hasTraces
}
