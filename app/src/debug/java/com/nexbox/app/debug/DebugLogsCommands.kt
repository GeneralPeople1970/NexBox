package com.nexbox.app.debug

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import com.nexbox.app.util.LogHistoryParser
import com.nexbox.app.util.LogOrigin
import com.nexbox.app.util.LogPrivacy
import com.nexbox.app.util.LogQuery
import com.nexbox.app.util.LogSettings
import com.nexbox.app.util.NexLog
import com.nexbox.app.util.QueryLogRecord
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal class DebugLogsCommands(private val context: Context) {
    fun logsGet(): JSONObject {
        val capture = NexLog.capture()
        val settings = capture.configuration.settings
        val entries = capture.entries
        val pipeline = NexLog.pipelineStatus()
        return JSONObject()
            .put("recordingEnabled", settings.recordingEnabled).put("adbEnabled", settings.adbEnabled)
            .put("detailedEnabled", settings.detailedEnabled).put("displayMinLevel", settings.minimumDisplayLevel.name)
            .put("captureMinLevel", settings.minimumCaptureLevel.name)
            .put("debugEnabled", settings.recordingEnabled && settings.adbEnabled)
            .put("cliEnabled", settings.recordingEnabled && settings.adbEnabled)
            .put("captureEnabled", settings.recordingEnabled || settings.adbEnabled)
            .put("entryCount", entries.size).put("maxEntries", 3000)
            .put("captureSession", pipeline.captureSession).put("epoch", capture.configuration.epoch)
            .put("lastId", capture.lastId).put("pendingDiskEntries", pipeline.pendingWrites)
            .put("droppedDiskEntries", pipeline.droppedWrites)
            .put("clearRequestedEpoch", pipeline.clearRequestedEpoch ?: JSONObject.NULL)
            .put("clearCompletedEpoch", pipeline.clearCompletedEpoch ?: JSONObject.NULL)
            .put("clearFailedEpoch", pipeline.clearFailedEpoch ?: JSONObject.NULL)
            .put("clearState", pipeline.clearState).put("clearPending", pipeline.clearState == "pending")
            .put("levelCounts", JSONObject().apply { NexLog.Level.entries.forEach { level ->
                put(level.name, entries.count { it.level == level })
            } })
            .put("levels", JSONArray().apply { NexLog.Level.entries.forEach { level ->
                put(JSONObject().put("name", level.name).put("label", level.label).put("priority", level.priority))
            } })
            .put("storageError", NexLog.storageError.value?.let(LogPrivacy::clean) ?: JSONObject.NULL)
            .put("oldestTimeMillis", entries.minOfOrNull { it.timeMillis } ?: JSONObject.NULL)
            .put("newestTimeMillis", entries.maxOfOrNull { it.timeMillis } ?: JSONObject.NULL)
    }

    fun logsSet(payload: JSONObject): JSONObject {
        val debug = boolean(payload, "debugEnabled")
        val cli = boolean(payload, "cliEnabled")
        require(debug == null || cli == null || debug == cli) { "debugEnabled and cliEnabled disagree" }
        val combined = debug ?: cli
        val recording = boolean(payload, "recordingEnabled")
        val adb = boolean(payload, "adbEnabled")
        require(combined == null || ((recording == null || recording == combined) && (adb == null || adb == combined))) {
            "Combined and individual logging settings disagree; use individual settings for separate destinations"
        }
        val detailed = boolean(payload, "detailedEnabled")
        check(NexLog.setOptions(recording ?: combined, adb ?: combined, detailed)) { "Could not update logging settings" }
        return logsGet().put("configurationUpdated", true).put("persistence", "asynchronous")
    }

    fun logsClear(): JSONObject {
        val before = NexLog.snapshot().size
        NexLog.i("DebugCLI", "action=clear-logs entries=$before")
        check(NexLog.clear()) { "Could not schedule diagnostic log clearing" }
        return logsGet().apply {
            put("clearedEntries", before).put("clearScheduled", true).put("memoryCleared", true)
            put("success", getString("clearState") != "failed")
            put("diskClearConfirmed", getString("clearState") == "completed")
        }
    }

    fun logsList(payload: JSONObject): JSONObject {
        val capture = NexLog.capture()
        val session = NexLog.pipelineStatus().captureSession
        val after = long(payload, "afterId")
        if (payload.has("captureSession")) check(payload.getString("captureSession") == session) {
            "Capture session changed; obtain a new log cursor"
        }
        if (after != null) {
            require(payload.has("captureSession")) { "afterId requires captureSession from logs.get/list" }
            require(after <= capture.lastId) { "afterId is newer than the current capture" }
            require(!payload.optBoolean("newestFirst", false)) { "Cursor reads are oldest ID first" }
        }
        val filter = query(payload, capture.configuration.settings)
        val matches = capture.entries.filter { (after == null || it.id > after) && filter.matches(it) }
        val selected = if (after != null) matches.sortedBy { it.id }.take(limit(payload, 300, 3000)).map { QueryLogRecord.from(it) }
            else order(matches.map { QueryLogRecord.from(it) }, payload.optBoolean("newestFirst", true))
                .take(limit(payload, 300, 3000))
        return JSONObject().put("totalCount", capture.entries.size).put("matchedCount", matches.size)
            .put("effectiveMinLevel", filter.minimum?.name ?: "ALL")
            .put("returnedCount", selected.size).put("captureSession", session).put("epoch", capture.configuration.epoch)
            .put("nextAfterId", if (after == null) capture.lastId else selected.lastOrNull()?.id ?: capture.lastId)
            .put("oldestAvailableId", capture.entries.firstOrNull()?.id ?: JSONObject.NULL)
            .put("cursorTruncated", after != null && capture.lostEntriesAfter(after))
            .put("entries", JSONArray().apply { selected.forEach { put(recordJson(it)) } })
    }

    fun logsHistory(payload: JSONObject): JSONObject {
        val requestedLimit = limit(payload, 1000, 10000)
        val filter = query(payload)
        val files = NexLog.historyForDebug()
        val records = order(files.flatMap(LogHistoryParser::parse), payload.optBoolean("newestFirst", true))
        val stop = payload.optBoolean("stopAtLimit", false)
        val matches = records.asSequence().filter(filter::matches)
            .take(if (stop) requestedLimit + 1 else Int.MAX_VALUE).toList()
        val selected = matches.take(requestedLimit)
        return JSONObject().put("unit", "record").put("matchedCount", matches.size)
            .put("effectiveMinLevel", filter.minimum?.name ?: "ALL")
            .put("matchedCountExact", !stop || matches.size <= requestedLimit)
            .put("returnedCount", selected.size).put("hasMore", matches.size > requestedLimit)
            .put("files", files.size).put("sourceTruncated", files.any { it.truncated })
            .put("entries", JSONArray().apply { selected.forEach { put(recordJson(it)) } })
    }

    fun logsExport(payload: JSONObject): JSONObject {
        val diagnostics = payload.optBoolean("diagnostics", false)
        require(!diagnostics || payload.keys().asSequence().all { it == "diagnostics" }) {
            "Diagnostics exports contain full history and cannot be combined with snapshot filters"
        }
        val capture = NexLog.capture()
        val filter = query(payload, capture.configuration.settings)
        val snapshot = capture.entries
        val matching = if (diagnostics) emptyList() else snapshot.filter(filter::matches)
        val selectedIds = order(matching.map { QueryLogRecord.from(it) }, payload.optBoolean("newestFirst", true))
            .take(limit(payload, 3000, 3000)).mapTo(hashSetOf()) { it.id }
        val selected = matching.filter { it.id in selectedIds }
        val uri = if (diagnostics) NexLog.exportDiagnosticsForShare()
            else NexLog.exportForShare(selected, "CLI filtered logs")
        check(uri != null) { "Log export is unavailable" }
        val directory = requireNotNull(context.getExternalFilesDir(DebugCommandProvider.DEBUG_DIRECTORY)) {
            "External app storage is unavailable for log export"
        }
        check(directory.isDirectory || directory.mkdirs()) { "Could not create debug export directory" }
        directory.listFiles().orEmpty().filter {
            it.isFile && it.name.startsWith("logs-export-") &&
                System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L
        }.forEach { it.delete() }
        val output = File.createTempFile("logs-export-", if (diagnostics) ".zip" else ".txt", directory)
        try {
            checkNotNull(context.contentResolver.openInputStream(uri)) { "Could not read generated log export" }.use { input ->
                output.outputStream().use { input.copyTo(it) }
            }
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
        return JSONObject().put("kind", if (diagnostics) "diagnostics" else "snapshot")
            .put("path", output.absolutePath).put("fileName", output.name).put("sizeBytes", output.length())
            .put("diagnostics", diagnostics).put("matchedCount", if (diagnostics) JSONObject.NULL else matching.size)
            .put("exportedCount", if (diagnostics) JSONObject.NULL else selected.size)
            .put("effectiveMinLevel", if (diagnostics) JSONObject.NULL else filter.minimum?.name ?: "ALL")
            .put("order", "chronological")
    }

    fun logsExits(payload: JSONObject): JSONObject {
        val filter = query(payload, settings = null)
        val manager = checkNotNull(context.getSystemService(ActivityManager::class.java)) { "Activity manager unavailable" }
        val requestedLimit = limit(payload, 20, 64)
        val exits = manager.getHistoricalProcessExitReasons(context.packageName, payload.optInt("pid", 0), 64)
            .filter { exit -> filter.matches(QueryLogRecord(exit.timestamp, null, null, "",
                LogOrigin(exit.timestamp, exit.processName, exit.pid, "", ""))) }.take(requestedLimit)
        return JSONObject().put("scope", "host_uid").put("returnedCount", exits.size)
            .put("entries", JSONArray().apply { exits.forEach { exit ->
                put(JSONObject().put("timeMillis", exit.timestamp).put("process", exit.processName).put("pid", exit.pid)
                    .put("reason", exit.reason).put("reasonName", when (exit.reason) {
                        ApplicationExitInfo.REASON_CRASH -> "java_crash"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
                        ApplicationExitInfo.REASON_ANR -> "anr"
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
                        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
                        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
                        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
                        else -> "other"
                    }).put("status", exit.status).put("importance", exit.importance)
                    .put("pssKb", exit.pss).put("rssKb", exit.rss))
            } })
    }

    private fun query(payload: JSONObject, settings: LogSettings? = NexLog.settings.value) = LogQuery(
        minimum = string(payload, "minLevel")?.let(::parseLevel),
        exact = string(payload, "level")?.let(::parseLevel), query = payload.optString("query"),
        tag = string(payload, "tag"), process = string(payload, "process"), thread = string(payload, "thread"),
        session = string(payload, "session"), context = string(payload, "context"),
        packageName = string(payload, "packageName"),
        userId = if (payload.has("userId")) payload.getInt("userId") else null,
        generation = long(payload, "createTime"), since = long(payload, "sinceMillis"), until = long(payload, "untilMillis"),
        pid = if (payload.has("pid")) payload.getInt("pid") else null, requestId = string(payload, "requestId")
    ).let { if (settings == null) it else it.withSettings(settings) }

    private fun order(records: List<QueryLogRecord>, newest: Boolean): List<QueryLogRecord> =
        records.sortedWith { left, right ->
            val time = when {
                left.timeMillis == null -> if (right.timeMillis == null) 0 else 1
                right.timeMillis == null -> -1
                newest -> right.timeMillis.compareTo(left.timeMillis)
                else -> left.timeMillis.compareTo(right.timeMillis)
            }
            if (time != 0) time else {
                val leftId = left.id ?: left.line?.toLong() ?: 0L
                val rightId = right.id ?: right.line?.toLong() ?: 0L
                if (newest) rightId.compareTo(leftId) else leftId.compareTo(rightId)
            }
        }

    private fun limit(payload: JSONObject, default: Int, maximum: Int): Int =
        payload.optInt("limit", default).also { require(it in 1..maximum) { "limit must be between 1 and $maximum" } }

    private fun string(payload: JSONObject, key: String): String? =
        if (payload.isNull(key)) null else payload.getString(key).trim().takeIf { it.isNotEmpty() }
    private fun boolean(payload: JSONObject, key: String): Boolean? = if (payload.has(key)) payload.getBoolean(key) else null
    private fun long(payload: JSONObject, key: String): Long? = if (payload.has(key)) payload.getLong(key) else null
    private fun parseLevel(value: String): NexLog.Level? {
        if (value.equals("all", true) || value.equals("none", true)) return null
        return requireNotNull(NexLog.Level.entries.firstOrNull { it.name.equals(value, true) || it.label.equals(value, true) }) {
            "Unknown log level"
        }
    }

    private fun recordJson(entry: QueryLogRecord): JSONObject = JSONObject()
        .put("id", entry.id ?: JSONObject.NULL).put("timeMillis", entry.timeMillis ?: JSONObject.NULL)
        .put("level", entry.level?.name ?: JSONObject.NULL).put("priority", entry.level?.priority ?: JSONObject.NULL)
        .put("tag", entry.tag ?: JSONObject.NULL).put("message", entry.message)
        .put("origin", entry.origin?.let {
            JSONObject().put("timeMillis", it.timeMillis).put("process", it.process).put("pid", it.pid)
                .put("thread", it.thread).put("session", it.session).put("context", it.context)
        } ?: JSONObject.NULL).apply {
            if (entry.file != null) {
                put("file", entry.file).put("line", entry.line).put("text", entry.message).put("truncated", entry.truncated)
            }
        }
}
