package com.nexbox.app.view.fake

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.nexbox.app.data.HostLocationFix
import com.nexbox.app.data.HostLocationRepository
import com.nexbox.app.util.NexLog
import kotlinx.coroutines.awaitCancellation

internal data class HostLocationUi(val locating: Boolean, val request: () -> Unit)

@Composable
internal fun rememberHostLocation(
    lifecycle: Lifecycle,
    selectionVersion: State<Int>,
    repository: HostLocationRepository,
    onLocation: (HostLocationFix?, Boolean) -> Unit
): HostLocationUi {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val currentSelectionVersion by selectionVersion
    val deliver by rememberUpdatedState(onLocation)
    var started by rememberSaveable { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf(false) }
    var permissionPending by rememberSaveable { mutableStateOf(false) }
    var settingsPending by rememberSaveable { mutableStateOf(false) }
    var explicit by rememberSaveable { mutableStateOf(false) }
    var requestedSelectionVersion by rememberSaveable { mutableIntStateOf(0) }
    var requestVersion by rememberSaveable { mutableIntStateOf(0) }
    var lastGranted by rememberSaveable { mutableStateOf<Boolean?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionPending = false
        lastGranted = repository.hasPermission()
    }

    fun request(automatic: Boolean) {
        if (pending || permissionPending || settingsPending) return
        explicit = !automatic
        requestedSelectionVersion = currentSelectionVersion
        requestVersion++
        pending = true
        lastGranted = repository.hasPermission()
        if (lastGranted == true) return
        val alreadyRequested = repository.wasPermissionRequested()
        if (automatic && alreadyRequested) return
        val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!automatic && alreadyRequested && activity != null &&
            permissions.none(activity::shouldShowRequestPermissionRationale)) {
            settingsPending = true
            try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")))
            } catch (error: Exception) {
                settingsPending = false
                NexLog.w("Location", "action=openHostLocationSettings failed", error)
            }
            return
        }
        permissionPending = true
        try {
            repository.markPermissionRequested()
            launcher.launch(permissions)
        } catch (error: Exception) {
            permissionPending = false
            NexLog.w("Location", "action=requestHostLocationPermission failed", error)
        }
    }

    val beginRequest by rememberUpdatedState<(Boolean) -> Unit>(::request)
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (settingsPending) settingsPending = false
            if (!started) {
                started = true
                beginRequest(true)
            } else if (!pending && !permissionPending) {
                val granted = repository.hasPermission()
                if (granted && lastGranted != true) beginRequest(true)
                lastGranted = granted
            }
            awaitCancellation()
        }
    }

    LaunchedEffect(lifecycle, pending, permissionPending, settingsPending, requestVersion) {
        if (!pending || permissionPending || settingsPending) return@LaunchedEffect
        // Leaving the foreground cancels all provider requests; an unfinished request resumes on return.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val fix = repository.locate()
            pending = false
            lastGranted = repository.hasPermission()
            if (requestedSelectionVersion == currentSelectionVersion) deliver(fix, explicit)
            awaitCancellation()
        }
    }

    return HostLocationUi(pending || permissionPending || settingsPending) { beginRequest(false) }
}
