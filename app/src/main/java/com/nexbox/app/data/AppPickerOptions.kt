package com.nexbox.app.data

import android.content.Context
import android.content.SharedPreferences
import com.nexbox.app.bean.InstalledAppBean
import com.nexbox.app.util.NexLog
import java.io.File
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppPickerSort { NAME, PACKAGE_NAME, APK_SIZE, FIRST_INSTALL_TIME, LAST_UPDATE_TIME }

data class AppPickerOptions(
    val sort: AppPickerSort = AppPickerSort.NAME,
    val reverse: Boolean = false,
    val showPackageName: Boolean = true,
    val showSystemApps: Boolean = false
)

/** Host preferences are independent of a virtual user's app order and appearance settings. */
class AppPickerPreferences internal constructor(private val preferences: SharedPreferences) {
    private val mutableOptions = MutableStateFlow(AppPickerOptions())
    val options: StateFlow<AppPickerOptions> = mutableOptions.asStateFlow()
    private val mutableSaveErrors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saveErrors = mutableSaveErrors.asSharedFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith(PREFIX)) synchronized(preferences) {
            read()?.let { mutableOptions.value = it }
        }
    }

    init {
        synchronized(preferences) {
            preferences.registerOnSharedPreferenceChangeListener(listener)
            read()?.let { mutableOptions.value = it }
        }
    }

    fun update(change: (AppPickerOptions) -> AppPickerOptions): Boolean = synchronized(preferences) {
        val next = change(read() ?: mutableOptions.value)
        try {
            preferences.edit().putString("${PREFIX}sort", next.sort.name)
                .putBoolean("${PREFIX}reverse", next.reverse)
                .putBoolean("${PREFIX}package_name", next.showPackageName)
                .putBoolean("${PREFIX}system_apps", next.showSystemApps).apply()
            mutableOptions.value = next
            NexLog.i("AppPicker", "action=saveOptions options=$next")
            true
        } catch (error: RuntimeException) {
            NexLog.e("AppPicker", "action=saveOptions rejected=true", error)
            mutableSaveErrors.tryEmit(Unit)
            false
        }
    }

    private fun read(): AppPickerOptions? = runCatching { decode(preferences.all) }
        .onFailure { NexLog.w("AppPicker", "action=loadOptions fallback=previous_or_default", it) }.getOrNull()

    companion object {
        private const val PREFIX = "app_picker_"
        @Volatile private var instance: AppPickerPreferences? = null
        fun get(context: Context): AppPickerPreferences = instance ?: synchronized(this) {
            instance ?: AppPickerPreferences(context.applicationContext.getSharedPreferences(
                AppearanceRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)).also { instance = it }
        }

        internal fun decode(values: Map<String, *>): AppPickerOptions = AppPickerOptions(
            sort = AppPickerSort.entries.firstOrNull { it.name == values["${PREFIX}sort"] } ?: AppPickerSort.NAME,
            reverse = values["${PREFIX}reverse"] as? Boolean ?: false,
            showPackageName = values["${PREFIX}package_name"] as? Boolean ?: true,
            showSystemApps = values["${PREFIX}system_apps"] as? Boolean ?: false
        )
    }
}

/** Sorting is done only when metadata/options/locale change, not on every keystroke. */
internal fun sortHostApps(apps: List<InstalledAppBean>, options: AppPickerOptions, locale: Locale): List<InstalledAppBean> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    return apps.filter { options.showSystemApps || !it.isSystemApp }.sortedWith { left, right ->
        val comparison = when (options.sort) {
            AppPickerSort.NAME -> ordered(collator.compare(left.name, right.name), options.reverse)
            AppPickerSort.PACKAGE_NAME -> ordered(left.packageName.compareTo(right.packageName, ignoreCase = true), options.reverse)
            AppPickerSort.APK_SIZE -> compareKnown(left.apkSizeBytes, right.apkSizeBytes, options.reverse)
            AppPickerSort.FIRST_INSTALL_TIME -> compareKnown(left.firstInstallTime, right.firstInstallTime, options.reverse)
            AppPickerSort.LAST_UPDATE_TIME -> compareKnown(left.lastUpdateTime, right.lastUpdateTime, options.reverse)
        }
        if (comparison != 0) comparison else left.packageName.compareTo(right.packageName)
    }
}

private fun ordered(value: Int, reverse: Boolean) = if (reverse) -value.compareTo(0) else value
private fun compareKnown(left: Long?, right: Long?, reverse: Boolean): Int = when {
    left == null -> if (right == null) 0 else 1
    right == null -> -1
    else -> ordered(left.compareTo(right), reverse)
}

internal fun filterHostApps(apps: List<InstalledAppBean>, query: String): List<InstalledAppBean> =
    if (query.isBlank()) apps else apps.filter { it.name.contains(query, true) || it.packageName.contains(query, true) }

/** Base plus distinct split APKs, never data/cache. A missing component means unknown, not zero. */
internal fun installedApkSize(basePath: String?, splitPaths: Array<String>?): Long? {
    if (basePath.isNullOrBlank()) return null
    return runCatching {
        (listOf(basePath) + splitPaths.orEmpty()).distinct().fold(0L) { size, path ->
            val file = File(path)
            check(file.isFile && file.canRead())
            Math.addExact(size, file.length())
        }
    }.getOrNull()
}
