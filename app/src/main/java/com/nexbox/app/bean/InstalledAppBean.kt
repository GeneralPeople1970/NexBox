package com.nexbox.app.bean

import android.graphics.drawable.Drawable
import com.nexbox.app.data.AppIconKey

data class InstalledAppBean(
    val name: String,
    val icon: Drawable?,
    val packageName: String,
    val sourceDir: String? = null,
    val isInstalled: Boolean = false,
    val isSystemApp: Boolean = false,
    val apkSizeBytes: Long? = null,
    val firstInstallTime: Long? = null,
    val lastUpdateTime: Long? = null,
    val iconKey: AppIconKey? = null
) {
    constructor(name: String, packageName: String, icon: Drawable?) : this(name, icon, packageName, null, false)
}
