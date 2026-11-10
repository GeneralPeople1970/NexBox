package com.nexbox.app.ui.components

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.shadow.Shadow
import com.kyant.backdrop.shadow.InnerShadow
import com.nexbox.app.ui.navigation.NavDestination
import com.nexbox.app.ui.theme.LocalNexBoxDarkTheme
import com.nexbox.app.ui.theme.LiquidNavigationLabelStyle
import com.nexbox.app.ui.theme.NexBoxAlpha
import com.nexbox.app.ui.theme.NexBoxShapes
import com.nexbox.app.ui.theme.NexBoxSpacing
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A glass track and one continuous, draggable lens shared by both skins.
 *
 * The gesture belongs to this fixed-size bar, never to its moving lens or the page. Horizontal
 * touch slop cancels the child tap before navigation; a drag previews locally and commits once
 * on release. The NavController remains the authority for the actual selected destination.
 *
 * Backdrop composition follows the Apache-2.0 Backdrop 1.0.6 catalog: record a non-interactive
 * accent-coloured row, then sample it through the lens. No InstallerX implementation is copied.
 */
@Composable
internal fun NexBoxLiquidNavigationBar(
    selectedDestination: NavDestination,
    onNavigate: (NavDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val destinations = NavDestination.entries
    val selectedIndex = destinations.indexOf(selectedDestination)
    val latestSelected by rememberUpdatedState(selectedIndex)
    val latestNavigate by rememberUpdatedState(onNavigate)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    var dragTravel by remember { mutableFloatStateOf(0f) }

    // Also handles back navigation or a programmatic tab change while a gesture is active.
    LaunchedEffect(selectedIndex) {
        dragging = false
        dragPosition = selectedIndex.toFloat()
        dragTravel = 0f
    }

    val targetPosition = if (dragging) dragPosition else selectedIndex.toFloat()
    val animatedPosition by animateFloatAsState(
        targetValue = targetPosition,
        animationSpec = spring(dampingRatio = 1f, stiffness = 1000f),
        visibilityThreshold = 0.001f,
        label = "liquid-navigation-position"
    )
    val pressProgress by animateFloatAsState(
        // InstallerX's release starts within 2.5% of the whole track, not at the position
        // spring's final precision. Waiting for 0.001 kept the enlarged rim alive too long.
        // Both pointer release and drag release follow this same settling policy.
        targetValue = if (dragging || isPressed ||
            abs(targetPosition - animatedPosition) > destinations.lastIndex * 0.025f) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 1000f),
        visibilityThreshold = 0.001f,
        label = "liquid-navigation-press"
    )
    // The reference separates fast optical feedback from the softer lens expansion.
    val lensScaleX by animateFloatAsState(
        targetValue = if (dragging || isPressed ||
            abs(targetPosition - animatedPosition) > destinations.lastIndex * 0.025f) 78f / 56f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 250f),
        visibilityThreshold = 0.001f,
        label = "liquid-navigation-scale-x"
    )
    val lensScaleY by animateFloatAsState(
        targetValue = if (dragging || isPressed ||
            abs(targetPosition - animatedPosition) > destinations.lastIndex * 0.025f) 78f / 56f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 250f),
        visibilityThreshold = 0.001f,
        label = "liquid-navigation-scale-y"
    )
    val backdrop = LocalNexBoxBackdrop.current
    val tabsBackdrop = rememberLayerBackdrop()
    val lensBackdrop = if (backdrop != null) rememberCombinedBackdrop(backdrop, tabsBackdrop) else tabsBackdrop
    val colors = MaterialTheme.colorScheme
    val dark = LocalNexBoxDarkTheme.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val inset = NexBoxSpacing.navGlassInset
    val insetPx = with(density) { inset.toPx() }
    val labels = destinations.map { stringResource(it.titleRes) }
    val textMeasurer = rememberTextMeasurer()
    val preferredWidth = remember(labels, density, textMeasurer) {
        val widestLabel = labels.maxOf { textMeasurer.measure(it, LiquidNavigationLabelStyle).size.width }
        val itemWidth = maxOf(
            NexBoxSpacing.navGlassTabMinWidth,
            with(density) { widestLabel.toDp() } + NexBoxSpacing.navGlassTabHorizontalPadding * 2
        )
        itemWidth * destinations.size + inset * 2
    }

    BoxWithConstraints(
        modifier.width(preferredWidth).height(NexBoxSpacing.navBarHeightGlass)
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val tabWidth = (maxWidth - inset * 2) / destinations.size
        val tabWidthPx = with(density) { tabWidth.toPx() }
        val maxPanelTravel = with(density) { NexBoxSpacing.xs.toPx() }
        val panelOffset by animateFloatAsState(
            targetValue = if (dragging) maxPanelTravel * dragTravel / (abs(dragTravel) + widthPx * 0.2f) else 0f,
            animationSpec = spring(dampingRatio = 1f, stiffness = 300f),
            label = "liquid-navigation-panel"
        )

        Box(
            Modifier.fillMaxSize().pointerInput(widthPx, insetPx, rtl, selectedIndex) {
                try {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragPosition = liquidTabPosition(offset.x, widthPx, insetPx, destinations.size, rtl)
                            dragTravel = 0f
                            dragging = true
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            // Only a claimed horizontal drag is consumed; vertical page scrolling
                            // and taps continue through their normal Compose handlers.
                            change.consume()
                            dragTravel += dragAmount
                            dragPosition = liquidTabPosition(change.position.x, widthPx, insetPx, destinations.size, rtl)
                        },
                        onDragEnd = {
                            val target = liquidTabIndex(dragPosition, destinations.size)
                            dragging = false
                            if (target != latestSelected) latestNavigate(destinations[target])
                        },
                        onDragCancel = {
                            dragging = false
                            dragPosition = latestSelected.toFloat()
                        }
                    )
                } finally {
                    // Resize/RTL changes can replace pointerInput during a gesture.
                    dragging = false
                }
            }
        ) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { translationX = panelOffset }
                    .nexBoxGlassChrome(colors.surfaceContainer, NexBoxShapes.navCapsule, layerBlock = {
                        // Pass scaling through Backdrop so it can invert the transform when
                        // sampling the page. The gesture detector above stays at its fixed size.
                        val scale = 1f + with(density) { NexBoxSpacing.lg.toPx() } / widthPx * pressProgress.coerceIn(0f, 1f)
                        scaleX = scale
                        scaleY = scale
                    })
            ) {
                LiquidNavigationItems(
                    modifier = Modifier.fillMaxSize().padding(inset).selectableGroup(),
                    selectedIndex = selectedIndex,
                    color = colors.onSurface,
                    interactionSource = interactionSource,
                    onSelect = { index ->
                        if (index != latestSelected) latestNavigate(destinations[index])
                    }
                )
            }

            // This layer is visual input only: no duplicate clickable nodes, focus targets,
            // or announcements. Alpha is outside the recording layer, as required by Backdrop.
            Box(
                Modifier.fillMaxSize().clearAndSetSemantics {}.alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .nexBoxGlassChrome(colors.surfaceContainer, NexBoxShapes.navCapsule)
            ) {
                LiquidNavigationItems(
                    modifier = Modifier.fillMaxSize().padding(inset),
                    selectedIndex = selectedIndex,
                    color = colors.primary,
                    magnification = { 1f + pressProgress.coerceIn(0f, 1f) * 0.2f }
                )
            }

            Box(
                Modifier
                    .align(AbsoluteAlignment.TopLeft)
                    .absoluteOffset {
                        val position = animatedPosition.coerceIn(0f, destinations.lastIndex.toFloat())
                        IntOffset(
                            (insetPx + liquidTabOffset(position, tabWidthPx, destinations.size, rtl) + panelOffset).roundToInt(),
                            insetPx.roundToInt()
                        )
                    }
                    .width(tabWidth)
                    .height(NexBoxSpacing.navBarHeightGlass - inset * 2)
                    .drawBackdrop(
                        backdrop = lensBackdrop,
                        shape = { NexBoxShapes.navCapsule },
                        effects = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val press = pressProgress.coerceIn(0f, 1f)
                                lens(
                                    NexBoxGlassEffect.indicatorRefractionHeight.toPx() * press,
                                    NexBoxGlassEffect.indicatorRefractionAmount.toPx() * press,
                                    chromaticAberration = true
                                )
                            }
                        },
                        highlight = null,
                        shadow = {
                            Shadow(
                                radius = NexBoxGlassEffect.shadowRadius,
                                offset = NexBoxGlassEffect.shadowOffset,
                                color = Color.Black.copy(alpha = NexBoxAlpha.glassShadow),
                                alpha = pressProgress.coerceIn(0f, 1f)
                            )
                        },
                        innerShadow = {
                            val press = pressProgress.coerceIn(0f, 1f)
                            InnerShadow(
                                radius = NexBoxGlassEffect.indicatorInnerShadowRadius * press,
                                color = Color.Black.copy(alpha = NexBoxAlpha.glassInnerShadow),
                                alpha = press
                            )
                        },
                        layerBlock = {
                            val stretch = abs(targetPosition - animatedPosition).coerceAtMost(0.12f)
                            scaleX = lensScaleX * (1f + stretch)
                            scaleY = lensScaleY / (1f + stretch * 0.6f)
                        },
                        onDrawSurface = {
                            val press = pressProgress.coerceIn(0f, 1f)
                            val restingAlpha = if (dark) NexBoxAlpha.glassSelectionDark else NexBoxAlpha.glassSelectionLight
                            fillClipped(
                                colors.onSurface.copy(alpha = restingAlpha * (1f - press) + NexBoxAlpha.glassSelectionPressed * press),
                                NexBoxShapes.navCapsule
                            )
                            drawGlassRim(NexBoxShapes.navCapsule, dark, press)
                        }
                    )
            )
        }
    }
}

@Composable
private fun LiquidNavigationItems(
    modifier: Modifier,
    selectedIndex: Int,
    color: Color,
    interactionSource: MutableInteractionSource? = null,
    onSelect: ((Int) -> Unit)? = null,
    magnification: () -> Float = { 1f }
) {
    val homeIcon = rememberNexBoxHomeIcon()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        NavDestination.entries.forEachIndexed { index, destination ->
            val target = if (onSelect != null) {
                Modifier.clip(NexBoxShapes.navCapsule)
                    .selectable(
                        selected = selectedIndex == index,
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Tab,
                        onClick = { onSelect(index) }
                    )
            } else Modifier
            Column(
                Modifier.weight(1f).fillMaxHeight().then(target).graphicsLayer {
                    scaleX = magnification()
                    scaleY = scaleX
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.navLabelGap, Alignment.CenterVertically)
            ) {
                Icon(if (destination == NavDestination.APPS) homeIcon else destination.selectedIcon,
                    contentDescription = null, tint = color,
                    modifier = Modifier.size(NexBoxSpacing.navIcon))
                Text(
                    text = stringResource(destination.titleRes),
                    color = color,
                    style = LiquidNavigationLabelStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
