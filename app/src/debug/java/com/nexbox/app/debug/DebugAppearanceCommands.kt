package com.nexbox.app.debug

import android.content.Context
import com.nexbox.app.data.AppearanceRepository
import com.nexbox.app.data.ThemeMode
import com.nexbox.app.data.UiStyle
import org.json.JSONObject

internal class DebugAppearanceCommands(context: Context) {
    private val repository = AppearanceRepository.get(context)

    fun get(): JSONObject = repository.settings.value.let {
        JSONObject().put("uiStyle", it.uiStyle.storedValue).put("themeMode", it.themeMode.storedValue)
            .put("liquidGlassEnabled", it.liquidGlassEnabled).put("predictiveBackEnabled", it.predictiveBackEnabled)
    }

    fun set(payload: JSONObject): JSONObject {
        val style = if (payload.has("uiStyle")) requireNotNull(UiStyle.entries.firstOrNull {
            it.storedValue == payload.getString("uiStyle")
        }) { "uiStyle must be material3 or miuix" } else null
        val theme = if (payload.has("themeMode")) requireNotNull(ThemeMode.entries.firstOrNull {
            it.storedValue == payload.getString("themeMode")
        }) { "themeMode must be system, light or dark" } else null
        check(repository.update { current ->
            current.copy(uiStyle = style ?: current.uiStyle, themeMode = theme ?: current.themeMode,
                liquidGlassEnabled = if (payload.has("liquidGlassEnabled")) payload.getBoolean("liquidGlassEnabled") else current.liquidGlassEnabled,
                predictiveBackEnabled = if (payload.has("predictiveBackEnabled")) payload.getBoolean("predictiveBackEnabled") else current.predictiveBackEnabled)
        }) { "Appearance settings were not saved" }
        return get().put("saved", true).put("persistence", "asynchronous")
    }
}
