package com.nexbox.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import com.nexbox.app.R
import com.nexbox.app.ui.theme.NexBoxShapes
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.app.ui.theme.miuixChoiceLabelStyle
import kotlinx.coroutines.flow.first

data class UserTab(val id: Int, val name: String?)

/** Selection is a stable ID; display names and long-press actions remain independent. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NexBoxUserBar(
    users: List<UserTab>,
    selectedUserId: Int,
    onSelectUser: (Int) -> Unit,
    onManageUser: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val miuix = usesMiuix()
    val ids = users.map { it.id }
    var selectionRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(selectedUserId, ids, selectionRequest, density) {
        val index = ids.indexOf(selectedUserId)
        if (index < 0) return@LaunchedEffect
        val layout = snapshotFlow { listState.layoutInfo }.first {
            it.totalItemsCount == ids.size && it.visibleItemsInfo.isNotEmpty() &&
                it.viewportEndOffset > it.viewportStartOffset
        }
        val itemWidth = with(density) { NexBoxSpacing.userTabWidth.roundToPx() }
        val spacing = with(density) { NexBoxSpacing.sm.roundToPx() }
        val stride = itemWidth + spacing
        val viewportWidth = layout.viewportEndOffset - layout.viewportStartOffset
        val contentWidth = ids.size * itemWidth + (ids.size - 1) * spacing +
            layout.beforeContentPadding + layout.afterContentPadding
        val targetScroll = (layout.beforeContentPadding + index * stride + itemWidth / 2f -
            viewportWidth / 2f).coerceIn(0f, (contentWidth - viewportWidth).coerceAtLeast(0).toFloat())
        val anchor = layout.visibleItemsInfo.first()
        val currentScroll = anchor.index * stride - anchor.offset
        listState.animateScrollBy(targetScroll - currentScroll)
    }
    if (users.isEmpty()) return
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth().selectableGroup(),
        contentPadding = PaddingValues(horizontal = NexBoxSpacing.screenPadding, vertical = NexBoxSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(NexBoxSpacing.sm)
    ) {
        items(users, key = { it.id }) { user ->
            val isSelected = user.id == selectedUserId
            Surface(shape = if (miuix) NexBoxShapes.choice else NexBoxShapes.chromeEdge,
                color = if (!miuix) Color.Transparent else if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = when {
                    !isSelected -> if (miuix) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    miuix -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.primary
                }
            ) {
                Box(
                    modifier = Modifier.width(NexBoxSpacing.userTabWidth)
                        .heightIn(min = NexBoxSpacing.touchTarget)
                        .semantics { selected = isSelected }
                        .combinedClickable(role = Role.Tab,
                            onClick = { selectionRequest++; onSelectUser(user.id) },
                            onLongClickLabel = stringResource(R.string.user_manage),
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onManageUser(user.id)
                            }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(user.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.user_prefix, user.id),
                        modifier = Modifier.padding(horizontal = NexBoxSpacing.md, vertical = NexBoxSpacing.sm),
                        style = if (miuix) miuixChoiceLabelStyle(isSelected) else MaterialTheme.typography.labelLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isSelected && !miuix) {
                        Box(Modifier.align(Alignment.BottomCenter).width(NexBoxSpacing.xxl)
                            .height(NexBoxSpacing.tabIndicatorHeight)
                            .background(MaterialTheme.colorScheme.primary, NexBoxShapes.pill))
                    }
                }
            }
        }
    }
}
