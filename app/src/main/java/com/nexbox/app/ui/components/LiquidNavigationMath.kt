package com.nexbox.app.ui.components

import kotlin.math.roundToInt

/**
 * Maps a container-local x coordinate to a logical tab position, with integer values at tab centres.
 * Insets are symmetric. Invalid geometry or a NaN coordinate falls back to the first logical tab.
 */
internal fun liquidTabPosition(
    x: Float,
    width: Float,
    inset: Float,
    tabCount: Int,
    isRtl: Boolean
): Float {
    require(tabCount >= 1) { "tabCount must be at least one" }
    if (tabCount == 1 || !width.isFinite() || !inset.isFinite() || inset < 0f || x.isNaN()) {
        return 0f
    }
    // Double intermediates avoid overflow when subtracting insets or handling extreme coordinates.
    val innerWidth = width.toDouble() - 2.0 * inset.toDouble()
    if (innerWidth <= 0.0) return 0f
    val lastPosition = (tabCount - 1).toDouble()
    val visualPosition = ((x.toDouble() - inset.toDouble()) / innerWidth * tabCount - 0.5)
        .coerceIn(0.0, lastPosition)
    return (if (isRtl) lastPosition - visualPosition else visualPosition).toFloat()
}

/** Returns the indicator's left offset within the inset area, without adding the inset itself. */
internal fun liquidTabOffset(
    position: Float,
    tabWidth: Float,
    tabCount: Int,
    isRtl: Boolean
): Float {
    require(tabCount >= 1) { "tabCount must be at least one" }
    if (tabCount == 1 || !tabWidth.isFinite() || tabWidth <= 0f) return 0f
    val lastPosition = (tabCount - 1).toDouble()
    val logicalPosition = if (position.isNaN()) 0.0 else position.toDouble().coerceIn(0.0, lastPosition)
    val visualPosition = if (isRtl) lastPosition - logicalPosition else logicalPosition
    return (visualPosition * tabWidth.toDouble()).toFloat().takeIf { it.isFinite() } ?: 0f
}

/** Rounds to the nearest logical tab; exact half-way values select the higher logical index. */
internal fun liquidTabIndex(position: Float, tabCount: Int): Int {
    require(tabCount >= 1) { "tabCount must be at least one" }
    if (position.isNaN()) return 0
    val lastIndex = tabCount - 1
    return position.coerceIn(0f, lastIndex.toFloat()).roundToInt().coerceIn(0, lastIndex)
}
