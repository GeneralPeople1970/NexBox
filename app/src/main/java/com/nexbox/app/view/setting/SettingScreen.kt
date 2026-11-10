package com.nexbox.app.view.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.components.LocalSettingsLayout
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import com.nexbox.app.R
import com.nexbox.app.ui.components.NexBoxSectionHeader
import com.nexbox.app.ui.components.NexBoxCard
import com.nexbox.app.ui.components.NexBoxNavRow
import com.nexbox.app.ui.components.usesMiuix
import com.nexbox.app.ui.components.Scaffold
import com.nexbox.app.ui.theme.NexBoxSpacing

/** A single scroll surface with independently reusable settings groups. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingScreen(
    onNavigateToDeviceSpoofing: () -> Unit = {},
    onNavigateToFakeLocation: () -> Unit = {},
    onNavigateToGms: () -> Unit = {},
    onNavigateToXpModules: () -> Unit = {},
    onNavigateToGallery: () -> Unit = {},
    onNavigateToRuntime: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {}
) {
    val miuix = usesMiuix()
    val scrollState = rememberScrollState()
    val headerScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = rememberTopAppBarState(), canScroll = { !miuix }
    )
    CompositionLocalProvider(LocalSettingsLayout provides true) {
        Scaffold(
            modifier = Modifier.nestedScroll(headerScrollBehavior.nestedScrollConnection),
            topBar = {
                if (!miuix) {
                    LargeTopAppBar(
                        title = {
                            Text(stringResource(R.string.settings_title), modifier = Modifier.semantics { heading() })
                        },
                        scrollBehavior = headerScrollBehavior,
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                    )
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier.fillMaxSize()
                    .verticalScroll(scrollState)
                    // Keep navigation avoidance inside the viewport so content scrolls behind glass.
                    .padding(innerPadding)
                    .padding(horizontal = if (miuix) NexBoxSpacing.settingsPageInset else NexBoxSpacing.screenPadding)
            ) {
                if (miuix) {
                    Text(stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = 36.sp),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = NexBoxSpacing.lg,
                            top = NexBoxSpacing.settingsTitleTop, bottom = NexBoxSpacing.settingsTitleBottom))
                } else {
                    Spacer(Modifier.height(NexBoxSpacing.lg))
                }
                SettingsSection(stringResource(R.string.appearance_title)) { AppearanceSettings() }
                SettingsSection(stringResource(R.string.settings_category_sandbox)) {
                    SandboxSettings(
                        onNavigateToDeviceSpoofing = onNavigateToDeviceSpoofing,
                        onNavigateToFakeLocation = onNavigateToFakeLocation,
                        onNavigateToGms = onNavigateToGms,
                        onNavigateToXpModules = onNavigateToXpModules,
                        onNavigateToGallery = onNavigateToGallery,
                        onNavigateToRuntime = onNavigateToRuntime
                    )
                }
                NexBoxCard {
                    NexBoxNavRow(
                        icon = Icons.Default.Info,
                        title = stringResource(R.string.about_title),
                        onClick = onNavigateToAbout,
                        showDivider = false
                    )
                }
                Spacer(Modifier.height(NexBoxSpacing.sectionGap))
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    if (usesMiuix()) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier.padding(start = NexBoxSpacing.lg, bottom = NexBoxSpacing.sm))
    } else {
        NexBoxSectionHeader(title, modifier = Modifier.padding(start = NexBoxSpacing.lg).semantics { heading() })
    }
    content()
    Spacer(Modifier.height(NexBoxSpacing.settingsSectionGap))
}
