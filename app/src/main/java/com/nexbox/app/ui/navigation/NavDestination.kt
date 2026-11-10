package com.nexbox.app.ui.navigation

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.nexbox.app.R

/**
 * 主页沿用 `apps` 路由以兼容子页面；修改展示名不改变路由标识。
 */
enum class NavDestination(
    val route: String,
    val titleRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    APPS("apps", R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
    SETTINGS("settings", R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings)
}

/** 子路由继承对应主标签的高亮。 */
fun selectedDestination(route: String?): NavDestination =
    NavDestination.entries.firstOrNull { destination ->
        route != null && (route == destination.route || route.startsWith("${destination.route}/"))
    } ?: NavDestination.APPS

/** Route changes and primary chrome commit together; exiting entries never delay the bar. */
fun primaryNavigationVisible(route: String?): Boolean = NavDestination.entries.any { it.route == route }

/**
 * 分身专属页面在路径中携带 userId，不能隐式回退到 0 号分身；共享相册不绑定用户。
 */
object SubRoutes {

    /** 应用选择器：克隆宿主已安装的应用到指定分身 */
    const val APP_PICKER = "apps/picker/{userId}"
    fun appPicker(userId: Int) = "apps/picker/$userId"

    const val FAKE_LOCATION = "settings/fake_location/{userId}"
    fun fakeLocation(userId: Int) = "settings/fake_location/$userId"

    const val FAKE_LOCATION_SCOPE = "settings/fake_location/{userId}/scope"
    fun fakeLocationScope(userId: Int) = "settings/fake_location/$userId/scope"

    const val FAKE_LOCATION_APP = "settings/fake_location/{userId}/scope/{packageName}/{generation}?label={appLabel}"
    fun fakeLocationApp(userId: Int, packageName: String, appLabel: String, generation: Long) =
        "settings/fake_location/$userId/scope/${Uri.encode(packageName)}/$generation?label=${Uri.encode(appLabel)}"

    const val DEVICE_SPOOFING = "settings/device_spoofing/{userId}"
    fun deviceSpoofing(userId: Int) = "settings/device_spoofing/$userId"

    /** One gallery shared by all clone users; it can be opened before any user exists. */
    const val GALLERY = "settings/gallery"

    /** Google 服务管理列出全部分身；模块管理按当前分身设置作用域。 */
    const val GMS = "settings/gms"
    const val XP_MODULES = "settings/xp_modules/{userId}"
    fun xpModules(userId: Int) = "settings/xp_modules/$userId"

    const val SETTINGS_ABOUT = "settings/about"
    const val SETTINGS_UPDATES = "settings/about/updates"

    /** 运行环境（隐藏 Root / 应用冷冻）二级页，入口在「沙盒」分组里。 */
    const val RUNTIME = "settings/runtime"

    /** 调试日志页，从「关于」进入。 */
    const val LOGS = "settings/logs"

    const val ARG_USER_ID = "userId"
    const val ARG_PACKAGE_NAME = "packageName"
    const val ARG_APP_LABEL = "appLabel"
    const val ARG_GENERATION = "generation"
}

/**
 * 原生视图无法可靠采样。导航栏与 content 是兄弟节点，因此由外壳统一关闭玻璃采样。
 */
private val GLASS_UNFRIENDLY_ROUTES = setOf(SubRoutes.FAKE_LOCATION, SubRoutes.FAKE_LOCATION_APP)

/** 当前路由是否允许玻璃采样。 */
fun routeAllowsGlass(route: String?): Boolean = route !in GLASS_UNFRIENDLY_ROUTES
