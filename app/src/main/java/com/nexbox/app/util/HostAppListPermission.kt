package com.nexbox.app.util

import android.widget.Toast
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nexbox.app.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class HostAppListPermissionUi(
    val access: HostAppListAccess,
    val requesting: Boolean,
    val permanentlyDenied: Boolean,
    val retry: () -> Unit,
    val refresh: () -> Unit,
    val openSettings: () -> Unit
)

/** Injectable platform boundary; the production provider always reads the actual system state. */
internal interface HostAppListPermissionBackend {
    fun access(context: Context): HostAppListAccess
    fun wasRequested(context: Context): Boolean
    fun markRequested(context: Context)
    fun canRequest(context: Context): Boolean
}

internal val LocalHostAppListPermissionBackend = staticCompositionLocalOf<HostAppListPermissionBackend> {
    object : HostAppListPermissionBackend {
        override fun access(context: Context) = AppListPermission.access(context)
        override fun wasRequested(context: Context) = AppListPermission.wasRequested(context)
        override fun markRequested(context: Context) = AppListPermission.markRequested(context)
        override fun canRequest(context: Context) = AppListPermission.canRequest(context)
    }
}

/** One lifecycle/permission-result gate for every host enumeration entry point. */
@Composable
internal fun rememberHostAppListPermission(
    loadKey: Any? = Unit,
    enabled: Boolean = true,
    onReady: (forceRefresh: Boolean) -> Unit,
    onUnavailable: (HostAppListAccess) -> Unit
): HostAppListPermissionUi {
    val context = LocalContext.current
    val backend = LocalHostAppListPermissionBackend.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var access by remember(context, backend) { mutableStateOf(backend.access(context)) }
    var requesting by rememberSaveable { mutableStateOf(false) }
    var returningFromSettings by rememberSaveable { mutableStateOf(false) }
    var requestNeedsFreshScan by rememberSaveable { mutableStateOf(false) }
    var verificationPending by rememberSaveable { mutableStateOf(false) }
    var verificationForcesScan by rememberSaveable { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var verificationGeneration by remember { mutableIntStateOf(0) }
    var verificationJob by remember { mutableStateOf<Job?>(null) }
    var lastDelivered by remember(context, backend, loadKey, enabled) { mutableStateOf<HostAppListAccess?>(null) }
    val ready by rememberUpdatedState(onReady)
    val unavailable by rememberUpdatedState(onUnavailable)
    val currentlyEnabled by rememberUpdatedState(enabled)

    fun isPending(): Boolean = currentlyEnabled && (lastDelivered == null || requesting || checking ||
        verificationPending || returningFromSettings)

    fun publish(force: Boolean, next: HostAppListAccess = backend.access(context)) {
        access = next
        if (!currentlyEnabled) return
        val previous = lastDelivered
        lastDelivered = next
        if (force || previous != next) NexLog.i("Permissions",
            "action=refreshHostAppList native=${next.native} vendor=${next.miui} canEnumerate=${next.canEnumerate} forced=$force")
        if (next.canEnumerate) {
            if (force || previous != next) ready(force || previous != null)
        } else if (force || previous != next) unavailable(next)
    }

    fun cancelVerification() {
        verificationGeneration++
        verificationJob?.cancel()
        verificationJob = null
        checking = false
    }

    fun verifyAfterReturn(force: Boolean) {
        cancelVerification()
        requesting = false
        verificationPending = true
        verificationForcesScan = verificationForcesScan || force
        if (!currentlyEnabled || !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val generation = verificationGeneration
        checking = true
        verificationJob = scope.launch {
            try {
                // MIUI may deliver an ActivityResult/ON_RESUME before its actual permission
                // bit is readable. A bounded re-read catches that propagation without either
                // trusting the result boolean or caching a denied/empty list indefinitely.
                val next = awaitHostAppListAccess(read = { backend.access(context) })
                if (generation == verificationGeneration) {
                    val forceScan = verificationForcesScan
                    verificationPending = false
                    verificationForcesScan = false
                    requestNeedsFreshScan = false
                    publish(forceScan, next)
                }
            } finally {
                if (generation == verificationGeneration) {
                    checking = false
                    verificationJob = null
                }
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        verifyAfterReturn(force = requestNeedsFreshScan)
    }

    fun openSettings() {
        returningFromSettings = true
        if (!AppListPermission.openSettings(context)) {
            returningFromSettings = false
            Toast.makeText(context, R.string.app_list_permission_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    fun request(force: Boolean, automatic: Boolean) {
        if (!currentlyEnabled || requesting || checking || verificationPending || returningFromSettings) return
        val next = backend.access(context)
        if (next.miui != MiuiAppListPermission.DENIED) {
            publish(force, next)
            return
        }
        // Auto-prompt only before the first request. Denial never creates a resume/recompose loop.
        if (automatic && backend.wasRequested(context)) {
            publish(force, next)
            return
        }
        if (!backend.canRequest(context)) {
            publish(force, next)
            if (!automatic) openSettings()
            return
        }
        // A requestable first read is pending, not a settled denial. Do not invalidate the
        // list or publish permission help before the system request has produced a result.
        access = next
        requesting = true
        requestNeedsFreshScan = true
        try {
            backend.markRequested(context)
            launcher.launch(AppListPermission.MIUI_PERMISSION)
        } catch (error: RuntimeException) {
            NexLog.e("Permissions", "action=requestHostAppList failed", error)
            requesting = false
            requestNeedsFreshScan = false
            publish(force = true)
            Toast.makeText(context, R.string.app_list_permission_request_failed, Toast.LENGTH_LONG).show()
        }
    }

    val evaluate by rememberUpdatedState<() -> Unit> {
        if (currentlyEnabled) {
            if (requesting || verificationPending || returningFromSettings) {
                val force = requestNeedsFreshScan || returningFromSettings
                returningFromSettings = false
                verifyAfterReturn(force)
            } else request(force = false, automatic = true)
        }
    }
    DisposableEffect(lifecycleOwner, context, backend, loadKey, enabled) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> evaluate()
                Lifecycle.Event.ON_STOP -> cancelVerification()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cancelVerification()
        }
    }
    // Cover the first composition (before ON_RESUME) and paused result verification as well
    // as the actual dialog. Only a delivered decision can expose the unavailable state.
    return HostAppListPermissionUi(access, isPending(),
        access.miui == MiuiAppListPermission.DENIED && !backend.canRequest(context),
        retry = { request(force = true, automatic = false) },
        refresh = {
            if (currentlyEnabled && !isPending()) publish(force = true)
        },
        openSettings = ::openSettings)
}

/** Grant propagation is finite and cancellable; a denial never becomes an optimistic grant. */
internal suspend fun awaitHostAppListAccess(
    read: () -> HostAppListAccess,
    pause: suspend (Long) -> Unit = { delay(it) }
): HostAppListAccess {
    var access = read()
    if (access.canEnumerate) return access
    for (waitMillis in longArrayOf(80, 160, 320, 640, 1000)) {
        pause(waitMillis)
        access = read()
        if (access.canEnumerate) return access
    }
    return access
}
