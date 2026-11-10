package com.nexbox.core.core.system.location

import com.nexbox.core.entity.location.BLocation
import com.nexbox.core.entity.location.BLocationConfig
import com.nexbox.core.entity.location.BLocationState
import com.nexbox.core.fake.frameworks.BLocationManager
import java.util.function.BooleanSupplier

/** Caller owns the user-lifecycle and configuration locks for the whole snapshot/commit. */
internal object LocationBatch {
    @JvmStatic
    fun snapshot(configs: Map<String, BLocationConfig>, targets: Map<String, Boolean>,
                 packages: List<String>?): List<BLocationState> {
        val names = packages ?: targets.keys
        if (packages != null) {
            require(names.size == names.toSet().size && names.all { it.isNotBlank() }) { "Invalid location targets" }
        }
        val result = ArrayList<BLocationState>(names.size)
        for (name in names) {
            check(targets.containsKey(name)) { "Location app is no longer installed" }
            val config = configs[name]
            result.add(BLocationState(name, config?.pattern ?: BLocationManager.CLOSE_MODE,
                config?.location, targets[name] == true))
        }
        return result
    }

    @JvmStatic
    fun apply(configs: MutableMap<String, BLocationConfig>, targets: Map<String, Boolean>?,
              updates: List<BLocationState>, forcePersistence: Boolean = false,
              persist: BooleanSupplier): List<BLocationState> {
        val names = HashSet<String>(updates.size)
        for (update in updates) {
            require(!update.packageName.isNullOrBlank() && names.add(update.packageName) &&
                update.pattern in BLocationManager.CLOSE_MODE..BLocationManager.OWN_MODE) {
                "Invalid location target or mode"
            }
            val location = update.location
            require(location == null || location.latitude.isFinite() && location.longitude.isFinite() &&
                location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0) {
                "Invalid location coordinates"
            }
            if (targets != null) {
                check(targets.containsKey(update.packageName)) { "Location app is no longer installed" }
                check(update.pattern == BLocationManager.CLOSE_MODE || targets[update.packageName] == true) {
                    "Location target is not an ordinary app"
                }
            }
        }
        data class Previous(val config: BLocationConfig?, val pattern: Int, val location: BLocation?)
        val previous = LinkedHashMap<String, Previous>()
        try {
            for (update in updates) {
                val config = configs[update.packageName]
                if (config != null && config.pattern == update.pattern && sameLocation(config.location, update.location)) continue
                if (config == null && update.pattern == BLocationManager.CLOSE_MODE && update.location == null) continue
                previous[update.packageName] = Previous(config, config?.pattern ?: BLocationManager.CLOSE_MODE, config?.location)
                val next = config ?: BLocationConfig().also { configs[update.packageName] = it }
                next.pattern = update.pattern
                next.location = update.location
            }
            // A no-op batch still confirms identity/targets, without rewriting the file.
            check(previous.isEmpty() && !forcePersistence || persist.asBoolean) { "Could not persist location configuration" }
        } catch (failure: Throwable) {
            for ((name, old) in previous) {
                if (old.config == null) configs.remove(name)
                else {
                    old.config.pattern = old.pattern
                    old.config.location = old.location
                    configs[name] = old.config
                }
            }
            throw failure
        }
        return updates.map { update ->
            val config = configs[update.packageName]
            BLocationState(update.packageName, config?.pattern ?: BLocationManager.CLOSE_MODE,
                config?.location, targets?.get(update.packageName) == true)
        }
    }

    private fun sameLocation(left: BLocation?, right: BLocation?): Boolean =
        left === right || left?.sameValueAs(right) == true
}
