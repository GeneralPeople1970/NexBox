package com.nexbox.app.data

import android.content.Context
import android.content.SharedPreferences
import com.nexbox.app.util.NexLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UiStyle(val storedValue: String) {
    MATERIAL3("material3"), MIUIX("miuix");

    companion object {
        fun fromStoredValue(value: String?) = entries.firstOrNull { it.storedValue == value } ?: MATERIAL3
    }
}

enum class ThemeMode(val storedValue: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        fun fromStoredValue(value: String?) = entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

data class AppearanceSettings(
    val uiStyle: UiStyle = UiStyle.MATERIAL3,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val liquidGlassEnabled: Boolean = false,
    val predictiveBackEnabled: Boolean = false
)

/**
 * Host-only appearance preferences. A synchronous initial value avoids a wrong-theme first frame.
 * Keep the legacy preference file so upgrading does not discard an existing dark-mode choice.
 *
 * Each edit changes only its own key. The preference monitor also serializes snapshots and flow
 * updates, so concurrent setters/listener callbacks cannot publish an older combination of settings.
 * [SharedPreferences.Editor.apply] keeps disk I/O off the UI thread; it does not report disk failures.
 */
class AppearanceRepository internal constructor(private val preferences: SharedPreferences) {
    private val lock = preferences
    private val mutableSettings = MutableStateFlow(AppearanceSettings())
    val settings: StateFlow<AppearanceSettings> = mutableSettings.asStateFlow()

    private val mutableSaveErrors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Synchronous storage errors. The current selection is kept when an edit is rejected. */
    val saveErrors: SharedFlow<Unit> = mutableSaveErrors.asSharedFlow()

    // SharedPreferences retains listeners weakly; keep this process-lifetime listener strongly held.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in APPEARANCE_KEYS) {
            synchronized(lock) {
                readSnapshot()?.let { mutableSettings.value = it.toAppearanceSettings() }
            }
        }
    }

    init {
        synchronized(lock) {
            // Register before reading to avoid missing a change between the initial snapshot and
            // listener registration. Initialization completes before any UI observes this instance.
            preferences.registerOnSharedPreferenceChangeListener(listener)
            readSnapshot()?.let { values ->
                mutableSettings.value = values.toAppearanceSettings()
                values.legacyThemeMode()?.let(::setThemeMode)
            }
        }
    }

    fun setUiStyle(style: UiStyle) {
        writePreference(
            edit = { putString(KEY_UI_STYLE, style.storedValue) },
            update = { it.copy(uiStyle = style) }
        )
    }

    fun setThemeMode(mode: ThemeMode) {
        writePreference(
            edit = { putString(KEY_THEME_MODE, mode.storedValue) },
            update = { it.copy(themeMode = mode) }
        )
    }

    fun setLiquidGlassEnabled(enabled: Boolean) {
        writePreference(
            edit = { putBoolean(KEY_LIQUID_GLASS, enabled) },
            update = { it.copy(liquidGlassEnabled = enabled) }
        )
    }

    fun setPredictiveBackEnabled(enabled: Boolean) {
        writePreference(
            edit = { putBoolean(KEY_PREDICTIVE_BACK, enabled) },
            update = { it.copy(predictiveBackEnabled = enabled) }
        )
    }

    /** Atomic host preference edit shared with Debug CLI; apply() cannot confirm disk persistence. */
    fun update(change: (AppearanceSettings) -> AppearanceSettings): Boolean = synchronized(lock) {
        val previous = mutableSettings.value
        val next = change(previous)
        if (next == previous) return@synchronized true
        writePreference(edit = {
            if (next.uiStyle != previous.uiStyle) putString(KEY_UI_STYLE, next.uiStyle.storedValue)
            if (next.themeMode != previous.themeMode) putString(KEY_THEME_MODE, next.themeMode.storedValue)
            if (next.liquidGlassEnabled != previous.liquidGlassEnabled) putBoolean(KEY_LIQUID_GLASS, next.liquidGlassEnabled)
            if (next.predictiveBackEnabled != previous.predictiveBackEnabled) putBoolean(KEY_PREDICTIVE_BACK, next.predictiveBackEnabled)
        }, update = { next })
    }

    private fun writePreference(
        edit: SharedPreferences.Editor.() -> Unit,
        update: (AppearanceSettings) -> AppearanceSettings
    ): Boolean {
        synchronized(lock) {
            val previous = mutableSettings.value
            try {
                preferences.edit().apply(edit).apply()
            } catch (error: RuntimeException) {
                NexLog.e("Appearance", "action=save success=false", error)
                mutableSettings.value = previous
                mutableSaveErrors.tryEmit(Unit)
                return false
            }
            // apply() has already updated the in-memory preference map, even when a listener is
            // scheduled for a later main-thread turn. A read failure must not discard known state.
            mutableSettings.value = readSnapshot()?.toAppearanceSettings() ?: update(previous)
            NexLog.i("Appearance", "action=save before=$previous after=${mutableSettings.value}")
            return true
        }
    }

    private fun readSnapshot(): Map<String, *>? = try {
        // One snapshot prevents mixed versions and allows wrong-typed/corrupt keys to fall back
        // individually instead of crashing getString()/getBoolean() during application startup.
        preferences.all
    } catch (error: RuntimeException) {
        NexLog.w("Appearance", "action=load usingPreviousOrDefault=true", error)
        null
    }

    private fun Map<String, *>.toAppearanceSettings() = AppearanceSettings(
        uiStyle = UiStyle.fromStoredValue(this[KEY_UI_STYLE] as? String),
        themeMode = legacyThemeMode() ?: ThemeMode.fromStoredValue(this[KEY_THEME_MODE] as? String),
        liquidGlassEnabled = this[KEY_LIQUID_GLASS] as? Boolean ?: false,
        predictiveBackEnabled = this[KEY_PREDICTIVE_BACK] as? Boolean ?: false
    )

    private fun Map<String, *>.legacyThemeMode(): ThemeMode? {
        if (containsKey(KEY_THEME_MODE)) return null
        return when (this[LEGACY_DARK_MODE]) {
            true -> ThemeMode.DARK
            false -> ThemeMode.LIGHT
            else -> null
        }
    }

    companion object {
        const val PREFERENCES_NAME = "app_prefs"
        const val KEY_UI_STYLE = "appearance_ui_style"
        const val KEY_THEME_MODE = "appearance_theme_mode"
        const val KEY_LIQUID_GLASS = "appearance_liquid_glass"
        const val KEY_PREDICTIVE_BACK = "appearance_predictive_back"
        const val LEGACY_DARK_MODE = "dark_mode"
        private val APPEARANCE_KEYS = setOf(KEY_UI_STYLE, KEY_THEME_MODE, KEY_LIQUID_GLASS,
            KEY_PREDICTIVE_BACK, LEGACY_DARK_MODE)

        @Volatile private var instance: AppearanceRepository? = null

        @JvmStatic
        fun get(context: Context): AppearanceRepository = instance ?: synchronized(this) {
            instance ?: AppearanceRepository(
                context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}
