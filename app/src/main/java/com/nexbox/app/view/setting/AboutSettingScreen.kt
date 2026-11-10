package com.nexbox.app.view.setting

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.nexbox.app.ui.icons.filled.BugReport
import com.nexbox.app.ui.icons.filled.Code
import com.nexbox.app.ui.icons.filled.Description
import com.nexbox.app.ui.icons.filled.Groups
import com.nexbox.app.ui.icons.filled.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexbox.app.R
import com.nexbox.app.data.UpdateRepository
import com.nexbox.app.ui.components.NexBoxCard
import com.nexbox.app.ui.components.NexBoxNavRow
import com.nexbox.app.ui.components.NexBoxTopAppBar
import com.nexbox.app.ui.components.Scaffold
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.app.util.ProjectBrowser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSettingScreen(
    onBack: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    onNavigateToUpdates: () -> Unit = {}
) {
    val context = LocalContext.current
    val updates = remember(context) { UpdateRepository.get(context) }
    val updateState by updates.state.collectAsStateWithLifecycle()
    val showUnavailable = {
        Toast.makeText(context, R.string.feature_not_implemented, Toast.LENGTH_SHORT).show()
    }
    val version = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    val logo = remember(context) {
        context.packageManager.getApplicationIcon(context.packageName).toBitmap(256, 256).asImageBitmap()
    }
    Scaffold(topBar = {
        NexBoxTopAppBar(title = stringResource(R.string.about_title), onBack = onBack)
    }) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
                .verticalScroll(rememberScrollState()).padding(NexBoxSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.sectionGap)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = NexBoxSpacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.sm)
            ) {
                Image(logo, contentDescription = null, modifier = Modifier.size(NexBoxSpacing.aboutLogo))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                Text(version, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            NexBoxCard {
                NexBoxNavRow(
                    icon = Icons.Default.SystemUpdate,
                    title = stringResource(R.string.about_check_updates),
                    subtitle = if (updateState.errorCode == null && updateState.updateAvailable) {
                        stringResource(R.string.update_available, updateState.latest?.version.orEmpty())
                    } else stringResource(R.string.about_updates_summary),
                    onClick = onNavigateToUpdates
                )
                NexBoxNavRow(
                    icon = Icons.Default.Code,
                    title = stringResource(R.string.about_source_code),
                    subtitle = stringResource(R.string.about_source_summary),
                    onClick = { ProjectBrowser.open(context, UpdateRepository.PROJECT_URL) }
                )
                NexBoxNavRow(
                    icon = Icons.Default.Groups,
                    title = stringResource(R.string.about_telegram_group),
                    subtitle = stringResource(R.string.about_telegram_summary),
                    onClick = showUnavailable
                )
                NexBoxNavRow(
                    icon = Icons.Default.Description,
                    title = stringResource(R.string.appearance_open_source_licenses),
                    subtitle = stringResource(R.string.about_licenses_summary),
                    onClick = showUnavailable
                )
                NexBoxNavRow(
                    icon = Icons.Default.BugReport,
                    title = stringResource(R.string.logs_title),
                    subtitle = stringResource(R.string.logs_entry_summary),
                    onClick = onNavigateToLogs,
                    showDivider = false
                )
            }
        }
    }
}
