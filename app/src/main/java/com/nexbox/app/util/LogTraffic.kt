package com.nexbox.app.util

import java.util.ArrayDeque

/** Bound repeat-heavy low-priority hook traffic, never rate-limit WARN/ERROR or operation results. */
internal class LogRateLimiter(private val limit: Int = 40, private val clock: () -> Long = System::nanoTime) {
    private data class Window(var start: Long, var count: Int = 0, var skipped: Int = 0)
    private val windows = linkedMapOf<String, Window>()
    /** -1 drops this entry; a non-negative result reports suppressed entries from the last window. */
    @Synchronized fun acquire(tag: String, level: NexLog.Level): Int {
        if (level.priority >= NexLog.Level.INFO.priority) return 0
        val now = clock()
        val key = "$tag:${level.priority}"
        if (key !in windows && windows.size >= 128) windows.remove(windows.keys.first())
        val window = windows.getOrPut(key) { Window(now) }
        var skipped = 0
        if (now - window.start >= 1_000_000_000L) {
            skipped = window.skipped
            window.start = now; window.count = 0; window.skipped = 0
        }
        if (window.count >= limit) {
            if (window.skipped < Int.MAX_VALUE) window.skipped++
            return -1
        }
        window.count++
        return skipped
    }
}

/** Logcat limits bytes, not UTF-16 characters; never cut a surrogate pair or a Chinese log line. */
internal fun logcatChunks(text: String, byteLimit: Int = 3000): List<String> {
    require(byteLimit >= 4)
    if (text.isEmpty()) return listOf("")
    val chunks = mutableListOf<String>()
    var start = 0
    var offset = 0
    var bytes = 0
    while (offset < text.length) {
        val codePoint = text.codePointAt(offset)
        val size = when { codePoint <= 0x7f -> 1; codePoint <= 0x7ff -> 2; codePoint <= 0xffff -> 3; else -> 4 }
        if (bytes + size > byteLimit) {
            chunks += text.substring(start, offset)
            start = offset
            bytes = 0
        }
        bytes += size
        offset += Character.charCount(codePoint)
    }
    chunks += text.substring(start)
    return chunks
}

internal data class PendingLog(
    val level: NexLog.Level, val tag: String, val message: String, val origin: LogOrigin, val epoch: Long
)

/** Retain pending events on Binder failure; acknowledgements remove exactly the sent prefix. */
internal class LogRelayQueue(private val capacity: Int = 256) {
    private val pending = ArrayDeque<PendingLog>()
    private var leased = 0
    @Volatile var dropped: Long = 0; private set
    @Synchronized fun offer(entry: PendingLog) {
        if (pending.size >= capacity) {
            dropped++
            if (entry.level.priority < NexLog.Level.WARN.priority) return
            // Do not evict an in-flight prefix: its acknowledgement must not consume newer events.
            var victim = -1
            var priority = Int.MAX_VALUE
            var index = 0
            for (candidate in pending) {
                if (index >= leased && candidate.level.priority < priority) {
                    victim = index
                    priority = candidate.level.priority
                }
                index++
            }
            if (victim < 0 || priority > entry.level.priority) return
            val iterator = pending.iterator()
            repeat(victim + 1) { iterator.next() }
            iterator.remove()
        }
        pending.addLast(entry)
    }
    @Synchronized fun peek(max: Int) = pending.take(max).also { leased = it.size }
    @Synchronized fun acknowledge(count: Int) {
        repeat(count.coerceAtMost(pending.size)) { pending.removeFirst() }
        leased = 0
    }
    @Synchronized fun isEmpty() = pending.isEmpty()
}
