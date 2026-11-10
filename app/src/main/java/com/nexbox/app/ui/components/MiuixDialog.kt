@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.nexbox.app.ui.components

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.kyant.shapes.RoundedRectangle
import com.nexbox.app.ui.theme.LocalNexBoxDarkTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal val LocalMiuixDialogMessage = staticCompositionLocalOf { false }

/** Resource-backed port of fan.miuix 1.0.12.4; see docs/MIUIX_DIALOG_REFERENCE.md. */
@Composable
internal fun MiuixDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier,
    dismissButton: (@Composable () -> Unit)?,
    icon: (@Composable () -> Unit)?,
    title: (@Composable () -> Unit)?,
    text: (@Composable () -> Unit)?,
    searchState: NexBoxSearchState?,
    contentScrollable: Boolean
) {
    val dark = LocalNexBoxDarkTheme.current
    val centered = useCenteredMiuixDialog()
    val dismissRequest by rememberUpdatedState(onDismissRequest)
    // Save only whether this presentation has already entered, not a lifecycle/resume event.
    // Recreated windows start at the final state; normal text/theme/size changes keep the animator.
    var entered by rememberSaveable { mutableStateOf(false) }
    val animateAsCentered = remember { centered }
    val panelProgress = remember { Animatable(if (entered) 1f else 0f) }
    val dimProgress = remember { Animatable(if (entered) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!entered) {
            entered = true
            withFrameNanos { }
            coroutineScope {
                launch { dimProgress.animateTo(1f, tween(300, easing = DialogDimEasing)) }
                if (animateAsCentered) {
                    // Folme's pad spring factors are damping=.8, response=.3 seconds (mass=1).
                    panelProgress.animateTo(1f, spring(dampingRatio = 0.8f,
                        stiffness = ((2.0 * PI / 0.3).pow(2)).toFloat()))
                } else {
                    panelProgress.animateTo(1f, tween(350, easing = DialogPhoneEasing))
                }
            }
        }
    }
    Dialog(
        onDismissRequest = { dismissRequest() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            // The content dispatcher owns search/IME-aware Back; the full-window scrim owns taps.
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        ConfigureMiuixDialogWindow(dark)
        CompositionLocalProvider(
            LocalGlassAllowed provides false,
            LocalNexBoxBackdrop provides null,
            LocalNavigationPadding provides PaddingValues(0.dp),
            // Compose Dialog is a ComponentDialog, not Miuix WindowDialog's NavigationEvent host.
            LocalSearchWindow provides false
        ) {
            BackHandler {
                if (searchState?.handleBack() != true) dismissRequest()
            }
            val density = LocalDensity.current
            val safeInsets = WindowInsets.safeDrawing.union(WindowInsets.ime)
            val bottomTravel = safeInsets.getBottom(density) + with(density) { 12.dp.toPx() }
            Box(Modifier.fillMaxSize()
                .semantics { dismiss { dismissRequest(); true } }) {
                Box(Modifier.fillMaxSize()
                    .graphicsLayer { alpha = dimProgress.value * if (dark) 0.6f else 0.3f }
                    .background(Color.Black)
                    .pointerInput(Unit) { detectTapGestures(onTap = { dismissRequest() }) }
                    .clearAndSetSemantics {})
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(safeInsets).padding(vertical = 12.dp)) {
                    val sideMargin = if (maxWidth < 360.dp) 8.dp else 12.dp
                    val panelWidth = if (maxWidth >= 394.dp) 370.dp else (maxWidth - sideMargin * 2).coerceAtLeast(0.dp)
                    val maximumHeight = with(density) { maxHeight.roundToPx() }
                    if (panelWidth > 0.dp) {
                        MiuixDialogPanel(
                            modifier = modifier.align(if (centered) Alignment.Center else Alignment.BottomCenter)
                                .width(panelWidth)
                                .graphicsLayer {
                                    val progress = panelProgress.value
                                    if (animateAsCentered) {
                                        val startScale = max(0.8f, 1f - 60f / max(size.width, size.height).coerceAtLeast(1f))
                                        scaleX = startScale + (1f - startScale) * progress
                                        scaleY = scaleX
                                        alpha = progress.coerceIn(0f, 1f)
                                    } else {
                                        translationY = (size.height + bottomTravel) * (1f - progress)
                                    }
                                },
                            // Keep text-field composition alive even if an IME momentarily uses
                            // the complete viewport; the outer viewport can legitimately be 0px.
                            maximumHeight = maximumHeight.coerceAtLeast(1),
                            dark = dark,
                            icon = icon,
                            title = title,
                            text = text,
                            contentScrollable = contentScrollable,
                            confirmButton = confirmButton,
                            dismissButton = dismissButton
                        )
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun ConfigureMiuixDialogWindow(dark: Boolean) {
    val view = LocalView.current
    val window = remember(view) {
        var node: android.view.ViewParent? = view.parent
        var owner = view as? DialogWindowProvider
        while (owner == null && node != null) {
            owner = node as? DialogWindowProvider
            node = node.parent
        }
        owner?.window
    }
    SideEffect {
        window?.let {
            // Only this Dialog's own window is touched; never fall back to the Activity window.
            WindowCompat.setDecorFitsSystemWindows(it, false)
            it.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            it.setDimAmount(0f)
            it.setWindowAnimations(0)
            // AndroidX owns softInputMode (ADJUST_NOTHING with decorFits=false on API31+).
            // The content consumes WindowInsets.ime; a second resize policy would race it.
            it.setBackgroundDrawableResource(android.R.color.transparent)
            it.decorView.elevation = 0f
            // A floating Dialog does not inherit the Activity's edge-to-edge/cutout policy.
            // Clearing only its bar colors leaves ROM contrast scrims or an inset status-bar
            // strip, notably on save-confirmation dialogs in light mode.
            it.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            it.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            it.attributes = it.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            it.statusBarColor = android.graphics.Color.TRANSPARENT
            it.navigationBarColor = android.graphics.Color.TRANSPARENT
            it.isStatusBarContrastEnforced = false
            it.isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}

@Composable
private fun useCenteredMiuixDialog(): Boolean {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val automotive = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    }
    return "tablet" in DialogRom.characteristics || "car" in DialogRom.characteristics || automotive ||
        (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_CAR ||
        // Android's device-size fallback for ROMs without the vendor tablet flag; no 840dp popup breakpoint.
        context.applicationContext.resources.configuration.smallestScreenWidthDp >= 600
}

private enum class DialogPart { Header, Body, Actions }

/**
 * The outer scroll is normally idle. It is the escape hatch for tiny IME windows / huge fonts.
 * The body ALWAYS receives a finite budget, including when it owns a LazyColumn or scroll itself.
 */
@Composable
private fun MiuixDialogPanel(
    modifier: Modifier,
    maximumHeight: Int,
    dark: Boolean,
    icon: (@Composable () -> Unit)?,
    title: (@Composable () -> Unit)?,
    text: (@Composable () -> Unit)?,
    contentScrollable: Boolean,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)?
) {
    val panelScroll = rememberScrollState()
    val bodyScroll = rememberScrollState()
    val shape = remember { RoundedRectangle(32.dp) }
    Layout(
        modifier = modifier.clip(shape)
            .background(if (dark) Color(0xFF242424) else Color.White)
            // Make blank panel space an opaque hit target without stealing child gestures.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .verticalScroll(panelScroll),
        content = {
            if (title != null || icon != null) {
                Column(Modifier.layoutId(DialogPart.Header).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top) {
                    NexBoxContentColor(if (dark) Color(0xE6FFFFFF) else Color.Black) {
                        icon?.invoke()
                        if (icon != null && title != null) Spacer(Modifier.height(14.dp))
                        CompositionLocalProvider(LocalTextStyle provides MiuixDialogTypography.Title) { title?.invoke() }
                    }
                }
            }
            if (text != null) {
                Box(Modifier.layoutId(DialogPart.Body).fillMaxWidth()
                    .then(if (contentScrollable) Modifier.verticalScroll(bodyScroll) else Modifier),
                    contentAlignment = Alignment.TopCenter) {
                    // Wrap-width plain messages center naturally. Wrapped text uses the full budget
                    // and start alignment; rich content keeps its own layout and explicit styles.
                    CompositionLocalProvider(LocalTextStyle provides MiuixDialogTypography.Message,
                        LocalMiuixDialogMessage provides true) {
                        NexBoxContentColor(if (dark) Color(0xCCFFFFFF) else Color(0xCC000000), text)
                    }
                }
            }
            MiuixDialogButtons(Modifier.layoutId(DialogPart.Actions), confirmButton, dismissButton)
        }
    ) { children, constraints ->
        val width = constraints.maxWidth
        val verticalPadding = 24.dp.roundToPx()
        val headerInset = 36.dp.roundToPx()
        val bodyInset = 28.dp.roundToPx()
        val actionsInset = 24.dp.roundToPx()
        val header = children.firstOrNull { it.layoutId == DialogPart.Header }
            ?.measure(Constraints(maxWidth = (width - headerInset * 2).coerceAtLeast(0)))
        val actions = children.first { it.layoutId == DialogPart.Actions }
            .measure(Constraints.fixedWidth((width - actionsInset * 2).coerceAtLeast(0)))
        val headerGap = if (header != null && header.height > 0) 16.dp.roundToPx() else 0
        val actionsGap = if (actions.height > 0) 8.dp.roundToPx() else 0
        val bodyChild = children.firstOrNull { it.layoutId == DialogPart.Body }
        val bodyGap = if (bodyChild != null) 16.dp.roundToPx() else 0
        val fixedHeight = verticalPadding * 2 + (header?.height ?: 0) + headerGap + actions.height + actionsGap + bodyGap
        val remaining = maximumHeight - fixedHeight
        // If fixed controls alone exceed the viewport, scroll the whole panel instead of leaving
        // the body with zero height. It still gets a finite viewport, never Infinity.
        val bodyBudget = if (remaining > 0) remaining else maximumHeight
        val body = bodyChild?.measure(Constraints(maxWidth = (width - bodyInset * 2).coerceAtLeast(0),
            maxHeight = bodyBudget.coerceAtLeast(1)))
        val height = fixedHeight + (body?.height ?: 0)
        layout(width, constraints.constrainHeight(height)) {
            var top = verticalPadding
            header?.let { it.placeRelative(headerInset, top); top += it.height + headerGap }
            body?.let { it.placeRelative(bodyInset, top); top += it.height + bodyGap }
            actions.placeRelative(actionsInset, top + actionsGap)
        }
    }
}

internal object MiuixDialogTypography {
    private val NativeText = PlatformTextStyle(includeFontPadding = true)
    val Title = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 18.sp, textAlign = TextAlign.Center, platformStyle = NativeText)
    val Message = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, textAlign = TextAlign.Start, platformStyle = NativeText)
    val Button = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 17.sp, textAlign = TextAlign.Center, platformStyle = NativeText)
}

// DimAnimator uses DecelerateInterpolator(1.5). PhoneDialogAnim uses the normalized
// EaseManager spring interpolator with damping=.88, response=.7 and a 350ms animator.
private val DialogDimEasing = Easing { fraction -> 1f - (1f - fraction).pow(3) }
private val DialogPhoneEasing = Easing { fraction ->
    val omega = 2.0 * PI / 0.7
    val decay = 0.88 * omega
    val frequency = omega * sqrt(1.0 - 0.88 * 0.88)
    (1.0 - exp(-decay * fraction) *
        (cos(frequency * fraction) + decay / frequency * sin(frequency * fraction))).toFloat()
}
