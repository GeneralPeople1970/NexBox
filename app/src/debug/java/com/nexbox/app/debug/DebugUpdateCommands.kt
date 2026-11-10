package com.nexbox.app.debug

import android.content.Context
import com.nexbox.app.BuildConfig
import com.nexbox.app.data.UpdateRelease
import com.nexbox.app.data.UpdateRepository
import com.nexbox.app.data.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

internal class DebugUpdateCommands(context: Context) {
    private val repository = UpdateRepository.get(context)

    fun get(): JSONObject = result(repository.state.value)

    fun set(payload: JSONObject): JSONObject {
        check(repository.setAutoCheckEnabled(payload.getBoolean("autoCheckEnabled"))) {
            "Update settings were not saved"
        }
        return get().put("saved", true).put("persistence", "asynchronous")
    }

    fun check(): JSONObject {
        val state = runBlocking(Dispatchers.IO) { repository.check(source = "cli") }
        val data = result(state)
        state.errorCode?.let { throw UpdateCheckException(it, data) }
        return data
    }

    private fun result(state: UpdateState): JSONObject = JSONObject()
        .put("projectUrl", UpdateRepository.PROJECT_URL)
        .put("releasesUrl", UpdateRepository.RELEASES_URL)
        .put("currentVersion", BuildConfig.VERSION_NAME)
        .put("state", JSONObject()
            .put("autoCheckEnabled", state.autoCheckEnabled)
            .put("checking", state.checking)
            .put("lastCheckedAt", state.lastCheckedAt.takeIf { it > 0L } ?: JSONObject.NULL)
            .put("lastAttemptAt", state.lastAttemptAt.takeIf { it > 0L } ?: JSONObject.NULL)
            .put("updateAvailable", state.updateAvailable)
            .put("errorCode", state.errorCode ?: JSONObject.NULL)
            .put("latest", state.latest?.let(::releaseJson) ?: JSONObject.NULL)
            .put("releases", JSONArray().apply { state.releases.forEach { put(releaseJson(it)) } }))

    private fun releaseJson(release: UpdateRelease): JSONObject = JSONObject()
        .put("tag", release.tag)
        .put("name", release.name)
        .put("version", release.version)
        .put("publishedAt", release.publishedAt)
        .put("url", release.url)
        .put("notesZh", release.notesZh)
        .put("notesEn", release.notesEn)
}

internal class UpdateCheckException(val code: String, val data: JSONObject) :
    IllegalStateException("Could not fetch update information; see data.state.errorCode and cached releases")
