package com.nexbox.core.core.system.location

import java.util.Collections
import java.util.function.Supplier

/** Only package eligibility is cached. Positions are always read under the configuration lock. */
internal class LocationTargetCache {
    private data class Entry(val generation: Long, val hideRoot: Boolean, val packages: Map<String, Boolean>)
    private val entries = HashMap<Int, Entry>()

    // The caller holds PackageManager's lifecycle lock, including across the loader. Package
    // monitor invalidation uses that same boundary, so a stale scan cannot repopulate this cache.
    fun get(userId: Int, generation: Long, hideRoot: Boolean = false,
            loader: Supplier<Map<String, Boolean>>): Map<String, Boolean> {
        synchronized(entries) {
            entries[userId]?.takeIf { it.generation == generation && it.hideRoot == hideRoot }?.let { return it.packages }
        }
        val packages = Collections.unmodifiableMap(LinkedHashMap(loader.get()))
        synchronized(entries) { entries[userId] = Entry(generation, hideRoot, packages) }
        return packages
    }

    fun invalidatePackage(packageName: String, userId: Int) = synchronized(entries) {
        // APK replacement can change module eligibility for copies in other profiles as well.
        entries.entries.removeAll { it.key == userId || packageName in it.value.packages }
    }

    fun removeUser(userId: Int) = synchronized(entries) { entries.remove(userId); Unit }
}
