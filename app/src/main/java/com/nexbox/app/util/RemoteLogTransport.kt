package com.nexbox.app.util

import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import com.nexbox.core.xposed.RuntimeLog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** All Binder work is off application/Hook threads; memory and transaction sizes are bounded. */
internal class RemoteLogTransport(
    context: Context,
    initial: LogConfiguration,
    private val onConfiguration: (LogConfiguration) -> Unit
) : AutoCloseable {
    private val resolver = context.contentResolver
    private val uri = DiagnosticLogProvider.uri(context)
    private val queue = LogRelayQueue()
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "nexlog-relay").apply { isDaemon = true } }
    private val started = AtomicBoolean()
    private val pump = LogRelayPump(executor, ::flush)
    private var configuration = initial
    private var reportedDrops = 0L
    private var retryFailures = 0
    private var lastFailure: String? = null
    private val observer = object : ContentObserver(null) { override fun onChange(selfChange: Boolean) { schedule() } }
    private val observerRegistration = LogObserverRegistration(
        register = { resolver.registerContentObserver(uri, false, observer) },
        unregister = { resolver.unregisterContentObserver(observer) }
    )

    fun start() {
        if (started.compareAndSet(false, true)) pump.request(immediate = true)
    }
    fun offer(event: PendingLog) { queue.offer(event); schedule() }
    /** Invoked on a dedicated crash IO task, not the possibly stalled ordinary relay queue. */
    fun sendCrash(event: PendingLog): Boolean {
        var accepted = false
        RuntimeLog.withoutLogging {
            accepted = checkNotNull(resolver.call(uri, "crash", null, DiagnosticLogProvider.encode(event))).getBoolean("accepted")
        }
        return accepted
    }
    private fun schedule() {
        if (started.get()) pump.request()
    }
    private fun update(bundle: Bundle?) {
        if (bundle == null) return
        val latest = DiagnosticLogProvider.decodeConfiguration(bundle)
        if (latest != configuration) { configuration = latest; onConfiguration(latest) }
    }
    private fun flush(): LogRelayPump.Result {
        var result = LogRelayPump.Result.IDLE
        // IPC itself can pass through container hooks. Suppress those to avoid a logging feedback loop.
        RuntimeLog.withoutLogging {
            try {
                observerRegistration.ensureRegistered()
                update(checkNotNull(resolver.call(uri, "config", null, null)))
                repeat(4) {
                    val batch = queue.peek(DiagnosticLogProvider.MAX_BATCH)
                    if (batch.isEmpty()) return@withoutLogging
                    val eligible = batch.filter { it.epoch == configuration.epoch && configuration.accepts(it.level) }
                    if (eligible.isNotEmpty()) {
                        val extras = Bundle().apply { putParcelableArrayList("events", ArrayList(eligible.map(DiagnosticLogProvider::encode))) }
                        val response = checkNotNull(resolver.call(uri, "append", null, extras))
                        update(response)
                    }
                    queue.acknowledge(batch.size)
                    if (retryFailures > 0 && configuration.enabled) {
                        queue.offer(batch.last().copy(level = NexLog.Level.WARN, tag = "LogRelay",
                            message = "recovered=true retries=$retryFailures lastFailure=$lastFailure",
                            origin = batch.last().origin.copy(timeMillis = System.currentTimeMillis(), thread = "nexlog-relay"),
                            epoch = configuration.epoch))
                        retryFailures = 0; lastFailure = null
                    }
                    val dropped = queue.dropped
                    if (dropped > reportedDrops && configuration.enabled) {
                        val source = batch.last()
                        queue.offer(source.copy(level = NexLog.Level.WARN, tag = "LogRelay",
                            message = "queueDropped=${dropped - reportedDrops}; Binder unavailable or producer faster than relay",
                            epoch = configuration.epoch))
                        reportedDrops = dropped
                    }
                }
                if (!queue.isEmpty()) result = LogRelayPump.Result.MORE
            } catch (error: Exception) {
                if (retryFailures < Int.MAX_VALUE) retryFailures++
                lastFailure = error.javaClass.simpleName
                result = LogRelayPump.Result.FAILED
                // The pump backs off even if producers keep filling the bounded queue.
            }
        }
        return result
    }

    override fun close() {
        if (!started.getAndSet(false)) return
        pump.close()
        executor.execute {
            RuntimeLog.withoutLogging {
                observerRegistration.close()
            }
        }
        executor.shutdown()
    }
}
