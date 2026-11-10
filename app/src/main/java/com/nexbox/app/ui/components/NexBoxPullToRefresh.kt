package com.nexbox.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nexbox.app.R
import com.nexbox.app.ui.theme.NexBoxSpacing
import top.yukonga.miuix.kmp.basic.PullToRefresh as MiuixPullToRefresh
import top.yukonga.miuix.kmp.basic.RefreshState
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState

/** Native refresh gestures/indicators for each skin. Content must contain a scrollable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexBoxPullToRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val latestContent by rememberUpdatedState(content)
    // Changing skin or disabling refresh during a modal action must not reset list state.
    val body = remember { movableContentOf { latestContent() } }
    val latestRefresh by rememberUpdatedState(onRefresh)
    var gesturePending by remember { mutableStateOf(false) }
    val activeRefresh = refreshing || gesturePending
    // Miuix finishes only after observing true -> false. Keep an accepted gesture active
    // for one frame even when a cached/no-op refresh completes before recomposition.
    LaunchedEffect(gesturePending) {
        if (gesturePending) {
            withFrameNanos { }
            gesturePending = false
        }
    }
    val requestRefresh = {
        if (enabled && !activeRefresh) {
            gesturePending = true
            latestRefresh()
        }
    }

    if (!enabled && !activeRefresh) {
        Box(modifier) { body() }
    } else if (usesMiuix()) {
        val state = rememberPullToRefreshState()
        Box(modifier) {
            MiuixPullToRefresh(
                isRefreshing = activeRefresh,
                onRefresh = requestRefresh,
                modifier = Modifier.fillMaxSize(),
                pullToRefreshState = state,
                refreshTexts = listOf(
                    stringResource(R.string.pull_refresh_pull),
                    stringResource(R.string.pull_refresh_release),
                    stringResource(R.string.pull_refresh_loading),
                    stringResource(R.string.pull_refresh_finished)
                )
            ) { body() }
            // Miuix 0.8.6 starts its header from a gesture, not a programmatic refresh.
            // A toolbar/menu refresh still needs a native progress indicator.
            if (activeRefresh && state.refreshState == RefreshState.Idle) {
                CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(NexBoxSpacing.md))
            }
        }
    } else {
        PullToRefreshBox(isRefreshing = activeRefresh, onRefresh = requestRefresh, modifier = modifier) { body() }
    }
}
