package com.nexbox.app.data

import android.content.Context
import android.os.Bundle
import android.util.AtomicFile
import com.nexbox.app.util.NexLog
import com.nexbox.core.utils.MutationReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs

internal data class LocationFavorite(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val version: String = ""
) {
    fun matches(latitude: Double, longitude: Double): Boolean =
        abs(this.latitude - latitude) < 0.000001 && abs(this.longitude - longitude) < 0.000001
}

/** Host bookmarks are shared by editors, independent of guest profiles and simulated locations. */
internal class LocationFavoritesRepository(context: Context, atomicFile: AtomicFile? = null) {
    private val baseFile = File(context.applicationContext.filesDir, "location_favorites.json")
    private val file = atomicFile ?: AtomicFile(baseFile)

    suspend fun load(): List<LocationFavorite> = withContext(Dispatchers.IO) {
        synchronized(lock) { read() }
    }

    suspend fun save(
        id: String?,
        name: String,
        latitude: Double,
        longitude: Double
    ): List<LocationFavorite> = withContext(Dispatchers.IO) {
      NexLog.trace("LocationFavorites", "saveFavorite", "existing=${id != null}") { operation ->
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= MAX_NAME_LENGTH)
        require(isValidWgs84Point(latitude, longitude))
        synchronized(lock) {
            val current = read().toMutableList()
            val index = if (id == null) current.indexOfFirst { it.matches(latitude, longitude) }
                else current.indexOfFirst { it.id == id }.also { check(it >= 0) { "Favorite no longer exists" } }
            if (index >= 0) {
                current[index] = current[index].copy(name = trimmed)
            } else {
                current.add(0, LocationFavorite(UUID.randomUUID().toString(), trimmed, latitude, longitude))
            }
            invalidateVersion(current[if (index >= 0) index else 0].id)
            write(current)
            revision.value++
            operation.finish(detail = "count=${current.size} replaced=${index >= 0}")
            read()
        }
      }
    }

    suspend fun delete(id: String): List<LocationFavorite> = withContext(Dispatchers.IO) {
      NexLog.trace("LocationFavorites", "deleteFavorite") { operation ->
        synchronized(lock) {
            val current = read()
            val remaining = current.filterNot { it.id == id }
            if (remaining.size != current.size) {
                invalidateVersion(id)
                write(remaining)
                revision.value++
            }
            operation.finish(detail = "removed=${remaining.size != current.size} remaining=${remaining.size}")
            remaining
        }
      }
    }

    suspend fun createOnly(name: String, latitude: Double, longitude: Double, requestId: String): Bundle =
        conditional(requestId) { receipt ->
            val trimmed = validName(name)
            require(isValidWgs84Point(latitude, longitude)) { "Valid coordinates are required" }
            val current = read()
            if (current.any { it.matches(latitude, longitude) }) throw MutationReceipt.Rejected("favorite_conflict")
            val place = LocationFavorite(UUID.randomUUID().toString(), trimmed, latitude, longitude)
            receipt.data.putString("favoriteId", place.id)
            receipt.begin("save_favorite")
            invalidateVersion(place.id)
            write(listOf(place) + current)
            revision.value++
            val saved = read().single { it.id == place.id }
            receipt.confirmed("favoriteCreated", Bundle().apply { putString("favoriteId", saved.id) })
            receipt.data.putBoolean("created", true)
            putFavorite(receipt, saved)
        }

    suspend fun renameIfVersion(id: String, expectedVersion: String, name: String, requestId: String): Bundle =
        conditional(requestId) { receipt ->
            val trimmed = validName(name)
            val current = read()
            requireVersion(current, id, expectedVersion)
            receipt.data.putString("favoriteId", id)
            receipt.begin("save_favorite")
            invalidateVersion(id)
            write(current.map { if (it.id == id) it.copy(name = trimmed) else it })
            revision.value++
            val saved = read().single { it.id == id }
            receipt.confirmed("favoriteRenamed", Bundle().apply { putString("favoriteId", id) })
            putFavorite(receipt, saved)
        }

    suspend fun deleteIfVersion(id: String, expectedVersion: String, requestId: String): Bundle =
        conditional(requestId) { receipt ->
            val current = read()
            requireVersion(current, id, expectedVersion)
            receipt.data.putString("favoriteId", id)
            receipt.begin("delete_favorite")
            invalidateVersion(id)
            write(current.filterNot { it.id == id })
            revision.value++
            receipt.confirmed("favoriteDeleted", Bundle().apply { putString("favoriteId", id) })
            receipt.data.putBoolean("deleted", true)
        }

    private suspend fun conditional(requestId: String, change: (MutationReceipt) -> Unit): Bundle =
        withContext(Dispatchers.IO) {
            val receipt = MutationReceipt(requestId)
            synchronized(lock) {
                try {
                    change(receipt)
                    receipt.data.putBoolean("saved", true)
                    receipt.success()
                } catch (failure: MutationReceipt.Rejected) {
                    receipt.failure(failure.code)
                } catch (failure: IllegalArgumentException) {
                    receipt.failure("invalid_request")
                } catch (failure: Exception) {
                    // Publication or its confirmation can fail after AtomicFile has replaced the file.
                    receipt.failure("favorite_storage_unconfirmed")
                } finally {
                    NexLog.i("LocationFavorites") {
                        "requestId=$requestId stage=${receipt.data.getString("stage")}" +
                            " mutationState=${receipt.data.getString("mutationState")} success=${receipt.data.getBoolean("success")}" +
                            " reason=" + receipt.data.getString("errorCode")
                    }
                }
            }
        }

    private fun validName(name: String): String = name.trim().also {
        require(it.isNotEmpty() && it.length <= MAX_NAME_LENGTH) { "Invalid favorite name" }
    }

    private fun requireVersion(current: List<LocationFavorite>, id: String, expected: String) {
        require(id.length in 1..128 && expected.length in 1..128) { "Invalid resource identity" }
        val place = current.firstOrNull { it.id == id } ?: throw MutationReceipt.Rejected("resource_not_found")
        if (place.version != expected) throw MutationReceipt.Rejected("resource_changed")
    }

    private fun putFavorite(receipt: MutationReceipt, place: LocationFavorite) {
        receipt.data.putString("version", place.version)
        receipt.data.putBundle("favorite", Bundle().apply {
            putString("favoriteId", place.id)
            putString("version", place.version)
            putString("name", place.name)
            putDouble("latitude", place.latitude)
            putDouble("longitude", place.longitude)
        })
    }

    private fun invalidateVersion(id: String) { versions[baseFile.absolutePath]?.remove(id) }

    private fun read(): List<LocationFavorite> = try {
        val entries = readEntries()
        val state = versions.getOrPut(baseFile.absolutePath) { mutableMapOf() }
        state.keys.retainAll(entries.map { it.id }.toSet())
        entries.map { place ->
            val previous = state[place.id]
            val versioned = if (previous != null && previous.copy(version = "") == place) previous
                else place.copy(version = "favorite1.${UUID.randomUUID()}")
            state[place.id] = versioned
            versioned
        }
    } catch (_: JSONException) {
        // JSON parser errors can contain names and coordinates; keep them out of CLI diagnostics.
        throw IllegalStateException("Favorites file is invalid")
    }

    private fun readEntries(): List<LocationFavorite> {
        if (!baseFile.exists() && !File(baseFile.path + ".bak").exists()) return emptyList()
        val document = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        check(document.getInt("version") == 1) { "Unsupported favorites format" }
        val entries = document.getJSONArray("places")
        val ids = HashSet<String>()
        return List(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            LocationFavorite(
                entry.getString("id"), entry.getString("name"),
                entry.getDouble("latitude"), entry.getDouble("longitude")
            ).also {
                check(it.id.isNotBlank() && ids.add(it.id)) { "Invalid favorite identity" }
                check(it.name.isNotBlank() && it.name.length <= MAX_NAME_LENGTH) { "Invalid favorite name" }
                check(isValidWgs84Point(it.latitude, it.longitude)) { "Invalid favorite coordinates" }
            }
        }
    }

    private fun write(favorites: List<LocationFavorite>) {
        val entries = JSONArray()
        favorites.forEach { place ->
            entries.put(JSONObject().apply {
                put("id", place.id)
                put("name", place.name)
                put("latitude", place.latitude)
                put("longitude", place.longitude)
            })
        }
        val bytes = JSONObject().put("version", 1).put("places", entries).toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        file.finishWrite(output)
        // AtomicFile logs rename failures rather than throwing them. Confirm the published content.
        check(file.readFully().contentEquals(bytes)) { "Favorites write could not be confirmed" }
    }

    companion object {
        const val MAX_NAME_LENGTH = 60
        private val lock = Any()
        private val revision = MutableStateFlow(0L)
        // No on-read file migration. Tokens intentionally expire when the host process restarts.
        private val versions = mutableMapOf<String, MutableMap<String, LocationFavorite>>()
        val changes = revision.asStateFlow()
    }
}
