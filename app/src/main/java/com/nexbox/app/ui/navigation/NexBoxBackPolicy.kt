package com.nexbox.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

internal data class NexBoxBackGuardState(val allowPreview: Boolean, val onBlocked: () -> Unit)

internal class NexBoxBackPolicy {
    private val guards = mutableStateListOf<Pair<Any, () -> NexBoxBackGuardState?>>()
    val guard: NexBoxBackGuardState? get() = guards.asReversed().firstNotNullOfOrNull { it.second() }
    fun register(token: Any, guard: () -> NexBoxBackGuardState?) { guards.add(token to guard) }
    fun remove(token: Any) { guards.removeAll { it.first == token } }
}

internal val LocalNexBoxBackPolicy = staticCompositionLocalOf<NexBoxBackPolicy?> { null }

/**
 * Draft and busy guards disable the host's predictive callback before a gesture starts.
 * Completed back invokes onBlocked; cancelled gestures leave the editor and dialog untouched.
 * A nested host's root shares its parent's policy, so no return is bypassed.
 */
@Composable
internal fun NexBoxBackGuard(blocked: Boolean, allowPreview: Boolean = false, onBlocked: () -> Unit) {
    val policy = LocalNexBoxBackPolicy.current
    val token = remember { Any() }
    val latestBlocked = rememberUpdatedState(blocked)
    val latestPreview = rememberUpdatedState(allowPreview)
    val latestOnBlocked = rememberUpdatedState(onBlocked)
    DisposableEffect(policy, token) {
        policy?.register(token) {
            if (latestBlocked.value) NexBoxBackGuardState(latestPreview.value) { latestOnBlocked.value() }
            else null
        }
        onDispose { policy?.remove(token) }
    }
    BackHandler(enabled = policy == null && blocked, onBack = onBlocked)
}
