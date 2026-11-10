package com.nexbox.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal fun appIconCacheBudget(maxHeapBytes: Long): Long =
    minOf(8L * 1024 * 1024, (maxHeapBytes / 16).coerceAtLeast(0))

/** Byte-bounded LRU. Only callers own work: leaving a row cancels its queued decode. */
internal class BoundedIconCache<K : Any, V : Any>(
    val maxBytes: Long,
    private val sizeOf: (V) -> Long
) {
    private sealed interface LoadResult<out V> {
        data class Loaded<V>(val value: V?) : LoadResult<V>
        data object OwnerCancelled : LoadResult<Nothing>
        data object Invalidated : LoadResult<Nothing>
    }

    private class Pending<V>(val generation: Long) {
        val result = CompletableDeferred<LoadResult<V>>()
    }

    private class KeyRequests(var callers: Int = 0, var invalidated: Boolean = false)

    private val lock = Any()
    private val values = LinkedHashMap<K, V>(16, 0.75f, true)
    private val pending = mutableMapOf<K, Pending<V>>()
    private val requestsByKey = mutableMapOf<K, KeyRequests>()
    private val decoders = Semaphore(2)
    private var generation = 0L
    private var bytes = 0L

    init { require(maxBytes >= 0) }

    val sizeBytes: Long get() = synchronized(lock) { bytes }
    val entryCount: Int get() = synchronized(lock) { values.size }

    suspend fun getOrLoad(key: K, load: suspend () -> V?): V? {
        val requests = synchronized(lock) { requestsByKey.getOrPut(key) { KeyRequests() }.also { it.callers++ } }
        try {
            return getOrLoadCurrent(key, requests, load)
        } finally {
            synchronized(lock) {
                requests.callers--
                if (requests.callers == 0 && requestsByKey[key] === requests) requestsByKey.remove(key)
            }
        }
    }

    private suspend fun getOrLoadCurrent(key: K, requests: KeyRequests, load: suspend () -> V?): V? {
        var retryGeneration: Long? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            var ownsLoad = false
            val request = synchronized(lock) {
                if (requests.invalidated) return null
                if (retryGeneration != null && retryGeneration != generation) return null
                values[key]?.let { return it }
                pending[key] ?: Pending<V>(generation).also {
                    pending[key] = it
                    ownsLoad = true
                }
            }
            if (!ownsLoad) {
                when (val result = request.result.await()) {
                    is LoadResult.Loaded -> return synchronized(lock) {
                        result.value.takeIf { request.generation == generation && !requests.invalidated }
                    }
                    LoadResult.Invalidated -> return null
                    LoadResult.OwnerCancelled -> {
                        retryGeneration = request.generation
                        continue
                    }
                }
            }
            try {
                val loaded = decoders.withPermit {
                    currentCoroutineContext().ensureActive()
                    if (synchronized(lock) { requests.invalidated || request.generation != generation || pending[key] !== request }) null else load()
                }
                currentCoroutineContext().ensureActive()
                val accepted = synchronized(lock) {
                    if (requests.invalidated || request.generation != generation || pending[key] !== request) null
                    else loaded?.also { putLocked(key, it) }
                }
                request.result.complete(LoadResult.Loaded(accepted))
                return accepted
            } catch (error: CancellationException) {
                // Remove ownership before waking peers: exactly one surviving row can
                // start a replacement; none can spin on an already-completed pending entry.
                synchronized(lock) { if (pending[key] === request) pending.remove(key) }
                request.result.complete(LoadResult.OwnerCancelled)
                throw error
            } catch (error: Throwable) {
                request.result.completeExceptionally(error)
                throw error
            } finally {
                synchronized(lock) { if (pending[key] === request) pending.remove(key) }
            }
        }
    }

    /** A trim is also a publication barrier for decodes queued before memory pressure. */
    fun trimTo(targetBytes: Long) = synchronized(lock) {
        invalidatePendingLocked()
        evictLocked(targetBytes.coerceIn(0, maxBytes))
    }

    fun invalidate(predicate: (K) -> Boolean) = synchronized(lock) {
        // A package/user change must not cancel unrelated visible rows waiting on the same queue.
        // Track callers as well as owners: cancellation removes an owner before its waiters resume.
        val callers = requestsByKey.entries.iterator()
        while (callers.hasNext()) {
            val entry = callers.next()
            if (predicate(entry.key)) {
                entry.value.invalidated = true
                callers.remove()
            }
        }
        val requests = pending.entries.iterator()
        while (requests.hasNext()) {
            val entry = requests.next()
            if (predicate(entry.key)) {
                entry.value.result.complete(LoadResult.Invalidated)
                requests.remove()
            }
        }
        val iterator = values.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (predicate(entry.key)) {
                bytes -= weightOf(entry.value)
                iterator.remove()
            }
        }
    }

    private fun invalidatePendingLocked() {
        generation++
        pending.values.forEach { it.result.complete(LoadResult.Invalidated) }
        pending.clear()
    }

    private fun putLocked(key: K, value: V) {
        val weight = weightOf(value)
        // Oversized entries may be displayed, but must not flush or exceed the cache.
        if (weight > maxBytes || maxBytes == 0L) return
        values.put(key, value)?.let { bytes -= weightOf(it) }
        bytes += weight
        evictLocked(maxBytes)
    }

    private fun evictLocked(targetBytes: Long) {
        val iterator = values.entries.iterator()
        while (bytes > targetBytes && iterator.hasNext()) {
            bytes -= weightOf(iterator.next().value)
            iterator.remove()
        }
    }

    private fun weightOf(value: V): Long = sizeOf(value).coerceAtLeast(1)
}
