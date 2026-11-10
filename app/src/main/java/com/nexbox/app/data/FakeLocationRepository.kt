package com.nexbox.app.data

import com.nexbox.app.util.NexLog

import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import com.nexbox.core.entity.location.BLocation
import com.nexbox.core.entity.location.BLocationState
import com.nexbox.core.fake.frameworks.BLocationManager
import com.nexbox.core.fake.frameworks.BPackageManager
import com.nexbox.core.utils.ConfigurationAppStopper

/**
 * Applies group defaults and independent app locations to selected packages using OWN_MODE. The
 * service's GLOBAL_MODE is process-wide and must not be used for per-profile UI choices. All calls
 * belong on an IO thread. App edits only touch that package; group edits preserve app overrides.
 *
 * A batch succeeds only after its persisted kernel receipt and preference commit succeed.
 * Failure is explicit; touched packages are restored best-effort and preferences keep the old state.
 */
class FakeLocationRepository internal constructor(
    private val backend: FakeLocationBackend,
    private val preferences: FakeLocationStateStore
) {
    constructor() : this(BinderFakeLocationBackend(), FakeLocationPrefs.stateStore)

    fun moveRocker(userId: Int, generation: Long, packageName: String, angle: Float, distance: Float): Boolean =
        synchronized(userLock(userId)) {
            if (userId < 0 || generation <= 0L || packageName.isBlank() ||
                !angle.isFinite() || !distance.isFinite() || distance !in 0f..2.5f) return@synchronized false
            val runtime = loadRuntime(userId, generation)
            val state = rockerState(runtime, packageName)
            val point = state.point ?: return@synchronized false
            if (!state.enabled || !state.selected) return@synchronized false
            val bearing = Math.toRadians(angle.toDouble())
            val arc = distance / 6371008.8
            val lat = Math.toRadians(point.first)
            val lon = Math.toRadians(point.second)
            val nextLat = kotlin.math.asin(kotlin.math.sin(lat) * kotlin.math.cos(arc) +
                kotlin.math.cos(lat) * kotlin.math.sin(arc) * kotlin.math.cos(bearing))
            val nextLon = lon + kotlin.math.atan2(kotlin.math.sin(bearing) * kotlin.math.sin(arc) * kotlin.math.cos(lat),
                kotlin.math.cos(arc) - kotlin.math.sin(lat) * kotlin.math.sin(nextLat))
            val latitude = Math.toDegrees(nextLat)
            val longitude = (Math.toDegrees(nextLon) + 540.0) % 360.0 - 180.0
            // Rocker motion is a live input stream; stopping its owner would terminate the gesture.
            updateUser(userId, point = if (state.independent) null else FakeLocationPoint(latitude, longitude),
                enabled = null, expectedIdentity = generation,
                appUpdate = if (state.independent) AppLocationUpdate(packageName, FakeLocationPoint(latitude, longitude)) else null,
                stopAffected = false, prepared = runtime) >= 0
        }

    /** No labels, Drawables or scope-list scans are part of the rocker input path. */
    fun loadRockerState(userId: Int, packageName: String, createTime: Long): FakeLocationRockerState =
        synchronized(userLock(userId)) { rockerState(loadRuntime(userId, createTime), packageName) }

    private fun loadRuntime(userId: Int, createTime: Long): RuntimeState {
        require(userId >= 0 && createTime > 0L)
        check(backend.userIdentity(userId) == createTime) { "Location user changed" }
        backend.checkAvailable()
        val snapshot = backend.snapshot(userId, createTime)
        val saved = preferences.read(userId, createTime)
        check(backend.userIdentity(userId) == createTime) { "Location user changed" }
        return RuntimeState(createTime, snapshot, saved)
    }

    private fun rockerState(runtime: RuntimeState, packageName: String): FakeLocationRockerState {
        check(packageName in runtime.snapshot.eligiblePackages) { "Location app is no longer available" }
        val saved = runtime.saved
        val location = saved.appLocations[packageName] ?: SavedAppFakeLocation(saved.enabled, saved.point)
        return FakeLocationRockerState(
            selected = saved.scopePackages?.contains(packageName) != false,
            independent = packageName in saved.appLocations,
            enabled = location.enabled,
            point = location.point?.let { it.latitude to it.longitude }
        )
    }

    /** Number of packages confirmed applied, or -1 when the operation could not be confirmed. */
    fun applyGlobalLocation(userId: Int, latitude: Double, longitude: Double, expectedCreateTime: Long? = null): Int {
        val point = FakeLocationPoint(latitude, longitude)
        if (userId < 0 || !point.isValid) {
            NexLog.w("Location") { "action=apply rejected=invalid_parameters userId=$userId" }
            return -1
        }
        return updateUser(userId, point, enabled = true, expectedIdentity = expectedCreateTime)
    }

    /** Retains the last selected point; false leaves the previous preference enabled state intact. */
    fun clearGlobalLocation(userId: Int, expectedCreateTime: Long? = null): Boolean =
        userId >= 0 && updateUser(userId, point = null, enabled = false, expectedIdentity = expectedCreateTime) >= 0

    /** Saving an editor draft must not implicitly enable a previously disabled group. */
    fun saveGlobalPoint(userId: Int, createTime: Long, latitude: Double, longitude: Double): Boolean {
        val point = FakeLocationPoint(latitude, longitude)
        return userId >= 0 && createTime > 0L && point.isValid &&
            updateUser(userId, point, enabled = null, expectedIdentity = createTime) >= 0
    }

    /** Restores both inherited and independent saved locations without changing either setting. */
    fun reconcileLocations(userId: Int, createTime: Long): Boolean =
        userId >= 0 && createTime > 0L && updateUser(userId, point = null, enabled = null,
            expectedIdentity = createTime, stopAffected = false) >= 0

    /** A snapshot is tied to the actual user generation, not just its recyclable numeric id. */
    fun loadScope(userId: Int): FakeLocationScopeSnapshot = synchronized(userLock(userId)) {
        require(userId >= 0)
        val identity = backend.userIdentity(userId)
        backend.checkAvailable()
        val apps = backend.scopeApps(userId)
        val saved = preferences.read(userId, identity)
        val available = apps.mapTo(linkedSetOf()) { it.packageName }
        check(backend.userIdentity(userId) == identity) { "Location user changed" }
        FakeLocationScopeSnapshot(
            createTime = identity,
            apps = apps,
            selectedPackages = (saved.scopePackages ?: available).intersect(available),
            enabled = saved.enabled,
            point = saved.point?.let { it.latitude to it.longitude },
            independentPackages = saved.appLocations.keys.intersect(available)
        )
    }

    fun loadAppLocation(userId: Int, packageName: String, createTime: Long): FakeLocationAppSnapshot =
        synchronized(userLock(userId)) {
            require(userId >= 0 && packageName.isNotBlank() && createTime > 0L)
            val identity = backend.userIdentity(userId)
            check(identity == createTime) { "Location user changed" }
            backend.checkAvailable()
            val app = checkNotNull(backend.scopeApps(userId).firstOrNull { it.packageName == packageName }) {
                "Location app is no longer available"
            }
            val saved = preferences.read(userId, identity)
            val location = saved.appLocations[packageName] ?: SavedAppFakeLocation(saved.enabled, saved.point)
            check(backend.userIdentity(userId) == identity) { "Location user changed" }
            FakeLocationAppSnapshot(
                createTime = identity,
                app = app,
                selected = saved.scopePackages?.contains(packageName) != false,
                independent = packageName in saved.appLocations,
                enabled = location.enabled,
                point = location.point?.let { it.latitude to it.longitude }
            )
        }

    /** Saving an app never silently changes its scope selection, matching device-profile editing. */
    fun applyAppLocation(userId: Int, packageName: String, createTime: Long,
                         latitude: Double, longitude: Double): Boolean {
        val point = FakeLocationPoint(latitude, longitude)
        if (userId < 0 || packageName.isBlank() || createTime <= 0L || !point.isValid) return false
        return updateUser(userId, point = null, enabled = null, expectedIdentity = createTime,
            appUpdate = AppLocationUpdate(packageName, point, enabled = true)) >= 0
    }

    /** An edited app point becomes independent while retaining its current enable/scope state. */
    fun saveAppPoint(userId: Int, packageName: String, createTime: Long,
                     latitude: Double, longitude: Double): Boolean {
        val point = FakeLocationPoint(latitude, longitude)
        return userId >= 0 && packageName.isNotBlank() && createTime > 0L && point.isValid &&
            updateUser(userId, point = null, enabled = null, expectedIdentity = createTime,
                appUpdate = AppLocationUpdate(packageName, point)) >= 0
    }

    /** Closing an app uses the scope gate and preserves its saved override and inherited state. */
    fun clearAppLocation(userId: Int, packageName: String, createTime: Long): Boolean =
        userId >= 0 && packageName.isNotBlank() && createTime > 0L &&
            updateUser(userId, point = null, enabled = null, expectedIdentity = createTime,
                excludedPackage = packageName) >= 0

    fun resetAppLocation(userId: Int, packageName: String, createTime: Long): Boolean =
        userId >= 0 && packageName.isNotBlank() && createTime > 0L &&
            updateUser(userId, point = null, enabled = null, expectedIdentity = createTime,
                appUpdate = AppLocationUpdate(packageName, inherit = true)) >= 0

    /** Scope changes also reconcile running simulation; a removed target receives CLOSE_MODE. */
    fun saveScope(userId: Int, createTime: Long, selectedPackages: Set<String>): Boolean =
        userId >= 0 && createTime > 0L && updateUser(userId, point = null, enabled = null,
            scopePackages = selectedPackages, expectedIdentity = createTime) >= 0

    /** Actual enabled package count, or -1 on an unavailable/incomplete service response. */
    fun enabledAppCount(userId: Int): Int = synchronized(userLock(userId)) {
        try {
            val identity = backend.userIdentity(userId)
            backend.checkAvailable()
            val count = backend.snapshot(userId, identity).locations.values.count { it.pattern != BLocationManager.CLOSE_MODE }
            check(backend.userIdentity(userId) == identity) { "Location user changed" }
            count
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            NexLog.w("Location", error) { "action=enabledAppCount userId=$userId serviceUnavailable=true" }
            -1
        }
    }

    private fun updateUser(
        userId: Int,
        point: FakeLocationPoint?,
        enabled: Boolean?,
        scopePackages: Set<String>? = null,
        excludedPackage: String? = null,
        expectedIdentity: Long? = null,
        appUpdate: AppLocationUpdate? = null,
        stopAffected: Boolean = true,
        prepared: RuntimeState? = null
    ): Int = synchronized(userLock(userId)) {
        val operation = NexLog.operation("Location", "applyProfile", "userId=$userId enabled=$enabled")
        var identity: Long? = null
        var previousPreference: SavedFakeLocation? = null
        var preferenceAttempted = false
        val snapshots = linkedMapOf<String, PackageFakeLocation>()
        val touched = mutableListOf<String>()
        val changedTargets = linkedSetOf<String>()
        val applied = try {
            identity = backend.userIdentity(userId)
            check(expectedIdentity == null || expectedIdentity == identity) { "Location user changed" }
            backend.checkAvailable()
            check(prepared == null || prepared.createTime == identity) { "Location user changed" }
            val runtime = prepared?.snapshot ?: backend.snapshot(userId, identity)
            val packages = runtime.locations.keys.toList()
            val eligible = runtime.eligiblePackages.intersect(runtime.locations.keys)
            operation.step("enumerated", "generation=$identity packageCount=${packages.size}")
            val previous = prepared?.saved ?: preferences.read(userId, identity)
            previousPreference = previous
            // Preserve existing selections on upgrades. Once first applied/saved, the explicit
            // whitelist does not silently opt newly cloned apps into location simulation.
            check(excludedPackage == null || excludedPackage in eligible) { "Location app is no longer available" }
            val selected = (scopePackages ?: previous.scopePackages ?: eligible).intersect(eligible).let {
                if (excludedPackage == null) it else it - excludedPackage
            }
            // Scope only gates application. Deselecting the final app must not erase the saved
            // group switch, so reselecting an app restores its inherited setting as well.
            val nextEnabled = enabled ?: previous.enabled
            val nextPoint = point ?: previous.point
            check(!nextEnabled || nextPoint?.isValid == true) { "No valid simulation location" }
            val appLocations = previous.appLocations.toMutableMap()
            if (appUpdate != null) {
                check(appUpdate.packageName in eligible) { "Location app is no longer available" }
                if (appUpdate.inherit) {
                    appLocations.remove(appUpdate.packageName)
                } else {
                    val previousApp = appLocations[appUpdate.packageName] ?:
                        SavedAppFakeLocation(previous.enabled, previous.point)
                    appLocations[appUpdate.packageName] = previousApp.copy(
                        enabled = appUpdate.enabled ?: previousApp.enabled,
                        point = appUpdate.point ?: previousApp.point
                    ).also { check(it.isValid) { "No valid app simulation location" } }
                }
            }
            if (scopePackages != null) {
                // Re-selecting a stored independent point also re-enables overrides saved by
                // the former per-app disable action. Unrelated scope edits keep them untouched.
                val previouslySelected = previous.scopePackages ?: eligible
                (selected - previouslySelected).forEach { pkg ->
                    appLocations[pkg]?.takeIf { !it.enabled && it.point?.isValid == true }?.let {
                        appLocations[pkg] = it.copy(enabled = true)
                    }
                }
            }
            val nextPreference = previous.copy(enabled = nextEnabled, point = nextPoint,
                scopePackages = selected, appLocations = appLocations.toMap())
            fun effective(preference: SavedFakeLocation, pkg: String): PackageFakeLocation {
                val location = preference.appLocations[pkg] ?: SavedAppFakeLocation(preference.enabled, preference.point)
                return if (pkg in (preference.scopePackages ?: eligible) && location.enabled) {
                    PackageFakeLocation(BLocationManager.OWN_MODE, location.point)
                } else PackageFakeLocation(BLocationManager.CLOSE_MODE, null)
            }
            // Complete the snapshot before touching any package: a failed enumeration/read is not
            // an empty, successfully disabled profile.
            val targetedPackage = appUpdate?.packageName ?: excludedPackage
            val affectedPackages = targetedPackage?.let { listOf(it) } ?: packages
            changedTargets += affectedPackages.filter { it in eligible && effective(previous, it) != effective(nextPreference, it) }
            val desiredStates = linkedMapOf<String, PackageFakeLocation>()
            for (pkg in affectedPackages) {
                val previous = runtime.locations.getValue(pkg)
                snapshots[pkg] = previous
                val location = appLocations[pkg] ?: SavedAppFakeLocation(nextEnabled, nextPoint)
                val desired = if (pkg in selected && location.enabled) {
                    check(location.point?.isValid == true) { "No valid simulation location" }
                    PackageFakeLocation(BLocationManager.OWN_MODE, location.point)
                } else previous.copy(pattern = BLocationManager.CLOSE_MODE)
                // Include every changed target before dispatch: Binder can die after committing.
                if (desired != previous) {
                    touched += pkg
                    desiredStates[pkg] = desired
                }
            }
            check(backend.writeBatch(userId, identity, desiredStates) == desiredStates) { "Location batch was not confirmed" }
            operation.step("batchConfirmed", "generation=$identity packageCount=${desiredStates.size} changedCount=${touched.size}")
            check(backend.userIdentity(userId) == identity) { "Location user changed" }
            if (targetedPackage != null && targetedPackage !in desiredStates) {
                // A same-coordinate edit can still save a new independent override. It must
                // revalidate that one target without rewriting its other raw location fields.
                check(backend.isEligiblePackage(userId, identity, targetedPackage)) { "Location app is no longer available" }
            }
            if (nextPreference != previous) {
                preferenceAttempted = true
                check(preferences.save(userId, identity, nextPreference)) { "Could not save location preferences" }
            }
            check(backend.userIdentity(userId) == identity) { "Location user changed" }
            val applied = selected.count { pkg -> appLocations[pkg]?.enabled ?: nextEnabled }
            operation.step("saved", "generation=$identity confirmedPackages=${affectedPackages.size} appliedPackages=$applied")
            applied
        } catch (failure: Exception) {
            var cancellation = failure as? CancellationException
            var rollbackFailures = 0
            operation.step("rollbackStart", "generation=$identity touchedPackages=${touched.size} reason=${failure.javaClass.simpleName}")
            if (identity != null) {
                if (touched.isNotEmpty()) {
                    val restore = touched.asReversed().associateWith { snapshots.getValue(it) }
                    try {
                        // The service rejects this restore if the numeric id has been recycled.
                        check(backend.writeBatch(userId, identity, restore) == restore) { "Location rollback was not confirmed" }
                    } catch (cancelled: CancellationException) {
                        if (cancellation == null) cancellation = cancelled
                    } catch (error: Exception) {
                        rollbackFailures++
                        NexLog.w("Location", error) { "op=${operation.id} action=rollbackBatch userId=$userId generation=$identity" }
                        // A removed package can reject the batch. Preserve the old best-effort
                        // recovery of the other targets without bypassing their generation check.
                        for ((pkg, state) in restore) {
                            try { backend.write(userId, identity, pkg, state) }
                            catch (cancelled: CancellationException) { if (cancellation == null) cancellation = cancelled }
                            catch (restoreError: Exception) {
                                rollbackFailures++
                                NexLog.w("Location", restoreError) { "op=${operation.id} action=rollback package=$pkg userId=$userId generation=$identity" }
                            }
                        }
                    }
                }
                if (preferenceAttempted && previousPreference != null) {
                    try {
                        if (!preferences.save(userId, identity, previousPreference)) {
                            rollbackFailures++
                            NexLog.w("Location") { "op=${operation.id} action=rollbackPreferences userId=$userId generation=$identity success=false" }
                        }
                    } catch (cancelled: CancellationException) {
                        if (cancellation == null) cancellation = cancelled
                    } catch (error: Exception) {
                        rollbackFailures++
                        NexLog.w("Location", error) { "op=${operation.id} action=rollbackPreferences userId=$userId generation=$identity" }
                        // Generation-scoped keys cannot overwrite the new profile's preferences.
                    }
                }
            }
            operation.fail(failure, "generation=$identity touchedPackages=${touched.size} rollbackFailures=$rollbackFailures")
            cancellation?.let { throw it }
            return@synchronized -1
        }
        // Configuration is committed. A stop failure must not roll it back or report it unsaved.
        if (stopAffected && changedTargets.isNotEmpty()) {
            try { backend.stopPackages(userId, checkNotNull(identity), changedTargets) }
            catch (failure: Exception) {
                operation.fail(failure, "generation=$identity configurationSaved=true stopFailed=true")
                throw failure
            }
        }
        operation.finish(detail = "generation=$identity appliedPackages=$applied changedPackages=${changedTargets.size} stopAffected=$stopAffected")
        applied
    }

    private data class AppLocationUpdate(
        val packageName: String,
        val point: FakeLocationPoint? = null,
        val enabled: Boolean? = null,
        val inherit: Boolean = false
    )

    private data class RuntimeState(
        val createTime: Long,
        val snapshot: FakeLocationRuntimeSnapshot,
        val saved: SavedFakeLocation
    )

    companion object {
        private val userLocks = ConcurrentHashMap<Int, Any>()
        private fun userLock(userId: Int) = userLocks.getOrPut(userId) { Any() }
    }
}

data class FakeLocationScopeSnapshot(
    val createTime: Long,
    val apps: List<ScopeApp>,
    val selectedPackages: Set<String>,
    val enabled: Boolean,
    val point: Pair<Double, Double>?,
    val independentPackages: Set<String> = emptySet()
)

data class FakeLocationAppSnapshot(
    val createTime: Long,
    val app: ScopeApp,
    val selected: Boolean,
    val independent: Boolean,
    val enabled: Boolean,
    val point: Pair<Double, Double>?
)

data class FakeLocationRockerState(
    val selected: Boolean,
    val independent: Boolean,
    val enabled: Boolean,
    val point: Pair<Double, Double>?
)

internal data class FakeLocationPoint(val latitude: Double, val longitude: Double) {
    val isValid: Boolean
        get() = latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
}

internal data class PackageFakeLocation(val pattern: Int, val point: FakeLocationPoint?)

internal data class FakeLocationRuntimeSnapshot(
    val locations: Map<String, PackageFakeLocation>,
    val eligiblePackages: Set<String>
)

internal interface FakeLocationBackend {
    fun userIdentity(userId: Int): Long
    fun checkAvailable()
    fun installedPackages(userId: Int): List<String>
    fun eligiblePackages(userId: Int): Set<String> = installedPackages(userId).toSet()
    fun scopeApps(userId: Int): List<ScopeApp> = eligiblePackages(userId).map { ScopeApp(it, it) }
    fun read(userId: Int, packageName: String): PackageFakeLocation
    fun write(userId: Int, createTime: Long, packageName: String, state: PackageFakeLocation)
    fun isEligiblePackage(userId: Int, createTime: Long, packageName: String): Boolean {
        check(userIdentity(userId) == createTime) { "Location user changed" }
        return packageName in eligiblePackages(userId)
    }
    fun snapshot(userId: Int, createTime: Long): FakeLocationRuntimeSnapshot {
        check(userIdentity(userId) == createTime) { "Location user changed" }
        val packages = installedPackages(userId).distinct()
        return FakeLocationRuntimeSnapshot(packages.associateWith { read(userId, it) },
            eligiblePackages(userId).intersect(packages.toSet()))
    }
    fun writeBatch(userId: Int, createTime: Long, states: Map<String, PackageFakeLocation>): Map<String, PackageFakeLocation> {
        check(userIdentity(userId) == createTime) { "Location user changed" }
        states.forEach { (pkg, state) -> if (read(userId, pkg) != state) write(userId, createTime, pkg, state) }
        return states.keys.associateWith { read(userId, it) }
    }
    fun stopPackages(userId: Int, createTime: Long, packages: Set<String>)
}

/** Use Binder interfaces directly: the legacy BLocationManager convenience methods swallow errors. */
private class BinderFakeLocationBackend : FakeLocationBackend {
    private val scopeRepository = ScopeAppRepository()
    private fun locationService() = checkNotNull(BLocationManager.get().service) { "Location service unavailable" }

    override fun userIdentity(userId: Int): Long =
        checkNotNull(UserIdentityResolver.userIdentity(userId)) { "Location user unavailable" }

    override fun checkAvailable() {
        check(locationService().asBinder().isBinderAlive) { "Location service unavailable" }
    }

    override fun snapshot(userId: Int, createTime: Long): FakeLocationRuntimeSnapshot {
        val entries = checkNotNull(BLocationManager.get().getLocationStates(userId, createTime, null)) {
            "Location snapshot unavailable"
        }
        return FakeLocationRuntimeSnapshot(locationStates(entries),
            entries.filter { it.eligible }.mapTo(linkedSetOf()) { it.packageName })
    }

    override fun writeBatch(userId: Int, createTime: Long, states: Map<String, PackageFakeLocation>): Map<String, PackageFakeLocation> {
        val updates = states.map { (pkg, state) ->
            BLocationState(pkg, state.pattern, state.point?.let { BLocation(it.latitude, it.longitude) }, false)
        }
        return locationStates(checkNotNull(BLocationManager.get().setLocationStates(userId, createTime, updates)) {
            "Location batch receipt unavailable"
        })
    }

    override fun isEligiblePackage(userId: Int, createTime: Long, packageName: String): Boolean {
        val entries = checkNotNull(BLocationManager.get().getLocationStates(userId, createTime, listOf(packageName))) {
            "Location target unavailable"
        }
        return entries.size == 1 && entries[0].packageName == packageName && entries[0].eligible
    }

    private fun locationStates(entries: List<BLocationState>): Map<String, PackageFakeLocation> {
        val result = linkedMapOf<String, PackageFakeLocation>()
        for (entry in entries) {
            check(!entry.packageName.isNullOrBlank() && entry.packageName !in result &&
                entry.pattern in BLocationManager.CLOSE_MODE..BLocationManager.OWN_MODE) { "Incomplete location snapshot" }
            val point = entry.location?.let { FakeLocationPoint(it.latitude, it.longitude) }
            check(point?.isValid != false) { "Invalid location snapshot" }
            result[entry.packageName] = PackageFakeLocation(entry.pattern, point)
        }
        return result
    }

    override fun installedPackages(userId: Int): List<String> {
        val service = checkNotNull(BPackageManager.get().service) { "Package service unavailable" }
        val apps = checkNotNull(service.getInstalledApplications(0, userId)) { "Package list unavailable" }
        return apps.map { app ->
            checkNotNull(app?.packageName?.takeIf { it.isNotBlank() }) { "Incomplete package list" }
        }
    }

    override fun eligiblePackages(userId: Int): Set<String> = scopeRepository.packageNames(userId)

    override fun scopeApps(userId: Int): List<ScopeApp> = scopeRepository.load(userId)

    override fun read(userId: Int, packageName: String): PackageFakeLocation {
        val service = locationService()
        val pattern = service.getPattern(userId, packageName)
        check(pattern in BLocationManager.CLOSE_MODE..BLocationManager.OWN_MODE) { "Unknown location mode" }
        val own = service.getOwnLocation(userId, packageName)
        return PackageFakeLocation(pattern, own?.let { FakeLocationPoint(it.latitude, it.longitude) })
    }

    override fun write(userId: Int, createTime: Long, packageName: String, state: PackageFakeLocation) {
        val location = state.point?.let { BLocation(it.latitude, it.longitude) }
        locationService().setLocationAndPattern(userId, createTime, packageName, location, state.pattern)
    }

    override fun stopPackages(userId: Int, createTime: Long, packages: Set<String>) {
        ConfigurationAppStopper.stop(userId, createTime, packages)
    }
}
