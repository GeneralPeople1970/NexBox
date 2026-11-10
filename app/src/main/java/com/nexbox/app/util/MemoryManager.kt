package com.nexbox.app.util

import android.app.ActivityManager
import android.content.Context
import com.nexbox.app.app.App

/**
 * 进程内存压力探测。
 *
 * 系统页缓存可回收，物理内存占用比例不是应用内存压力。
 * 解码决策只使用系统 lowMemory/threshold 和本进程距堆上限的余量。
 */
object MemoryManager {

    private const val TAG = "MemoryManager"

    /**
     * 读取结果缓存。`getMemoryInfo` 是一次跨进程调用，
     * 而列表扫描会针对每个应用询问一次内存状态，不缓存会把 IPC 打爆。
     */
    private const val CACHE_TTL_MS = 1_000L
    private var cachedInfo: ActivityManager.MemoryInfo? = null
    private var cachedAt = 0L

    /** 暂缓解码；调用方必须保留对缺失图标的重试机会。 */
    fun shouldSkipIconLoading(context: Context? = null): Boolean {
        return readPressure(context ?: App.getContext()).skipIconLoading
    }

    private fun readPressure(context: Context): MemoryPressureSnapshot {
        val info = readMemoryInfo(context)
        val runtime = Runtime.getRuntime()
        return MemoryPressureSnapshot(
            systemLowMemory = info?.lowMemory == true,
            availableBytes = info?.availMem ?: 0L,
            thresholdBytes = info?.threshold ?: 0L,
            heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            heapMaxBytes = runtime.maxMemory()
        )
    }

    @Synchronized
    private fun readMemoryInfo(context: Context): ActivityManager.MemoryInfo? {
        val now = System.nanoTime() / 1_000_000L
        val cached = cachedInfo
        if (cached != null && now - cachedAt < CACHE_TTL_MS) {
            return cached
        }
        return try {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(mi)
            cachedInfo = mi
            cachedAt = now
            mi
        } catch (e: Exception) {
            NexLog.e(TAG, "Error getting memory info: ${e.message}", e)
            null
        }
    }
}

/** Pure decision boundary: total physical RAM deliberately does not participate. */
internal data class MemoryPressureSnapshot(
    val systemLowMemory: Boolean,
    val availableBytes: Long,
    val thresholdBytes: Long,
    val heapUsedBytes: Long,
    val heapMaxBytes: Long
) {
    private val systemUnderPressure: Boolean
        get() = systemLowMemory || (thresholdBytes > 0L && availableBytes in 0L..thresholdBytes)

    private fun heapUsageAtLeast(percent: Int): Boolean =
        heapMaxBytes > 0L && heapUsedBytes.coerceAtLeast(0L).toDouble() / heapMaxBytes >= percent / 100.0

    val skipIconLoading: Boolean get() = systemUnderPressure || heapUsageAtLeast(85)
    val critical: Boolean get() = systemUnderPressure || heapUsageAtLeast(92)
}
