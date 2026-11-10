package com.nexbox.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 统一的间距与尺寸刻度。
 *
 * 基础间距共用；皮肤专属布局由调用方选择，不能将 Miuix 的页面留白套到 Material 上。
 *
 * 新增屏幕时请使用此处的语义 token（如 [screenPadding]、[sectionGap]），
 * 不要直接写字面量 `.dp`。
 */
object NexBoxSpacing {

    /** 基础刻度 */
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp

    /** 语义 token */
    val screenPadding = lg
    val sectionGap = xl
    val rowGap = md
    val cardPadding = lg

    /** 尺寸 */
    val gridMinCell = 96.dp
    val appIcon = 52.dp
    val listIcon = 40.dp
    val touchTarget = 48.dp
    val searchHeight = 52.dp
    val buttonHorizontalPadding = 20.dp
    val choiceHorizontalPadding = 16.dp
    val userTabWidth = 112.dp
    val tabIndicatorHeight = 3.dp
    val aboutLogo = 88.dp
    /** Miuix 大标题布局；Material 页面使用 screenPadding 和原生 TopAppBar 的尺寸。 */
    val settingsPageInset = 12.dp
    val settingsTitleTop = 48.dp
    val settingsTitleBottom = 28.dp
    val settingsSectionGap = 24.dp

    /**
     * 导航尺寸。
     *
     * 两套皮肤的高度不同是各自设计语言的一部分，因此两个值都保留。
     */
    val navRailWidth = 88.dp
    val navBarHeightMiuix = 64.dp
    val navBarHeightMaterial = 80.dp

    /** 玻璃导航按内容收拢：短标签每项 76dp，两项连内边距共 160dp。 */
    val navBarHeightGlass = 64.dp
    val navGlassInset = xs
    val navGlassTabMinWidth = 76.dp
    val navGlassTabHorizontalPadding = md
    val navGlassHorizontalGap = md
    val navGlassVerticalGap = md
    val navGlassMaxWidth = 360.dp
    val navIcon = 24.dp
    val navLabelGap = 1.dp
}
