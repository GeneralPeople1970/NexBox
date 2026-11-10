package com.nexbox.app.data

data class AppIconSource(val path: String, val sizeBytes: Long, val modifiedAt: Long)

/** Resource identity only; metadata and screen snapshots never retain decoded bitmaps. */
data class AppIconKey(
    val packageName: String,
    val sources: List<AppIconSource>,
    val versionCode: Long,
    val lastUpdateTime: Long,
    val owner: CloneTarget?,
    val accessGeneration: Long,
    val configuration: String
)
