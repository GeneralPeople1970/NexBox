package com.nexbox.app.debug

import android.content.Context
import com.nexbox.app.data.FakeLocationAppSnapshot
import com.nexbox.app.data.FakeLocationRepository
import com.nexbox.app.data.FakeLocationScopeSnapshot
import com.nexbox.app.data.HostLocationRepository
import com.nexbox.app.data.LocationFavorite
import com.nexbox.app.data.LocationFavoritesRepository
import com.nexbox.app.data.MapTilePreferences
import com.nexbox.app.data.MapTileProvider
import com.nexbox.app.data.MapTileSettings
import com.nexbox.app.data.isValidWgs84Point
import com.nexbox.app.debug.DebugCommandJson.jsonArray
import com.nexbox.app.debug.DebugCommandJson.optionalPackage
import com.nexbox.app.debug.DebugCommandJson.requiredPackage
import com.nexbox.app.debug.DebugCommandJson.scopeAppJson
import com.nexbox.app.debug.DebugCommandJson.stringSet
import com.nexbox.app.debug.DebugCommandTargets.target
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

internal class DebugLocationCommands(private val context: Context) {
    private val locations = FakeLocationRepository()
    private val favorites = LocationFavoritesRepository(context)

    fun currentLocation(): JSONObject {
        val host = HostLocationRepository(context)
        val fix = runBlocking(Dispatchers.IO) { host.locate() }
        val point = fix?.let { it.latitude to it.longitude }
        val cached = if (fix == null) host.cachedLocation() else null
        val display = fix ?: cached
        val permission = host.hasPermission()
        return JSONObject().put("permissionGranted", permission).put("available", fix != null)
            .put("approximate", display?.approximate ?: JSONObject.NULL)
            .put("point", pointJson(point))
            .put("displayPoint", pointJson(display?.let { it.latitude to it.longitude }
                ?: (HostLocationRepository.DEFAULT_LATITUDE to HostLocationRepository.DEFAULT_LONGITUDE)))
            .put("timestampMillis", display?.timestampMillis ?: JSONObject.NULL)
            .put("source", when { fix != null -> "device"; cached != null -> "cache"; else -> "default" })
            .put("reason", when {
                !permission -> "permission_required"
                fix == null -> "location_unavailable"
                else -> JSONObject.NULL
            })
    }

    fun favoritesList(): JSONObject = favoritesJson(runBlocking(Dispatchers.IO) { favorites.load() })

    fun favoriteCreate(payload: JSONObject): JSONObject = DebugConditionalCommands.json(runBlocking(Dispatchers.IO) {
        favorites.createOnly(favoriteName(payload), payload.getDouble("latitude"), payload.getDouble("longitude"),
            payload.getString("requestId"))
    })

    fun favoriteRenameIfVersion(payload: JSONObject): JSONObject = DebugConditionalCommands.json(runBlocking(Dispatchers.IO) {
        favorites.renameIfVersion(payload.getString("favoriteId"), payload.getString("expectedVersion"),
            favoriteName(payload), payload.getString("requestId"))
    })

    fun favoriteDeleteIfVersion(payload: JSONObject): JSONObject {
        require(payload.getBoolean("confirm")) { "confirm=true is required" }
        return DebugConditionalCommands.json(runBlocking(Dispatchers.IO) {
            favorites.deleteIfVersion(payload.getString("favoriteId"), payload.getString("expectedVersion"), payload.getString("requestId"))
        })
    }

    fun favoriteAdd(payload: JSONObject): JSONObject {
        val name = favoriteName(payload)
        val latitude = payload.optDouble("latitude", Double.NaN)
        val longitude = payload.optDouble("longitude", Double.NaN)
        require(isValidWgs84Point(latitude, longitude)) { "Valid latitude and longitude are required" }
        val saved = runBlocking(Dispatchers.IO) { favorites.save(null, name, latitude, longitude) }
        return JSONObject().put("favorite", favoriteJson(saved.first { it.matches(latitude, longitude) }))
    }

    fun favoriteRename(payload: JSONObject): JSONObject {
        val place = favorite(payload)
        val name = favoriteName(payload)
        val saved = runBlocking(Dispatchers.IO) { favorites.save(place.id, name, place.latitude, place.longitude) }
        return JSONObject().put("favorite", favoriteJson(saved.first { it.id == place.id }))
    }

    fun favoriteDelete(payload: JSONObject): JSONObject {
        require(payload.optBoolean("confirm")) { "Favorite deletion requires confirm=true" }
        val place = favorite(payload)
        val remaining = runBlocking(Dispatchers.IO) { favorites.delete(place.id) }
        return favoritesJson(remaining).put("favoriteId", place.id).put("deleted", true)
    }

    fun favoriteApply(payload: JSONObject, saveOnly: Boolean): JSONObject {
        val owner = target(payload, destructive = true)
        val place = favorite(payload)
        val request = JSONObject().put("userId", owner.userId).put("createTime", owner.createTime)
            .put("latitude", place.latitude).put("longitude", place.longitude)
        optionalPackage(payload)?.let { request.put("packageName", it) }
        return locationApply(request, saveOnly).put("favoriteId", place.id)
    }

    private fun favorite(payload: JSONObject): LocationFavorite {
        val id = (payload.opt("favoriteId") as? String)?.trim().orEmpty()
        require(id.isNotEmpty()) { "favoriteId is required" }
        return checkNotNull(runBlocking(Dispatchers.IO) { favorites.load().firstOrNull { it.id == id } }) {
            "Favorite no longer exists"
        }
    }

    private fun favoriteName(payload: JSONObject): String {
        val name = (payload.opt("name") as? String)?.trim().orEmpty()
        require(name.isNotEmpty() && name.length <= LocationFavoritesRepository.MAX_NAME_LENGTH) {
            "name must contain 1 to ${LocationFavoritesRepository.MAX_NAME_LENGTH} characters"
        }
        return name
    }

    private fun favoritesJson(places: List<LocationFavorite>): JSONObject = JSONObject()
        .put("favorites", JSONArray().apply { places.forEach { put(favoriteJson(it)) } }).put("count", places.size)

    private fun favoriteJson(place: LocationFavorite): JSONObject = JSONObject()
        .put("favoriteId", place.id).put("name", place.name)
        .put("version", place.version)
        .put("latitude", place.latitude).put("longitude", place.longitude)

    fun locationGet(payload: JSONObject): JSONObject {
        val target = target(payload, destructive = false)
        val snapshot = locations.loadScope(target.userId)
        check(snapshot.createTime == target.createTime) { "Location user generation changed" }
        return scopeJson(snapshot)
            .put("userId", target.userId).put("createTime", target.createTime)
    }

    fun locationGetApp(payload: JSONObject): JSONObject {
        val target = target(payload, destructive = false)
        val snapshot = locations.loadAppLocation(target.userId, requiredPackage(payload), target.createTime)
        return appLocationJson(snapshot).put("userId", target.userId)
    }

    fun locationApply(payload: JSONObject, saveOnly: Boolean): JSONObject {
        val target = target(payload, destructive = false)
        val latitude = payload.getDouble("latitude")
        val longitude = payload.getDouble("longitude")
        val packageName = optionalPackage(payload)
        val success = if (packageName == null) {
            if (saveOnly) locations.saveGlobalPoint(target.userId, target.createTime, latitude, longitude)
            else locations.applyGlobalLocation(target.userId, latitude, longitude, target.createTime) >= 0
        } else {
            if (saveOnly) locations.saveAppPoint(target.userId, packageName, target.createTime, latitude, longitude)
            else locations.applyAppLocation(target.userId, packageName, target.createTime, latitude, longitude)
        }
        check(success) { "Location operation was not confirmed" }
        return if (packageName == null) locationGet(payload) else locationGetApp(payload)
    }

    fun locationDisable(payload: JSONObject): JSONObject {
        val target = target(payload, destructive = false)
        val packageName = optionalPackage(payload)
        val success = if (packageName == null) locations.clearGlobalLocation(target.userId, target.createTime)
        else locations.clearAppLocation(target.userId, packageName, target.createTime)
        check(success) { "Location disable was not confirmed" }
        return if (packageName == null) locationGet(payload) else locationGetApp(payload)
    }

    fun locationResetApp(payload: JSONObject): JSONObject {
        val target = target(payload, destructive = false)
        val packageName = requiredPackage(payload)
        check(locations.resetAppLocation(target.userId, packageName, target.createTime)) { "Location inheritance was not confirmed" }
        return locationGetApp(payload)
    }

    fun locationScopeGet(payload: JSONObject): JSONObject = locationGet(payload)

    fun locationScopeSet(payload: JSONObject): JSONObject {
        val target = target(payload, destructive = false)
        val requested = stringSet(payload, "packages")
        val snapshot = locations.loadScope(target.userId)
        check(snapshot.createTime == target.createTime) { "Location user generation changed" }
        require(snapshot.apps.map { it.packageName }.containsAll(requested)) { "Location scope contains an unavailable app or Xposed module" }
        check(locations.saveScope(target.userId, target.createTime, requested)) {
            "Location scope was not confirmed"
        }
        return locationGet(payload)
    }

    fun rockerGet(payload: JSONObject): JSONObject {
        val owner = target(payload, destructive = true)
        val packageName = requiredPackage(payload)
        val state = locations.loadRockerState(owner.userId, packageName, owner.createTime)
        return JSONObject().put("userId", owner.userId).put("createTime", owner.createTime).put("packageName", packageName)
            .put("selected", state.selected).put("independent", state.independent).put("enabled", state.enabled)
            .put("effectiveEnabled", state.enabled && state.selected && state.point != null).put("point", pointJson(state.point))
    }

    fun rockerMove(payload: JSONObject): JSONObject {
        val owner = target(payload, destructive = true)
        val packageName = requiredPackage(payload)
        val angle = payload.getDouble("angle").toFloat()
        val distance = payload.getDouble("distance").toFloat()
        require(angle.isFinite() && angle in 0f..360f && distance.isFinite() && distance in 0f..2.5f) {
            "angle must be 0..360 degrees and distance 0..2.5 metres"
        }
        val moved = locations.moveRocker(owner.userId, owner.createTime, packageName, angle, distance)
        return rockerGet(payload).put("success", moved).put("appsStopped", false)
    }

    fun mapSourceGet(): JSONObject {
        val settings = MapTilePreferences(context).read()
        return mapSourceJson(settings)
    }

    fun mapSourceSet(payload: JSONObject): JSONObject {
        val provider = requireNotNull(MapTileProvider.entries.firstOrNull {
            it.preferenceValue == payload.optString("provider")
        }) { "provider must be osm_de, osm or custom" }
        val preferences = MapTilePreferences(context)
        val current = preferences.read()
        val settings = MapTileSettings(provider,
            payload.optString("template", current.customTemplate).trim(),
            payload.optString("attribution", current.customAttribution).trim())
        require(settings.isValid()) { "A valid HTTPS XYZ template and attribution are required" }
        check(preferences.save(settings)) { "Map source was not saved" }
        return mapSourceJson(preferences.read())
    }

    private fun scopeJson(snapshot: FakeLocationScopeSnapshot): JSONObject = JSONObject()
        .put("createTime", snapshot.createTime).put("enabled", snapshot.enabled)
        .put("point", pointJson(snapshot.point)).put("selectedPackages", jsonArray(snapshot.selectedPackages))
        .put("independentPackages", jsonArray(snapshot.independentPackages))
        .put("apps", JSONArray().apply { snapshot.apps.forEach { put(scopeAppJson(it.packageName, it.label, it.versionName)) } })

    private fun appLocationJson(snapshot: FakeLocationAppSnapshot): JSONObject = JSONObject()
        .put("createTime", snapshot.createTime).put("packageName", snapshot.app.packageName)
        .put("name", snapshot.app.label).put("versionName", snapshot.app.versionName ?: JSONObject.NULL)
        .put("selected", snapshot.selected).put("independent", snapshot.independent)
        .put("enabled", snapshot.enabled)
        .put("effectiveEnabled", snapshot.selected && snapshot.enabled && snapshot.point != null)
        .put("point", pointJson(snapshot.point))

    private fun pointJson(point: Pair<Double, Double>?): Any = point?.let {
        JSONObject().put("latitude", it.first).put("longitude", it.second)
    } ?: JSONObject.NULL

    private fun mapSourceJson(settings: MapTileSettings): JSONObject {
        val source = settings.source()
        return JSONObject().put("provider", settings.provider.preferenceValue).put("template", settings.customTemplate)
            .put("attribution", settings.customAttribution).put("activeTemplate", source.template)
            .put("activeAttribution", source.attribution).put("maxZoom", source.maxZoom)
            .put("availableProviders", jsonArray(MapTileProvider.entries.map { it.preferenceValue }))
    }
}
