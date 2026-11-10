package com.nexbox.app.ui.navigation

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Lifecycle for visible-page refreshes and native surfaces, not for authorizing user actions.
 * Navigation temporarily moves a RESUMED entry to STARTED during predictive back, including a
 * cancelled gesture. Treat that as the same visible visit, while preserving real Activity pauses
 * and a page's move below STARTED. Keep using the original entry lifecycle for click guards.
 */
@Composable
fun rememberPageRefreshLifecycle(): Lifecycle {
    val pageLifecycle = LocalLifecycleOwner.current.lifecycle
    val hostLifecycle = (LocalActivity.current as? LifecycleOwner)?.lifecycle ?: pageLifecycle
    val owner = remember(pageLifecycle, hostLifecycle) { PageRefreshLifecycleOwner() }
    DisposableEffect(pageLifecycle, hostLifecycle, owner) {
        fun update() {
            owner.moveTo(pageRefreshState(pageLifecycle.currentState, hostLifecycle.currentState))
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        pageLifecycle.addObserver(observer)
        if (hostLifecycle !== pageLifecycle) hostLifecycle.addObserver(observer)
        update()
        onDispose {
            pageLifecycle.removeObserver(observer)
            if (hostLifecycle !== pageLifecycle) hostLifecycle.removeObserver(observer)
            owner.moveTo(Lifecycle.State.DESTROYED)
        }
    }
    return owner.lifecycle
}

internal fun pageRefreshState(page: Lifecycle.State, host: Lifecycle.State): Lifecycle.State {
    val visibleState = if (page.isAtLeast(Lifecycle.State.STARTED)) Lifecycle.State.RESUMED else page
    return minOf(visibleState, host)
}

private class PageRefreshLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    fun moveTo(state: Lifecycle.State) {
        // A composition abandoned before its first frame can still be INITIALIZED.
        if (registry.currentState == Lifecycle.State.INITIALIZED && state == Lifecycle.State.DESTROYED) return
        registry.currentState = state
    }
}
