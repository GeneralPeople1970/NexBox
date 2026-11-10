package com.nexbox.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.nexbox.app.R

/** Keep measured content and scroll position while callers guard the in-flight action. */
@Composable
fun NexBoxLoadingOverlay(
    loading: Boolean,
    modifier: Modifier = Modifier,
    progress: (() -> Float)? = null,
    status: String = stringResource(R.string.operation_in_progress),
    content: @Composable () -> Unit
) {
    Box(modifier) {
        Box(if (loading) Modifier.blur(3.dp).alpha(0.45f).clearAndSetSemantics { } else Modifier) {
            content()
        }
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(28.dp).semantics { stateDescription = status },
                strokeWidth = 3.dp,
                progress = progress
            )
        }
    }
}
