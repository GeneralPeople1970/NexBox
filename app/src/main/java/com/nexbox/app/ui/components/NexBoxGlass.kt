package com.nexbox.app.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import com.nexbox.app.ui.theme.LocalNexBoxDarkTheme
import com.nexbox.app.ui.theme.NexBoxAlpha
import com.nexbox.app.ui.theme.NexBoxShapes

internal val LocalNexBoxBackdrop = compositionLocalOf<LayerBackdrop?> { null }
private val LocalGlassEnabled = staticCompositionLocalOf { false }
private val LocalBackdropScenes = staticCompositionLocalOf<BackdropScenes?> { null }

/** A leaving navigation destination must never clear the incoming destination's source. */
private class BackdropScenes {
    private val sources = mutableStateListOf<LayerBackdrop>()
    val current: LayerBackdrop? get() = sources.lastOrNull()
    fun attach(source: LayerBackdrop) { sources.add(source) }
    fun detach(source: LayerBackdrop) { sources.remove(source) }
}

/** Native MapView/SurfaceView/video windows opt out, without changing the saved glass choice. */
val LocalGlassAllowed = staticCompositionLocalOf { true }

@Composable
fun NexBoxGlassRoot(enabled: Boolean, content: @Composable () -> Unit) {
    val scenes = remember { BackdropScenes() }
    CompositionLocalProvider(
        LocalGlassEnabled provides enabled,
        LocalBackdropScenes provides scenes,
        LocalNexBoxBackdrop provides if (enabled) scenes.current else null,
        content = content
    )
}

/** One source per visible page, never per list item; no layer is allocated while disabled. */
@Composable
internal fun NexBoxBackdropScene(content: @Composable () -> Unit) {
    val scenes = LocalBackdropScenes.current
    val source = if (isNexBoxGlassActive()) rememberLayerBackdrop() else null
    DisposableEffect(scenes, source) {
        if (source != null) scenes?.attach(source)
        onDispose { if (source != null) scenes?.detach(source) }
    }
    CompositionLocalProvider(LocalNexBoxBackdrop provides source, content = content)
}

@Composable
internal fun Modifier.recordNexBoxBackdrop(): Modifier {
    val backdrop = LocalNexBoxBackdrop.current
    return if (backdrop != null && LocalGlassAllowed.current) layerBackdrop(backdrop) else this
}

@Composable
fun isNexBoxGlassActive(): Boolean = LocalGlassEnabled.current && LocalGlassAllowed.current

/**
 * 玻璃特效自身的参数。
 *
 * 放在效果旁边而不是 [com.nexbox.app.ui.theme.NexBoxSpacing]：这些值只被玻璃绘制组件消费，
 * 不属于布局刻度。
 */
internal object NexBoxGlassEffect {
    val blurRadius = 6.dp
    // lens 的第一个参数是折射带宽，不是位移。12dp 会把小底栏画成一圈厚边。
    val refractionHeight = 4.dp
    val refractionAmount = 8.dp
    val shadowRadius = 10.dp
    val shadowOffset = DpOffset(0.dp, 2.dp)

    val indicatorRefractionHeight = 10.dp
    val indicatorRefractionAmount = 14.dp
    val indicatorInnerShadowRadius = 8.dp

    /** 玻璃边缘高光的可见宽度。 */
    val rimWidth = 0.5.dp
}

/**
 * 按 [shape] 填充颜色。
 *
 * `drawBackdrop` 的 `onDrawSurface` 拿到的是原始 [DrawScope]，库不会替调用方裁剪，
 * 直接 `drawRect(color)` 会画出一个未裁剪的矩形。满宽矩形的表面看不出来，但任何
 * 带圆角的表面（如浮起的底栏胶囊）四角都会露出一块方形底色。
 */
internal fun DrawScope.fillClipped(color: Color, shape: Shape) {
    drawPath(pathOf(shape), color)
}

/** [shape] 在当前尺寸下的轮廓路径。同上，`onDrawSurface` 不会替调用方裁剪。 */
private fun DrawScope.pathOf(shape: Shape): Path =
    when (val outline = shape.createOutline(size, layoutDirection, this)) {
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Generic -> outline.path
    }

/**
 * 玻璃边缘高光。
 *
 * 顶边受光、底边背光，模拟一块有厚度的玻璃边缘。**必须裁剪到轮廓内**，否则
 * 描边会有一半落在形状外，在圆角处形成一圈毛边。
 *
 * @param dark 当前是否为深色主题，决定顶边与底边各自的高光强度。
 */
internal fun DrawScope.drawGlassRim(shape: Shape, dark: Boolean, strength: Float = 1f) {
    val path = pathOf(shape)
    val stroke = NexBoxGlassEffect.rimWidth.toPx()
    // 描边以路径为中心向两侧各铺一半，裁剪后可见宽度正好是 rimWidth。
    clipPath(path) {
        drawPath(
            path = path,
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = strength * (if (dark) NexBoxAlpha.glassRimTopDark else NexBoxAlpha.glassRimTopLight)),
                0.45f to Color.White.copy(alpha = 0f),
                1f to Color.Black.copy(alpha = strength * (if (dark) NexBoxAlpha.glassRimBottomDark else NexBoxAlpha.glassRimBottomLight))
            ),
            style = Stroke(width = stroke * 2)
        )
    }
}

/**
 * 玻璃承载区（当前只有底栏）的唯一入口，见 [NexBoxNavigationScaffold]。
 *
 * 契约：本修饰符只改变绘制，绝不影响测量。底栏在普通 / 玻璃两种状态下的形态
 * 差异由调用方决定，并落在统一预留的占位之内；按压形变同样不改变内容避让。
 *
 * 关闭玻璃或当前页面不可采样时，本修饰符自动退化为不透明 [background]，
 * 调用方不要再写 `if (glass) … else Modifier`。
 */
@Composable
fun Modifier.nexBoxGlassChrome(
    color: Color = MaterialTheme.colorScheme.surface,
    shape: Shape = NexBoxShapes.chromeEdge,
    refraction: Boolean = true,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null
): Modifier = nexBoxGlassSurface(color = color, shape = shape, refraction = refraction, layerBlock = layerBlock)

/**
 * Applies effects to the sampled background only; text and hit targets remain unfiltered.
 *
 * 采样是**逐帧**的：页面内容滚动时，落在 [shape] 身后的部分会被重新采样、模糊并折射，
 * 这就是液态玻璃在滑动时最明显的观感来源。底色不透明度必须留出足够的透光量，
 * 否则模糊结果会被底色冲淡成一片纯色，滑动时看不出任何变化。
 *
 * @param refraction 是否叠加折射（lens）。折射的作用深度是按大面积面板设计的，
 *   用在小控件上（如浮动按钮）会形成一圈可见的内边界，看上去像控件重影。
 *   小控件传 false，只保留模糊。
 */
@Composable
fun Modifier.nexBoxGlassSurface(
    color: Color = MaterialTheme.colorScheme.surface,
    shape: Shape = NexBoxShapes.chrome,
    refraction: Boolean = true,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null
): Modifier {
    val backdrop = LocalNexBoxBackdrop.current
    if (backdrop == null || !LocalGlassAllowed.current) return background(color, shape)
    val dark = LocalNexBoxDarkTheme.current
    // A translucent-enough tint keeps controls legible over high-contrast photos without
    // flattening the blur behind it into a solid colour.
    val tint = remember(color, dark) {
        color.copy(alpha = if (dark) NexBoxAlpha.glassTintDark else NexBoxAlpha.glassTintLight)
    }
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(NexBoxGlassEffect.blurRadius.toPx())
            if (refraction &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                shape is CornerBasedShape
            ) {
                lens(NexBoxGlassEffect.refractionHeight.toPx(), NexBoxGlassEffect.refractionAmount.toPx())
            }
        },
        // drawBackdrop 默认还会叠一层高光和 24dp 阴影；这里由窄 rim 唯一负责边缘。
        highlight = null,
        layerBlock = layerBlock,
        shadow = {
            Shadow(
                radius = NexBoxGlassEffect.shadowRadius,
                offset = NexBoxGlassEffect.shadowOffset,
                color = Color.Black.copy(alpha = if (dark) NexBoxAlpha.glassShadowDark else NexBoxAlpha.glassShadow)
            )
        },
        onDrawSurface = {
            fillClipped(tint, shape)
            drawGlassRim(shape, dark)
        }
    )
}
