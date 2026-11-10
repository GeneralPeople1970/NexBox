package com.nexbox.app.view.apps

import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.nexbox.app.R
import com.nexbox.app.ui.components.*
import com.nexbox.app.ui.theme.NexBoxSpacing

/** Search never replaces the title or changes header geometry on activation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeHeader(
    searchState: NexBoxSearchState,
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
        Column {
            if (usesMiuix()) {
                Text(stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = 36.sp),
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().statusBarsPadding()
                        .padding(start = NexBoxSpacing.settingsPageInset + NexBoxSpacing.lg,
                            end = NexBoxSpacing.settingsPageInset,
                            top = NexBoxSpacing.settingsTitleTop, bottom = NexBoxSpacing.settingsTitleBottom)
                        .semantics { heading() })
            } else {
                LargeTopAppBar(title = {
                    Text(stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() })
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    scrollBehavior = scrollBehavior)
            }
            NexBoxSearchField(searchState, stringResource(R.string.search_apps_hint),
                Modifier.padding(horizontal = NexBoxSpacing.screenPadding, vertical = NexBoxSpacing.sm))
        }
    }
}
