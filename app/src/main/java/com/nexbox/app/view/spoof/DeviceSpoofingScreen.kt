package com.nexbox.app.view.spoof

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.nexbox.app.ui.icons.filled.SimCard
import com.nexbox.app.ui.icons.filled.Apps
import com.nexbox.app.ui.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavBackStackEntry
import androidx.lifecycle.Lifecycle
import com.nexbox.app.R
import com.nexbox.app.data.CloneTarget
import com.nexbox.app.ui.components.AlertDialog
import com.nexbox.app.ui.components.Button
import com.nexbox.app.ui.components.CircularProgressIndicator
import com.nexbox.app.ui.components.ConfigurationStopFailureDialog
import com.nexbox.app.ui.components.NexBoxCard
import com.nexbox.app.ui.components.NexBoxNavRow
import com.nexbox.app.ui.components.NexBoxTopAppBar
import com.nexbox.app.ui.components.OutlinedButton
import com.nexbox.app.ui.components.OutlinedTextField
import com.nexbox.app.ui.components.Scaffold
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.components.TextButton
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.app.ui.NexBoxNavHost
import com.nexbox.app.ui.popFrom
import com.nexbox.app.ui.navigation.NexBoxBackGuard
import com.nexbox.app.ui.navigation.nexBoxComposable
import com.nexbox.app.util.NexLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nexbox.app.data.DeviceRepository
import com.nexbox.core.utils.ConfigurationStopException

private enum class DeviceEditorPage { MAIN, PARAMETERS, SIM, SCOPE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSpoofingScreen(
    userId: Int = 0,
    packageName: String? = null,
    appLabel: String? = null,
    expectedGeneration: Long? = null,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(userId, packageName, expectedGeneration, stateSaver = DeviceProfileDraftSaver) {
        mutableStateOf<DeviceProfileDraft?>(null)
    }
    var saved by rememberSaveable(userId, packageName, expectedGeneration, stateSaver = DeviceProfileDraftSaver) {
        mutableStateOf<DeviceProfileDraft?>(null)
    }
    var detailCheckpoint by rememberSaveable(userId, packageName, expectedGeneration, stateSaver = DeviceProfileDraftSaver) {
        mutableStateOf<DeviceProfileDraft?>(null)
    }
    var defaults by remember(userId, packageName) { mutableStateOf<DeviceProfileDraft?>(null) }
    val editorNavigation = rememberNavController()
    val editorEntry by editorNavigation.currentBackStackEntryAsState()
    val page = editorEntry?.destination?.route?.let(DeviceEditorPage::valueOf) ?: DeviceEditorPage.MAIN
    var confirmLeave by rememberSaveable(userId, packageName) { mutableStateOf(false) }
    var error by rememberSaveable(userId, packageName) { mutableStateOf<Int?>(null) }
    var validationAttempted by rememberSaveable(userId, packageName) { mutableStateOf(false) }
    var busy by remember(userId, packageName) { mutableStateOf(false) }
    var randomizing by remember(userId, packageName) { mutableStateOf(false) }
    var stopFailure by remember(userId, packageName) { mutableStateOf<ConfigurationStopException?>(null) }
    var loadAttempt by remember(userId, packageName) { mutableStateOf(0) }
    var generation by rememberSaveable(userId, packageName) { mutableStateOf<Long?>(expectedGeneration) }
    val mainScroll = rememberScrollState()
    val detailScroll = rememberScrollState()
    val simScroll = rememberScrollState()

    fun edit(next: DeviceProfileDraft) {
        draft = next.copy(inheritGlobal = false)
    }

    LaunchedEffect(userId, packageName, expectedGeneration, loadAttempt) {
        busy = true
        try {
            val loaded = withContext(Dispatchers.IO) {
                DeviceRepository.loadEditor(userId, packageName, expectedGeneration)
            }
            if (generation != null && generation != loaded.target.createTime) {
                error = R.string.clone_user_changed
                NexLog.w("DeviceProfile", "action=loadEditor rejected=stale_generation userId=$userId")
                return@LaunchedEffect
            }
            generation = loaded.target.createTime
            saved = DeviceProfileDraft(loaded.profile)
            defaults = DeviceProfileDraft(loaded.defaults)
            if (draft == null) draft = DeviceProfileDraft(loaded.profile)
            if (page != DeviceEditorPage.MAIN && detailCheckpoint == null) detailCheckpoint = draft
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            NexLog.e("DeviceProfile", "action=loadEditor userId=$userId", failure)
            error = R.string.device_profile_load_failed
        } finally {
            busy = false
        }
    }

    fun leavePage() {
        confirmLeave = false
        validationAttempted = false
        if (page != DeviceEditorPage.MAIN) editorNavigation.popBackStack() else onBack()
    }

    fun requestBack(source: NavBackStackEntry) {
        if (editorNavigation.currentBackStackEntry != source ||
            !source.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (busy) return
        val checkpoint = if (page != DeviceEditorPage.MAIN) detailCheckpoint else saved
        val current = draft
        if (current != null && !current.sameValues(checkpoint)) confirmLeave = true else leavePage()
    }

    fun save(leaveAfterSave: Boolean = false) {
        val current = draft ?: return
        if (busy) return
        val invalid = if (current.inheritGlobal) null else current.validationError()
        if (invalid != null) {
            NexLog.w("DeviceProfile", "action=saveEditor rejected=invalid_profile userId=$userId fieldResource=$invalid")
            error = null
            validationAttempted = true
            confirmLeave = false
            scope.launch {
                val scroll = when (page) {
                    DeviceEditorPage.PARAMETERS -> detailScroll
                    DeviceEditorPage.SIM -> simScroll
                    else -> mainScroll
                }
                scroll.animateScrollTo(scroll.maxValue)
            }
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    DeviceRepository.saveEditor(CloneTarget(userId, checkNotNull(generation)), packageName,
                        current.inheritGlobal) { current.toProfile(userId) }
                }
                val stoppingFailure = result.stopFailure
                val accepted = DeviceProfileDraft(result.saved)
                draft = accepted
                saved = accepted
                detailCheckpoint = accepted
                confirmLeave = false
                validationAttempted = false
                stopFailure = stoppingFailure
                if (leaveAfterSave && stoppingFailure == null) leavePage()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                NexLog.e("DeviceProfile", "action=saveEditor userId=$userId", failure)
                error = R.string.device_profile_save_failed
                confirmLeave = false
            } finally {
                busy = false
            }
        }
    }

    fun randomize() {
        val current = draft ?: return
        if (busy) return
        if (page == DeviceEditorPage.SIM && (!current.base.simProfile.enabled || current.base.simProfile.cards.isEmpty())) {
            Toast.makeText(context, R.string.sim_random_requires_card, Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        randomizing = true
        error = null
        scope.launch {
            try {
                val randomized = withContext(Dispatchers.IO) {
                    DeviceRepository.requireEditableTarget(CloneTarget(userId, checkNotNull(generation)), packageName)
                    when (page) {
                        DeviceEditorPage.SIM -> current.withSim(current.base.simProfile.randomizedCards())
                        else -> DeviceProfileDraft(DeviceRepository.randomizeAll(current.toProfile(userId))
                            .withSimProfile(current.base.simProfile))
                    }
                }
                edit(randomized)
                NexLog.i("DeviceProfile", "action=randomize userId=$userId page=$page draftOnly=true")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                NexLog.e("DeviceProfile", "action=randomizeAll userId=$userId", failure)
                error = R.string.device_profile_random_failed
            } finally {
                randomizing = false
                busy = false
            }
        }
    }

    fun restoreGlobal() {
        if (packageName == null || busy) return
        busy = true
        error = null
        scope.launch {
            try {
                draft = withContext(Dispatchers.IO) {
                    DeviceProfileDraft(DeviceRepository.inheritedProfile(
                        CloneTarget(userId, checkNotNull(generation)), packageName), inheritGlobal = true)
                }
                validationAttempted = false
                NexLog.i("DeviceProfile", "action=resetEditor userId=$userId inheritGlobal=true draftOnly=true")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = R.string.device_profile_load_failed
                NexLog.e("DeviceProfile", "action=resetEditor userId=$userId", failure)
            } finally {
                busy = false
            }
        }
    }

    // Draft/checkpoints stay above the nested entries. The covered MAIN page remains a real
    // preview during back; seeking or cancelling never mutates page selection or saves data.
    NexBoxNavHost(editorNavigation, Modifier.fillMaxSize(), startDestination = DeviceEditorPage.MAIN.name) {
        DeviceEditorPage.entries.forEach { editorPage ->
            nexBoxComposable(editorPage.name) { entry ->
                if (editorPage == DeviceEditorPage.SCOPE && packageName == null) {
                    DeviceScopeScreen(userId, generation, onBack = { editorNavigation.popFrom(entry) })
                } else {
                    val checkpoint = if (editorPage != DeviceEditorPage.MAIN) detailCheckpoint else saved
                    val dirty = draft?.let { !it.sameValues(checkpoint) } == true
                    NexBoxBackGuard(blocked = busy || dirty, allowPreview = !busy && !dirty, onBlocked = { requestBack(entry) })
                    Scaffold(topBar = {
                        NexBoxTopAppBar(
                            title = if (editorPage == DeviceEditorPage.MAIN && !appLabel.isNullOrBlank()) appLabel else stringResource(when (editorPage) {
                                DeviceEditorPage.PARAMETERS -> R.string.device_more_parameters
                                DeviceEditorPage.SIM -> R.string.device_sim_cards
                                else -> R.string.device_spoofing
                            }),
                            onBack = { requestBack(entry) }
                        )
                    }) { padding ->
                        val current = draft
                        if (current == null) {
                            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                                if (busy) CircularProgressIndicator() else Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.md)
                                ) {
                                    Text(stringResource(error ?: R.string.device_profile_load_failed))
                                    TextButton(onClick = { loadAttempt++ }) { Text(stringResource(R.string.device_profile_retry)) }
                                }
                            }
                        } else {
                            Column(
                                Modifier.fillMaxSize().padding(padding)
                                    .verticalScroll(when (editorPage) {
                                        DeviceEditorPage.PARAMETERS -> detailScroll
                                        DeviceEditorPage.SIM -> simScroll
                                        else -> mainScroll
                                    })
                                    .padding(NexBoxSpacing.screenPadding),
                                verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.md)
                            ) {
                                if (editorPage == DeviceEditorPage.MAIN) {
                                    DeviceValueField(stringResource(R.string.device_manufacturer_label), current.manufacturer, !busy) {
                                        edit(current.copy(manufacturer = it))
                                    }
                                    DeviceValueField(stringResource(R.string.device_model_label), current.model, !busy) {
                                        edit(current.copy(model = it))
                                    }
                                    DeviceValueField(stringResource(R.string.device_spoofing_android_id), current.androidId, !busy) {
                                        edit(current.copy(androidId = it))
                                    }
                                }

                                if (editorPage == DeviceEditorPage.PARAMETERS) {
                                    val build = remember(current.manufacturer, current.model, current.base) { current.toProfile(userId) }
                                    DeviceValueField(stringResource(R.string.device_brand), build.brand, readOnly = true)
                                    DeviceValueField(stringResource(R.string.device_code), build.device, readOnly = true)
                                    DeviceValueField(stringResource(R.string.device_product), build.effectiveProduct(), readOnly = true)
                                    DeviceValueField(stringResource(R.string.device_fingerprint), build.effectiveFingerprint(),
                                        readOnly = true, singleLine = false)
                                    DeviceValueField(stringResource(R.string.device_serial), current.serial, !busy) {
                                        edit(current.copy(serial = it))
                                    }
                                    DeviceValueField("IMEI", current.imei, !busy, keyboardType = KeyboardType.Number) {
                                        edit(current.copy(imei = it))
                                    }
                                    DeviceValueField(stringResource(R.string.device_mac), current.macAddress, !busy) {
                                        edit(current.copy(macAddress = it))
                                    }
                                    DeviceValueField("OAID", current.oaid, !busy) { edit(current.copy(oaid = it)) }
                                } else if (editorPage == DeviceEditorPage.SIM) {
                                    SimProfileEditor(current.base.simProfile, enabled = !busy) { edit(current.withSim(it)) }
                                } else if (editorPage == DeviceEditorPage.MAIN) {
                                    NexBoxCard {
                                        NexBoxNavRow(Icons.Default.SimCard, stringResource(R.string.device_sim_cards), onClick = {
                                            if (!busy) {
                                                detailCheckpoint = current
                                                editorNavigation.navigate(DeviceEditorPage.SIM.name) { launchSingleTop = true }
                                                error = null
                                            }
                                        })
                                        NexBoxNavRow(Icons.Default.Tune, stringResource(R.string.device_more_parameters),
                                            showDivider = false, onClick = {
                                            if (!busy) {
                                                detailCheckpoint = current
                                                editorNavigation.navigate(DeviceEditorPage.PARAMETERS.name) { launchSingleTop = true }
                                                error = null
                                            }
                                        })
                                    }
                                    if (packageName == null) NexBoxCard {
                                        NexBoxNavRow(Icons.Default.Apps, stringResource(R.string.device_random_scope),
                                            showDivider = false, onClick = {
                                                if (!busy) editorNavigation.navigate(DeviceEditorPage.SCOPE.name) { launchSingleTop = true }
                                            })
                                    }
                                }

                                val displayedError = error ?: if (validationAttempted) current.validationError() else null
                                // The SIM editor already shows its current validation summary.
                                displayedError?.takeUnless {
                                    editorPage == DeviceEditorPage.SIM && it == R.string.sim_profile_invalid
                                }?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NexBoxSpacing.md)) {
                                    OutlinedButton(onClick = ::randomize, enabled = !busy, modifier = Modifier.weight(1f)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            if (randomizing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                            Text(stringResource(R.string.device_randomize_ids),
                                                modifier = Modifier.alpha(if (randomizing) 0f else 1f))
                                        }
                                    }
                                    OutlinedButton(onClick = {
                                        if (packageName != null && editorPage != DeviceEditorPage.SIM) {
                                            restoreGlobal()
                                        } else defaults?.let {
                                            edit(when (editorPage) {
                                                DeviceEditorPage.SIM -> current.withSim(it.base.simProfile)
                                                DeviceEditorPage.PARAMETERS -> current.copy(serial = it.serial, imei = it.imei,
                                                    macAddress = it.macAddress, oaid = it.oaid)
                                                else -> it
                                            })
                                            error = null
                                            NexLog.i("DeviceProfile", "action=resetEditor userId=$userId draftOnly=true")
                                        }
                                    }, enabled = !busy && defaults != null, modifier = Modifier.weight(1f)) {
                                        Text(stringResource(if (packageName != null && editorPage != DeviceEditorPage.SIM)
                                            R.string.device_restore_global else R.string.device_spoofing_reset_to_default))
                                    }
                                }
                                Button(onClick = { save() }, enabled = !busy && !current.sameValues(saved),
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.device_spoofing_accept_change))
                                }
                            }
                        }
                    }

                }
            }
        }
    }

    ConfigurationStopFailureDialog(stopFailure, onDismiss = { stopFailure = null })

    if (confirmLeave) AlertDialog(
        onDismissRequest = { if (!busy) confirmLeave = false },
        title = { Text(stringResource(R.string.device_unsaved_title)) },
        confirmButton = {
            TextButton(onClick = { save(leaveAfterSave = true) }, enabled = !busy) {
                Text(stringResource(R.string.device_save_and_leave))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                draft = if (page != DeviceEditorPage.MAIN) detailCheckpoint else saved
                error = null
                leavePage()
            }, enabled = !busy) { Text(stringResource(R.string.device_discard_changes)) }
        }
    )
}

@Composable
private fun DeviceValueField(
    label: String,
    value: String,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Ascii,
    onChange: (String) -> Unit = {}
) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled, readOnly = readOnly,
        label = { Text(label) }, singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType))
}
