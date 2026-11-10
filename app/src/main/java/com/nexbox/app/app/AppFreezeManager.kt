package com.nexbox.app.app

import com.nexbox.app.data.UserIdentityResolver
import com.nexbox.app.util.NexLog

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import com.nexbox.core.NexBoxCore
import com.nexbox.core.core.env.ExternalActivityGuard
import com.nexbox.app.data.CloneInstallCoordinator
import com.nexbox.app.data.RuntimeSettingsChanges
import com.nexbox.app.data.RuntimeSettingsRepository

object AppFreezeManager {
    private const val TAG = "AppFreezeManager"
    private const val PREFS_NAME = "AppFreezeRules"
    internal const val KEY_GLOBAL_AUTO_FREEZE = "global_auto_freeze_enabled"
    internal const val KEY_INSTANT_FREEZE_BUTTON = "instant_freeze_button_enabled"
    private const val AUTO_FREEZE_DELAY_MS = 1200L
    private const val EXTERNAL_FLOW_RECHECK_MS = 1000L

    private val handler = Handler(Looper.getMainLooper())
    private val startedActivityCounts = ConcurrentHashMap<String, Int>()
    private val pendingStops = ConcurrentHashMap<String, Runnable>()

    internal fun preferences() =
            App.getContext()
                    .getSharedPreferences(
                            PREFS_NAME,
                            Context.MODE_PRIVATE
                    )

    fun isGlobalAutoFreezeEnabled(): Boolean {
        return preferences().getBoolean(KEY_GLOBAL_AUTO_FREEZE, false)
    }

    fun setGlobalAutoFreezeEnabled(enabled: Boolean) {
        RuntimeSettingsRepository.apply(RuntimeSettingsChanges(autoFreeze = enabled))
    }

    fun isInstantFreezeButtonEnabled(): Boolean {
        return preferences().getBoolean(KEY_INSTANT_FREEZE_BUTTON, false)
    }

    fun setInstantFreezeButtonEnabled(enabled: Boolean) {
        RuntimeSettingsRepository.apply(RuntimeSettingsChanges(instantFreezeButton = enabled))
    }

    fun shouldShowInstantFreezeButton(): Boolean {
        return isInstantFreezeButtonEnabled()
    }

    fun isExcludedFromAutoFreeze(packageName: String, userId: Int): Boolean {
        return preferences().getBoolean(exclusionKey(packageName, userId), false)
    }

    fun setExcludedFromAutoFreeze(packageName: String, userId: Int, excluded: Boolean) {
        RuntimeSettingsRepository.apply(RuntimeSettingsChanges(freezeExcluded = excluded,
            excludePackage = packageName, excludeUser = UserIdentityResolver.captureTarget(userId)))
    }

    fun shouldAutoFreeze(packageName: String, userId: Int): Boolean {
        return isGlobalAutoFreezeEnabled() && !isExcludedFromAutoFreeze(packageName, userId)
    }

    fun onActivityStarted(packageName: String?, userId: Int) {
        val key = activityKey(packageName, userId) ?: return
        cancelPendingFreeze(key)
        ExternalActivityGuard.clear(packageName, userId)
        val updatedCount = (startedActivityCounts[key] ?: 0) + 1
        startedActivityCounts[key] = updatedCount
        NexLog.d(TAG, "onActivityStarted: $key count=$updatedCount")
    }

    fun onActivityResumed(packageName: String?, userId: Int) {
        val key = activityKey(packageName, userId) ?: return
        cancelPendingFreeze(key)
        ExternalActivityGuard.clear(packageName, userId)
    }

    fun onActivityStopped(packageName: String?, userId: Int, isChangingConfigurations: Boolean) {
        val key = activityKey(packageName, userId) ?: return
        val currentCount = startedActivityCounts[key] ?: 0
        val updatedCount = (currentCount - 1).coerceAtLeast(0)
        if (updatedCount == 0) {
            startedActivityCounts.remove(key)
        } else {
            startedActivityCounts[key] = updatedCount
        }
        NexLog.d(
                TAG,
                "onActivityStopped: $key count=$updatedCount changingConfigurations=$isChangingConfigurations"
        )

        if (isChangingConfigurations) {
            return
        }
        if (!shouldAutoFreeze(packageName.orEmpty(), userId)) {
            NexLog.d(TAG, "action=autoFreeze package=$packageName userId=$userId skipped=policy")
            return
        }
        if (updatedCount == 0) {
            scheduleFreeze(packageName.orEmpty(), userId, key)
        }
    }

    private fun scheduleFreeze(packageName: String, userId: Int, key: String) {
        cancelPendingFreeze(key)
        NexLog.d(TAG, "action=autoFreeze stage=scheduled package=$packageName userId=$userId delayMs=$AUTO_FREEZE_DELAY_MS")
        lateinit var runnable: Runnable
        runnable = Runnable {
            if (!shouldAutoFreeze(packageName, userId)) {
                pendingStops.remove(key)
                return@Runnable
            }
            if ((startedActivityCounts[key] ?: 0) > 0) {
                pendingStops.remove(key)
                return@Runnable
            }
            val remainingMs = ExternalActivityGuard.getRemainingMs(packageName, userId)
            if (remainingMs > 0L) {
                val retryDelayMs = minOf(remainingMs, EXTERNAL_FLOW_RECHECK_MS)
                NexLog.d(
                        TAG,
                        "Delaying auto-freeze for external activity flow: $packageName userId=$userId remainingMs=$remainingMs"
                )
                pendingStops[key] = runnable
                handler.postDelayed(runnable, retryDelayMs)
                return@Runnable
            }
            pendingStops.remove(key)
            try {
                NexLog.i(TAG, "Auto-freezing package after exit: $packageName userId=$userId")
                NexBoxCore.get().stopPackage(packageName, userId)
            } catch (e: Exception) {
                NexLog.e(TAG, "Auto-freeze failed for $packageName userId=$userId: ${e.message}", e)
            }
        }
        pendingStops[key] = runnable
        handler.postDelayed(runnable, AUTO_FREEZE_DELAY_MS)
    }

    private fun cancelPendingFreeze(key: String) {
        pendingStops.remove(key)?.let { handler.removeCallbacks(it) }
    }

    private fun cancelAllPendingFreezes() {
        pendingStops.values.forEach { handler.removeCallbacks(it) }
        pendingStops.clear()
    }

    fun clearUser(userId: Int) {
        NexLog.i(TAG, "action=clearFreezeRules userId=$userId stage=start")
        val preferences = preferences()
        val edit = preferences.edit()
        preferences.all.keys.filter { it.startsWith("exclude_auto_freeze_${userId}_") }
            .forEach(edit::remove)
        check(edit.commit()) { "Could not clear freeze rules" }
        // Both maps and Handler.removeCallbacks are thread-safe. Finish removing
        // old callbacks before a subsequent install is allowed to reuse this ID.
        pendingStops.keys.filter { it.startsWith("$userId:") }.toList().forEach(::cancelPendingFreeze)
        startedActivityCounts.keys.removeAll { it.startsWith("$userId:") }
        NexLog.i(TAG, "action=clearFreezeRules userId=$userId stage=finish")
    }

    internal fun onSettingsSaved() {
        NexLog.i(TAG, "action=saveRule success=true")
        if (!isGlobalAutoFreezeEnabled()) cancelAllPendingFreezes()
    }

    internal fun exclusionKey(packageName: String, userId: Int): String {
        return "exclude_auto_freeze_${userId}_$packageName"
    }

    private fun activityKey(packageName: String?, userId: Int): String? {
        if (packageName.isNullOrBlank()) {
            return null
        }
        return "$userId:$packageName"
    }
}
