package com.nexbox.app.data

import android.content.Context
import com.nexbox.app.util.NexLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.URI
import java.security.MessageDigest

enum class MapTileProvider(val preferenceValue: String) {
    OPEN_STREET_MAP_DE("osm_de"), OPEN_STREET_MAP("osm"), CUSTOM("custom");

    companion object {
        // Existing CARTO selections move to the new default; saved custom sources stay intact.
        fun fromPreference(value: String?) = entries.firstOrNull { it.preferenceValue == value } ?: OPEN_STREET_MAP_DE
    }
}

/** Every supported source uses XYZ Web Mercator tiles with WGS-84 geographic coordinates. */
data class MapTileSettings(
    val provider: MapTileProvider = MapTileProvider.OPEN_STREET_MAP_DE,
    val customTemplate: String = "",
    val customAttribution: String = ""
) {
    fun isValid(): Boolean = provider != MapTileProvider.CUSTOM ||
        (isValidXyzTemplate(customTemplate) && customAttribution.isNotBlank() && customAttribution.length <= 250)

    fun source(): XyzMapSource = when (provider) {
        MapTileProvider.OPEN_STREET_MAP_DE -> XyzMapSource(
            template = "https://tile.openstreetmap.de/{z}/{x}/{y}.png",
            attribution = "© OpenStreetMap contributors",
            maxZoom = 18
        )
        MapTileProvider.OPEN_STREET_MAP -> XyzMapSource(
            template = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            attribution = "© OpenStreetMap contributors",
            maxZoom = 19
        )
        MapTileProvider.CUSTOM -> {
            require(isValid()) { "A valid HTTPS WGS-84 XYZ source and attribution are required" }
            XyzMapSource(customTemplate.trim(), customAttribution.trim(), maxZoom = 19)
        }
    }
}

data class XyzMapSource(val template: String, val attribution: String, val maxZoom: Int) {
    // Separate persistent cache namespaces when the provider, style, or custom endpoint changes.
    // Do not put a custom URL (which may contain a provider token) into cache names or logs.
    val cacheName: String = "NexBox-XYZ-" + MessageDigest.getInstance("SHA-256")
        .digest(template.toByteArray(Charsets.UTF_8)).take(12)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun tileUrl(zoom: Int, x: Int, y: Int): String {
        require(zoom in 0..maxZoom && x in 0 until (1 shl zoom) && y in 0 until (1 shl zoom))
        return template.replace("{z}", zoom.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
    }
}

fun isValidXyzTemplate(template: String): Boolean {
    if (template.length !in 1..2048 || template != template.trim()) return false
    if (!listOf("{z}", "{x}", "{y}").all(template::contains)) return false
    val sample = template.replace("{z}", "0").replace("{x}", "0").replace("{y}", "0")
    if ('{' in sample || '}' in sample) return false
    return runCatching {
        val uri = URI(sample)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawFragment == null && uri.port in -1..65535 && uri.port != 0
    }.getOrDefault(false)
}

/** Android fixes, map selections and bookmarks all use WGS-84 without an offset conversion. */
internal fun isValidWgs84Point(latitude: Double, longitude: Double): Boolean =
    latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

class MapTilePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("map_tiles", Context.MODE_PRIVATE)

    fun read(): MapTileSettings = runCatching {
        val values = preferences.all
        MapTileSettings(
            MapTileProvider.fromPreference(values["provider"] as? String),
            values["custom_template"] as? String ?: "",
            values["custom_attribution"] as? String ?: ""
        ).takeIf(MapTileSettings::isValid) ?: MapTileSettings()
    }.onFailure { NexLog.w("Map", "action=loadTileSettings fallback=default", it) }.getOrDefault(MapTileSettings())

    fun save(settings: MapTileSettings): Boolean {
        if (!settings.isValid()) {
            NexLog.w("Map", "action=saveTileSettings rejected=invalid_template provider=${settings.provider}")
            return false
        }
        return runCatching {
            preferences.edit().putString("provider", settings.provider.preferenceValue)
                .putString("custom_template", settings.customTemplate)
                .putString("custom_attribution", settings.customAttribution).commit()
        }.onFailure { NexLog.e("Map", "action=saveTileSettings failed", it) }
            .getOrDefault(false).also { saved ->
                if (saved) revision.update { it + 1 }
                val message = "action=saveTileSettings provider=${settings.provider} success=$saved"
                if (saved) NexLog.i("Map", message) else NexLog.w("Map", message)
            }
    }

    companion object {
        private val revision = MutableStateFlow(0L)
        val changes = revision.asStateFlow()
    }
}
