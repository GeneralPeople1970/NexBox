package com.nexbox.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.defaultTextStyles

/**
 * NexBox 字体刻度。
 *
 * 未在此处声明的角色沿用 Material 3 默认值。声明过的角色已内含字重，
 * 屏幕里不要再手写 `fontWeight = FontWeight.Bold` 做强调。
 *
 * 项目未打包自定义字体（无 res/font/），因此统一使用 [FontFamily.Default]。
 */
val Typography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

/** Miuix 保留原生字号；不能把其 18sp 小顶栏、17sp 正文压成 MD3 的 14sp 标签。 */
val MiuixTextStyles = defaultTextStyles()

val MiuixActionLabelStyle = TextStyle(fontFamily = FontFamily.Default, fontSize = 17.sp,
    lineHeight = 24.sp, fontWeight = FontWeight.Medium)

fun miuixChoiceLabelStyle(selected: Boolean) = TextStyle(fontFamily = FontFamily.Default,
    fontSize = 15.sp, lineHeight = 22.sp,
    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)

/** 同一皮肤下 facade 与原生控件使用同一文字刻度，但两种皮肤不必长得一样。 */
val MiuixTypography = Typography.copy(
    headlineLarge = MiuixTextStyles.title1,
    headlineMedium = MiuixTextStyles.title2,
    headlineSmall = MiuixTextStyles.title2,
    titleLarge = MiuixTextStyles.title3,
    titleMedium = MiuixTextStyles.headline1,
    titleSmall = MiuixTextStyles.subtitle,
    bodyLarge = MiuixTextStyles.main,
    bodyMedium = MiuixTextStyles.body2,
    bodySmall = MiuixTextStyles.footnote1,
    labelLarge = MiuixTextStyles.button,
    labelMedium = MiuixTextStyles.footnote1,
    labelSmall = MiuixTextStyles.footnote2
)

/** 悬浮导航是紧凑的图标标签，不继承各皮肤普通底栏的较大文字。 */
val LiquidNavigationLabelStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.sp
)
