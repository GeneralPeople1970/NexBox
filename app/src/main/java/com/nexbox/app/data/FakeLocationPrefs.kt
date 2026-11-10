package com.nexbox.app.data

import android.content.Context
import android.content.SharedPreferences
import com.nexbox.app.app.App
import com.nexbox.app.util.NexLog
import java.util.concurrent.CancellationException
import org.json.JSONObject

/** Host preferences belong to a profile generation, never to a recyclable numeric id alone. */
object FakeLocationPrefs {
    private const val PREFS_NAME = "fake_location"
    internal val stateStore: FakeLocationPreferenceStore by lazy {
        FakeLocationPreferenceStore(App.getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }

    fun isEnabled(userId: Int): Boolean = withCurrentUser(userId, false) { created ->
        stateStore.read(userId, created).enabled
    }

    fun point(userId: Int): Pair<Double, Double>? = withCurrentUser(userId, null) { created ->
        stateStore.read(userId, created).point?.let { it.latitude to it.longitude }
    }

    fun setEnabled(userId: Int, enabled: Boolean): Boolean = withCurrentUser(userId, false) { created ->
        stateStore.save(userId, created, stateStore.read(userId, created).copy(enabled = enabled))
    }

    fun setPoint(userId: Int, latitude: Double, longitude: Double): Boolean = withCurrentUser(userId, false) { created ->
        stateStore.save(userId, created, stateStore.read(userId, created).copy(point = FakeLocationPoint(latitude, longitude)))
    }

    /** Call only after confirmed deletion. An optional generation avoids clearing a reused id. */
    fun clearUser(userId: Int, createTime: Long? = null): Boolean = try {
        stateStore.clearUser(userId, createTime)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        NexLog.e("LocationPrefs", "action=clearUser userId=$userId generation=$createTime", error)
        false
    }

    private fun <T> withCurrentUser(userId: Int, fallback: T, action: (Long) -> T): T = try {
        val created = UserIdentityResolver.userIdentity(userId)
        if (userId < 0 || created == null || created <= 0) fallback else action(created)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        NexLog.w("LocationPrefs", "action=readUserState userId=$userId fallback=true", error)
        fallback
    }
}

internal data class SavedFakeLocation(
    val enabled: Boolean = false,
    val point: FakeLocationPoint? = null,
    // null migrates the old all-app behaviour once. An empty set is an explicit empty scope.
    val scopePackages: Set<String>? = null,
    val appLocations: Map<String, SavedAppFakeLocation> = emptyMap()
)

/** An explicit disabled override must not fall back to an enabled group location. */
internal data class SavedAppFakeLocation(
    val enabled: Boolean = false,
    val point: FakeLocationPoint? = null
) {
    val isValid: Boolean get() = point?.isValid != false && (!enabled || point != null)
}

internal interface FakeLocationStateStore {
    fun read(userId: Int, createTime: Long): SavedFakeLocation
    fun save(userId: Int, createTime: Long, state: SavedFakeLocation): Boolean
}

/** Pure storage logic. Legacy enabled_$id/lat_$id/lon_$id values have no trustworthy owner. */
internal class FakeLocationPreferenceStore(private val preferences: SharedPreferences) : FakeLocationStateStore {
    override fun read(userId: Int, createTime: Long): SavedFakeLocation = synchronized(preferences) {
        if (userId < 0 || createTime <= 0) return@synchronized SavedFakeLocation()
        val values = preferences.all
        val prefix = prefix(userId, createTime)
        val lat = (values["${prefix}lat"] as? String)?.toDoubleOrNull()
        val lon = (values["${prefix}lon"] as? String)?.toDoubleOrNull()
        val point = if (lat != null && lon != null) FakeLocationPoint(lat, lon).takeIf { it.isValid } else null
        val scopePackages = (values["${prefix}scope"] as? Set<*>)?.mapNotNull { it as? String }?.toSet()
        val appLocations = values["${prefix}apps"]?.let { stored ->
            check(stored is String) { "Invalid saved app locations" }
            val entries = JSONObject(stored)
            entries.keys().asSequence().associateWith { packageName ->
                check(packageName.isNotBlank()) { "Invalid saved location package" }
                val entry = entries.getJSONObject(packageName)
                val appPoint = if (entry.has("lat") || entry.has("lon")) {
                    FakeLocationPoint(entry.getString("lat").toDouble(), entry.getString("lon").toDouble())
                } else null
                SavedAppFakeLocation(entry.getBoolean("enabled"), appPoint).also {
                    check(it.isValid) { "Invalid saved app location" }
                }
            }
        }.orEmpty()
        SavedFakeLocation(enabled = values["${prefix}enabled"] as? Boolean ?: false,
            point = point, scopePackages = scopePackages, appLocations = appLocations)
    }

    override fun save(userId: Int, createTime: Long, state: SavedFakeLocation): Boolean {
        if (userId < 0 || createTime <= 0 || state.point?.isValid == false ||
            state.appLocations.any { (packageName, location) -> packageName.isBlank() || !location.isValid }) return false
        val prefix = prefix(userId, createTime)
        val appLocations = JSONObject().apply {
            state.appLocations.toSortedMap().forEach { (packageName, location) ->
                put(packageName, JSONObject().apply {
                    put("enabled", location.enabled)
                    location.point?.let { point ->
                        put("lat", point.latitude.toString())
                        put("lon", point.longitude.toString())
                    }
                })
            }
        }.toString()
        val keys = setOf("${prefix}enabled", "${prefix}lat", "${prefix}lon", "${prefix}scope", "${prefix}apps")
        return editChecked(keys) {
            putBoolean("${prefix}enabled", state.enabled)
            putString("${prefix}lat", state.point?.latitude?.toString())
            putString("${prefix}lon", state.point?.longitude?.toString())
            putStringSet("${prefix}scope", state.scopePackages?.toSet())
            putString("${prefix}apps", appLocations)
        }
    }

    fun clearUser(userId: Int, createTime: Long? = null): Boolean = synchronized(preferences) {
        val ownedPrefix = if (createTime == null) "user_${userId}_" else prefix(userId, createTime)
        val legacy = setOf("enabled_$userId", "lat_$userId", "lon_$userId")
        val keys = preferences.all.keys.filter { it.startsWith(ownedPrefix) || it in legacy }.toSet()
        editChecked(keys) { keys.forEach(::remove) }
    }

    private fun editChecked(keys: Set<String>, edit: SharedPreferences.Editor.() -> Unit): Boolean = synchronized(preferences) {
        val previous = try {
            preferences.all
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            NexLog.e("LocationPrefs", "action=snapshot fieldCount=${keys.size}", error)
            return@synchronized false
        }
        var cancellation: CancellationException? = null
        try {
            if (preferences.edit().apply(edit).commit()) return@synchronized true
            NexLog.w("LocationPrefs", "action=commit returned=false fieldCount=${keys.size} rollback=attempting")
        } catch (cancelled: CancellationException) {
            cancellation = cancelled
        } catch (error: Exception) {
            NexLog.e("LocationPrefs", "action=commit failed fieldCount=${keys.size} rollback=attempting", error)
            // Restore only our keys: commit(false) may still have updated SharedPreferences memory.
        }
        try {
            val rollback = preferences.edit()
            keys.forEach { key ->
                when (val value = previous[key]) {
                    is String -> rollback.putString(key, value)
                    is Boolean -> rollback.putBoolean(key, value)
                    is Int -> rollback.putInt(key, value)
                    is Long -> rollback.putLong(key, value)
                    is Float -> rollback.putFloat(key, value)
                    is Set<*> -> rollback.putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> rollback.remove(key)
                }
            }
            val restored = rollback.commit()
            if (!restored) NexLog.e("LocationPrefs", "action=rollback returned=false fieldCount=${keys.size}")
        } catch (cancelled: CancellationException) {
            if (cancellation == null) cancellation = cancelled
        } catch (error: Exception) {
            NexLog.e("LocationPrefs", "action=rollback failed fieldCount=${keys.size}", error)
            // Failure remains explicit even when the best-effort rollback cannot reach storage.
        }
        cancellation?.let { throw it }
        false
    }

    private fun prefix(userId: Int, createTime: Long) = "user_${userId}_${createTime}_"
}
