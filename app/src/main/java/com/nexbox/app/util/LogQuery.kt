package com.nexbox.app.util

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A historical record may lack a timestamp/level; missing metadata never satisfies a filter. */
internal data class QueryLogRecord(
    val timeMillis: Long?,
    val level: NexLog.Level?,
    val tag: String?,
    val message: String,
    val origin: LogOrigin? = null,
    val id: Long? = null,
    val file: String? = null,
    val line: Int? = null,
    val truncated: Boolean = false
) {
    companion object {
        fun from(entry: NexLog.Entry) = QueryLogRecord(entry.timeMillis, entry.level, entry.tag, entry.message, entry.origin, entry.id)
    }
}

internal data class LogQuery(
    val minimum: NexLog.Level? = null,
    val exact: NexLog.Level? = null,
    val query: String = "",
    val tag: String? = null,
    val process: String? = null,
    val thread: String? = null,
    val session: String? = null,
    val context: String? = null,
    val packageName: String? = null,
    val userId: Int? = null,
    val generation: Long? = null,
    val since: Long? = null,
    val until: Long? = null,
    val pid: Int? = null,
    val requestId: String? = null
) {
    fun withSettings(settings: LogSettings): LogQuery = copy(minimum = settings.displayLevel(minimum))

    companion object {
        fun forDisplay(settings: LogSettings, minimum: NexLog.Level?, query: String) = LogQuery(
            minimum = settings.displayLevel(minimum),
            query = query.trim().take(160).split(QUERY_WHITESPACE).filter { it.isNotEmpty() }.take(12).joinToString(" ")
        )
    }

    private val terms = query.trim().split(QUERY_WHITESPACE).filter { it.isNotEmpty() }
    private val targetUserId = userId?.toString()
    private val targetCreateTime = generation?.toString()
    init {
        require(query.length <= 160 && terms.size <= 12) { "query is limited to 160 characters and 12 terms" }
        require(since == null || until == null || since <= until) { "sinceMillis must be <= untilMillis" }
    }

    fun matches(entry: NexLog.Entry): Boolean =
        matches(entry.timeMillis, entry.level, entry.tag, entry.message, entry.origin)

    fun matches(record: QueryLogRecord): Boolean =
        matches(record.timeMillis, record.level, record.tag, record.message, record.origin)

    private fun matches(timeMillis: Long?, level: NexLog.Level?, recordTag: String?, message: String,
                        origin: LogOrigin?): Boolean {
        if (minimum != null && (level == null || level.priority < minimum.priority)) return false
        if (exact != null && level != exact) return false
        if (!contains(recordTag, tag) || !contains(origin?.process, process) || !contains(origin?.thread, thread) ||
            !contains(origin?.session, session) || !contains(origin?.context, context)) return false
        if (pid != null && origin?.pid != pid) return false
        if (since != null && (timeMillis == null || timeMillis < since)) return false
        if (until != null && (timeMillis == null || timeMillis > until)) return false
        // Operation targets belong to the message; process context is only a fallback. This also
        // prevents a core operation for user 2 from being attributed to user 1's process context.
        if (userId != null || generation != null || packageName != null || requestId != null) {
            val target = logFields(message.substringBefore('\n'))
            val processFields = logFields(origin?.context.orEmpty())
            val targetUser = target["userId"]
            val targetGeneration = target.firstField("generation", "createTime", "userCreateTime")
            val processUser = processFields["userId"]
            val processGeneration = processFields.firstField("generation", "createTime", "userCreateTime")
            val sameUser = targetUser == null || targetUser == processUser
            val sameGeneration = targetGeneration == null || targetGeneration == processGeneration
            // Never combine user 2's operation with user 1's generation/package fallback.
            if (targetUserId != null && (targetUser ?: processUser.takeIf { sameGeneration }) != targetUserId) return false
            if (targetCreateTime != null && (targetGeneration ?: processGeneration.takeIf { sameUser }) != targetCreateTime) return false
            val targetPackage = target.firstField("targetPackage", "package", "packageName", "guestPackage", "module", "modulePackage")
            val processPackage = processFields.firstField("guestPackage", "package", "packageName")
            if (packageName != null && (targetPackage ?: processPackage.takeIf { sameUser && sameGeneration }) != packageName) return false
            if (requestId != null && target["requestId"] != requestId) return false
        }
        if (terms.isEmpty()) return true
        val originLabel = origin?.label().orEmpty()
        return terms.all { term ->
            recordTag?.contains(term, ignoreCase = true) == true || message.contains(term, ignoreCase = true) ||
                originLabel.contains(term, ignoreCase = true)
        }
    }

    private fun contains(value: String?, query: String?) = query == null || value?.contains(query, true) == true
}

private val QUERY_WHITESPACE = Regex("\\s+")
private val LOG_FIELDS = Regex("(?:^|[\\s\\[({,;])([A-Za-z][A-Za-z0-9]*)=([^\\s\\]}) ,;]+)")
private fun logFields(text: String): Map<String, String> = buildMap {
    LOG_FIELDS.findAll(text).forEach { match ->
        if (!containsKey(match.groupValues[1])) put(match.groupValues[1], match.groupValues[2])
    }
}
private fun Map<String, String>.firstField(vararg keys: String): String? = keys.firstNotNullOfOrNull { get(it) }

/** Keeps multiline exception chains together instead of filtering individual stack lines. */
internal object LogHistoryParser {
    private val header = Regex("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) ([A-Za-z]+)/([^\\s]+): (.*)$")
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val crashTime = Regex("^crash-(\\d+)-")

    fun parse(file: NexLog.HistoryFile): List<QueryLogRecord> {
        if (file.name.startsWith("crash-") || file.name.startsWith("crash_")) {
            val first = file.text.substringBefore('\n')
            val fields = logFields(first)
            val time = fields["timeMillis"]?.toLongOrNull()
                ?: crashTime.find(file.name)?.groupValues?.get(1)?.toLongOrNull()
            return listOf(QueryLogRecord(time, NexLog.Level.ERROR, "Crash", LogPrivacy.clean(file.text),
                origin(first, fields, time), file = file.name, line = 1,
                truncated = file.truncated || file.text.length > LogPrivacy.MAX_MESSAGE_CHARS))
        }
        val result = mutableListOf<QueryLogRecord>()
        var firstLine = 1
        var current: MatchResult? = null
        val body = StringBuilder()
        fun flush() {
            if (body.isEmpty()) return
            val match = current
            val raw = body.toString().trimEnd()
            if (match == null) {
                result += QueryLogRecord(null, null, null, LogPrivacy.clean(raw), file = file.name, line = firstLine,
                    truncated = raw.length > LogPrivacy.MAX_MESSAGE_CHARS)
                return
            }
            val level = NexLog.Level.entries.firstOrNull {
                it.name.equals(match.groupValues[2], true) || it.label.equals(match.groupValues[2], true)
            }
            val prefix = raw.takeIf { it.startsWith('[') }?.substringBefore(']')?.removePrefix("[").orEmpty()
            val fields = logFields(prefix)
            val metadata = prefix.takeIf { fields.firstField("timeMillis", "process") != null }.orEmpty()
            val time = fields["timeMillis"]?.toLongOrNull() ?: runCatching {
                LocalDateTime.parse(match.groupValues[1], dateFormat).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
            val message = if (metadata.isNotEmpty()) raw.substringAfter(']').trimStart() else raw
            result += QueryLogRecord(time, level, match.groupValues[3], LogPrivacy.clean(message), origin(metadata, fields, time),
                file = file.name, line = firstLine, truncated = message.length > LogPrivacy.MAX_MESSAGE_CHARS)
        }
        file.text.lineSequence().forEachIndexed { index, line ->
            val match = header.matchEntire(line)
            if (match != null) {
                flush()
                body.setLength(0)
                current = match
                firstLine = index + 1
                body.append(match.groupValues[4])
            } else {
                if (body.isNotEmpty()) body.append('\n')
                body.append(line)
            }
        }
        flush()
        if (file.truncated && result.isNotEmpty()) result[result.lastIndex] = result.last().copy(truncated = true)
        return result
    }

    private fun origin(text: String, fields: Map<String, String>, time: Long?): LogOrigin? {
        val process = fields["process"] ?: return null
        return LogOrigin(time ?: 0L, process, fields["pid"]?.toIntOrNull() ?: 0,
            fields["thread"].orEmpty(), fields["session"].orEmpty(), text)
    }
}
