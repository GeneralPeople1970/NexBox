package com.nexbox.app.view.setting

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import com.nexbox.app.ui.icons.filled.BlurOn
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.nexbox.app.ui.icons.filled.Brightness6
import com.nexbox.app.ui.icons.filled.Palette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nexbox.app.R
import com.nexbox.app.data.AppearanceRepository
import com.nexbox.app.data.ThemeMode
import com.nexbox.app.data.UiStyle
import com.nexbox.app.ui.components.NexBoxCard
import com.nexbox.app.ui.components.NexBoxNavRow
import com.nexbox.app.ui.components.NexBoxPickerRow
import com.nexbox.app.ui.components.Switch
import com.nexbox.app.ui.theme.LocalAppearance

/** Reusable settings group; the parent owns scrolling and navigation. */
@Composable
fun AppearanceSettings() {
    val context = LocalContext.current
    val appearance = LocalAppearance.current
    val appearanceRepository = remember(context.applicationContext) { AppearanceRepository.get(context) }

    LaunchedEffect(appearanceRepository) {
        appearanceRepository.saveErrors.collect {
            Toast.makeText(context, R.string.appearance_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    val styleOptions = UiStyle.entries.map { stringResource(uiStyleLabel(it)) }
    val themeOptions = ThemeMode.entries.map { stringResource(themeModeLabel(it)) }

    Column {
        NexBoxCard {
            NexBoxPickerRow(
                icon = Icons.Default.Palette,
                title = stringResource(R.string.appearance_ui_style),
                value = stringResource(uiStyleLabel(appearance.uiStyle)),
                options = styleOptions,
                selectedIndex = UiStyle.entries.indexOf(appearance.uiStyle),
                onSelectedIndexChange = { appearanceRepository.setUiStyle(UiStyle.entries[it]) }
            )
            NexBoxPickerRow(
                icon = Icons.Default.Brightness6,
                title = stringResource(R.string.appearance_theme_mode),
                value = stringResource(themeModeLabel(appearance.themeMode)),
                options = themeOptions,
                selectedIndex = ThemeMode.entries.indexOf(appearance.themeMode),
                onSelectedIndexChange = { appearanceRepository.setThemeMode(ThemeMode.entries[it]) }
            )
            NexBoxNavRow(
                icon = Icons.Default.BlurOn,
                title = stringResource(R.string.appearance_liquid_glass),
                trailing = {
                    Switch(checked = appearance.liquidGlassEnabled,
                        onCheckedChange = appearanceRepository::setLiquidGlassEnabled)
                }
            )
            NexBoxNavRow(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                title = stringResource(R.string.appearance_predictive_back),
                trailing = {
                    Switch(checked = appearance.predictiveBackEnabled,
                        onCheckedChange = appearanceRepository::setPredictiveBackEnabled)
                },
                showDivider = false
            )
        }
    }
}

private fun uiStyleLabel(style: UiStyle) = when (style) {
    UiStyle.MATERIAL3 -> R.string.appearance_material3
    UiStyle.MIUIX -> R.string.appearance_miuix
}

private fun themeModeLabel(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> R.string.appearance_theme_system
    ThemeMode.LIGHT -> R.string.appearance_theme_light
    ThemeMode.DARK -> R.string.appearance_theme_dark
}
