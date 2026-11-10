@file:OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)

package com.nexbox.app.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.LocalOwnersProvider
import androidx.navigation.createGraph
import androidx.navigation.get
import com.nexbox.app.ui.navigation.*
import com.nexbox.app.ui.theme.LocalAppearance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/**
 * NavController still owns routes, restoration, SavedState and ViewModels. This renderer uses
 * only public ComposeNavigator APIs (backStack/prepareForTransition/onTransitionComplete) and
 * NavBackStackEntry.LocalOwnersProvider. Lifecycle ordering follows AndroidX NavHost 2.8.2
 * (Apache-2.0), but destination content is registered locally rather than accessing internals.
 *
 * Owning the back driver lets editors block previews before a gesture starts, and lets Material's
 * cross-activity animation distinguish live touch, cancellation and release. MIUIX keeps the
 * existing full-width/quarter-width transforms and 450 ms curve.
 */
@Composable
internal fun NexBoxNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = NavDestination.APPS.route,
    builder: NexBoxNavGraphBuilder.() -> Unit
) {
    val owner = checkNotNull(LocalViewModelStoreOwner.current)
    val lifecycleOwner = LocalLifecycleOwner.current
    val hostLifecycle by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val hostActive = hostLifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val inheritedPolicy = LocalNexBoxBackPolicy.current
    val appearance = LocalAppearance.current
    val animation = navigationAnimation(appearance)
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val aospEasing = rememberAospPageEasing()
    val driftPx = with(LocalDensity.current) { 96.dp.roundToPx() }
    // Route definitions are fixed, but page lambdas must see the newest editor draft/callbacks.
    // Do not recreate the navigation graph for a keystroke or a predictive animation frame.
    val destinations = NexBoxNavGraphBuilder().apply(builder)
    val graph = remember(navController, startDestination) {
        navController.createGraph(startDestination) { destinations.install(this) }
    }
    navController.setViewModelStore(owner.viewModelStore)
    navController.graph = graph
    DisposableEffect(navController, lifecycleOwner) {
        navController.setLifecycleOwner(lifecycleOwner)
        onDispose { }
    }
    val navigator = navController.navigatorProvider.get<ComposeNavigator>("composable")
    val backStack by navigator.backStack.collectAsState()
    val visible by navController.visibleEntries.collectAsState()
    val current = backStack.lastOrNull() ?: return
    val previous = backStack.getOrNull(backStack.lastIndex - 1)
    val policies = remember(navController) { mutableMapOf<String, NexBoxBackPolicy>() }
    fun policy(entry: NavBackStackEntry): NexBoxBackPolicy =
        if (entry.destination.route == startDestination && inheritedPolicy != null) inheritedPolicy
        else policies.getOrPut(entry.id) { NexBoxBackPolicy() }

    val state = remember(navController) { SeekableTransitionState(current) }
    val transition = rememberTransition(state, label = "nexbox-navigation")
    var gesture by remember(navController) { mutableStateOf<NexBoxBackGesture?>(null) }
    val latestCurrent by rememberUpdatedState(current)
    val latestVisible by rememberUpdatedState(visible)
    val latestStack by rememberUpdatedState(backStack)
    val latestAnimation by rememberUpdatedState(animation)
    val latestPolicy by rememberUpdatedState(policy(current))
    val previewBlocked = policy(current).guard?.allowPreview == false
    val saveableState = rememberSaveableStateHolder()
    val zIndices = remember(navController) { mutableMapOf<String, Float>() }

    fun commitWithoutPreview(source: NavBackStackEntry) {
        if (navController.currentBackStackEntry != source ||
            !source.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val guard = policy(source).guard
        if (guard != null) guard.onBlocked() else navController.popBackStack()
    }

    // Registered before content so an active search, selection or dialog still wins. Nested
    // hosts only register with >1 entries; their root delegates back/guards to its parent host.
    BackHandler(enabled = hostActive && previous != null && (!appearance.predictiveBackEnabled || previewBlocked)) {
        commitWithoutPreview(latestCurrent)
    }
    PredictiveBackHandler(enabled = hostActive && previous != null && appearance.predictiveBackEnabled && !previewBlocked) { events ->
        val source = latestCurrent
        val destination = latestStack.getOrNull(latestStack.lastIndex - 1)
        val primarySwitch = primaryNavigationVisible(source.destination.route) &&
            primaryNavigationVisible(destination?.destination?.route)
        val allowPreview = latestPolicy.guard?.allowPreview != false && gesture == null && !primarySwitch &&
            source.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        var started = false
        var lastTime = SystemClock.uptimeMillis()
        var lastProgress = 0f
        try {
            events.collect { event ->
                if (destination != null && allowPreview && navController.currentBackStackEntry == source) {
                    val now = SystemClock.uptimeMillis()
                    if (!started) {
                        navigator.prepareForTransition(source)
                        navigator.prepareForTransition(destination)
                        gesture = NexBoxBackGesture(source, destination, initialTouchY = event.touchY,
                            touchY = event.touchY, swipeEdge = event.swipeEdge)
                        started = true
                    }
                    val speed = if (now > lastTime) (event.progress - lastProgress) * 1000f / (now - lastTime) else 0f
                    gesture = gesture?.copy(progress = event.progress, touchY = event.touchY,
                        releaseVelocity = speed)
                    state.seekTo(event.progress, destination)
                    lastTime = now
                    lastProgress = event.progress
                }
            }
            if (started) {
                gesture = gesture?.copy(
                    phase = if (latestPolicy.guard == null) BackPhase.COMMIT else BackPhase.BLOCKED,
                    releaseProgress = gesture?.progress ?: 0f
                )
            } else if (gesture == null) commitWithoutPreview(source)
        } catch (_: CancellationException) {
            if (started) gesture = gesture?.copy(phase = BackPhase.CANCEL)
        }
    }

    LaunchedEffect(current, gesture == null) {
        val preview = gesture
        val finishingCommit = preview != null && preview.phase == BackPhase.COMMIT &&
            current == preview.destination && state.fraction == 1f
        if (preview != null && current != preview.source && !finishingCommit) {
            // External navigation can invalidate a scope/editor while a finger is down.
            // Do not resurrect or pop that stale source when the old gesture later completes.
            gesture = null
            state.snapTo(current)
            navigator.onTransitionComplete(preview.source)
            navigator.onTransitionComplete(preview.destination)
        } else if (preview == null && state.targetState != current) state.animateTo(current)
    }

    LaunchedEffect(gesture?.phase) {
        val released = gesture ?: return@LaunchedEffect
        if (released.phase == BackPhase.LIVE) return@LaunchedEffect
        val aosp = latestAnimation == NavigationAnimation.AOSP
        suspend fun settle(to: Float, committing: Boolean) {
            val clock = Animatable(0f)
            val from = state.fraction
            val sampler = launch {
                snapshotFlow { clock.value }.collect { elapsed ->
                    val fraction = if (committing && aosp) aospEasing.transform(elapsed) else elapsed
                    val progress = from + (to - from) * fraction
                    gesture = gesture?.copy(progress = progress, settleProgress = elapsed)
                    state.seekTo(progress.coerceIn(0f, 1f), released.destination)
                }
            }
            try {
                if (!committing && aosp) clock.animateTo(1f, spring(stiffness = 1500f))
                else clock.animateTo(1f, tween(
                    if (aosp) 450 else (kotlin.math.abs(to - from) * 450).toInt().coerceAtLeast(1),
                    easing = LinearEasing))
            } finally { sampler.cancelAndJoin() }
            gesture = gesture?.copy(progress = to, settleProgress = 1f)
            state.seekTo(to, released.destination)
        }
        val mayCommit = released.phase == BackPhase.COMMIT &&
            navController.currentBackStackEntry == released.source && policy(released.source).guard == null
        if (mayCommit) {
            settle(1f, committing = true)
            // A save, external state update or dialog can install a guard during release.
            if (navController.currentBackStackEntry == released.source && policy(released.source).guard == null) {
                navController.popBackStack()
                state.snapTo(released.destination)
            } else {
                gesture = gesture?.copy(phase = BackPhase.CANCEL)
                return@LaunchedEffect
            }
        } else {
            settle(0f, committing = false)
            state.snapTo(released.source)
        }
        gesture = null
        navigator.onTransitionComplete(released.source)
        navigator.onTransitionComplete(released.destination)
        if (released.phase == BackPhase.BLOCKED && navController.currentBackStackEntry == released.source) {
            policy(released.source).guard?.onBlocked?.invoke()
        }
    }

    val popping = gesture != null || state.currentState !in backStack
    CompositionLocalProvider(LocalNexBoxNavController provides navController) {
        transition.AnimatedContent(
            modifier = modifier,
            contentKey = { it.id },
            transitionSpec = {
                val primarySwitch = primaryNavigationVisible(initialState.destination.route) &&
                    primaryNavigationVisible(targetState.destination.route)
                val initialZ = zIndices.getOrPut(initialState.id) { 0f }
                val targetZ = if (initialState == targetState) initialZ else initialZ + if (popping) -1f else 1f
                zIndices[targetState.id] = targetZ
                if (primarySwitch) ContentTransform(EnterTransition.None, ExitTransition.None, targetZ)
                else nexBoxContentTransform(animation, popping, gesture != null, direction, aospEasing, driftPx)
                    .apply { targetContentZIndex = targetZ }
            }
        ) { entry ->
            // A predictive target is still below STARTED in some Navigation versions even
            // after prepareForTransition; retain exactly the two gesture entries explicitly.
            if (entry in visible || entry == gesture?.source || entry == gesture?.destination) {
                entry.LocalOwnersProvider(saveableState) {
                    CompositionLocalProvider(LocalNexBoxBackPolicy provides policy(entry)) {
                        Box(Modifier.fillMaxSize().aospPredictivePage(entry, gesture, animation, aospEasing)) {
                            NexBoxNavEntry(this@AnimatedContent, entry,
                                retainForPredictive = gesture != null && animation == NavigationAnimation.AOSP,
                                gestureActive = gesture != null) {
                                destinations.content(entry.destination.route).invoke(this, entry)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(transition.currentState, transition.targetState, current, gesture == null) {
        if (gesture == null && transition.currentState == transition.targetState && transition.targetState == current) {
            visible.filter { it.destination.navigatorName == "composable" }.forEach(navigator::onTransitionComplete)
            policies.keys.retainAll(backStack.map { it.id }.toSet())
            zIndices.keys.retainAll(setOf(current.id))
        }
    }
    DisposableEffect(navigator) {
        onDispose {
            (latestVisible + latestStack).filter { it.destination.navigatorName == "composable" }
                .distinctBy { it.id }.forEach(navigator::onTransitionComplete)
        }
    }
}
