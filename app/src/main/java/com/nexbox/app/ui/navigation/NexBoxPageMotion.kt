package com.nexbox.app.ui.navigation

import android.view.animation.AnimationUtils
import androidx.activity.BackEventCompat
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

private val MiuixPageEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val BackGestureEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
internal enum class BackPhase { LIVE, COMMIT, CANCEL, BLOCKED }
internal data class NexBoxBackGesture(
    val source: NavBackStackEntry,
    val destination: NavBackStackEntry,
    val initialTouchY: Float,
    val touchY: Float,
    val swipeEdge: Int,
    val progress: Float = 0f,
    val releaseProgress: Float = 0f,
    val releaseVelocity: Float = 0f,
    val settleProgress: Float = 0f,
    val phase: BackPhase = BackPhase.LIVE
)

@Composable
internal fun rememberAospPageEasing(): Easing {
    val context = LocalContext.current
    return remember(context) {
        // Android 12+ already ships this exact two-segment AOSP path; no GPL easing code.
        val interpolator = AnimationUtils.loadInterpolator(context, android.R.interpolator.fast_out_extra_slow_in)
        Easing { interpolator.getInterpolation(it) }
    }
}

/** MIUIX is intentionally unchanged. Only Material distinguishes button-back from gesture-back. */
internal fun nexBoxContentTransform(
    animation: NavigationAnimation,
    popping: Boolean,
    predictive: Boolean,
    direction: Int,
    aospEasing: Easing,
    driftPx: Int
): ContentTransform {
    if (animation == NavigationAnimation.AOSP) {
        if (predictive) {
            // A 450 ms seekable clock retains BOTH entries; actual geometry is touch-driven.
            return ContentTransform(
                fadeIn(tween(450, easing = LinearEasing), initialAlpha = 1f),
                fadeOut(tween(450, easing = LinearEasing), targetAlpha = 1f),
                targetContentZIndex = -1f
            )
        }
        val slide = tween<androidx.compose.ui.unit.IntOffset>(450, easing = aospEasing)
        return if (popping) ContentTransform(
            slideInHorizontally(slide) { -direction * driftPx },
            slideOutHorizontally(slide) { direction * driftPx } + fadeOut(tween(83, delayMillis = 35)),
            targetContentZIndex = -1f
        ) else ContentTransform(
            slideInHorizontally(slide) { direction * driftPx } + fadeIn(tween(83, delayMillis = 50)),
            slideOutHorizontally(slide) { -direction * driftPx },
            targetContentZIndex = 1f
        )
    }
    val slide = tween<androidx.compose.ui.unit.IntOffset>(450, easing = MiuixPageEasing)
    return if (popping) ContentTransform(
        slideInHorizontally(slide) { -direction * it / 4 } + fadeIn(tween(450, easing = MiuixPageEasing), initialAlpha = 0.9f),
        slideOutHorizontally(slide) { direction * it },
        targetContentZIndex = -1f
    ) else ContentTransform(
        slideInHorizontally(slide) { direction * it },
        slideOutHorizontally(slide) { -direction * it / 4 } + fadeOut(tween(450, easing = MiuixPageEasing), targetAlpha = 0.9f),
        targetContentZIndex = 1f
    )
}

/**
 * Independent implementation of the AOSP cross-activity motion observed in InstallerX Revived:
 * 96dp drift, 8dp edge margin, 0.9 minimum scale, finger-Y damping, 450ms release/90ms fade,
 * and a velocity kick (stiffness 200, damping .75). No InstallerX classes or source are copied.
 */
@Composable
internal fun Modifier.aospPredictivePage(
    entry: NavBackStackEntry,
    gesture: NexBoxBackGesture?,
    animation: NavigationAnimation,
    easing: Easing
): Modifier {
    if (gesture == null || animation != NavigationAnimation.AOSP) return this
    val density = LocalDensity.current
    val drift = with(density) { 96.dp.toPx() }
    val edge = with(density) { 8.dp.toPx() }
    val incoming = entry == gesture.destination
    val committing = gesture.phase == BackPhase.COMMIT
    val live = BackGestureEasing.transform(
        (if (committing) gesture.releaseProgress else gesture.progress).coerceIn(0f, 1f))
    val settled = if (committing) easing.transform(gesture.settleProgress.coerceIn(0f, 1f)) else 0f
    val baseScale = 1f - 0.1f * live
    val scale = (baseScale + (1f - baseScale) * settled) * releaseScaleKick(gesture)
    return graphicsLayer {
        val snappedScale = if (size.width > 0) (size.width * scale).roundToInt() / size.width else scale
        scaleX = snappedScale
        scaleY = snappedScale
        val insetX = size.width * (1f - snappedScale) / 2f
        val insetY = size.height * (1f - snappedScale) / 2f
        val hug = if (gesture.swipeEdge == BackEventCompat.EDGE_RIGHT) 0f
            else (size.width * 0.05f - edge).coerceAtLeast(0f) * live
        val x = if (incoming) -drift * (1f - settled) else hug + drift * settled
        val fingerDelta = gesture.touchY - gesture.initialTouchY
        val relativeY = if (size.height > 0) (abs(fingerDelta) * 2f / size.height).coerceIn(0f, 1f) else 0f
        val y = (insetY - edge).coerceAtLeast(0f) * relativeY * (2f - relativeY) * sign(fingerDelta)
        // Align the scaled top/left edges to pixels so text does not shimmer during back.
        translationX = (insetX + x).roundToInt() - insetX
        translationY = (insetY + y).roundToInt() - insetY
        alpha = if (!incoming && committing) (1f - gesture.settleProgress * 5f).coerceIn(0f, 1f) else 1f
        shape = RoundedCornerShape(32.dp * (live * (1f - settled)).coerceIn(0f, 1f))
        clip = true
    }.drawWithContent {
        drawContent()
        // InstallerX's display sets dimAmount=.5; only the revealed parent is dimmed.
        if (incoming) drawRect(Color.Black.copy(alpha = 0.5f * (1f - settled)))
    }
}

private fun releaseScaleKick(gesture: NexBoxBackGesture): Float {
    if (gesture.phase != BackPhase.COMMIT) return 1f
    val seconds = gesture.settleProgress.coerceIn(0f, 1f) * 0.45f
    val naturalFrequency = sqrt(200f)
    val dampedFrequency = naturalFrequency * sqrt(1f - 0.75f * 0.75f)
    val minimumKick = if (gesture.releaseProgress < 0.1f) 1.2f else 0f
    val initialVelocity = -(abs(gesture.releaseVelocity) * 0.2f).coerceIn(minimumKick, 10f)
    val displacement = initialVelocity / dampedFrequency * exp(-0.75f * naturalFrequency * seconds) * sin(dampedFrequency * seconds)
    return (1f + displacement).coerceIn(0.5f, 1f)
}
