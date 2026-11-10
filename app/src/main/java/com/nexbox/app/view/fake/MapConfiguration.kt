package com.nexbox.app.view.fake

import android.content.Context
import com.nexbox.app.util.NexLog
import org.osmdroid.config.Configuration
import java.io.File

internal object MapConfiguration {
    @Volatile private var configured = false

    fun ensureConfigured(context: Context) {
        if (configured) return
        synchronized(this) {
            if (configured) return
            try {
                val app = context.applicationContext
                val config = Configuration.getInstance()
                config.load(app, app.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
                // load() restores old preferences. Override its default "osmdroid" user
                // agent afterwards or OSM tile sources reject every download request.
                config.userAgentValue = app.packageName
                config.osmdroidBasePath = File(app.cacheDir, "osmdroid")
                config.osmdroidTileCache = File(config.osmdroidBasePath, "tiles")
                configured = true
            } catch (error: Exception) {
                NexLog.e("MapConfiguration", "Could not configure osmdroid", error)
            }
        }
    }
}
