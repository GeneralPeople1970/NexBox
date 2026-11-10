package com.nexbox.app.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBar as MaterialNavigationBar
import androidx.compose.material3.NavigationBarItem as MaterialNavigationBarItem
import androidx.compose.material3.NavigationRail as MaterialNavigationRail
import androidx.compose.material3.NavigationRailItem as MaterialNavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.nexbox.app.ui.navigation.NavDestination
import com.nexbox.app.ui.theme.NexBoxSpacing
import top.yukonga.miuix.kmp.basic.NavigationBar as MiuixNavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem as MiuixNavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationRail as MiuixNavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem as MiuixNavigationRailItem

/** Only the navigation chrome changes skin. The NavHost content has one stable layout slot. */
@Composable
fun NexBoxNavigationScaffold(
    selectedDestination: NavDestination,
    onNavigate: (NavDestination) -> Unit,
    showNavigation: Boolean = true,
    drawNavigation: Boolean = true,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val homeIcon = rememberNexBoxHomeIcon()
        val wide = maxWidth >= 600.dp
        val glass = isNexBoxGlassActive()
        val miuix = usesMiuix()
        val showRail = showNavigation && wide
        // Navigation belongs to the route, not the keyboard. Search focus must not remove
        // the bar or change the page's reserved bottom space during the IME animation.
        val showBottomNavigation = showNavigation && !wide
        val navigationHeight = if (miuix) NexBoxSpacing.navBarHeightMiuix else NexBoxSpacing.navBarHeightMaterial
        val navigationColor = if (miuix || wide) MaterialTheme.colorScheme.surface
            else NavigationBarDefaults.containerColor
        // 为普通栏与玻璃栏的最大占位预留滚动内边距，开关玻璃不改变内容测量。
        val navBarExtent = maxOf(navigationHeight,
            NexBoxSpacing.navBarHeightGlass + NexBoxSpacing.navGlassVerticalGap * 2)
        val contentPadding = PaddingValues(bottom = if (showBottomNavigation) navBarExtent else 0.dp)
        CompositionLocalProvider(LocalNavigationPadding provides contentPadding) {
            Box(Modifier.fillMaxSize().padding(start = if (showRail) NexBoxSpacing.navRailWidth else 0.dp)) { content() }
        }
        if (showRail && drawNavigation) {
            // 侧边栏不是「底栏」，不参与液态玻璃——玻璃只作用于底部导航。
            val chromeModifier = Modifier.align(Alignment.CenterStart).fillMaxHeight()
                .width(NexBoxSpacing.navRailWidth)
            if (miuix) {
                MiuixNavigationRail(modifier = chromeModifier, color = navigationColor,
                    showDivider = true) {
                    NavDestination.entries.forEach { destination ->
                        val label = stringResource(destination.titleRes)
                        MiuixNavigationRailItem(selected = selectedDestination == destination,
                            onClick = { onNavigate(destination) },
                            icon = if (destination == NavDestination.APPS) homeIcon else destination.selectedIcon,
                            label = label,
                            modifier = Modifier.navigationKeyboardAction { onNavigate(destination) }.clearAndSetSemantics {
                                role = Role.Tab
                                selected = selectedDestination == destination
                                contentDescription = label
                                onClick { onNavigate(destination); true }
                            })
                    }
                }
            } else {
                MaterialNavigationRail(modifier = chromeModifier, containerColor = navigationColor) {
                    NavDestination.entries.forEach { destination ->
                        MaterialNavigationRailItem(selected = selectedDestination == destination,
                            onClick = { onNavigate(destination) },
                            icon = { Icon(if (destination == NavDestination.APPS) homeIcon
                                else if (selectedDestination == destination) destination.selectedIcon else destination.unselectedIcon,
                                contentDescription = null) },
                            label = { Text(stringResource(destination.titleRes)) })
                    }
                }
            }
        } else if (showBottomNavigation && drawNavigation) {
            // 液态玻璃只作用于底栏。玻璃开启时底栏浮起为胶囊、半透明并采样身后的内容；
            // 关闭时贴边满宽、不透明。两种形态都落在 navBarExtent 之内，内容不会因此移动。
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(
                    horizontal = if (glass) NexBoxSpacing.navGlassHorizontalGap else 0.dp,
                    vertical = if (glass) NexBoxSpacing.navGlassVerticalGap else 0.dp
                ), contentAlignment = Alignment.Center) {
                val chromeModifier = Modifier.fillMaxWidth().height(navigationHeight)
                if (glass) {
                    NexBoxLiquidNavigationBar(
                        selectedDestination = selectedDestination,
                        onNavigate = onNavigate,
                        modifier = Modifier.widthIn(max = NexBoxSpacing.navGlassMaxWidth)
                    )
                } else if (miuix) {
                    MiuixNavigationBar(modifier = chromeModifier, color = navigationColor,
                        defaultWindowInsetsPadding = false, showDivider = true) {
                        NavDestination.entries.forEach { destination ->
                            val label = stringResource(destination.titleRes)
                            MiuixNavigationBarItem(selected = selectedDestination == destination,
                                onClick = { onNavigate(destination) },
                                icon = if (destination == NavDestination.APPS) homeIcon else destination.selectedIcon,
                                label = label,
                                modifier = Modifier.navigationKeyboardAction { onNavigate(destination) }.clearAndSetSemantics {
                                    role = Role.Tab
                                    selected = selectedDestination == destination
                                    contentDescription = label
                                    onClick { onNavigate(destination); true }
                                })
                        }
                    }
                } else {
                    MaterialNavigationBar(modifier = chromeModifier, containerColor = navigationColor,
                        windowInsets = WindowInsets(0, 0, 0, 0)) {
                        NavDestination.entries.forEach { destination ->
                            MaterialNavigationBarItem(selected = selectedDestination == destination,
                                onClick = { onNavigate(destination) },
                                icon = { Icon(if (destination == NavDestination.APPS) homeIcon
                                    else if (selectedDestination == destination) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = null) },
                                label = { Text(stringResource(destination.titleRes)) })
                        }
                    }
                }
            }
        }
    }
}

// Miuix's native navigation items handle pointer taps only. Keep hardware keyboards and D-pads usable.
private fun Modifier.navigationKeyboardAction(onClick: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.key in listOf(Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionCenter)) {
            if (event.type == KeyEventType.KeyUp) onClick()
            true
        } else false
    }.focusable()
