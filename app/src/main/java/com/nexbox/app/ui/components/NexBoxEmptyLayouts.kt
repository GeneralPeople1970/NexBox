package com.nexbox.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Keep the viewport's minimum height inside the scroll container, even with unbounded content. */
@Composable
fun NexBoxScrollableEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    action: (@Composable () -> Unit)? = null
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        NexBoxEmptyState(
            message = message,
            modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
            supporting = supporting,
            action = action
        )
    }
}

/**
 * Lazy layouts call arrange when all items fit in the viewport. Share the unused height
 * between empty rows, keeping headers above them. Overflow retains normal list scrolling.
 * Grid callers supply row indices; list callers supply item indices.
 */
internal class NexBoxEmptyStateArrangement(
    private val emptyIndices: Set<Int>,
    override val spacing: Dp = 0.dp
) : Arrangement.Vertical {
    override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
        val gap = spacing.roundToPx()
        val emptyCount = sizes.indices.count { it in emptyIndices }
        val remaining = (totalSize - sizes.sum() - gap * (sizes.size - 1).coerceAtLeast(0))
            .coerceAtLeast(0)
        var position = 0
        var allocated = 0
        var emptyOrdinal = 0
        sizes.forEachIndexed { index, size ->
            val extra = if (index in emptyIndices && emptyCount > 0) {
                val nextAllocated = (++emptyOrdinal * remaining.toLong() / emptyCount).toInt()
                (nextAllocated - allocated).also { allocated = nextAllocated }
            } else 0
            outPositions[index] = position + extra / 2
            position += size + gap + extra
        }
    }
}
