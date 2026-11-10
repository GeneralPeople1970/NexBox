package com.nexbox.app.data

/** A numeric ID is not an identity: zero generation explicitly means an absent user. */
data class CloneTarget(val userId: Int, val createTime: Long) {
    init { require(userId >= 0 && createTime >= 0) }
}
