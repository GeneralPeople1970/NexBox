package com.nexbox.app.bean

import android.graphics.drawable.Drawable
import com.nexbox.app.data.AppIconKey

data class AppInfo(
    val name: String,
    val icon: Drawable?,
    val packageName: String,
    val sourceDir: String? = null,
    val isXpModule: Boolean = false,
    val iconKey: AppIconKey? = null,
    val sourceAvailable: Boolean = true
) {
    constructor(name: String, packageName: String, icon: Drawable?) : this(name, icon, packageName, null, false)
}
