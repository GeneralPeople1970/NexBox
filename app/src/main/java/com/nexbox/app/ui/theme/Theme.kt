package com.nexbox.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexbox.app.data.AppearanceRepository
import com.nexbox.app.data.AppearanceSettings
import com.nexbox.app.data.ThemeMode
import com.nexbox.app.data.UiStyle
import com.nexbox.app.ui.components.NexBoxGlassRoot
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.LocalContentColor as MiuixLocalContentColor
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

val LocalAppearance = staticCompositionLocalOf { AppearanceSettings() }
val LocalNexBoxDarkTheme = staticCompositionLocalOf { false }

/**
 * Material 3 没有「禁用态前景」这一角色，而 Miuix 有（`disabledOnPrimaryButton`、
 * `disabledOnSecondaryVariant`、`disabledOnSurface`）。
 *
 * 该缺口此前由组件直接读 `MiuixTheme.colorScheme` 来填补，结果同一个组件同时从两套
 * 调色板取色——启用态来自 Material、禁用态来自 Miuix。改由 [NexBoxTheme] 按当前皮肤
 * 统一提供，组件只认这一个来源。
 */
@Immutable
data class NexBoxExtendedColors(
    val disabledContent: Color,
    val disabledOnPrimary: Color,
    val disabledOnSecondary: Color
)

val LocalNexBoxExtendedColors = staticCompositionLocalOf {
    NexBoxExtendedColors(Color.Unspecified, Color.Unspecified, Color.Unspecified)
}

private val DarkColorScheme = darkColorScheme(
    primary = NexBoxDarkPrimary,
    onPrimary = NexBoxDarkOnPrimary,
    primaryContainer = NexBoxDarkPrimaryContainer,
    onPrimaryContainer = NexBoxDarkOnPrimaryContainer,
    secondary = NexBoxDarkSecondary,
    onSecondary = NexBoxDarkOnSecondary,
    secondaryContainer = NexBoxDarkSecondaryContainer,
    onSecondaryContainer = NexBoxDarkOnSecondaryContainer,
    tertiary = NexBoxDarkTertiary,
    onTertiary = NexBoxDarkOnTertiary,
    tertiaryContainer = NexBoxDarkTertiaryContainer,
    onTertiaryContainer = NexBoxDarkOnTertiaryContainer,
    error = NexBoxDarkError,
    onError = NexBoxDarkOnError,
    errorContainer = NexBoxDarkErrorContainer,
    onErrorContainer = NexBoxDarkOnErrorContainer,
    background = NexBoxDarkBackground,
    onBackground = NexBoxDarkOnBackground,
    surface = NexBoxDarkSurface,
    onSurface = NexBoxDarkOnSurface,
    surfaceVariant = NexBoxDarkSurfaceVariant,
    onSurfaceVariant = NexBoxDarkOnSurfaceVariant,
    outline = NexBoxDarkOutline,
    outlineVariant = NexBoxDarkOutlineVariant,
    scrim = NexBoxDarkScrim,
    inverseSurface = NexBoxDarkInverseSurface,
    inverseOnSurface = NexBoxDarkInverseOnSurface,
    inversePrimary = NexBoxDarkInversePrimary,
    surfaceDim = NexBoxDarkSurfaceDim,
    surfaceBright = NexBoxDarkSurfaceBright,
    surfaceContainerLowest = NexBoxDarkSurfaceContainerLowest,
    surfaceContainerLow = NexBoxDarkSurfaceContainerLow,
    surfaceContainer = NexBoxDarkSurfaceContainer,
    surfaceContainerHigh = NexBoxDarkSurfaceContainerHigh,
    surfaceContainerHighest = NexBoxDarkSurfaceContainerHighest
)

private val LightColorScheme = lightColorScheme(
    primary = NexBoxLightPrimary,
    onPrimary = NexBoxLightOnPrimary,
    primaryContainer = NexBoxLightPrimaryContainer,
    onPrimaryContainer = NexBoxLightOnPrimaryContainer,
    secondary = NexBoxLightSecondary,
    onSecondary = NexBoxLightOnSecondary,
    secondaryContainer = NexBoxLightSecondaryContainer,
    onSecondaryContainer = NexBoxLightOnSecondaryContainer,
    tertiary = NexBoxLightTertiary,
    onTertiary = NexBoxLightOnTertiary,
    tertiaryContainer = NexBoxLightTertiaryContainer,
    onTertiaryContainer = NexBoxLightOnTertiaryContainer,
    error = NexBoxLightError,
    onError = NexBoxLightOnError,
    errorContainer = NexBoxLightErrorContainer,
    onErrorContainer = NexBoxLightOnErrorContainer,
    background = NexBoxLightBackground,
    onBackground = NexBoxLightOnBackground,
    surface = NexBoxLightSurface,
    onSurface = NexBoxLightOnSurface,
    surfaceVariant = NexBoxLightSurfaceVariant,
    onSurfaceVariant = NexBoxLightOnSurfaceVariant,
    outline = NexBoxLightOutline,
    outlineVariant = NexBoxLightOutlineVariant,
    scrim = NexBoxLightScrim,
    inverseSurface = NexBoxLightInverseSurface,
    inverseOnSurface = NexBoxLightInverseOnSurface,
    inversePrimary = NexBoxLightInversePrimary,
    surfaceDim = NexBoxLightSurfaceDim,
    surfaceBright = NexBoxLightSurfaceBright,
    surfaceContainerLowest = NexBoxLightSurfaceContainerLowest,
    surfaceContainerLow = NexBoxLightSurfaceContainerLow,
    surfaceContainer = NexBoxLightSurfaceContainer,
    surfaceContainerHigh = NexBoxLightSurfaceContainerHigh,
    surfaceContainerHighest = NexBoxLightSurfaceContainerHighest
)

/**
 * @param dynamicColor 默认关闭。Android 12+ 的设备上若开启动态取色，
 *   本项目的调色板（[NexBoxLightPrimary] 等）将完全不被使用，
 *   [Color.kt] 在新设备上会退化成死代码。保留该开关以便日后提供
 *   「跟随系统取色」选项，但默认必须走 NexBox 自有配色。
 */
@Composable
fun NexBoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    appearance: AppearanceSettings = AppearanceSettings(),
    content: @Composable () -> Unit
) {
    val baseColorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val miuixBase = if (darkTheme) miuixDarkColorScheme() else miuixLightColorScheme()
    val miuixColors = miuixBase.copy(
        primaryVariant = miuixBase.primary,
        primaryContainer = miuixBase.primary,
        onPrimaryContainer = miuixBase.onPrimary
    )
    // 配色规则：MIUIX 模式下 Miuix 调色板是色彩角色的唯一来源，MD3 模式下 NexBox 调色板是。
    // 组件永不直接读 MiuixTheme.colorScheme——否则同一个组件会同时从两套调色板取色，
    // 这正是此前 Miuix 皮肤「混乱」的根因。
    //
    // Miuix 没有 tertiary / onTertiary / surfaceDim / surfaceBright / surfaceContainerLow /
    // Lowest / inverse* / scrim 这些角色，缺失的按下表用同族替代，避免 NexBox 的蓝色残留
    // 在 Miuix 皮肤里。两套 provider 必须停留在同一 composition 位置，否则切换皮肤会销毁
    // 页面状态。
    val colorScheme = if (appearance.uiStyle == UiStyle.MIUIX) {
        baseColorScheme.copy(
            primary = miuixColors.primary,
            onPrimary = miuixColors.onPrimary,
            primaryContainer = miuixColors.primaryContainer,
            onPrimaryContainer = miuixColors.onPrimaryContainer,
            // Miuix 无独立 tertiary 角色，用 secondary 族代替，保证三色系不出现 NexBox 残余色。
            tertiary = miuixColors.secondary,
            onTertiary = miuixColors.onSecondary,
            tertiaryContainer = miuixColors.tertiaryContainer,
            onTertiaryContainer = miuixColors.onTertiaryContainer,
            secondary = miuixColors.secondary,
            onSecondary = miuixColors.onSecondary,
            // Miuix 的 tonal 按钮底色就是 secondaryVariant，成对使用它与其前景色；
            // 配成 secondaryContainer / onSecondaryVariant 会在浅色下失读。
            secondaryContainer = miuixColors.secondaryVariant,
            onSecondaryContainer = miuixColors.onSecondaryVariant,
            error = miuixColors.error,
            onError = miuixColors.onError,
            errorContainer = miuixColors.errorContainer,
            onErrorContainer = miuixColors.onErrorContainer,
            // Material 的 background 在宿主中表示页面基底；Miuix 的 Scaffold / 顶栏使用
            // surface。按用途桥接，保留浅灰（深色为黑色）页面与 surfaceContainer 卡片的层级。
            background = miuixColors.surface,
            onBackground = miuixColors.onSurface,
            surface = miuixColors.surface,
            onSurface = miuixColors.onSurface,
            surfaceVariant = miuixColors.surfaceVariant,
            onSurfaceVariant = miuixColors.onSurfaceVariantSummary,
            // Miuix 只有三档容器色。低档位就近取 surfaceContainer，避免留空后回退到 NexBox 色。
            surfaceContainerLowest = miuixColors.surface,
            surfaceContainerLow = miuixColors.surfaceContainer,
            surfaceContainer = miuixColors.surfaceContainer,
            surfaceContainerHigh = miuixColors.surfaceContainerHigh,
            surfaceContainerHighest = miuixColors.surfaceContainerHighest,
            surfaceDim = miuixColors.surfaceContainer,
            surfaceBright = miuixColors.surface,
            outline = miuixColors.outline,
            outlineVariant = miuixColors.dividerLine
            // inverseSurface / inverseOnSurface / inversePrimary / scrim 无 Miuix 对应角色，
            // 保持 NexBox 调色板：它们只用于 Snackbar 与遮罩，与 MIUI 视觉语言无关。
        )
    } else baseColorScheme

    val extendedColors = if (appearance.uiStyle == UiStyle.MIUIX) {
        NexBoxExtendedColors(
            disabledContent = miuixColors.disabledOnSurface,
            disabledOnPrimary = miuixColors.disabledOnPrimaryButton,
            disabledOnSecondary = miuixColors.disabledOnSecondaryVariant
        )
    } else {
        val disabled = colorScheme.onSurface.copy(alpha = NexBoxAlpha.disabled)
        NexBoxExtendedColors(disabled, disabled, disabled)
    }

    CompositionLocalProvider(
        LocalAppearance provides appearance,
        LocalNexBoxDarkTheme provides darkTheme,
        LocalNexBoxExtendedColors provides extendedColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = NexBoxShapes.material,
            typography = if (appearance.uiStyle == UiStyle.MIUIX) MiuixTypography else Typography
        ) {
            // MiuixTheme also writes Foundation's LocalIndication. Capture Material's ripple
            // before entering it, then select the active skin's indication below. Keeping
            // these providers in one stable slot preserves remembered page/scroll state.
            val materialIndication = LocalIndication.current
            MiuixTheme(colors = miuixColors, textStyles = MiuixTextStyles) {
                // Both native content locals default to black outside a Card/Surface.
                // Root colors cover independent windows; container/slot colors still override them.
                val indication = if (appearance.uiStyle == UiStyle.MIUIX) LocalIndication.current
                    else materialIndication
                CompositionLocalProvider(
                    LocalIndication provides indication,
                    LocalContentColor provides colorScheme.onBackground,
                    MiuixLocalContentColor provides colorScheme.onBackground,
                    content = content
                )
            }
        }
    }
}

/** Shared theme entry point for the main window and host-owned import/gallery windows. */
@Composable
fun NexBoxAppearanceHost(
    glassAllowed: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val repository = remember(context.applicationContext) { AppearanceRepository.get(context) }
    val appearance by repository.settings.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val dark = when (appearance.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    SideEffect {
        context.findActivity()?.let { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    NexBoxTheme(darkTheme = dark, appearance = appearance) {
        NexBoxGlassRoot(enabled = appearance.liquidGlassEnabled && glassAllowed, content = content)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.findActivity() else null
    else -> null
}
