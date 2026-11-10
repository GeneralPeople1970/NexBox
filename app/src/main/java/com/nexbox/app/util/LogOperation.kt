package com.nexbox.app.util

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit scope survives coroutine dispatcher changes; logging never changes operation results. */
class LogOperation internal constructor(
    val id: String,
    private val name: String,
    private val fields: String,
    private val emit: (NexLog.Level, String, Throwable?) -> Unit,
    private val clock: () -> Long = System::nanoTime,
    private val enabled: (NexLog.Level) -> Boolean = { true }
) {
    private val started = readClock()
    private val finished = AtomicBoolean()
    init { emitSafely(NexLog.Level.INFO) { "op=$id action=$name stage=start $fields" } }

    fun step(stage: String, detail: String = "") {
        if (!finished.get()) emitSafely(NexLog.Level.DEBUG) { "op=$id action=$name stage=$stage $fields $detail" }
    }
    fun finish(success: Boolean = true, detail: String = "") {
        if (finished.compareAndSet(false, true)) emitSafely(if (success) NexLog.Level.INFO else NexLog.Level.WARN) {
            "op=$id action=$name stage=finish success=$success durationMs=${elapsed()} $fields $detail"
        }
    }
    fun fail(error: Throwable, detail: String = "") {
        if (finished.compareAndSet(false, true)) {
            val cancelled = error is CancellationException
            emitSafely(if (cancelled) NexLog.Level.INFO else NexLog.Level.ERROR, if (cancelled) null else error) {
                "op=$id action=$name stage=${if (cancelled) "cancelled" else "failed"} durationMs=${elapsed()} $fields $detail"
            }
        }
    }

    private inline fun emitSafely(level: NexLog.Level, error: Throwable? = null, message: () -> String) {
        try { if (enabled(level)) emit(level, message(), error) }
        catch (_: Exception) { }
        catch (_: LinkageError) { }
    }

    private fun readClock(): Long? = try { clock() }
    catch (_: Exception) { null }
    catch (_: LinkageError) { null }

    private fun elapsed(): Long {
        val start = started ?: return 0L
        val end = readClock() ?: return 0L
        return (end - start).coerceAtLeast(0L) / 1_000_000
    }
}
