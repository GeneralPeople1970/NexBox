@file:OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)

package com.nexbox.app.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.nexbox.app.data.AppearanceSettings
import com.nexbox.app.data.UiStyle

internal val LocalNexBoxNavController = staticCompositionLocalOf<NavHostController?> { null }

internal enum class NavigationAnimation { STANDARD, AOSP, MIUIX }

internal fun navigationAnimation(appearance: AppearanceSettings): NavigationAnimation = when {
    !appearance.predictiveBackEnabled -> NavigationAnimation.STANDARD
    appearance.uiStyle == UiStyle.MATERIAL3 -> NavigationAnimation.AOSP
    else -> NavigationAnimation.MIUIX
}

/** Keep content public to our renderer; ComposeNavigator.Destination.content is internal. */
internal class NexBoxNavGraphBuilder {
    private val destinations = linkedMapOf<String, @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit>()
    private val arguments = linkedMapOf<String, List<NamedNavArgument>>()

    internal fun register(route: String, arguments: List<NamedNavArgument>,
        content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit) {
        destinations[route] = content
        this.arguments[route] = arguments
    }

    internal fun content(route: String?) = checkNotNull(destinations[route]) { "Unregistered route: $route" }
    internal fun install(graph: NavGraphBuilder) {
        arguments.forEach { (route, arguments) -> graph.composable(route = route, arguments = arguments) { } }
    }
}

/** Register normal destinations with one shared back/input policy, including future pages. */
internal fun NexBoxNavGraphBuilder.nexBoxComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) {
    register(route, arguments, content)
}

@Composable
internal fun NexBoxNavEntry(
    animatedScope: AnimatedContentScope,
    entry: NavBackStackEntry,
    retainForPredictive: Boolean = false,
    gestureActive: Boolean = false,
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) {
    val navController = checkNotNull(LocalNexBoxNavController.current)
    val currentEntry by navController.currentBackStackEntryAsState()
    val lifecycleState by entry.lifecycle.currentStateFlow.collectAsState()
    val interactive = !gestureActive && currentEntry == entry && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    if (retainForPredictive) {
        // AOSP geometry lives in graphicsLayer instead of enter/exit transforms. Give each
        // child's EnterExitState a real seekable duration even though its base alpha stays 1.
        animatedScope.transition.animateFloat(transitionSpec = { tween(450) }, label = "predictive-retention") {
            if (it == EnterExitState.Visible) 1f else 0f
        }
    }
    Box(
        Modifier.fillMaxSize()
            // Never express transition input blocking through a button's enabled color. A
            // departing page can remain visible for animation without accepting stale taps.
            .pointerInput(interactive) {
                if (!interactive) awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            }
            .then(if (interactive) Modifier else Modifier.clearAndSetSemantics {})
    ) {
        content(animatedScope, entry)
    }
}
