package com.nexbox.app.view.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nexbox.app.R
import com.nexbox.app.ui.components.LocalSettingsLayout
import com.nexbox.app.ui.components.NexBoxTopAppBar
import com.nexbox.app.ui.components.Scaffold
import com.nexbox.app.ui.components.usesMiuix
import com.nexbox.app.ui.theme.NexBoxSpacing

/**
 * 「运行环境」二级页。原先内联在设置首页的隐藏 Root / 应用冷冻两项挪到这里，
 * 首页只在「沙盒」分组里留一个入口行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeSettingsScreen(onBack: () -> Unit = {}) {
    val miuix = usesMiuix()
    var busy by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalSettingsLayout provides true) {
        Scaffold(topBar = {
            NexBoxTopAppBar(title = stringResource(R.string.settings_category_permission), onBack = if (busy) null else onBack)
        }) { innerPadding ->
            Column(
                modifier = Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(horizontal = if (miuix) NexBoxSpacing.settingsPageInset else NexBoxSpacing.screenPadding)
            ) {
                RuntimeSettings(onBusyChanged = { busy = it })
            }
        }
    }
}
