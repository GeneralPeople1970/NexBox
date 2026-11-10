package com.nexbox.app.data

import android.content.Context
import com.nexbox.app.util.NexLog

/** The map camera is a host UI preference, independent of simulated user/app locations. */
internal class MapViewportPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("map_viewport", Context.MODE_PRIVATE)

    fun readZoom(): Double = runCatching {
        (preferences.all["zoom"] as? String)?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..19.0 }
            ?: DEFAULT_ZOOM
    }.onFailure { NexLog.w("Map", "action=restoreViewport fallback=default type=${it.javaClass.simpleName}") }
        .getOrDefault(DEFAULT_ZOOM)

    /** Called after gesture coalescing and before pause/release; disk writes remain asynchronous. */
    fun saveZoom(zoom: Double) {
        if (!zoom.isFinite() || zoom !in 0.0..19.0) return
        runCatching {
            val value = zoom.toString()
            if (preferences.all["zoom"] == value) return
            preferences.edit().putString("zoom", value).apply()
            NexLog.d("Map") { "action=saveViewport zoom=$zoom persistence=asynchronous" }
        }.onFailure { NexLog.w("Map", "action=saveViewport failed type=${it.javaClass.simpleName}") }
    }

    companion object { const val DEFAULT_ZOOM = 12.0 }
}
