package com.nexbox.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexbox.app.data.CurrentUserSelection
import com.nexbox.app.util.NexLog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nexbox.app.ui.navigation.NavDestination
import com.nexbox.app.ui.navigation.SubRoutes
import com.nexbox.app.ui.navigation.routeAllowsGlass
import com.nexbox.app.ui.navigation.nexBoxComposable as composable
import com.nexbox.app.ui.navigation.primaryNavigationVisible
import com.nexbox.app.ui.navigation.selectedDestination
import com.nexbox.app.ui.components.LocalGlassAllowed
import com.nexbox.app.ui.components.NexBoxNavigationScaffold
import com.nexbox.app.view.apps.AppPickerScreen
import com.nexbox.app.view.apps.AppsScreen
import com.nexbox.app.view.fake.FakeLocationScreen
import com.nexbox.app.view.fake.FakeLocationAppScreen
import com.nexbox.app.view.fake.FakeLocationScopeScreen
import com.nexbox.app.view.gallery.GalleryScreen
import com.nexbox.app.view.gms.GmsScreen
import com.nexbox.app.view.list.XpModulesScreen
import com.nexbox.app.view.setting.AboutSettingScreen
import com.nexbox.app.view.setting.LogScreen
import com.nexbox.app.view.setting.RuntimeSettingsScreen
import com.nexbox.app.view.setting.SettingScreen
import com.nexbox.app.view.setting.UpdateSettingScreen
import com.nexbox.app.view.spoof.DeviceSpoofingScreen

@Composable
fun NexBoxApp() {
    val navController = rememberNavController()
    val focusManager = LocalFocusManager.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // 当前正在查看的分身。唯一真源，供主界面页签与各二级页面共用——
    // 二级页面此前一律默认 userId = 0，导致在第二个分身上修改配置会写到 0 号分身。
    var currentUserId by rememberSaveable { mutableStateOf(0) }
    val currentTarget by CurrentUserSelection.target.collectAsStateWithLifecycle()
    LaunchedEffect(currentTarget) { currentTarget?.let { currentUserId = it.userId } }
    LaunchedEffect(currentRoute, currentTarget) {
        NexLog.i("Navigation", "route=$currentRoute userId=${currentTarget?.userId} generation=${currentTarget?.createTime}")
    }
    LaunchedEffect(currentRoute) { focusManager.clearFocus(force = true) }

    // MapView 等原生表面无法被玻璃采样；规则集中在路由表，见 NavDestination.routeAllowsGlass。
    CompositionLocalProvider(LocalGlassAllowed provides routeAllowsGlass(currentRoute)) {
        Box(Modifier.fillMaxSize()) {
        NexBoxNavHost(
            navController = navController,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(NavDestination.APPS.route) { entry ->
                NexBoxPrimaryPage(navController, entry, NavDestination.APPS, drawNavigation = false) {
                    AppsScreen(
                        selectedUserId = currentUserId,
                        onSelectedUserChange = { currentUserId = it },
                        onNavigateToAppPicker = { navController.navigateFrom(entry, SubRoutes.appPicker(it)) }
                    )
                }
            }

            composable(NavDestination.SETTINGS.route) { entry ->
                NexBoxPrimaryPage(navController, entry, NavDestination.SETTINGS, drawNavigation = false) {
                    SettingScreen(
                    onNavigateToDeviceSpoofing = { navController.navigateFrom(entry, SubRoutes.deviceSpoofing(currentUserId)) },
                    onNavigateToFakeLocation = { navController.navigateFrom(entry, SubRoutes.fakeLocation(currentUserId)) },
                    onNavigateToGms = { navController.navigateFrom(entry, SubRoutes.GMS) },
                    onNavigateToXpModules = { navController.navigateFrom(entry, SubRoutes.xpModules(currentUserId)) },
                    onNavigateToGallery = { navController.navigateFrom(entry, SubRoutes.GALLERY) },
                    onNavigateToRuntime = { navController.navigateFrom(entry, SubRoutes.RUNTIME) },
                    onNavigateToAbout = { navController.navigateFrom(entry, SubRoutes.SETTINGS_ABOUT) }
                    )
                }
            }

            composable(SubRoutes.SETTINGS_ABOUT) { entry ->
                AboutSettingScreen(
                    onBack = { navController.popFrom(entry) },
                    onNavigateToLogs = { navController.navigateFrom(entry, SubRoutes.LOGS) },
                    onNavigateToUpdates = { navController.navigateFrom(entry, SubRoutes.SETTINGS_UPDATES) }
                )
            }

            composable(SubRoutes.SETTINGS_UPDATES) { entry ->
                UpdateSettingScreen(onBack = { navController.popFrom(entry) })
            }

            composable(SubRoutes.RUNTIME) { entry ->
                RuntimeSettingsScreen(onBack = { navController.popFrom(entry) })
            }

            composable(SubRoutes.LOGS) { entry ->
                LogScreen(onBack = { navController.popFrom(entry) })
            }

            composable(
                route = SubRoutes.APP_PICKER,
                arguments = listOf(
                    navArgument(SubRoutes.ARG_USER_ID) {
                        type = NavType.IntType
                        defaultValue = 0
                    }
                )
            ) { entry ->
                AppPickerScreen(
                    userId = entry.arguments?.getInt(SubRoutes.ARG_USER_ID) ?: 0,
                    onBack = { navController.popFrom(entry) },
                    onInstalled = { currentUserId = it }
                )
            }

            composable(
                route = SubRoutes.DEVICE_SPOOFING,
                arguments = listOf(
                    navArgument(SubRoutes.ARG_USER_ID) {
                        type = NavType.IntType
                        defaultValue = 0
                    }
                )
            ) { entry ->
                DeviceSpoofingScreen(
                    userId = entry.arguments?.getInt(SubRoutes.ARG_USER_ID) ?: 0,
                    onBack = { navController.popFrom(entry) }
                )
            }

            composable(
                route = SubRoutes.FAKE_LOCATION,
                arguments = listOf(
                    navArgument(SubRoutes.ARG_USER_ID) {
                        type = NavType.IntType
                        defaultValue = 0
                    }
                )
            ) { entry ->
                val userId = entry.arguments?.getInt(SubRoutes.ARG_USER_ID) ?: 0
                FakeLocationScreen(
                    userId = userId,
                    onBack = { navController.popFrom(entry) },
                    onOpenScope = { navController.navigateFrom(entry, SubRoutes.fakeLocationScope(userId)) }
                )
            }

            composable(
                route = SubRoutes.FAKE_LOCATION_SCOPE,
                arguments = listOf(navArgument(SubRoutes.ARG_USER_ID) {
                    type = NavType.IntType
                    defaultValue = 0
                })
            ) { entry ->
                val userId = entry.arguments?.getInt(SubRoutes.ARG_USER_ID) ?: 0
                FakeLocationScopeScreen(
                    userId = userId,
                    onBack = { navController.popFrom(entry) },
                    onOpenApp = { targetUserId, packageName, appLabel, generation ->
                        navController.navigateFrom(entry, SubRoutes.fakeLocationApp(targetUserId, packageName, appLabel, generation))
                    }
                )
            }

            composable(
                route = SubRoutes.FAKE_LOCATION_APP,
                arguments = listOf(
                    navArgument(SubRoutes.ARG_USER_ID) { type = NavType.IntType },
                    navArgument(SubRoutes.ARG_PACKAGE_NAME) { type = NavType.StringType },
                    navArgument(SubRoutes.ARG_GENERATION) { type = NavType.LongType },
                    navArgument(SubRoutes.ARG_APP_LABEL) { type = NavType.StringType; defaultValue = "" }
                )
            ) { entry ->
                FakeLocationAppScreen(
                    userId = requireNotNull(entry.arguments).getInt(SubRoutes.ARG_USER_ID),
                    packageName = requireNotNull(entry.arguments?.getString(SubRoutes.ARG_PACKAGE_NAME)),
                    appLabel = entry.arguments?.getString(SubRoutes.ARG_APP_LABEL),
                    expectedGeneration = requireNotNull(entry.arguments).getLong(SubRoutes.ARG_GENERATION),
                    onBack = { navController.popFrom(entry) }
                )
            }

            composable(SubRoutes.GMS) { entry ->
                GmsScreen(onBack = { navController.popFrom(entry) })
            }

            composable(
                route = SubRoutes.XP_MODULES,
                arguments = listOf(navArgument(SubRoutes.ARG_USER_ID) {
                    type = NavType.IntType
                    defaultValue = 0
                })
            ) { entry ->
                XpModulesScreen(userId = entry.arguments?.getInt(SubRoutes.ARG_USER_ID) ?: 0,
                    onBack = { navController.popFrom(entry) })
            }

            composable(SubRoutes.GALLERY) { entry ->
                GalleryScreen(onBack = { navController.popFrom(entry) })
            }
        }
        // One chrome instance survives primary-tab changes, so a tap animates the same lens
        // and backdrop instead of disposing it and creating one already at the target tab.
        // Entry-owned scaffolds below retain stable content insets during push/pop animations.
        NexBoxNavigationScaffold(
            selectedDestination = selectedDestination(currentRoute),
            showNavigation = primaryNavigationVisible(currentRoute),
            onNavigate = { next ->
                val source = navController.currentBackStackEntry
                if (source != null && primaryNavigationVisible(source.destination.route) &&
                    source.destination.route != next.route) {
                    focusManager.clearFocus(force = true)
                    navController.navigate(next.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        ) {}
        }
    }
}

/** Insets belong to the page. A covered primary page also paints its non-interactive back preview. */
@Composable
internal fun NexBoxPrimaryPage(
    navController: NavHostController,
    source: NavBackStackEntry,
    destination: NavDestination,
    drawNavigation: Boolean = true,
    content: @Composable () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val committedEntry by navController.currentBackStackEntryAsState()
    CompositionLocalProvider(LocalGlassAllowed provides routeAllowsGlass(source.destination.route)) {
        NexBoxNavigationScaffold(
            selectedDestination = destination,
            drawNavigation = drawNavigation || !primaryNavigationVisible(committedEntry?.destination?.route),
            onNavigate = { next ->
                if (navController.currentBackStackEntry == source && next != destination) {
                    focusManager.clearFocus(force = true)
                    navController.navigate(next.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
            content = content
        )
    }
}

/** Ignore queued taps from a page whose destination was already replaced. */
internal fun NavHostController.navigateFrom(source: NavBackStackEntry, route: String) {
    if (currentBackStackEntry == source) navigate(route) { launchSingleTop = true }
}

internal fun NavHostController.popFrom(source: NavBackStackEntry) {
    if (currentBackStackEntry == source) popBackStack()
}
