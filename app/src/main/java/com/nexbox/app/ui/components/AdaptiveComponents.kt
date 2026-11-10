@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.nexbox.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FabPosition
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.nexbox.app.data.UiStyle
import com.nexbox.app.ui.theme.LocalAppearance
import com.nexbox.app.ui.theme.LocalNexBoxDarkTheme
import com.nexbox.app.ui.theme.LocalNexBoxExtendedColors
import com.nexbox.app.ui.theme.NexBoxShapes
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.app.ui.theme.MiuixActionLabelStyle
import com.nexbox.app.ui.theme.miuixChoiceLabelStyle
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardColors as MiuixCardColors
import top.yukonga.miuix.kmp.basic.Checkbox as MiuixCheckbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator as MiuixCircularProgressIndicator
import top.yukonga.miuix.kmp.basic.FloatingActionButton as MiuixFloatingActionButton
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixHorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults as MiuixProgressDefaults
import top.yukonga.miuix.kmp.basic.Surface as MiuixSurface
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.SwitchDefaults as MiuixSwitchDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.extra.WindowBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.LocalContentColor as MiuixLocalContentColor

/** Small, intentionally bounded Material-like facade; business screens do not depend on a skin. */
@Composable
internal fun usesMiuix(): Boolean = LocalAppearance.current.uiStyle == UiStyle.MIUIX

internal val LocalNavigationPadding = staticCompositionLocalOf { PaddingValues(0.dp) }

@Composable
internal fun NexBoxContentColor(color: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides color, MiuixLocalContentColor provides color, content = content)
}

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    style: TextStyle = LocalTextStyle.current
) {
    if (usesMiuix()) {
        // A hard-broken short message must align to the body's start edge, not a centered
        // wrap-width block. Preserve explicit alignment/styles inside rich dialog content.
        val messageModifier = if (LocalMiuixDialogMessage.current && style == MiuixDialogTypography.Message &&
            textAlign == null && ('\n' in text || '\r' in text)) modifier.fillMaxWidth() else modifier
        MiuixText(text, messageModifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
            fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing,
            textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight,
            overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines, style = style)
    } else {
        androidx.compose.material3.Text(text, modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
            fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing,
            textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight,
            overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines, style = style)
    }
}

@Composable
fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = if (usesMiuix()) MiuixLocalContentColor.current else LocalContentColor.current
) {
    if (usesMiuix()) MiuixIcon(imageVector, contentDescription, modifier, tint)
    else androidx.compose.material3.Icon(imageVector, contentDescription, modifier, tint)
}

@Composable
fun Icon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = if (usesMiuix()) MiuixLocalContentColor.current else LocalContentColor.current
) {
    if (usesMiuix()) MiuixIcon(painter, contentDescription, modifier, tint)
    else androidx.compose.material3.Icon(painter, contentDescription, modifier, tint)
}

@Composable
fun IconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    if (usesMiuix()) MiuixIconButton(onClick,
        modifier.sizeIn(minWidth = NexBoxSpacing.touchTarget, minHeight = NexBoxSpacing.touchTarget)
            .then(if (enabled) Modifier else Modifier.semantics { disabled() }), enabled = enabled) {
        if (enabled) content() else NexBoxContentColor(LocalNexBoxExtendedColors.current.disabledContent, content)
    }
    else androidx.compose.material3.IconButton(onClick, modifier, enabled = enabled, content = content)
}

@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    if (usesMiuix()) {
        LocalMiuixDialogButtonRole.current?.let { role ->
            MiuixDialogButton(role, onClick, modifier, enabled, content)
            return
        }
        val foreground = if (enabled) MaterialTheme.colorScheme.onPrimary else LocalNexBoxExtendedColors.current.disabledOnPrimary
        MiuixButton(onClick, modifier, enabled = enabled, cornerRadius = NexBoxShapes.buttonRadius,
            minHeight = NexBoxSpacing.touchTarget,
            insideMargin = PaddingValues(horizontal = NexBoxSpacing.buttonHorizontalPadding, vertical = NexBoxSpacing.md),
            colors = MiuixButtonDefaults.buttonColors(
                MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))) {
            MiuixButtonLabel(foreground) { content() }
        }
    } else androidx.compose.material3.Button(onClick, modifier, enabled = enabled, content = content)
}

@Composable
fun FilledTonalButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    if (usesMiuix()) {
        LocalMiuixDialogButtonRole.current?.let { role ->
            MiuixDialogButton(role, onClick, modifier, enabled, content)
            return
        }
        val foreground = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else LocalNexBoxExtendedColors.current.disabledOnSecondary
        MiuixButton(onClick, modifier, enabled = enabled, cornerRadius = NexBoxShapes.buttonRadius,
            minHeight = NexBoxSpacing.touchTarget,
            insideMargin = PaddingValues(horizontal = NexBoxSpacing.buttonHorizontalPadding, vertical = NexBoxSpacing.md)) {
            MiuixButtonLabel(foreground) { content() }
        }
    } else androidx.compose.material3.FilledTonalButton(onClick, modifier, enabled = enabled, content = content)
}

/** Destructive actions keep the existing secondary-button shape and a neutral surface. */
@Composable
fun DestructiveButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    if (usesMiuix()) {
        if (LocalMiuixDialogButtonRole.current != null) {
            MiuixDialogButton(MiuixDialogButtonRole.Destructive, onClick, modifier, enabled, content)
            return
        }
        val foreground = if (enabled) scheme.error else LocalNexBoxExtendedColors.current.disabledContent
        MiuixButton(onClick, modifier, enabled = enabled, cornerRadius = NexBoxShapes.buttonRadius,
            minHeight = NexBoxSpacing.touchTarget,
            insideMargin = PaddingValues(horizontal = NexBoxSpacing.buttonHorizontalPadding, vertical = NexBoxSpacing.md),
            colors = MiuixButtonDefaults.buttonColors(MiuixTheme.colorScheme.secondaryVariant)) {
            MiuixButtonLabel(foreground) { content() }
        }
    } else androidx.compose.material3.FilledTonalButton(onClick, modifier, enabled = enabled,
        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
            containerColor = scheme.surfaceContainerHighest,
            contentColor = scheme.error
        ), content = content)
}

@Composable
fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    if (usesMiuix()) {
        FilledTonalButton(onClick, modifier, enabled, content)
    } else androidx.compose.material3.OutlinedButton(onClick, modifier, enabled = enabled, content = content)
}

@Composable
fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    if (usesMiuix()) {
        LocalMiuixDialogButtonRole.current?.let { role ->
            MiuixDialogButton(role, onClick, modifier, enabled, content)
            return
        }
        val scheme = MaterialTheme.colorScheme
        val foreground = if (enabled) scheme.primary
            else LocalNexBoxExtendedColors.current.disabledContent
        // Miuix TextButton only takes a String; Button retains arbitrary icons/label slots.
        MiuixButton(onClick, modifier, enabled = enabled, minWidth = NexBoxSpacing.touchTarget,
            minHeight = NexBoxSpacing.touchTarget, cornerRadius = NexBoxShapes.buttonRadius,
            insideMargin = PaddingValues(horizontal = NexBoxSpacing.choiceHorizontalPadding, vertical = NexBoxSpacing.md),
            colors = MiuixButtonDefaults.buttonColors(Color.Transparent)) {
            MiuixButtonLabel(foreground) { content() }
        }
    } else androidx.compose.material3.TextButton(onClick, modifier, enabled = enabled, content = content)
}

@Composable
private fun MiuixButtonLabel(color: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTextStyle provides MiuixActionLabelStyle) {
        NexBoxContentColor(color, content)
    }
}

@Composable
fun Checkbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    if (usesMiuix()) {
        MiuixCheckbox(
            state = ToggleableState(checked),
            onClick = if (onCheckedChange != null) { { onCheckedChange(!checked) } } else null,
            modifier = modifier,
            enabled = enabled
        )
    } else androidx.compose.material3.Checkbox(checked, onCheckedChange, modifier, enabled)
}

@Composable
fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    if (usesMiuix()) {
        // Keep the established enabled-state colors, size and interaction unchanged.
        // Both disabled states follow the reference SlidingButton's actual selectors.
        // Resource/selector provenance: docs/MIUIX_SWITCH_REFERENCE.md.
        val dark = LocalNexBoxDarkTheme.current
        val scheme = MaterialTheme.colorScheme
        MiuixSwitch(checked, onCheckedChange, modifier, enabled = enabled,
            colors = MiuixSwitchDefaults.switchColors(
                checkedThumbColor = scheme.onPrimary,
                uncheckedThumbColor = Color.White,
                disabledCheckedThumbColor = if (dark) Color(0x4DFFFFFF) else Color(0xB3FFFFFF),
                disabledUncheckedThumbColor = if (dark) Color(0x40FFFFFF) else Color(0xCCFFFFFF),
                checkedTrackColor = scheme.primary,
                uncheckedTrackColor = if (dark) Color(0x33FFFFFF) else Color(0x19000000),
                disabledCheckedTrackColor = scheme.primary.copy(alpha = 0.3f),
                disabledUncheckedTrackColor = if (dark) Color(0x21FFFFFF) else Color(0x0D000000)
            ))
    } else androidx.compose.material3.Switch(checked, onCheckedChange, modifier, enabled = enabled)
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    cornerRadius: Dp? = null,
    colors: CardColors = CardDefaults.cardColors(),
    content: @Composable ColumnScope.() -> Unit
) {
    if (usesMiuix()) {
        val radius = cornerRadius ?: NexBoxShapes.cardRadius
        val shape = RoundedCornerShape(radius)
        NexBoxContentColor(colors.contentColor) {
            // cornerRadius 必须显式传给 MiuixCard：它自己画的圆角取自 CardDefaults，
            // 与调用方 clip 的圆角不一定是同一个数，此前卡片会出现裁剪圆角与绘制圆角
            // 不一致。两者现在同取一个参数。
            MiuixCard(modifier.clip(shape), cornerRadius = radius, insideMargin = PaddingValues(0.dp),
                colors = MiuixCardColors(colors.containerColor, colors.contentColor), content = content)
        }
    } else androidx.compose.material3.Card(modifier,
        shape = cornerRadius?.let { RoundedCornerShape(it) } ?: CardDefaults.shape,
        colors = colors, content = content)
}

@Composable
fun Surface(
    modifier: Modifier = Modifier,
    shape: Shape = NexBoxShapes.chromeEdge,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    border: BorderStroke? = null,
    shadowElevation: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    if (usesMiuix()) {
        NexBoxContentColor(contentColor) {
            MiuixSurface(modifier, shape, color, contentColor, border, shadowElevation, content)
        }
    } else androidx.compose.material3.Surface(modifier, shape = shape, color = color,
        contentColor = contentColor, border = border, shadowElevation = shadowElevation, content = content)
}

@Composable
fun ListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    if (usesMiuix()) {
        val endActions: (@Composable RowScope.() -> Unit)? = trailingContent?.let { trailing -> { trailing() } }
        BasicComponent(modifier = modifier, startAction = leadingContent, endActions = endActions) {
            overlineContent?.invoke()
            headlineContent()
            if (supportingContent != null) {
                CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall) {
                    NexBoxContentColor(MaterialTheme.colorScheme.onSurfaceVariant, supportingContent)
                }
            }
        }
    } else androidx.compose.material3.ListItem(
        headlineContent, modifier, overlineContent, supportingContent, leadingContent, trailingContent,
        // Inherit the enclosing Material card/sheet surface without painting a second layer.
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
fun HorizontalDivider(modifier: Modifier = Modifier, thickness: Dp = 1.dp, color: Color = MaterialTheme.colorScheme.outlineVariant) {
    // 此前无论哪套皮肤都走 Material 实现，Miuix 皮肤下卡片里的分隔线与其余控件不一致。
    if (usesMiuix()) MiuixHorizontalDivider(modifier, thickness, color)
    else androidx.compose.material3.HorizontalDivider(modifier, thickness, color)
}

/**
 * The layout node is deliberately shared by both skins. Changing UI style must not dispose
 * a page's scroll/input state. Only the visible controls switch implementations.
 */
@Composable
fun Scaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit
) {
    val navigationPadding = LocalNavigationPadding.current
    val direction = LocalLayoutDirection.current
    NexBoxBackdropScene {
        NexBoxContentColor(contentColor) {
            androidx.compose.material3.Scaffold(
                modifier = modifier.imePadding(), topBar = topBar, bottomBar = bottomBar,
                snackbarHost = {
                    Box(Modifier.padding(bottom = navigationPadding.calculateBottomPadding())) { snackbarHost() }
                },
                floatingActionButton = {
                    Box(Modifier.padding(bottom = navigationPadding.calculateBottomPadding())) { floatingActionButton() }
                },
                floatingActionButtonPosition = floatingActionButtonPosition, containerColor = containerColor,
                contentColor = contentColor, contentWindowInsets = contentWindowInsets
            ) { padding ->
                val combined = PaddingValues(
                    start = padding.calculateStartPadding(direction) + navigationPadding.calculateStartPadding(direction),
                    top = padding.calculateTopPadding() + navigationPadding.calculateTopPadding(),
                    end = padding.calculateEndPadding(direction) + navigationPadding.calculateEndPadding(direction),
                    bottom = padding.calculateBottomPadding() + navigationPadding.calculateBottomPadding()
                )
                // The source excludes top bars, FABs and navigation, avoiding recursive sampling.
                Box(Modifier.fillMaxSize().recordNexBoxBackdrop().background(containerColor)) { content(combined) }
            }
        }
    }
}

@Composable
fun FloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    content: @Composable () -> Unit
) {
    // 浮动按钮不使用玻璃。
    //
    // 小面积控件上，backdrop 采样到的背景位置会偏离控件实际所在区域：实测按钮中心
    // 反推出的采样源接近黑色，而它背后其实是浅色的空白页，于是渲染出一块偏暗的
    // 渐变，看上去像控件重影。模糊对小按钮本身也没有观感收益。
    // 玻璃仅作用于底部导航栏。
    val shape = NexBoxShapes.fab
    if (usesMiuix()) {
        MiuixFloatingActionButton(onClick, modifier, shape, containerColor = containerColor) {
            NexBoxContentColor(contentColor, content)
        }
    } else {
        androidx.compose.material3.FloatingActionButton(onClick, modifier,
            containerColor = containerColor, contentColor = contentColor, content = content)
    }
}

@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1
) {
    if (usesMiuix()) {
        Column(modifier) {
            if (label != null) {
                Box(Modifier.padding(start = NexBoxSpacing.xs, bottom = NexBoxSpacing.xs)) { label() }
            }
            Box {
                MiuixTextField(value, onValueChange, Modifier.fillMaxWidth(), enabled = enabled,
                    readOnly = readOnly, textStyle = textStyle, keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
                    singleLine = singleLine, maxLines = maxLines, minLines = minLines,
                    visualTransformation = visualTransformation,
                    borderColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                if (value.isEmpty() && placeholder != null) {
                    Box(Modifier.align(Alignment.CenterStart).padding(start = if (leadingIcon == null) NexBoxSpacing.lg else NexBoxSpacing.touchTarget)) {
                        NexBoxContentColor(MaterialTheme.colorScheme.onSurfaceVariant, placeholder)
                    }
                }
            }
            supportingText?.invoke()
        }
    } else androidx.compose.material3.OutlinedTextField(value, onValueChange, modifier, enabled, readOnly,
        textStyle, label, placeholder, leadingIcon, trailingIcon, supportingText = supportingText,
        isError = isError, visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions, singleLine = singleLine, maxLines = maxLines, minLines = minLines)
}

@Composable
fun CircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 4.dp,
    progress: (() -> Float)? = null
) {
    if (usesMiuix()) MiuixCircularProgressIndicator(modifier = modifier, strokeWidth = strokeWidth,
        progress = progress?.invoke(),
        colors = MiuixProgressDefaults.progressIndicatorColors(foregroundColor = color))
    else if (progress == null) androidx.compose.material3.CircularProgressIndicator(modifier, color, strokeWidth)
    else androidx.compose.material3.CircularProgressIndicator(progress = progress, modifier = modifier,
        color = color, strokeWidth = strokeWidth)
}

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    searchState: NexBoxSearchState? = null,
    // Set false when text owns a LazyColumn/verticalScroll. MIUIx will give it a finite viewport.
    contentScrollable: Boolean = true
) {
    if (usesMiuix()) {
        MiuixDialog(onDismissRequest, confirmButton, modifier, dismissButton, icon, title, text,
            searchState, contentScrollable)
    } else {
        // 与 Miuix 分支同理：对话框渲染在独立窗口，采样不到主窗口背景。
        CompositionLocalProvider(LocalGlassAllowed provides false) {
            androidx.compose.material3.AlertDialog(onDismissRequest, confirmButton, modifier,
                dismissButton, icon, title, text,
                properties = DialogProperties(dismissOnBackPress = searchState?.active != true))
        }
    }
}

@Composable
fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit
) {
    if (usesMiuix()) {
        val show = remember { mutableStateOf(true) }
        WindowBottomSheet(show = show, modifier = modifier, onDismissRequest = onDismissRequest) {
            // WindowBottomSheet paints its background without supplying a content color.
            CompositionLocalProvider(LocalGlassAllowed provides false) {
                NexBoxContentColor(MiuixTheme.colorScheme.onBackground) { Column(content = content) }
            }
        }
    } else {
        // 与 Miuix 分支同理：底部弹窗渲染在独立窗口，采样不到主窗口背景。
        CompositionLocalProvider(LocalGlassAllowed provides false) {
            androidx.compose.material3.ModalBottomSheet(onDismissRequest, modifier, sheetState = sheetState, content = content)
        }
    }
}

/**
 * Miuix 分段控件要把每个页签等宽分布，而 M3 的 `PrimaryTabRow` 不向 `tabs` 槽暴露
 * `RowScope`。用一个私有 CompositionLocal 把 Miuix 分支的 RowScope 传给 [Tab]。
 */
private val LocalMiuixTabRowScope = staticCompositionLocalOf<RowScope?> { null }

@Composable
fun PrimaryTabRow(selectedTabIndex: Int, modifier: Modifier = Modifier, tabs: @Composable () -> Unit) {
    if (usesMiuix()) {
        // 此前 Miuix 分支是「横向滚动的按钮条」（horizontalScroll + MiuixButton），读起来
        // 是另一个控件而不是另一个皮肤。改为分段控件：胶囊容器 + 选中项主色实底，
        // 与 Material 的 indicator 语义对齐。
        Surface(
            modifier = modifier.fillMaxWidth()
                .padding(horizontal = NexBoxSpacing.md, vertical = NexBoxSpacing.sm),
            shape = NexBoxShapes.choice,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(Modifier.fillMaxWidth().selectableGroup().padding(NexBoxSpacing.xs)) {
                val scope = this
                CompositionLocalProvider(LocalMiuixTabRowScope provides scope) { tabs() }
            }
        }
    } else androidx.compose.material3.PrimaryTabRow(selectedTabIndex, modifier, tabs = tabs)
}

@Composable
fun Tab(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, text: (@Composable () -> Unit)? = null) {
    if (usesMiuix()) {
        val scope = LocalMiuixTabRowScope.current
        val container = if (selected && enabled) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
        val content = when {
            !enabled -> LocalNexBoxExtendedColors.current.disabledContent
            selected -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        }
        val tabModifier = (if (scope != null) with(scope) { modifier.weight(1f) } else modifier)
            .semantics { role = Role.Tab; this.selected = selected }
        MiuixButton(onClick, tabModifier,
            enabled = enabled, minWidth = 0.dp, minHeight = NexBoxSpacing.touchTarget,
            cornerRadius = NexBoxShapes.buttonRadius,
            insideMargin = PaddingValues(horizontal = NexBoxSpacing.choiceHorizontalPadding, vertical = NexBoxSpacing.md),
            colors = MiuixButtonDefaults.buttonColors(container)) {
            CompositionLocalProvider(LocalTextStyle provides miuixChoiceLabelStyle(selected)) {
                NexBoxContentColor(content) { text?.invoke() }
            }
        }
    } else androidx.compose.material3.Tab(selected, onClick, modifier, enabled, text = text)
}
