package com.nexbox.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/** Independent destinations: local recording never implicitly enables logcat output. */
data class LogSettings(
    val recordingEnabled: Boolean = false,
    val adbEnabled: Boolean = false,
    val detailedEnabled: Boolean = false
) {
    val minimumCaptureLevel get() = if (detailedEnabled) NexLog.Level.VERBOSE else NexLog.Level.WARN
    val minimumDisplayLevel get() = minimumCaptureLevel

    fun accepts(level: NexLog.Level): Boolean = level.priority >= minimumCaptureLevel.priority

    fun displayLevel(requested: NexLog.Level?): NexLog.Level? = if (detailedEnabled) {
        requested
    } else {
        requested?.takeIf { it.priority >= minimumDisplayLevel.priority } ?: minimumDisplayLevel
    }

    companion object {
        internal const val RECORDING_KEY = "logs_recording_enabled"
        internal const val ADB_KEY = "logs_adb_enabled"
        internal const val DETAILED_KEY = "logs_detailed_enabled"
        internal const val EPOCH_KEY = "logs_capture_epoch"

        internal fun fromPreferences(values: Map<String, *>) = LogSettings(
            recordingEnabled = values[RECORDING_KEY] as? Boolean ?: false,
            adbEnabled = values[ADB_KEY] as? Boolean ?: false,
            detailedEnabled = values[DETAILED_KEY] as? Boolean ?: false
        )
    }
}

internal data class LogConfiguration(val settings: LogSettings, val epoch: Long) {
    val enabled get() = settings.recordingEnabled || settings.adbEnabled
    fun accepts(level: NexLog.Level): Boolean = enabled && settings.accepts(level)
}

internal data class LogCapture(val configuration: LogConfiguration, val entries: List<NexLog.Entry>, val lastId: Long) {
    fun lostEntriesAfter(id: Long): Boolean = entries.firstOrNull()?.let { id < it.id - 1 } ?: (id < lastId)
}

data class LogOrigin(
    val timeMillis: Long, val process: String, val pid: Int, val thread: String, val session: String,
    val context: String = ""
) {
    internal fun label() = "process=$process pid=$pid thread=$thread session=$session" + if (context.isEmpty()) "" else " $context"
}

/**
 * Serializes settings, snapshots and queued file operations on one monitor. File callbacks must
 * enqueue work, not do disk I/O. Keeping publication inside the lock prevents stale snapshots from
 * reappearing after a concurrent append/clear, and keeps disk operations in the same order as UI.
 */
internal class LogRecorder(
    private val capacity: Int = 3000,
    private val persist: (LogSettings, Long) -> Unit,
    private val mirror: (NexLog.Level, String, String) -> Unit,
    private val append: (NexLog.Entry) -> Unit,
    private val clearFiles: (Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val persistenceFailure: (Throwable) -> Unit = {},
    private val scheduleSnapshot: (() -> Unit) -> Unit = { it() }
) {
    private val lock = Any()
    private val buffer = ArrayDeque<NexLog.Entry>()
    private var nextId = 0L
    private var epoch = 0L
    private var snapshotGeneration = 0L
    private var snapshotScheduled = false
    private val mutableSettings = MutableStateFlow(LogSettings())
    val settings = mutableSettings.asStateFlow()
    private val mutableEntries = MutableStateFlow<List<NexLog.Entry>>(emptyList())
    val entries = mutableEntries.asStateFlow()

    init { require(capacity > 0) }

    fun configuration() = synchronized(lock) { LogConfiguration(mutableSettings.value, epoch) }

    /** Export/CLI observes the exact capture boundary, not the last throttled UI publication. */
    fun snapshot(): List<NexLog.Entry> = synchronized(lock) { buffer.toList() }

    fun capture(): LogCapture = synchronized(lock) {
        LogCapture(LogConfiguration(mutableSettings.value, epoch), buffer.toList(), nextId)
    }

    fun restoreSettings(settings: LogSettings, captureEpoch: Long = 0L) = synchronized(lock) {
        require(captureEpoch >= 0)
        mutableSettings.value = settings
        epoch = captureEpoch
    }

    fun setRecordingEnabled(enabled: Boolean) = updateSettings { it.copy(recordingEnabled = enabled) }
    fun setAdbEnabled(enabled: Boolean) = updateSettings { it.copy(adbEnabled = enabled) }
    fun setDetailedEnabled(enabled: Boolean) = updateSettings { it.copy(detailedEnabled = enabled) }
    fun setDebugEnabled(enabled: Boolean) = updateSettings { it.copy(recordingEnabled = enabled, adbEnabled = enabled) }
    fun setDestinations(recording: Boolean?, adb: Boolean?) = setOptions(recording, adb, null)
    fun setOptions(recording: Boolean?, adb: Boolean?, detailed: Boolean?) = updateSettings {
        it.copy(recordingEnabled = recording ?: it.recordingEnabled, adbEnabled = adb ?: it.adbEnabled,
            detailedEnabled = detailed ?: it.detailedEnabled)
    }

    private fun updateSettings(update: (LogSettings) -> LogSettings): Boolean = synchronized(lock) {
        val previous = mutableSettings.value
        val next = update(previous)
        try {
            // Recheck the detail threshold per entry, preserving eligible in-flight warnings/errors.
            val captureChanged = next.recordingEnabled != previous.recordingEnabled || next.adbEnabled != previous.adbEnabled
            val nextEpoch = if (captureChanged) Math.addExact(epoch, 1) else epoch
            persist(next, nextEpoch)
            epoch = nextEpoch
            mutableSettings.value = next
            true
        } catch (error: RuntimeException) {
            persistenceFailure(error)
            false
        }
    }

    fun log(
        level: NexLog.Level, tag: String, origin: LogOrigin? = null,
        expectedEpoch: Long? = null, writeFile: Boolean = true, message: () -> String
    ) = synchronized(lock) {
        val config = mutableSettings.value
        if (expectedEpoch != null && expectedEpoch != epoch) return@synchronized
        if (!config.recordingEnabled && !config.adbEnabled) return@synchronized
        if (!config.accepts(level)) return@synchronized
        val text = message()
        if (config.adbEnabled) mirror(level, tag, if (origin == null) text else "[${origin.label()}] $text")
        if (config.recordingEnabled) {
            val entry = NexLog.Entry(origin?.timeMillis ?: clock(), level, tag, text, ++nextId, origin)
            if (buffer.size == capacity) buffer.removeFirst()
            buffer.addLast(entry)
            publishSnapshotLater()
            if (writeFile) append(entry)
        }
    }

    fun clear(): Boolean = synchronized(lock) {
        try { persist(mutableSettings.value, Math.addExact(epoch, 1)) }
        catch (error: RuntimeException) { persistenceFailure(error); return@synchronized false }
        epoch++
        buffer.clear()
        snapshotGeneration++
        snapshotScheduled = false
        mutableEntries.value = emptyList()
        clearFiles(epoch)
        true
    }

    private fun publishSnapshotLater() {
        if (snapshotScheduled) return
        snapshotScheduled = true
        val generation = snapshotGeneration
        scheduleSnapshot {
            synchronized(lock) {
                if (generation == snapshotGeneration) {
                    snapshotScheduled = false
                    mutableEntries.value = buffer.toList()
                }
            }
        }
    }

    /** Only fatal-crash persistence may perform synchronous IO, on the crashing/Binder thread. */
    fun captureCrash(expectedEpoch: Long, write: () -> Boolean): Boolean = withCapture(expectedEpoch) { config ->
        config.recordingEnabled && write()
    }

    fun withCapture(expectedEpoch: Long, action: (LogSettings) -> Boolean): Boolean = synchronized(lock) {
        val config = mutableSettings.value
        if (expectedEpoch != epoch || (!config.recordingEnabled && !config.adbEnabled)) false else action(config)
    }
}

/** Presentation order; filtering and export selection share LogQuery. */
internal fun List<NexLog.Entry>.newestLogsFirst(minLevel: NexLog.Level?): List<NexLog.Entry> =
    filter { minLevel == null || it.level.priority >= minLevel.priority }
        .sortedWith(compareByDescending<NexLog.Entry> { it.timeMillis }.thenByDescending { it.id })

/** Terms are ANDed across safe message/tag/origin fields. */
internal fun List<NexLog.Entry>.searchLogs(query: String): List<NexLog.Entry> {
    val matcher = LogQuery.forDisplay(LogSettings(detailedEnabled = true), null, query)
    return filter(matcher::matches)
}
