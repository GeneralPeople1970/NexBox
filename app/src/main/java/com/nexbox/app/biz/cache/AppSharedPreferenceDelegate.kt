package com.nexbox.app.biz.cache

import android.content.Context
import com.nexbox.app.util.NexLog
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class AppSharedPreferenceDelegate<T>(
    private val context: Context,
    private val defaultValue: T,
    private val prefName: String = "app_prefs"
) : ReadWriteProperty<Any?, T> {

    private val prefs by lazy {
        context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
    }

    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        val key = property.name
        return when (defaultValue) {
            is Boolean -> prefs.getBoolean(key, defaultValue) as T
            is String -> (prefs.getString(key, defaultValue) ?: defaultValue) as T
            is Int -> prefs.getInt(key, defaultValue) as T
            is Long -> prefs.getLong(key, defaultValue) as T
            is Float -> prefs.getFloat(key, defaultValue) as T
            else -> defaultValue
        }
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = NexLog.trace(
        "Settings", "writePreference", "key=${property.name} value=${if (value is Boolean || value is Number) value else "<redacted>"}"
    ) {
        val key = property.name
        val editor = prefs.edit()
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
        }
        editor.apply()
    }
}
