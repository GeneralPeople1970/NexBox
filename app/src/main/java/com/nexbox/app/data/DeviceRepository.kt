package com.nexbox.app.data

import com.nexbox.core.fake.device.DeviceCatalog
import com.nexbox.core.fake.device.DeviceSpoofManager
import com.nexbox.core.fake.device.DeviceSpoofProfile
import com.nexbox.core.utils.ConfigurationStopException

internal data class DeviceEditorSnapshot(
    val target: CloneTarget,
    val profile: DeviceSpoofProfile,
    val defaults: DeviceSpoofProfile
)

internal data class DeviceScopeSnapshot(
    val target: CloneTarget,
    val apps: List<ScopeApp>,
    val selected: Set<String>,
    val independent: Set<String>
)

internal data class DeviceSaveResult<T>(val saved: T, val stopFailure: ConfigurationStopException?)

/** Host device operations shared by the editor, app actions and Debug CLI. Call on IO. */
internal object DeviceRepository {
    fun loadEditor(userId: Int, packageName: String?, expectedGeneration: Long?): DeviceEditorSnapshot =
        synchronized(UsersRepository.mutationLock) {
            val target = UserIdentityResolver.captureTarget(userId)
            check(expectedGeneration == null || expectedGeneration == target.createTime) {
                "User changed while opening device editor"
            }
            val profile = if (packageName == null) getProfile(userId, target.createTime)
                else getAppProfile(packageName, userId, target.createTime)
            val defaults = getDefaultProfile(userId)
            UserIdentityResolver.validateTarget(target)
            DeviceEditorSnapshot(target, profile, defaults)
        }

    fun saveEditor(
        target: CloneTarget,
        packageName: String?,
        inheritGlobal: Boolean,
        profile: () -> DeviceSpoofProfile
    ): DeviceSaveResult<DeviceSpoofProfile> = synchronized(UsersRepository.mutationLock) {
        UserIdentityResolver.validateTarget(target)
        val accepted = if (packageName != null && inheritGlobal) getProfile(target.userId, target.createTime)
            else profile()
        val failure = captureStopFailure {
            if (packageName != null && inheritGlobal) resetAppProfile(packageName, target.userId, target.createTime)
            else if (packageName == null) saveUserProfile(target.userId, target.createTime, accepted)
            else saveAppProfile(packageName, target.userId, target.createTime, accepted)
        }
        DeviceSaveResult(accepted, failure)
    }

    fun requireEditableTarget(target: CloneTarget, packageName: String?) = synchronized(UsersRepository.mutationLock) {
        UserIdentityResolver.validateTarget(target)
        if (packageName != null) check(DeviceSpoofManager.isEligibleScopeTarget(packageName, target.userId)) {
            "Device profile app is no longer available"
        }
    }

    fun inheritedProfile(target: CloneTarget, packageName: String): DeviceSpoofProfile =
        synchronized(UsersRepository.mutationLock) {
            requireEditableTarget(target, packageName)
            getProfile(target.userId, target.createTime)
        }

    fun loadScope(userId: Int, expectedGeneration: Long?, apps: ScopeAppRepository): DeviceScopeSnapshot =
        synchronized(UsersRepository.mutationLock) {
            val target = UserIdentityResolver.captureTarget(userId)
            check(expectedGeneration == null || expectedGeneration == target.createTime) {
                "User changed while editing device scope"
            }
            val populated = UsersRepository.cloneGroups().any { it.id == userId && it.createTime == target.createTime }
            val available = if (populated) apps.load(userId) else emptyList()
            val packages = available.mapTo(linkedSetOf()) { it.packageName }
            val selected = if (packages.isEmpty()) emptySet() else
                getScopePackages(userId, target.createTime)?.intersect(packages) ?: packages
            val overrides = packages.filterTo(linkedSetOf()) {
                DeviceSpoofManager.hasAppProfile(it, userId, target.createTime)
            }
            UserIdentityResolver.validateTarget(target)
            DeviceScopeSnapshot(target, available, selected, overrides)
        }

    fun saveScopeSelection(
        target: CloneTarget,
        packageName: String,
        requested: Set<String>,
        apps: ScopeAppRepository
    ): DeviceSaveResult<Set<String>> = synchronized(UsersRepository.mutationLock) {
        UserIdentityResolver.validateTarget(target)
        val available = apps.packageNames(target.userId)
        check(packageName in available) { "Scope app is no longer available" }
        val accepted = requested.intersect(available)
        val failure = captureStopFailure { saveScopePackages(target.userId, target.createTime, accepted) }
        DeviceSaveResult(accepted, failure)
    }

    // A stop failure means the configuration is already saved. Other failures still propagate.
    private inline fun captureStopFailure(save: () -> Unit): ConfigurationStopException? = try {
        save()
        null
    } catch (failure: ConfigurationStopException) {
        failure
    }

    fun getProfile(userId: Int, created: Long): DeviceSpoofProfile = DeviceSpoofManager.getProfile(userId, created)
    fun getAppProfile(packageName: String, userId: Int, created: Long): DeviceSpoofProfile =
        DeviceSpoofManager.getAppProfile(packageName, userId, created)
    fun hasAppProfile(packageName: String, userId: Int, created: Long): Boolean =
        DeviceSpoofManager.hasAppProfile(packageName, userId, created)
    fun getDefaultProfile(userId: Int): DeviceSpoofProfile = DeviceSpoofManager.getDefaultProfile(userId)
    fun getScopePackages(userId: Int, created: Long): Set<String>? = DeviceSpoofManager.getScopePackages(userId, created)
    fun saveUserProfile(userId: Int, created: Long, profile: DeviceSpoofProfile) =
        DeviceSpoofManager.saveUserProfile(userId, created, profile)
    fun saveAppProfile(packageName: String, userId: Int, created: Long, profile: DeviceSpoofProfile) =
        DeviceSpoofManager.saveAppProfile(packageName, userId, created, profile)
    fun saveScopePackages(userId: Int, created: Long, packages: Set<String>) =
        DeviceSpoofManager.saveScopePackages(userId, created, packages)
    fun resetToDefaults(userId: Int, created: Long) = DeviceSpoofManager.resetToDefaults(userId, created)
    fun resetAppProfile(packageName: String, userId: Int, created: Long) =
        DeviceSpoofManager.resetAppProfile(packageName, userId, created)
    fun randomizeAppProfile(packageName: String, userId: Int, created: Long): DeviceSpoofProfile =
        DeviceSpoofManager.randomizeAppProfile(packageName, userId, created)
    fun randomizeAll(profile: DeviceSpoofProfile): DeviceSpoofProfile = DeviceSpoofManager.randomizeAll(profile)
    fun randomizeModel(profile: DeviceSpoofProfile): DeviceSpoofProfile = DeviceSpoofManager.randomizeModel(profile)
    fun randomizeIdentifiers(profile: DeviceSpoofProfile): DeviceSpoofProfile = DeviceSpoofManager.randomizeIdentifiers(profile)
    fun selectCatalogEntry(profile: DeviceSpoofProfile, entry: DeviceCatalog.Entry): DeviceSpoofProfile =
        DeviceSpoofManager.selectCatalogEntry(profile, entry)
    fun editProfile(userId: Int, base: DeviceSpoofProfile, manufacturer: String, model: String, androidId: String): DeviceSpoofProfile =
        DeviceSpoofManager.editProfile(userId, base, manufacturer, model, androidId)
    fun initializeGlobalEsimSpoofSetting(): Boolean = DeviceSpoofManager.initializeGlobalEsimSpoofSetting()
    fun setGlobalEsimSpoofEnabled(enabled: Boolean) = DeviceSpoofManager.setGlobalEsimSpoofEnabled(enabled)
}
