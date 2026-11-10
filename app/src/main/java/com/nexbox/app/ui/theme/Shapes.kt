package com.nexbox.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 圆角 token 的唯一声明处，但不同设计系统不强制使用相同的组件形状。
 *
 * Material 组件优先读取自身 Defaults + MaterialTheme.shapes；Miuix 不读取 Material
 * 主题，因此使用下方语义 token。同一控件的裁剪与绘制必须使用同一个值。
 *
 * 新增屏幕请使用语义 token（[card]、[tile]、[chrome]），不要写 `RoundedCornerShape(x.dp)`。
 */
object NexBoxShapes {

    /**
     * 圆角的 Dp 原值。
     *
     * Miuix 组件不读 `MaterialTheme.shapes`，其 `cornerRadius` 参数只接受 Dp。
     * 为避免 Shape 与 Dp 两份值各自漂移，两者由同一组常量派生。
     */
    val extraSmallRadius = 4.dp
    val smallRadius = 8.dp
    val mediumRadius = 12.dp
    val largeRadius = 16.dp
    val extraLargeRadius = 28.dp

    /** 与 Material 3 形状刻度同序。改这里等于同时改 [material] 与所有 Miuix 分支。 */
    val extraSmall = RoundedCornerShape(extraSmallRadius)
    val small = RoundedCornerShape(smallRadius)
    val medium = RoundedCornerShape(mediumRadius)
    val large = RoundedCornerShape(largeRadius)
    val extraLarge = RoundedCornerShape(extraLargeRadius)

    /** Miuix 设置卡片；Material 设置卡仍使用 CardDefaults.shape（medium，12dp）。 */
    val settingsCardRadius = 20.dp
    val settingsCard = RoundedCornerShape(settingsCardRadius)
    /** Miuix 卡片以及应用自绘内容的语义 token。 */
    val card = large
    val tile = large
    val control = medium
    val badge = extraSmall
    val fab = large

    /** 与语义 Shape 配对的 Dp 值，供 Miuix 的 `cornerRadius` 参数使用。 */
    val controlRadius = mediumRadius
    val buttonRadius = largeRadius
    val choice = large
    val cardRadius = largeRadius

    /** 悬浮玻璃面板（当前无调用方，保留给后续浮动控件）。 */
    val chrome = large

    val pill = RoundedCornerShape(percent = 50)

    /** 玻璃轨道与跟手指示器均为真胶囊，圆角随各自高度变化。 */
    val navCapsule = pill

    /**
     * 与屏幕边缘齐平的导航 / 顶栏。
     *
     * 普通底栏与顶栏保持无圆角，浮起的玻璃底栏另用 [navCapsule]。
     * 两种底栏的内容避让由导航外壳统一预留，不由 Shape 改变页面测量。
     */
    val chromeEdge = RoundedCornerShape(0.dp)

    /** 传给 [androidx.compose.material3.MaterialTheme]。 */
    val material = Shapes(extraSmall, small, medium, large, extraLarge)
}

/**
 * 统一的不透明度刻度。
 *
 * 玻璃底色透太多会让背景穿透文字，不透又失去玻璃观感；深浅色需要不同取值，
 * 因此分开声明而不是在调用点写 `0.82f` 这类魔法数字。
 */
object NexBoxAlpha {
    /**
     * 玻璃面板底色。深浅色均使用对应皮肤的容器色，不固定成白色或黑色。
     *
     * 取值由「观感」与「可读性」两端夹出来：底色太实会让模糊后的内容被冲淡成一片
     * 纯色，滑动时看不出玻璃（0.76/0.82 时底栏就是一块灰板）；太透则底栏自身
     * 的图标文字会在花哨内容上失读。使用容器色而非页底色，保留 40% 的磨砂底色。
     */
    const val glassTintDark = 0.40f
    const val glassTintLight = 0.40f

    /**
     * 玻璃边缘高光。顶边受光、底边背光，是玻璃「有厚度」的主要线索——
     * 只有模糊和折射而没有这道边时，面板看起来仍像一块半透明贴纸。
     */
    const val glassRimTopLight = 0.70f
    const val glassRimTopDark = 0.24f
    const val glassRimBottomLight = 0.06f
    const val glassRimBottomDark = 0.20f

    /** 选中胶囊只轻压背景，拖动时进一步透出被折射的图标与文字。 */
    const val glassSelectionLight = 0.10f
    const val glassSelectionDark = 0.10f
    const val glassSelectionPressed = 0.03f
    const val glassShadow = 0.10f
    const val glassShadowDark = 0.20f
    const val glassInnerShadow = 0.15f

    /** 全屏媒体作为玻璃底图时的淡化程度（相册预览页）。 */
    const val mediaBackdrop = 0.24f

    /** 照片不遵循主题明暗，用深色托底保证白色徽标在亮图上仍可见。 */
    const val mediaBadgeScrim = 0.60f

    /** 禁用态前景的通用不透明度，用于 Material 侧替代 Miuix 的 disabled 角色。 */
    const val disabled = 0.38f

    /**
     * 开关关闭态轨道的透明度。
     *
     * Material 3 的关闭态靠「surfaceContainerHighest 轨道 + outline 描边」两件事成立，
     * 而 Miuix 的开关不画描边，且它的 surfaceContainerHighest 与卡片底色几乎相同——
     * 关闭态于是只剩一个孤零零的圆点，看不出那是个开关。把 onSurfaceVariant 压到这个
     * 透明度叠在卡片上，得到的中间调在深浅色下都与卡片拉开距离，且只用到当前皮肤自己的角色。
     */
    const val switchTrackOff = 0.32f
}
