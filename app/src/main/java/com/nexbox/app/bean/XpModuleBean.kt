package com.nexbox.app.bean

import com.nexbox.app.data.AppIconKey

/**
 * 宿主上声明了 Xposed 模块元数据的一个应用。
 *
 * 不复用 [AppInfo]：Xposed 模块需要版本、描述、作用域等信息，而 AppInfo 的
 * sourceDir / isXpModule 语义在这里都用不上。
 */
data class XpModuleBean(
    val packageName: String,
    val name: String,
    val versionName: String?,
    val description: String?,
    val minXposedVersion: Int?,
    val scopeCount: Int,
    val isEnabled: Boolean,
    val userId: Int = -1,
    val installed: Boolean = false,
    val targets: Set<String> = emptySet(),
    val supported: Boolean = true,
    val error: String? = null,
    val pendingLegacySelection: Boolean = false,
    val modern: Boolean = false,
    val recommendedScope: Set<String> = emptySet(),
    val staticScope: Boolean = false,
    val iconKey: AppIconKey? = null
)
