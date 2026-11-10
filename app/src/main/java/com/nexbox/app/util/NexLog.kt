package com.nexbox.app.util

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.FileProvider
import com.nexbox.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.nexbox.core.xposed.RuntimeLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Host/container diagnostics; the main process alone owns the rolling log and live list. */
object NexLog {
    enum class Level(val label: String, val priority: Int) {
        VERBOSE("Verbose", Log.VERBOSE), DEBUG("Debug", Log.DEBUG), INFO("Information", Log.INFO),
        WARN("Warning", Log.WARN), ERROR("Error", Log.ERROR);
        companion object { fun fromPriority(value: Int) = entries.lastOrNull { it.priority <= value } ?: VERBOSE }
    }
    data class Entry(
        val timeMillis: Long, val level: Level, val tag: String, val message: String,
        val id: Long = 0, val origin: LogOrigin? = null
    )
    data class HistoryFile(val name: String, val text: String, val truncated: Boolean = false)
    internal data class PipelineStatus(val captureSession: String, val pendingWrites: Int, val droppedWrites: Long,
                                       val clearRequestedEpoch: Long?, val clearCompletedEpoch: Long?, val clearFailedEpoch: Long?) {
        val clearState: String get() = when (clearRequestedEpoch) {
            null -> "idle"
            clearCompletedEpoch -> "completed"
            clearFailedEpoch -> "failed"
            else -> "pending"
        }
    }
    const val LOGCAT_TAG = "NexBox"
    private const val PREFS_NAME = "app_prefs"
    private val session = UUID.randomUUID().toString().take(12)
    private val operationIds = AtomicLong()
    private val io = Executors.newSingleThreadScheduledExecutor { Thread(it, "nexlog-io").apply { isDaemon = true } }
    private val publications = Executors.newSingleThreadScheduledExecutor { Thread(it, "nexlog-snapshot").apply { isDaemon = true } }
    private val writeLock = Any()
    private val writeBatch = ArrayList<Entry>(64)
    private val pendingWrites = AtomicInteger()
    private val droppedWrites = AtomicLong()
    private val clearRequestedEpoch = AtomicLong(-1L)
    private val clearCompletedEpoch = AtomicLong(-1L)
    private val clearFailedEpoch = AtomicLong(-1L)
    private val rateLimiter = LogRateLimiter()
    private val lineFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }
    private val mutableStorageError = MutableStateFlow<String?>(null)
    val storageError = mutableStorageError.asStateFlow()
    @Volatile private var appContext: Context? = null
    @Volatile private var fileStore: LogFileStore? = null
    @Volatile private var remote: RemoteLogTransport? = null
    @Volatile private var mainProcess = true
    @Volatile private var processName = "uninitialized"
    private var legacyFiles: List<File> = emptyList()
    private var legacyCrashDirectory: File? = null

    private val recorder = LogRecorder(
        persist = { settings, epoch ->
            checkNotNull(appContext).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(LogSettings.RECORDING_KEY, settings.recordingEnabled)
                .putBoolean(LogSettings.ADB_KEY, settings.adbEnabled)
                .putBoolean(LogSettings.DETAILED_KEY, settings.detailedEnabled)
                .putLong(LogSettings.EPOCH_KEY, epoch).apply()
        },
        mirror = { level, tag, text ->
            // Android truncates long records. Preserve multiline stacks in bounded ordered chunks.
            logcatChunks(text).forEachIndexed { index, chunk ->
                Log.println(level.priority, LOGCAT_TAG, "[$tag part=${index + 1}] $chunk")
            }
        },
        append = ::enqueueWrite,
        clearFiles = { clearEpoch ->
            clearRequestedEpoch.set(clearEpoch)
            synchronized(writeLock) {
                enqueueBatchLocked()
                io.execute {
                    RuntimeLog.withoutLogging {
                        try {
                            fileStore?.clear(clearEpoch)
                            legacyDiagnostics().forEach { file ->
                                check(file.delete() || !file.exists()) { "Could not clear legacy diagnostic file" }
                            }
                            clearCompletedEpoch.set(clearEpoch)
                            mutableStorageError.value = null
                        } catch (error: Exception) {
                            clearFailedEpoch.set(clearEpoch)
                            storageFailure("clear", error)
                        }
                    }
                }
            }
        },
        persistenceFailure = { error ->
            mutableStorageError.value = "Debugging settings were not saved: ${error.javaClass.simpleName}"
        },
        scheduleSnapshot = { publish ->
            publications.schedule({ publish() }, 50, TimeUnit.MILLISECONDS)
        }
    )
    val entries = recorder.entries
    val settings = recorder.settings

    /** Called before Bcore initialization, in every container process; never performs Binder IO here. */
    @Synchronized fun install(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext ?: context
        appContext = app
        processName = Application.getProcessName().orEmpty().ifBlank { app.packageName }
        mainProcess = processName == app.packageName
        val preferences = runCatching { app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all }
            .getOrDefault(emptyMap<String, Any>())
        fileStore = LogFileStore(File(app.filesDir, "diagnostics"))
        val savedEpoch = (preferences[LogSettings.EPOCH_KEY] as? Long)?.takeIf { it >= 0 && it < Long.MAX_VALUE } ?: 0L
        val minimumEpoch = runCatching { fileStore?.minimumEpoch() ?: 0L }.getOrElse {
            mutableStorageError.value = "Could not restore diagnostic clear barrier: ${it.javaClass.simpleName}"
            savedEpoch
        }
        recorder.restoreSettings(LogSettings.fromPreferences(preferences), maxOf(savedEpoch, minimumEpoch))
        if (mainProcess) {
            val oldDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "logs")
            legacyFiles = listOf(File(oldDir, "nexbox.log"), File(oldDir, "nexbox.log.1"))
            legacyCrashDirectory = File(app.filesDir, "crash_logs")
        } else {
            remote = RemoteLogTransport(app, recorder.configuration(), ::applyRemoteConfiguration)
        }
        RuntimeLog.setSink(object : RuntimeLog.Sink {
            override fun isEnabled() = recorder.configuration().enabled
            override fun isEnabled(priority: Int) = NexLog.isEnabled(Level.fromPriority(priority))
            override fun write(priority: Int, tag: String?, message: String?, error: Throwable?) {
                log(Level.fromPriority(priority), tag ?: "Container", message.orEmpty(), error)
            }
            override fun crash(thread: Thread, error: Throwable) { captureCrash(thread, error) }
        })
        remote?.start()
        i("Session") { "stage=attach ${environmentSummary()}" }
    }

    fun setRecordingEnabled(enabled: Boolean): Boolean = changeSettings { recorder.setRecordingEnabled(enabled) }
    fun setAdbEnabled(enabled: Boolean): Boolean = changeSettings { recorder.setAdbEnabled(enabled) }
    fun setDetailedEnabled(enabled: Boolean): Boolean = changeSettings { recorder.setDetailedEnabled(enabled) }
    /** Enables or disables both local capture and the NexBox Logcat mirror as one setting change. */
    fun setDebugEnabled(enabled: Boolean): Boolean = changeSettings { recorder.setDebugEnabled(enabled) }
    fun setDestinations(recording: Boolean?, adb: Boolean?): Boolean = changeSettings { recorder.setDestinations(recording, adb) }
    fun setOptions(recording: Boolean?, adb: Boolean?, detailed: Boolean?): Boolean =
        changeSettings { recorder.setOptions(recording, adb, detailed) }
    private fun changeSettings(change: () -> Boolean): Boolean {
        if (!mainProcess) return false
        val changed = change()
        if (changed) {
            publishConfiguration()
            i("Session") { "stage=settings recording=${settings.value.recordingEnabled} adb=${settings.value.adbEnabled}" +
                " detailed=${settings.value.detailedEnabled} displayMinLevel=${settings.value.minimumDisplayLevel.name} ${environmentSummary()}" }
        }
        return changed
    }
    private fun publishConfiguration() {
        RuntimeLog.configurationChanged()
        appContext?.let { app ->
            runCatching { app.contentResolver.notifyChange(DiagnosticLogProvider.uri(app), null) }
        }
    }
    private fun applyRemoteConfiguration(configuration: LogConfiguration) {
        recorder.restoreSettings(configuration.settings, configuration.epoch)
        RuntimeLog.configurationChanged()
    }
    internal fun configuration() = recorder.configuration()
    fun isEnabled(): Boolean = recorder.configuration().enabled
    fun isEnabled(level: Level): Boolean = recorder.configuration().accepts(level)
    fun snapshot(): List<Entry> = recorder.snapshot()
    internal fun capture(): LogCapture = recorder.capture()
    internal fun pipelineStatus() = PipelineStatus(session, pendingWrites.get(), droppedWrites.get(),
        clearRequestedEpoch.get().takeIf { it >= 0 }, clearCompletedEpoch.get().takeIf { it >= 0 },
        clearFailedEpoch.get().takeIf { it >= 0 })

    @JvmStatic @JvmOverloads fun v(tag: String, message: String, error: Throwable? = null) = log(Level.VERBOSE, tag, message, error)
    @JvmStatic @JvmOverloads fun d(tag: String, message: String, error: Throwable? = null) = log(Level.DEBUG, tag, message, error)
    @JvmStatic @JvmOverloads fun i(tag: String, message: String, error: Throwable? = null) = log(Level.INFO, tag, message, error)
    @JvmStatic @JvmOverloads fun w(tag: String, message: String, error: Throwable? = null) = log(Level.WARN, tag, message, error)
    @JvmStatic @JvmOverloads fun e(tag: String, message: String, error: Throwable? = null) = log(Level.ERROR, tag, message, error)

    inline fun v(tag: String, error: Throwable? = null, message: () -> String) {
        logLazy(Level.VERBOSE, tag, error, message)
    }
    inline fun d(tag: String, error: Throwable? = null, message: () -> String) {
        logLazy(Level.DEBUG, tag, error, message)
    }
    inline fun i(tag: String, error: Throwable? = null, message: () -> String) {
        logLazy(Level.INFO, tag, error, message)
    }
    inline fun w(tag: String, error: Throwable? = null, message: () -> String) {
        logLazy(Level.WARN, tag, error, message)
    }
    inline fun e(tag: String, error: Throwable? = null, message: () -> String) {
        logLazy(Level.ERROR, tag, error, message)
    }

    @PublishedApi
    internal inline fun logLazy(level: Level, tag: String, error: Throwable?, message: () -> String) {
        if (!isEnabled(level)) return
        try { log(level, tag, message(), error) }
        catch (failure: Exception) { reportLazyFailure(failure) }
        catch (failure: LinkageError) { reportLazyFailure(failure) }
    }

    @PublishedApi
    internal fun reportLazyFailure(failure: Throwable) = pipelineFailure(failure)

    fun log(level: Level, tag: String, message: String, error: Throwable? = null) {
        val configuration = recorder.configuration()
        if (!configuration.accepts(level)) return
        try {
            val safeTag = LogPrivacy.tag(tag)
            val skipped = rateLimiter.acquire(safeTag, level)
            if (skipped < 0) return
            val text = LogPrivacy.clean((if (skipped > 0) "[suppressed=$skipped low-priority entries] " else "") +
                message + if (error == null) "" else "\n" + stackTraceOf(error))
            val origin = origin()
            val relay = remote
            if (relay != null) relay.offer(PendingLog(level, safeTag, text, origin, configuration.epoch))
            else recorder.log(level, safeTag, origin, configuration.epoch) { text }
        } catch (failure: Exception) {
            pipelineFailure(failure)
        } catch (failure: LinkageError) {
            pipelineFailure(failure)
        }
    }

    private fun pipelineFailure(error: Throwable) {
        // Fail closed: do not fall back to printing an unredacted message or exception.
        try {
            val message = "Diagnostic pipeline unavailable: ${error.javaClass.simpleName}"
            if (mutableStorageError.value != message) {
                mutableStorageError.value = message
                recorder.log(Level.ERROR, "LogPipeline", writeFile = false) { message }
            }
        } catch (_: Exception) {
            // A broken mirror/snapshot destination must not make the diagnostic fallback fatal.
        } catch (_: LinkageError) { }
    }
    internal fun acceptRemote(entry: PendingLog, callerPid: Int) {
        if (!mainProcess) return
        val current = recorder.configuration()
        if (!current.accepts(entry.level) || current.epoch != entry.epoch) return
        try {
            val origin = verifiedOrigin(entry.origin, callerPid)
            recorder.log(entry.level, LogPrivacy.tag(entry.tag), origin, entry.epoch) { LogPrivacy.clean(entry.message) }
        } catch (error: Exception) { pipelineFailure(error) }
        catch (error: LinkageError) { pipelineFailure(error) }
    }
    private fun verifiedOrigin(source: LogOrigin, callerPid: Int): LogOrigin {
        return source.copy(
            pid = callerPid, process = LogPrivacy.tag(source.process),
            thread = LogPrivacy.tag(source.thread), session = LogPrivacy.tag(source.session),
            context = LogPrivacy.clean(source.context.take(512)),
            timeMillis = source.timeMillis.coerceIn(System.currentTimeMillis() - 86_400_000L, System.currentTimeMillis() + 60_000L)
        )
    }
    fun operation(tag: String, name: String, fields: String = "") = LogOperation(
        "$session-${operationIds.incrementAndGet()}", name, fields,
        { level, message, error -> log(level, tag, message, error) }, enabled = ::isEnabled
    )
    fun <T> trace(tag: String, name: String, fields: String = "", block: (LogOperation) -> T): T {
        val operation = operation(tag, name, fields)
        return try { block(operation).also { operation.finish() } }
        catch (error: Throwable) { operation.fail(error); throw error }
    }

    /** The epoch prevents in-flight remote events from reappearing after clear/disable. */
    fun clear(): Boolean {
        if (!mainProcess) return false
        return recorder.clear().also { if (it) publishConfiguration() }
    }
    fun logFilePath(): String? = fileStore?.current?.absolutePath
    private fun origin() = LogOrigin(System.currentTimeMillis(), LogPrivacy.tag(processName), Process.myPid(), LogPrivacy.tag(Thread.currentThread().name), session,
        LogPrivacy.clean(RuntimeLog.getProcessContext().take(512)))

    private fun enqueueWrite(entry: Entry) {
        // Reserve space for actionable failures when debug traffic saturates the disk queue.
        val queueLimit = if (entry.level.priority >= Level.WARN.priority) 1024 else 960
        if (pendingWrites.incrementAndGet() > queueLimit) {
            pendingWrites.decrementAndGet()
            droppedWrites.incrementAndGet()
            mutableStorageError.value = "Disk queue full; some entries were not persisted"
            return
        }
        synchronized(writeLock) {
            writeBatch.add(entry)
            if (writeBatch.size == 1) io.schedule({
                synchronized(writeLock) { enqueueBatchLocked() }
            }, 50, TimeUnit.MILLISECONDS)
            if (writeBatch.size >= 64) enqueueBatchLocked()
        }
    }

    /** Called while holding writeLock; clear/history barriers enqueue the preceding partial batch. */
    private fun enqueueBatchLocked() {
        if (writeBatch.isEmpty()) return
        val batch = writeBatch.toList()
        writeBatch.clear()
        io.execute {
            try {
                RuntimeLog.withoutLogging {
                    try { fileStore?.appendBatch(batch.map(::format)) }
                    catch (error: Exception) { storageFailure("append", error) }
                }
            } finally { pendingWrites.addAndGet(-batch.size) }
        }
    }
    private fun storageFailure(action: String, error: Throwable) {
        val message = LogPrivacy.clean("action=$action ${error.javaClass.simpleName}: ${error.message}")
        if (mutableStorageError.value == message) return
        mutableStorageError.value = message
        recorder.log(Level.ERROR, "LogStorage", writeFile = false) { LogPrivacy.clean(message + "\n" + stackTraceOf(error)) }
    }

    /** Crash-only bounded relay and atomic persistence. Ordinary logging never waits for Binder. */
    private fun captureCrash(thread: Thread, error: Throwable) {
        val configuration = recorder.configuration()
        if (!configuration.enabled) return
        RuntimeLog.withoutLogging {
            try {
                val source = origin().copy(thread = LogPrivacy.tag(thread.name))
                val event = PendingLog(Level.ERROR, "Crash", LogPrivacy.clean("${environmentSummary()}\n${stackTraceOf(error)}"), source, configuration.epoch)
                val relay = remote
                if (relay == null) acceptRemoteCrash(event, source.pid)
                else {
                    val sent = io.submit(Callable { relay.sendCrash(event) })
                    try { sent.get(1500, TimeUnit.MILLISECONDS) }
                    catch (failure: Exception) {
                        if (failure is TimeoutException) sent.cancel(false)
                        // Host unavailable: re-read the persisted opt-in before using an emergency file.
                        // The cross-process clear barrier also rejects stale files after a clear.
                        @Suppress("DEPRECATION")
                        val preferences = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS)?.all.orEmpty()
                        if ((preferences[LogSettings.EPOCH_KEY] as? Long ?: 0L) == configuration.epoch) {
                            val saved = LogSettings.fromPreferences(preferences)
                            if (saved.recordingEnabled) fileStore?.writeCrash(
                                "${source.label()} epoch=${event.epoch} fallback=host_unavailable relayFailure=${failure.cause?.javaClass?.simpleName ?: failure.javaClass.simpleName}\n${event.message}",
                                source.pid, source.timeMillis, event.epoch)
                            if (saved.adbEnabled) recorder.log(Level.ERROR, "Crash", source, event.epoch, writeFile = false) { event.message }
                        }
                    }
                }
            } catch (failure: Exception) { mutableStorageError.value = "Crash persistence failed: ${failure.javaClass.simpleName}" }
            catch (failure: LinkageError) { pipelineFailure(failure) }
        }
    }

    internal fun acceptRemoteCrash(event: PendingLog, callerPid: Int): Boolean {
        if (!mainProcess || event.level != Level.ERROR) return false
        return recorder.withCapture(event.epoch) { config ->
            val source = verifiedOrigin(event.origin, callerPid)
            val message = LogPrivacy.clean(event.message)
            recorder.log(Level.ERROR, "Crash", source, event.epoch, writeFile = false) { message }
            !config.recordingEnabled || fileStore?.writeCrash("${source.label()} epoch=${event.epoch}\n$message",
                source.pid, source.timeMillis, event.epoch) != null
        }
    }

    /** Writes the caller's selected snapshot chronologically; never appends a Logcat dump. */
    fun exportForShare(
        snapshot: List<Entry> = recorder.capture().let { capture ->
            capture.entries.filter { capture.configuration.settings.accepts(it.level) }
        },
        scope: String = "capture threshold"
    ): Uri? {
        val context = appContext ?: return null
        return try {
            val out = newExport(context, ".txt")
            out.bufferedWriter().use { writer ->
                writer.appendLine("NexBox log export @ ${Date()}")
                writer.appendLine(environmentSummary())
                writer.appendLine("---- collected host logs (${snapshot.size}); $scope ----")
                snapshot.sortedWith(compareBy<Entry> { it.timeMillis }.thenBy { it.id })
                    .forEach { writer.appendLine(format(it)) }
            }
            shareUri(context, out)
        } catch (error: Exception) { storageFailure("export", error); null }
    }

    /** Explicit history export; runs behind queued writes. Caller must use a background thread. */
    fun exportDiagnosticsForShare(): Uri? {
        val context = appContext ?: return null
        return try {
            val export = synchronized(writeLock) {
                enqueueBatchLocked()
                io.submit(Callable {
                    var result: Uri? = null
                    RuntimeLog.withoutLogging {
                        val out = newExport(context, ".zip")
                        ZipOutputStream(out.outputStream().buffered()).use { zip ->
                            fun add(name: String, text: String) {
                                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                            }
                            val exportSettings = recorder.configuration().settings
                            add("environment.txt", environmentSummary() + "\nrecording=${exportSettings.recordingEnabled} adb=${exportSettings.adbEnabled}" +
                                " detailedEnabled=${exportSettings.detailedEnabled} captureMinLevel=${exportSettings.minimumCaptureLevel.name}" +
                                "\ndiskQueueDropped=${droppedWrites.get()} storageError=${storageError.value ?: "none"}\n" + exitHistory(context))
                            (fileStore?.files().orEmpty() + legacyDiagnostics().sortedByDescending { it.lastModified() }.take(12)).forEachIndexed { index, file ->
                                add("history/$index-${file.name}", readSanitized(file))
                            }
                        }
                        result = shareUri(context, out)
                    }
                    result
                })
            }
            export.get(30, TimeUnit.SECONDS)
        } catch (error: Exception) { storageFailure("diagnostic-export", error); null }
    }

    /** Sanitized rolling/crash history for the Debug CLI; never exposes raw diagnostic files. */
    fun historyForDebug(): List<HistoryFile> {
        if (!mainProcess) return emptyList()
        return runCatching {
            val history = synchronized(writeLock) {
                enqueueBatchLocked()
                io.submit(Callable {
                    (fileStore?.files().orEmpty() + legacyDiagnostics())
                        .filter { it.isFile }
                        .distinctBy { it.canonicalPath }
                        .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
                        .map { HistoryFile(it.name, readSanitized(it), it.length() > 1024 * 1024 + 32 * 1024) }
                })
            }
            history.get(30, TimeUnit.SECONDS)
        }.getOrElse {
            storageFailure("history", it)
            throw it
        }
    }
    private fun legacyDiagnostics(): List<File> {
        val directory = legacyCrashDirectory
        val crashes = if (directory == null || !directory.exists()) emptyList() else {
            checkNotNull(directory.listFiles()) { "Could not enumerate legacy crash files" }
                .filter { it.isFile && it.name.matches(Regex("crash_[0-9_]+\\.log")) }
        }
        return legacyFiles.filter { it.isFile } + crashes
    }
    private fun readSanitized(file: File): String {
        val bytes = ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer = ByteArray(4096)
            var remaining = 1024 * 1024 + 32 * 1024
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                bytes.write(buffer, 0, count); remaining -= count
            }
        }
        return bytes.toString("UTF-8").lineSequence().joinToString("\n") { LogPrivacy.clean(it) }
    }
    private fun exitHistory(context: Context): String = runCatching {
        context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 10).joinToString("\n") {
                "exit timestamp=${it.timestamp} process=${LogPrivacy.tag(it.processName)} pid=${it.pid} reason=${it.reason} status=${it.status} importance=${it.importance}"
            }
    }.getOrElse { "exitHistory=unavailable (${it.javaClass.simpleName})" }
    private fun newExport(context: Context, extension: String): File {
        val directory = File(context.cacheDir, "shared_logs")
        check(directory.isDirectory || directory.mkdirs()) { "Could not create export directory" }
        directory.listFiles().orEmpty().filter {
            it.isFile && it.name.startsWith("nexbox-log-") && System.currentTimeMillis() - it.lastModified() > 7 * 86_400_000L
        }.forEach { it.delete() }
        return File.createTempFile("nexbox-log-", extension, directory)
    }
    private fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    private fun environmentSummary(): String =
        "version=${BuildConfig.VERSION_NAME} versionCode=${BuildConfig.VERSION_CODE} buildType=${BuildConfig.BUILD_TYPE} " +
            "sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} securityPatch=${Build.VERSION.SECURITY_PATCH} is64Bit=${Process.is64Bit()} " +
            "abi=${Build.SUPPORTED_ABIS.joinToString(",")} manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} " +
            "process=$processName pid=${Process.myPid()} session=$session"
    private fun format(entry: Entry): String =
        "${lineFormat.get()!!.format(Date(entry.timeMillis))} ${entry.level.label}/${entry.tag}: " +
            "[timeMillis=${entry.timeMillis}${entry.origin?.let { " ${it.label()}" }.orEmpty()}] " + LogPrivacy.clean(entry.message)
    private fun stackTraceOf(error: Throwable): String =
        StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().trimEnd()
}
