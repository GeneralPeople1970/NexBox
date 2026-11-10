package com.nexbox.app.data

import android.content.Context
import android.content.SharedPreferences
import com.nexbox.app.app.App
import com.nexbox.app.app.AppFreezeManager
import com.nexbox.app.util.NexLog
import com.nexbox.core.utils.ConfigurationAppStopper

data class RuntimeSettingsChanges(
    val hideRoot: Boolean? = null,
    val autoFreeze: Boolean? = null,
    val instantFreezeButton: Boolean? = null,
    val freezeExcluded: Boolean? = null,
    val excludeUser: CloneTarget? = null,
    val excludePackage: String? = null
)

class RuntimeSettingsSaveException(val unconfirmedKeys: Set<String>, cause: Throwable) : IllegalStateException(
    if (unconfirmedKeys.isEmpty()) "Runtime settings were not saved; previous values were restored"
    else "Runtime settings could not be fully saved; some values may already be persisted", cause)

/** IO-only: persist the complete request before stopping its combined set of affected guests. */
object RuntimeSettingsRepository {
    private const val HIDE_ROOT_KEY = "mHideRoot"

    // The host is the sole writer; the long-lived core service must also reload after a commit.
    @Suppress("DEPRECATION")
    fun isHideRootEnabled(): Boolean = App.getContext().getSharedPreferences(
        "app_prefs", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
    ).getBoolean(HIDE_ROOT_KEY, false)

    fun apply(changes: RuntimeSettingsChanges) = synchronized(UsersRepository.mutationLock) {
        val host = App.getContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val freeze = AppFreezeManager.preferences()
        val oldRoot = host.getBoolean(HIDE_ROOT_KEY, false)
        val oldAuto = freeze.getBoolean(AppFreezeManager.KEY_GLOBAL_AUTO_FREEZE, false)
        val oldButton = freeze.getBoolean(AppFreezeManager.KEY_INSTANT_FREEZE_BUTTON, false)
        val nextAuto = changes.autoFreeze ?: oldAuto
        val hostUpdates = linkedMapOf<String, Boolean>()
        val freezeUpdates = linkedMapOf<String, Boolean>()
        changes.hideRoot?.takeIf { it != oldRoot }?.let { hostUpdates[HIDE_ROOT_KEY] = it }
        changes.autoFreeze?.takeIf { it != oldAuto }?.let { freezeUpdates[AppFreezeManager.KEY_GLOBAL_AUTO_FREEZE] = it }
        changes.instantFreezeButton?.takeIf { it != oldButton }?.let { freezeUpdates[AppFreezeManager.KEY_INSTANT_FREEZE_BUTTON] = it }
        if (changes.freezeExcluded != null) {
            val user = requireNotNull(changes.excludeUser) { "An exact user identity is required" }
            val packageName = requireNotNull(changes.excludePackage?.takeIf { it.isNotBlank() }) { "A package is required" }
            require(user.createTime > 0L)
            UserIdentityResolver.validateTarget(user)
            val key = AppFreezeManager.exclusionKey(packageName, user.userId)
            if (freeze.getBoolean(key, false) != changes.freezeExcluded) freezeUpdates[key] = changes.freezeExcluded
        }
        if (hostUpdates.isEmpty() && freezeUpdates.isEmpty()) return@synchronized

        val candidates = ConfigurationAppStopper.captureAllVirtualApps()
        if (changes.freezeExcluded != null) {
            val user = requireNotNull(changes.excludeUser)
            check(candidates.any { it.userId == user.userId &&
                it.generation == user.createTime && it.packageName == changes.excludePackage }) {
                "The runtime settings target is no longer available"
            }
        }
        val affected = candidates.filter { target ->
            val key = AppFreezeManager.exclusionKey(target.packageName, target.userId)
            val oldExcluded = freeze.getBoolean(key, false)
            val nextExcluded = freezeUpdates[key] ?: oldExcluded
            hostUpdates.isNotEmpty() || changes.instantFreezeButton?.let { it != oldButton } == true ||
                (oldAuto && !oldExcluded) != (nextAuto && !nextExcluded)
        }
        val originalHost = host.all
        val originalFreeze = freeze.all
        val operation = NexLog.operation("Settings", "saveRuntime", "keys=${(hostUpdates.keys + freezeUpdates.keys).joinToString()} affected=${affected.size}")
        try {
            check(write(host, hostUpdates)) { "Could not save runtime settings" }
            check(write(freeze, freezeUpdates)) { "Could not save freeze settings" }
        } catch (failure: Exception) {
            val unconfirmedKeys = linkedSetOf<String>()
            try { if (!restore(host, hostUpdates.keys, originalHost)) unconfirmedKeys += hostUpdates.keys }
            catch (rollback: Exception) { unconfirmedKeys += hostUpdates.keys; failure.addSuppressed(rollback) }
            try { if (!restore(freeze, freezeUpdates.keys, originalFreeze)) unconfirmedKeys += freezeUpdates.keys }
            catch (rollback: Exception) { unconfirmedKeys += freezeUpdates.keys; failure.addSuppressed(rollback) }
            operation.fail(failure, "rollbackConfirmed=${unconfirmedKeys.isEmpty()} unconfirmedKeys=${unconfirmedKeys.joinToString()}")
            throw RuntimeSettingsSaveException(unconfirmedKeys.toSet(), failure)
        }
        AppFreezeManager.onSettingsSaved()
        operation.step("saved")
        try {
            ConfigurationAppStopper.stop(affected)
            operation.finish()
        } catch (failure: Exception) {
            operation.fail(failure, "configurationSaved=true stopFailed=true")
            throw failure
        }
    }

    private fun write(preferences: SharedPreferences, values: Map<String, Boolean>): Boolean {
        if (values.isEmpty()) return true
        return preferences.edit().apply { values.forEach { (key, value) -> putBoolean(key, value) } }.commit()
    }

    private fun restore(preferences: SharedPreferences, keys: Set<String>, original: Map<String, *>): Boolean {
        if (keys.isEmpty()) return true
        return preferences.edit().apply {
            keys.forEach { key ->
                if (key in original) putBoolean(key, original[key] as Boolean) else remove(key)
            }
        }.commit()
    }
}
