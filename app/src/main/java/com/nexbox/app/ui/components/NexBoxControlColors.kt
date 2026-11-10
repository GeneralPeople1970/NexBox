package com.nexbox.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

internal fun controlContrast(foreground: Color, background: Color): Double {
    val ink = foreground.compositeOver(background).luminance().toDouble()
    val paper = background.luminance().toDouble()
    return (max(ink, paper) + 0.05) / (min(ink, paper) + 0.05)
}

/** Keep the theme hue and paired text role; nudge only as far as small-text contrast requires. */
internal fun readableContainer(container: Color, foreground: Color): Color =
    contrastAdjusted(container, foreground) { candidate, fixed -> controlContrast(fixed, candidate) }

internal fun readableContent(foreground: Color, container: Color): Color =
    contrastAdjusted(foreground, container, ::controlContrast)

private inline fun contrastAdjusted(color: Color, fixed: Color, ratio: (Color, Color) -> Double): Color {
    if (ratio(color, fixed) >= 4.5) return color
    val target = if (ratio(Color.Black, fixed) > ratio(Color.White, fixed)) Color.Black else Color.White
    var low = 0f
    var high = 1f
    repeat(16) {
        val mid = (low + high) / 2
        if (ratio(lerp(color, target, mid), fixed) >= 4.5) high = mid else low = mid
    }
    return lerp(color, target, high)
}
