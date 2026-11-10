package com.nexbox.app.view.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import com.nexbox.app.ui.icons.filled.CloudSync
import com.nexbox.app.ui.icons.filled.Extension
import androidx.compose.material.icons.filled.LocationOn
import com.nexbox.app.ui.icons.filled.Memory
import com.nexbox.app.ui.icons.filled.PhoneAndroid
import com.nexbox.app.ui.icons.filled.PhotoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nexbox.app.R
import com.nexbox.app.ui.components.NexBoxCard
import com.nexbox.app.ui.components.NexBoxNavRow

/** Reusable settings group; the parent owns scrolling and navigation. */
@Composable
fun SandboxSettings(
    onNavigateToDeviceSpoofing: () -> Unit = {},
    onNavigateToFakeLocation: () -> Unit = {},
    onNavigateToGms: () -> Unit = {},
    onNavigateToXpModules: () -> Unit = {},
    onNavigateToGallery: () -> Unit = {},
    onNavigateToRuntime: () -> Unit = {}
) {
    Column {
        NexBoxCard {
            NexBoxNavRow(
                icon = Icons.Default.PhoneAndroid,
                title = stringResource(id = R.string.nav_device_spoofing),
                subtitle = stringResource(R.string.sandbox_device_summary),
                onClick = onNavigateToDeviceSpoofing
            )
            NexBoxNavRow(
                icon = Icons.Default.LocationOn,
                title = stringResource(id = R.string.nav_fake_location),
                subtitle = stringResource(R.string.sandbox_location_summary),
                onClick = onNavigateToFakeLocation
            )
            NexBoxNavRow(
                icon = Icons.Default.CloudSync,
                title = stringResource(id = R.string.nav_gms),
                subtitle = stringResource(R.string.sandbox_gms_summary),
                onClick = onNavigateToGms
            )
            NexBoxNavRow(
                icon = Icons.Default.Extension,
                title = stringResource(id = R.string.module_setting),
                subtitle = stringResource(R.string.sandbox_modules_summary),
                onClick = onNavigateToXpModules
            )
            NexBoxNavRow(
                icon = Icons.Default.PhotoLibrary,
                title = stringResource(id = R.string.nav_gallery),
                subtitle = stringResource(R.string.sandbox_gallery_summary),
                onClick = onNavigateToGallery
            )
            NexBoxNavRow(
                icon = Icons.Default.Memory,
                title = stringResource(id = R.string.settings_category_permission),
                subtitle = stringResource(R.string.settings_runtime_summary),
                showDivider = false,
                onClick = onNavigateToRuntime
            )
        }
    }
}
