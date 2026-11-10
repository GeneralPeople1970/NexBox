package com.nexbox.app.view.setting

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import com.nexbox.app.ui.icons.filled.AcUnit
import com.nexbox.app.ui.icons.filled.Security
import com.nexbox.app.ui.icons.filled.SimCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nexbox.app.R
import com.nexbox.app.app.AppFreezeManager
import com.nexbox.app.app.AppManager
import com.nexbox.app.data.RuntimeSettingsChanges
import com.nexbox.app.data.RuntimeSettingsRepository
import com.nexbox.app.data.RuntimeSettingsSaveException
import com.nexbox.app.ui.navigation.NexBoxBackGuard
import com.nexbox.app.ui.components.NexBoxCard
import com.nexbox.app.ui.components.ConfigurationStopFailureDialog
import com.nexbox.app.ui.components.NexBoxNavRow
import com.nexbox.app.ui.components.NexBoxLoadingOverlay
import com.nexbox.app.ui.components.Switch
import com.nexbox.core.NexBoxCore
import com.nexbox.app.data.DeviceRepository
import com.nexbox.core.utils.ConfigurationStopException
import com.nexbox.app.util.NexLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reusable settings group; the parent owns scrolling and navigation. */
@Composable
fun RuntimeSettings(onBusyChanged: (Boolean) -> Unit = {}) {
    var isHideRoot by remember { mutableStateOf(NexBoxCore.get().isHideRoot) }
    var isAutoFreeze by remember { mutableStateOf(AppFreezeManager.isGlobalAutoFreezeEnabled()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isEsimEnabled by remember { mutableStateOf<Boolean?>(null) }
    var esimBusy by remember { mutableStateOf(false) }
    var esimLoadAttempt by remember { mutableStateOf(0) }
    var stopFailure by remember { mutableStateOf<ConfigurationStopException?>(null) }
    var runtimeBusy by remember { mutableStateOf(false) }
    var pendingRuntimeChanges by remember { mutableStateOf<RuntimeSettingsChanges?>(null) }

    fun saveRuntime(changes: RuntimeSettingsChanges) {
        if (runtimeBusy || esimBusy) return
        runtimeBusy = true
        pendingRuntimeChanges = changes
        scope.launch {
            try {
                withContext(Dispatchers.IO) { RuntimeSettingsRepository.apply(changes) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ConfigurationStopException) {
                stopFailure = failure
            } catch (failure: Exception) {
                NexLog.e("Settings", "action=saveRuntime", failure)
                val message = if (failure is RuntimeSettingsSaveException && failure.unconfirmedKeys.isNotEmpty())
                    R.string.runtime_settings_partial_save else R.string.runtime_settings_save_failed
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } finally {
                isHideRoot = AppManager.mNexBoxLoader.hideRoot()
                isAutoFreeze = AppFreezeManager.isGlobalAutoFreezeEnabled()
                runtimeBusy = false
                pendingRuntimeChanges = null
            }
        }
    }

    LaunchedEffect(esimLoadAttempt) {
        esimBusy = true
        try {
            isEsimEnabled = withContext(Dispatchers.IO) { DeviceRepository.initializeGlobalEsimSpoofSetting() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            NexLog.e("DeviceProfile", "action=loadGlobalEsim", failure)
            Toast.makeText(context, R.string.device_esim_setting_failed, Toast.LENGTH_SHORT).show()
        } finally {
            esimBusy = false
        }
    }

    SideEffect { onBusyChanged(runtimeBusy || esimBusy) }
    NexBoxBackGuard(blocked = runtimeBusy || esimBusy, onBlocked = {})
    Column {
        NexBoxCard {
            NexBoxLoadingOverlay(runtimeBusy && pendingRuntimeChanges?.hideRoot != null) {
                NexBoxNavRow(
                    icon = Icons.Default.Security,
                    title = stringResource(id = R.string.hide_root),
                    subtitle = stringResource(id = R.string.hide_root_summary),
                    trailing = {
                        Switch(
                            checked = isHideRoot,
                            enabled = !runtimeBusy && !esimBusy,
                            onCheckedChange = { saveRuntime(RuntimeSettingsChanges(hideRoot = it)) }
                        )
                    }
                )
            }
            NexBoxLoadingOverlay(runtimeBusy && pendingRuntimeChanges?.autoFreeze != null) {
                NexBoxNavRow(
                    icon = Icons.Default.AcUnit,
                    title = stringResource(id = R.string.auto_freeze_title),
                    subtitle = stringResource(id = R.string.auto_freeze_summary),
                    trailing = {
                        Switch(
                            checked = isAutoFreeze,
                            enabled = !runtimeBusy && !esimBusy,
                            onCheckedChange = { saveRuntime(RuntimeSettingsChanges(autoFreeze = it)) }
                        )
                    }
                )
            }
            NexBoxLoadingOverlay(esimBusy) {
                NexBoxNavRow(
                    icon = Icons.Default.SimCard,
                    title = stringResource(R.string.device_spoofing_esim),
                    subtitle = stringResource(R.string.device_spoofing_esim_summary),
                    showDivider = false,
                    onClick = if (isEsimEnabled == null && !esimBusy) ({ esimLoadAttempt++ }) else null,
                    trailing = {
                        Switch(
                            checked = isEsimEnabled == true,
                            enabled = isEsimEnabled != null && !esimBusy && !runtimeBusy,
                            onCheckedChange = { checked ->
                                if (!esimBusy && !runtimeBusy) {
                                    esimBusy = true
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { DeviceRepository.setGlobalEsimSpoofEnabled(checked) }
                                            isEsimEnabled = checked
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: ConfigurationStopException) {
                                            isEsimEnabled = checked
                                            stopFailure = failure
                                        } catch (failure: Exception) {
                                            NexLog.e("DeviceProfile", "action=saveGlobalEsim", failure)
                                            Toast.makeText(context, R.string.device_esim_setting_failed, Toast.LENGTH_SHORT).show()
                                        } finally {
                                            esimBusy = false
                                        }
                                    }
                                }
                            }
                        )
                    }
                )
            }
        }
    }
    ConfigurationStopFailureDialog(stopFailure, onDismiss = { stopFailure = null })
}
