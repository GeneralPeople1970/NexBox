package com.nexbox.app.data

data class CloneInstallReceipt(val userId: Int, val createTime: Long, val packageName: String) {
    fun target() = CloneTarget(userId, createTime)
}

data class CloneGroupChoice(val target: CloneTarget, val name: String)

data class CloneGroupOptions(val groupCount: Int, val available: List<CloneGroupChoice>)
