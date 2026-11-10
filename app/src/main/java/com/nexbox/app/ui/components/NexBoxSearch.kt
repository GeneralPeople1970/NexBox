package com.nexbox.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.nexbox.app.R
import com.nexbox.app.ui.theme.NexBoxShapes
import com.nexbox.app.ui.theme.NexBoxSpacing

/** Query/session survive recreation; focus and IME deliberately do not. */
@Stable
class NexBoxSearchState(initialQuery: String = "", initiallyActive: Boolean = false) {
    var query by mutableStateOf(initialQuery)
    var active by mutableStateOf(initiallyActive)
    var focused by mutableStateOf(false)
        internal set
    internal var dismissInput: (() -> Unit)? = null

    fun clearFocus() {
        dismissInput?.invoke()
        focused = false
    }

    fun close() {
        clearFocus()
        query = ""
        active = false
    }

    fun handleBack(): Boolean {
        if (!active) return false
        if (focused) clearFocus() else close()
        return true
    }

    companion object {
        val Saver = listSaver<NexBoxSearchState, Any>(
            save = { listOf(it.query, it.active) },
            restore = { NexBoxSearchState(it[0] as String, it[1] as Boolean) }
        )
    }
}

@Composable
fun rememberNexBoxSearchState(vararg inputs: Any?): NexBoxSearchState =
    rememberSaveable(*inputs, saver = NexBoxSearchState.Saver) { NexBoxSearchState() }

/** Miuix windows use NavigationEvent; register after their dismissal handler. */
internal val LocalSearchWindow = staticCompositionLocalOf { false }

@Composable
private fun SearchBackHandler(state: NexBoxSearchState) {
    if (LocalSearchWindow.current) {
        val navigation = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
        NavigationBackHandler(navigation, isBackEnabled = state.active,
            onBackCompleted = { state.handleBack() })
    } else {
        BackHandler(enabled = state.active) { state.handleBack() }
    }
}

/** Observe only unhandled taps. Never consume scrolling, buttons, or text selection. */
fun Modifier.dismissSearchFocusOnBlankTap(state: NexBoxSearchState): Modifier = pointerInput(state) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        val up = waitForUpOrCancellation(pass = PointerEventPass.Final)
        if (up != null && !up.isConsumed && state.focused) state.clearFocus()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NexBoxSearchField(
    state: NexBoxSearchState,
    hint: String,
    modifier: Modifier = Modifier,
    maxQueryLength: Int = Int.MAX_VALUE
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val currentImeVisible by rememberUpdatedState(imeVisible)
    var sawIme by remember { mutableStateOf(false) }
    var dismissingIme by remember { mutableStateOf(false) }
    DisposableEffect(state, focusManager, keyboard) {
        state.dismissInput = {
            // A quick re-tap may arrive before the previous hide animation has reported its
            // final insets. That old hide must not be mistaken for Back on the new focus.
            dismissingIme = dismissingIme || currentImeVisible
            sawIme = false
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
        onDispose { state.dismissInput = null; state.focused = false }
    }
    // Android consumes the first back itself. Clear the remaining focus when that IME closes.
    LaunchedEffect(imeVisible, state.focused, dismissingIme) {
        if (dismissingIme) {
            if (!imeVisible) {
                dismissingIme = false
                if (state.focused) keyboard?.show()
            } else if (state.focused) {
                // Finish the pending hide first, then reopen for this new focus session.
                keyboard?.hide()
            }
            return@LaunchedEffect
        }
        when {
            !state.focused -> sawIme = false
            imeVisible -> sawIme = true
            sawIme -> {
                sawIme = false
                state.clearFocus()
            }
        }
    }
    SearchBackHandler(state)
    val colors = MaterialTheme.colorScheme
    val openLabel = stringResource(R.string.app_search_open)
    val container by animateColorAsState(
        if (!usesMiuix() && state.active) lerp(colors.surfaceContainerHigh, colors.primary, 0.04f)
        else colors.surfaceContainerHigh,
        tween(200), label = "search-container"
    )
    val minHeight = NexBoxSpacing.searchHeight * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val hintColor = readableContent(colors.onSurfaceVariant, container)
    BasicTextField(
        value = state.query,
        onValueChange = { state.query = it.take(maxQueryLength); state.active = true },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { state.clearFocus() }),
        modifier = modifier.fillMaxWidth()
            .clip(NexBoxShapes.pill).background(container)
            .onFocusChanged { state.focused = it.isFocused; if (it.isFocused) state.active = true }
            .semantics { contentDescription = hint; onClick(label = openLabel, action = null) },
        decorationBox = { editor ->
            Row(Modifier.fillMaxWidth().heightIn(min = minHeight)
                .padding(start = NexBoxSpacing.lg, end = NexBoxSpacing.xs),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, Modifier.size(NexBoxSpacing.xl), hintColor)
                Spacer(Modifier.width(NexBoxSpacing.md))
                Box(Modifier.weight(1f).padding(vertical = NexBoxSpacing.sm)) {
                    if (state.query.isEmpty()) Text(hint, color = hintColor,
                        style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    editor()
                }
                // Reserve a stable slot, but an empty field has nothing to clear. Remove
                // the action immediately when emptied, including during focus changes.
                Box(Modifier.size(NexBoxSpacing.touchTarget), contentAlignment = Alignment.Center) {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = state::close,
                            modifier = Modifier.size(NexBoxSpacing.touchTarget)) {
                            Icon(Icons.Default.Close, stringResource(R.string.app_search_close), tint = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    )
}
