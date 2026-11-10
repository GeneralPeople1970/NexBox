package com.nexbox.app.util

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One timer covers batching, retry backoff and recovery from missed configuration notifications. */
internal class LogRelayPump(
    private val executor: ScheduledExecutorService,
    private val flush: () -> Result,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) : AutoCloseable {
    enum class Result { IDLE, MORE, FAILED }

    private val lock = Any()
    private var pending: ScheduledFuture<*>? = null
    private var dueAt = Long.MAX_VALUE
    private var running = false
    private var requested = false
    private var closed = false
    private var failures = 0
    private var retryAt = 0L
    private var ticket = 0L

    fun request(immediate: Boolean = false) = synchronized(lock) {
        if (closed) return@synchronized
        requested = true
        if (!running) {
            val now = nowMillis()
            scheduleLocked(maxOf(now + if (immediate) 0 else BATCH_MS, retryAt))
        }
    }

    private fun scheduleLocked(deadline: Long) {
        if (pending != null && dueAt <= deadline) return
        pending?.cancel(false)
        dueAt = deadline
        val expected = ++ticket
        pending = executor.schedule({ run(expected) }, (deadline - nowMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }

    private fun run(expected: Long) {
        synchronized(lock) {
            if (closed || ticket != expected) return
            pending = null
            dueAt = Long.MAX_VALUE
            running = true
            requested = false
        }
        val result = try { flush() } catch (_: Exception) { Result.FAILED }
        synchronized(lock) {
            running = false
            if (closed) return
            val now = nowMillis()
            val delay = if (result == Result.FAILED) {
                failures = (failures + 1).coerceAtMost(7)
                retryDelay(failures).also { retryAt = now + it }
            } else {
                failures = 0
                retryAt = 0
                if (result == Result.MORE || requested) BATCH_MS else RECONCILE_MS
            }
            scheduleLocked(now + delay)
        }
    }

    override fun close() = synchronized(lock) {
        closed = true
        ticket++
        pending?.cancel(false)
        pending = null
    }

    companion object {
        const val BATCH_MS = 50L
        const val RECONCILE_MS = 60_000L
        fun retryDelay(failures: Int): Long =
            (1_000L shl (failures - 1).coerceIn(0, 6)).coerceAtMost(RECONCILE_MS)
    }
}
