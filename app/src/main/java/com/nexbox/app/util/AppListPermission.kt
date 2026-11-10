package com.nexbox.app.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Locale
import kotlinx.coroutines.CancellationException

internal enum class AppListPermissionState { GRANTED, DENIED, UNKNOWN }

internal enum class MiuiAppListPermission { UNSUPPORTED, GRANTED, DENIED, UNKNOWN }

internal data class HostAppListAccess(val native: AppListPermissionState, val miui: MiuiAppListPermission) {
    // Unsupported ROMs retain Android's native package visibility behavior, even if its bit is unreadable.
    val canEnumerate: Boolean get() = miui == MiuiAppListPermission.UNSUPPORTED || miui == MiuiAppListPermission.GRANTED
    val state: AppListPermissionState get() = when (miui) {
        MiuiAppListPermission.DENIED -> AppListPermissionState.DENIED
        MiuiAppListPermission.UNKNOWN -> AppListPermissionState.UNKNOWN
        MiuiAppListPermission.GRANTED -> AppListPermissionState.GRANTED
        else -> native
    }
}

internal class HostAppListAccessException(val access: HostAppListAccess) : SecurityException("Application list permission required")

/** QUERY_ALL_PACKAGES stays a manifest permission; only the capability-detected OEM permission is requested. */
internal object AppListPermission {
    const val MIUI_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"

    fun read(context: Context): AppListPermissionState = access(context).state

    @Suppress("DEPRECATION")
    fun access(context: Context): HostAppListAccess = HostAppListAccess(
        native = inspect(Build.VERSION.SDK_INT) {
            context.checkSelfPermission(Manifest.permission.QUERY_ALL_PACKAGES) == PackageManager.PERMISSION_GRANTED
        },
        miui = inspectMiui(supported = {
            try { context.packageManager.getPermissionInfo(MIUI_PERMISSION, 0); true }
            catch (_: PackageManager.NameNotFoundException) { false }
        }, granted = { context.checkSelfPermission(MIUI_PERMISSION) == PackageManager.PERMISSION_GRANTED })
    )

    internal fun inspectMiui(supported: () -> Boolean, granted: () -> Boolean): MiuiAppListPermission = try {
        if (!supported()) MiuiAppListPermission.UNSUPPORTED
        else if (granted()) MiuiAppListPermission.GRANTED else MiuiAppListPermission.DENIED
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { MiuiAppListPermission.UNKNOWN }

    fun requireHostAccess(context: Context) {
        val access = access(context)
        if (!access.canEnumerate) {
            NexLog.w("Permissions", "action=enumerateApps rejected=true native=${access.native} vendor=${access.miui}")
            throw HostAppListAccessException(access)
        }
    }

    fun wasRequested(context: Context): Boolean = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        .all["miui_app_list_requested"] as? Boolean ?: false

    fun markRequested(context: Context) {
        NexLog.i("Permissions", "action=requestHostAppList")
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("miui_app_list_requested", true).apply()
    }

    fun canRequest(context: Context): Boolean = !wasRequested(context) ||
        context.activity()?.shouldShowRequestPermissionRationale(MIUI_PERMISSION) == true

    /** An unreadable vendor permission state is not evidence of a denial. */
    fun inspect(sdkInt: Int, isGranted: () -> Boolean): AppListPermissionState {
        if (sdkInt < 30) return AppListPermissionState.GRANTED
        return try {
            if (isGranted()) AppListPermissionState.GRANTED else AppListPermissionState.DENIED
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AppListPermissionState.UNKNOWN
        }
    }

    fun isMiuiFamily(manufacturer: String?, brand: String?): Boolean {
        val vendors = setOf("xiaomi", "redmi", "poco")
        return listOf(manufacturer, brand).any {
            val vendor = it?.trim()?.lowercase(Locale.ROOT)
            vendor != null && vendor in vendors
        }
    }

    fun isMiuiDevice(): Boolean = isMiuiFamily(Build.MANUFACTURER, Build.BRAND)

    fun shouldShowHelp(
        state: AppListPermissionState,
        loading: Boolean,
        appCount: Int,
        miuiDevice: Boolean
    ): Boolean = !loading && appCount == 0 && (state != AppListPermissionState.GRANTED || miuiDevice)

    fun shouldRefreshAfterResume(
        previous: AppListPermissionState,
        current: AppListPermissionState,
        returningFromSettings: Boolean
    ): Boolean = returningFromSettings || previous != current

    internal enum class SettingsTarget { MIUI_PERMISSIONS, APP_DETAILS, APP_LIST, SYSTEM_SETTINGS }

    fun settingsTargets(miuiDevice: Boolean): List<SettingsTarget> = buildList {
        if (miuiDevice) add(SettingsTarget.MIUI_PERMISSIONS)
        add(SettingsTarget.APP_DETAILS)
        add(SettingsTarget.APP_LIST)
        add(SettingsTarget.SYSTEM_SETTINGS)
    }

    /** Avoid resolveActivity: package-visibility restrictions can hide an otherwise usable handler. */
    fun openSettings(context: Context): Boolean = trySettingsTargets(settingsTargets(isMiuiDevice())) { target ->
        val intent = when (target) {
            SettingsTarget.MIUI_PERMISSIONS -> Intent("miui.intent.action.APP_PERM_EDITOR")
                .setPackage("com.miui.securitycenter")
                .putExtra("extra_pkgname", context.packageName)
            SettingsTarget.APP_DETAILS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
            SettingsTarget.APP_LIST -> Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
            SettingsTarget.SYSTEM_SETTINGS -> Intent(Settings.ACTION_SETTINGS)
        }
        if (!context.hasActivity()) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** OEM targets may be absent or non-exported; each failure falls back without changing permissions. */
    fun trySettingsTargets(targets: List<SettingsTarget>, start: (SettingsTarget) -> Unit): Boolean {
        for (target in targets) {
            try {
                start(target)
                NexLog.i("Permissions", "action=openSettings target=$target dispatched=true")
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (error: Exception) {
                NexLog.w("Permissions", "action=openSettings target=$target fallback=true", error)
                // ActivityNotFoundException and SecurityException are normal on other ROM versions.
            }
        }
        return false
    }

    private tailrec fun Context.hasActivity(): Boolean = when (this) {
        is Activity -> true
        is ContextWrapper -> baseContext !== this && baseContext.hasActivity()
        else -> false
    }

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> if (baseContext !== this) baseContext.activity() else null
        else -> null
    }
}
