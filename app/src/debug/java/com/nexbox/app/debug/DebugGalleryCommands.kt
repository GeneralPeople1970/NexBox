package com.nexbox.app.debug

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nexbox.app.data.SharedMediaRepository
import com.nexbox.app.util.LogPrivacy
import com.nexbox.app.util.NexLog
import com.nexbox.app.view.gallery.NexBoxGalleryPreviewActivity
import com.nexbox.core.media.NexBoxMediaContract
import com.nexbox.core.media.NexBoxMediaEntry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

internal class DebugGalleryCommands(private val context: Context) {
    fun importVerified(payload: JSONObject): JSONObject = DebugConditionalCommands.json(SharedMediaRepository.importVerified(
        payload.getString("contentBase64"), payload.getLong("expectedLength"), payload.getString("expectedSha256"),
        payload.getString("mimeType"), payload.getString("requestId")))

    fun deleteIfVersion(payload: JSONObject): JSONObject {
        require(payload.getBoolean("confirm")) { "confirm=true is required" }
        return DebugConditionalCommands.json(SharedMediaRepository.deleteIfVersion(payload.getLong("mediaId"),
            payload.getString("expectedVersion"), payload.getString("expectedOwnerToken"),
            payload.getString("expectedSha256"), payload.getString("requestId")))
    }

    fun exportIfVersion(payload: JSONObject): JSONObject {
        require(payload.getBoolean("confirmExternalWrite")) { "confirmExternalWrite=true is required" }
        return DebugConditionalCommands.json(runBlocking(Dispatchers.IO) {
            SharedMediaRepository.exportIfVersion(context, payload.getLong("mediaId"), payload.getString("expectedVersion"),
                payload.getString("expectedOwnerToken"), payload.getString("expectedSha256"), payload.getString("requestId"))
        })
    }

    fun galleryList(payload: JSONObject): JSONObject {
        val query = payload.optString("query").trim()
        val mediaType = payload.optString("type", "all").lowercase()
        require(mediaType in setOf("all", "image", "video")) { "type must be all, image, or video" }
        val reverse = payload.optBoolean("reverse", false)
        val requestedLimit = payload.optInt("limit", 300)
        require(requestedLimit in 1..5000) { "limit must be between 1 and 5000" }
        val limit = requestedLimit
        val entries = SharedMediaRepository.listMediaEntries()
            .filter { entry ->
                entry.isPending == 0 &&
                (mediaType == "all" || (mediaType == "image" && !entry.isVideo) ||
                    (mediaType == "video" && entry.isVideo)) &&
                    (query.isEmpty() || entry.displayName.orEmpty().contains(query, true) ||
                        entry.mimeType.orEmpty().contains(query, true))
            }.let { if (reverse) it.reversed() else it }.take(limit)
        return JSONObject().put("count", entries.size).put("entries", JSONArray().apply {
            entries.forEach { put(mediaEntryJson(it)) }
        })
    }

    fun galleryRescan(): JSONObject {
        SharedMediaRepository.rescan()
        return JSONObject().put("rescanned", true).put("count", SharedMediaRepository.listMediaEntries().size)
    }

    fun galleryGet(payload: JSONObject): JSONObject {
        val id = payload.optLong("mediaId", -1L)
        require(id > 0L) { "mediaId is required" }
        val entry = checkNotNull(SharedMediaRepository.getEntry(id)) {
            "Media entry does not exist"
        }
        return mediaEntryJson(entry)
    }

    fun galleryPreview(payload: JSONObject): JSONObject {
        val id = payload.optLong("mediaId", -1L)
        require(id > 0L) { "mediaId is required" }
        checkNotNull(SharedMediaRepository.getEntry(id)) {
            "Media entry does not exist"
        }
        val intent = NexBoxGalleryPreviewActivity.intentFor(context, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JSONObject().put("requestAccepted", true).put("mediaId", id)
    }

    fun galleryImport(payload: JSONObject): JSONObject {
        val paths = mutableListOf<String>()
        payload.optString("path").takeIf { it.isNotBlank() }?.let(paths::add)
        payload.optJSONArray("paths")?.let { array ->
            require(array.length() <= 1000) { "At most 1000 media paths are supported" }
            for (index in 0 until array.length()) {
                require(array.get(index) is String) { "paths must contain strings" }
                paths += array.getString(index)
            }
        }
            ?: payload.optString("paths").takeIf { it.isNotBlank() }?.let { value -> paths += value.split(',').map(String::trim) }
        require(paths.size in 1..1000 && paths.all { it.isNotBlank() }) { "1..1000 nonempty media paths are required" }
        val imported = JSONArray()
        val failures = JSONArray()
        paths.forEachIndexed { index, raw ->
            try {
                val file = raw.takeIf { !it.startsWith("content://") }?.let { File(it).canonicalFile }
                if (file != null) require(File(raw).isAbsolute && file.isFile && file.canRead()) { "A readable absolute media path is required" }
                val uri = file?.let { Uri.fromFile(it) } ?: Uri.parse(raw)
                val mime = context.contentResolver.getType(uri) ?: file?.let { NexBoxMediaContract.inferMimeType(it) }
                val entry = SharedMediaRepository.importMedia(context, uri, mime)
                imported.put(mediaEntryJson(entry))
            } catch (failure: Exception) {
                NexLog.w("Gallery", "action=cliImportItem index=$index success=false", failure)
                failures.put(JSONObject().put("index", index).put("path", LogPrivacy.clean(raw).take(512))
                    .put("message", LogPrivacy.clean(failure.message ?: failure.javaClass.simpleName)))
            }
        }
        return JSONObject().put("imported", imported).put("importedCount", imported.length())
            .put("failures", failures).put("failureCount", failures.length())
    }

    fun galleryDelete(payload: JSONObject): JSONObject {
        require(payload.optBoolean("confirm", false)) { "confirm=true is required" }
        val ids = mediaIds(payload)
        val deleted = JSONArray()
        val failures = JSONArray()
        ids.distinct().forEach { id ->
            try {
                val entry = checkNotNull(SharedMediaRepository.getEntry(id)) {
                    "Media entry does not exist"
                }
                check(SharedMediaRepository.deleteMedia(entry)) {
                    "Media file could not be deleted"
                }
                deleted.put(id)
            } catch (failure: Exception) {
                NexLog.w("Gallery", "action=cliDeleteItem mediaId=$id success=false", failure)
                failures.put(JSONObject().put("mediaId", id).put("message", LogPrivacy.clean(failure.message ?: failure.javaClass.simpleName)))
            }
        }
        return JSONObject().put("deleted", deleted).put("deletedCount", deleted.length())
            .put("failures", failures).put("failureCount", failures.length())
    }

    fun galleryExport(payload: JSONObject): JSONObject {
        val ids = mediaIds(payload)
        val exported = JSONArray()
        val failures = JSONArray()
        ids.distinct().forEach { id ->
            try {
                val uri = runBlocking(Dispatchers.IO) { SharedMediaRepository.export(context, id) }
                exported.put(JSONObject().put("mediaId", id).put("uri", uri.toString()))
            } catch (failure: Exception) {
                failures.put(JSONObject().put("mediaId", id).put("message", LogPrivacy.clean(failure.message ?: failure.javaClass.simpleName)))
            }
        }
        return JSONObject().put("exported", exported).put("exportedCount", exported.length())
            .put("failures", failures).put("failureCount", failures.length())
    }

    private fun mediaIds(payload: JSONObject): List<Long> {
        val ids = mutableListOf<Long>()
        if (payload.has("mediaId")) ids += payload.getLong("mediaId")
        if (payload.has("mediaIds")) {
            val array = payload.getJSONArray("mediaIds")
            require(array.length() <= 1000) { "At most 1000 media IDs are supported" }
            for (index in 0 until array.length()) {
                require(array.get(index) is Int || array.get(index) is Long) { "mediaIds must contain integer IDs" }
                ids += array.getLong(index)
            }
        }
        require(ids.size in 1..1000 && ids.all { it > 0 }) { "1..1000 positive media IDs are required" }
        return ids.distinct()
    }

    private fun mediaEntryJson(entry: NexBoxMediaEntry): JSONObject = JSONObject()
        .put("id", entry.id).put("displayName", entry.displayName ?: JSONObject.NULL)
        .put("title", entry.title ?: JSONObject.NULL).put("mimeType", entry.mimeType ?: JSONObject.NULL)
        .put("sizeBytes", entry.size).put("dateAdded", entry.dateAdded).put("dateModified", entry.dateModified)
        .put("dateTaken", entry.dateTaken).put("relativePath", entry.relativePath ?: JSONObject.NULL)
        .put("width", entry.width).put("height", entry.height).put("duration", entry.duration)
        .put("isVideo", entry.isVideo).put("isPending", entry.isPending)
        .put("publicUri", entry.publicUri.toString()).put("privateUri", entry.privateUri.toString())
}
