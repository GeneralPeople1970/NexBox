package com.nexbox.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.nexbox.app.ui.icons.filled.ChevronRight
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar as MaterialTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.nexbox.app.R
import com.nexbox.app.ui.theme.NexBoxShapes
import com.nexbox.app.ui.theme.NexBoxSpacing
import top.yukonga.miuix.kmp.basic.SmallTopAppBar as MiuixSmallTopAppBar

/**
 * NexBox 共享界面组件。
 *
 * 这些组件取代了此前在 7 个屏幕里逐字复制的卡片、顶栏、导航行与空状态。
 * 新增屏幕请优先复用此处组件，不要重新手写 `CardDefaults.cardColors(...)`。
 */

/** Semantic settings scope; only Miuix consumes its iconless, spacious row layout. */
val LocalSettingsLayout = staticCompositionLocalOf { false }

/** Detail actions line up with the home FAB, including its bottom-navigation clearance. */
@Composable
internal fun homeActionBottomInset(): Dp = if (LocalConfiguration.current.screenWidthDp >= 600) 0.dp else maxOf(
    if (usesMiuix()) NexBoxSpacing.navBarHeightMiuix else NexBoxSpacing.navBarHeightMaterial,
    NexBoxSpacing.navBarHeightGlass + NexBoxSpacing.navGlassVerticalGap * 2
)

/** Settings is a semantic scope, not permission to apply Miuix geometry to Material. */
@Composable
internal fun usesMiuixSettingsLayout(): Boolean = LocalSettingsLayout.current && usesMiuix()

/**
 * Miuix 默认使用 surfaceContainer，Material 3 使用 surfaceContainerHigh，
 * 以匹配各自的页面 / 卡片层级；显式强调色仍保留其默认前景色映射。
 */
@Composable
fun NexBoxCard(
    modifier: Modifier = Modifier,
    containerColor: Color = if (usesMiuix()) MaterialTheme.colorScheme.surfaceContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = if (usesMiuix() && containerColor == MaterialTheme.colorScheme.surfaceContainer) {
        // Miuix 的 surfaceContainer 与 surfaceVariant 同色，Material 按颜色反查会误取
        // 次要文字色。仅为默认中性卡明确正文色，不覆盖错误 / 强调卡的配对颜色。
        CardDefaults.cardColors(containerColor = containerColor, contentColor = MaterialTheme.colorScheme.onSurface)
    } else CardDefaults.cardColors(containerColor = containerColor)
    Card(
        cornerRadius = if (usesMiuixSettingsLayout()) NexBoxShapes.settingsCardRadius else null,
        colors = colors,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

/**
 * 统一的顶栏。
 *
 * 顶栏始终是不透明表面——**液态玻璃只作用于底栏**（见 [NexBoxNavigationScaffold]）。
 *
 * 标题字重已由 [Typography] 的 `titleMedium`/`titleLarge` 承担，调用方不要再传
 * `fontWeight`。需要把标题替换成别的控件时（如搜索框）用 [titleContent]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexBoxTopAppBar(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    titleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val barColor = MaterialTheme.colorScheme.background
    val navigationIcon: @Composable () -> Unit = {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(id = R.string.navigate_back))
            }
        }
    }
    if (usesMiuix()) {
        if (titleContent == null) {
            MiuixSmallTopAppBar(title = title.orEmpty(), color = barColor,
                navigationIcon = navigationIcon, actions = actions)
        } else {
            Surface(color = barColor) {
                Row(Modifier.fillMaxWidth().statusBarsPadding()
                    .heightIn(min = NexBoxSpacing.touchTarget + NexBoxSpacing.lg),
                    verticalAlignment = Alignment.CenterVertically) {
                    navigationIcon()
                    Box(Modifier.weight(1f)) { titleContent() }
                    Row(content = actions)
                }
            }
        }
        return
    }
    MaterialTopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = barColor),
        title = {
            if (titleContent != null) {
                titleContent()
            } else if (title != null) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
            }
        },
        navigationIcon = navigationIcon,
        actions = actions
    )
}

/** 设置类页面里的分组标题。 */
@Composable
fun NexBoxSectionHeader(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(bottom = NexBoxSpacing.sm)
    )
}

/**
 * 卡片内的一行导航 / 开关项。
 *
 * @param trailing 右侧控件。传 null 时显示 [Icons.Default.ChevronRight]（即可点击跳转的语义）。
 *   需要放 Switch 等控件时显式传入。
 * @param showDivider 是否在本行之后画分隔线。分组内的最后一行传 false。
 */
@Composable
fun NexBoxNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val settings = usesMiuixSettingsLayout()
    ListItem(
        headlineContent = { Text(text = title, fontWeight = if (settings) FontWeight.Medium else null) },
        supportingContent = subtitle?.let { { Text(text = it) } },
        leadingContent = if (settings) null else { { Icon(imageVector = icon, contentDescription = null) } },
        trailingContent = trailing ?: {
            Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null,
                tint = if (settings) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        modifier = (if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = if (settings) NexBoxSpacing.xs else 0.dp)
    )
    if (showDivider && !settings) {
        HorizontalDivider()
    }
}

/**
 * 统一的空状态。Lazy 容器须提供 fillParentMaxSize 或 NexBoxEmptyStateArrangement；
 * 独立滚动空页使用 NexBoxScrollableEmptyState，避免无限高度约束使垂直居中失效。
 */
@Composable
fun NexBoxEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(NexBoxSpacing.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (supporting != null) {
            Spacer(modifier = Modifier.height(NexBoxSpacing.sm))
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (action != null) {
            Spacer(modifier = Modifier.height(NexBoxSpacing.lg))
            action()
        }
    }
}

/** 表示该应用已在当前分身内安装（已克隆）的小圆角标签。 */
@Composable
fun InstalledBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = NexBoxShapes.badge,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                horizontal = NexBoxSpacing.sm,
                vertical = NexBoxSpacing.xs
            )
        ) {
            Text(
                text = stringResource(id = R.string.installed_badge),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
