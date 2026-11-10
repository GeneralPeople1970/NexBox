package com.nexbox.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.pm.PackageInfoCompat
import com.nexbox.app.app.App
import com.nexbox.app.bean.XpModuleBean
import com.nexbox.app.util.NexLog
import com.nexbox.core.NexBoxCore
import com.nexbox.core.core.system.xposed.XposedModulePolicy
import com.nexbox.core.entity.pm.ModuleLoadState
import com.nexbox.core.entity.pm.XposedRuntimeStatus
import com.nexbox.core.entity.pm.InstallOption
import com.nexbox.core.xposed.ModuleMetadata
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** The screen's blocking data boundary; UI tests can drive real navigation without touching profiles. */
interface XpModuleDataSource {
    fun users(): List<Int>
    fun load(userId: Int): XpModuleRepository.Snapshot
    fun load(owner: CloneTarget): XpModuleRepository.Snapshot
    fun loadProfiles(includeTargets: Boolean): Map<Int, XpModuleRepository.Snapshot> =
        users().distinct().sorted().associateWith { load(it) }
    fun hostCandidates(): List<XpModuleBean>
    fun save(userId: Int, modulePackage: String, enabled: Boolean, targets: Set<String>)
    fun save(owner: CloneTarget, modulePackage: String, enabled: Boolean, targets: Set<String>)
    fun importHost(packageName: String, userId: Int): String
    fun importApk(uri: Uri, userId: Int): String
    fun installExisting(packageName: String, userId: Int)
    fun installExisting(packageName: String, owner: CloneTarget)
    fun configurationIntent(packageName: String, owner: CloneTarget): Intent?
    fun openConfiguration(packageName: String, owner: CloneTarget)
    fun restartTargets(userId: Int, targets: Set<String>)
}

class ModulePostInstallException(val owner: CloneTarget, val packageName: String, cause: Exception) :
    IllegalStateException("Module package was installed, but post-install validation failed", cause)

/** Activation is owned by Bcore. Call blocking methods on Dispatchers.IO. */
class XpModuleRepository(private val context: Context = App.getContext()) : XpModuleDataSource {
    data class TargetApp(
        val packageName: String,
        val label: String,
        val versionName: String? = null,
        val iconKey: AppIconKey? = null,
        val isModule: Boolean = false
    )
    data class Snapshot(
        val modules: List<XpModuleBean>,
        val targets: List<TargetApp>,
        val reports: List<ModuleLoadState>,
        val status: XposedRuntimeStatus,
        val legacySelections: Set<String>,
        val owner: CloneTarget
    )

    private val manager get() = NexBoxCore.getBXposedManager()
    private val pm get() = context.packageManager

    override fun users(): List<Int> = UsersRepository.users().map { it.id }

    override fun loadProfiles(includeTargets: Boolean): Map<Int, Snapshot> {
        val users = UsersRepository.users()
        val revision = UsersRepository.changes.value
        val status = manager.runtimeStatus
        check(status.state != "service_unavailable" && status.state != "configuration_error") {
            status.message ?: "The module list is unavailable."
        }
        val legacy = checkNotNull(checkNotNull(manager.service).pendingLegacySelections) { "Module legacy selections unavailable" }.toSet()
        val result = users.associate { user ->
            user.id to loadSnapshot(CloneTarget(user.id, user.createTime), includeTargets, status, legacy, revision)
        }
        check(users.associate { it.id to it.createTime } == UsersRepository.users().associate { it.id to it.createTime } &&
            revision == UsersRepository.changes.value) { "Module users changed while loading" }
        return result
    }

    override fun load(userId: Int): Snapshot = load(UserIdentityResolver.captureTarget(userId))

    override fun load(owner: CloneTarget): Snapshot {
        val userId = owner.userId
        UserIdentityResolver.validateTarget(owner)
        val snapshot = loadSnapshot(owner, true, manager.runtimeStatus,
            checkNotNull(checkNotNull(manager.service).pendingLegacySelections) { "Module legacy selections unavailable" }.toSet(), UsersRepository.changes.value)
        check(UserIdentityResolver.userIdentity(userId) == owner.createTime) { "Module user changed while loading" }
        return snapshot
    }

    private fun loadSnapshot(owner: CloneTarget, includeTargets: Boolean, status: XposedRuntimeStatus,
                             legacy: Set<String>, revision: Long): Snapshot {
        val userId = owner.userId
        return NexLog.trace(TAG, "loadModules", "userId=$userId generation=${owner.createTime}") { operation ->
            require(userId >= 0 && owner.createTime > 0L)
            check(status.state != "service_unavailable" && status.state != "configuration_error") {
                status.message ?: "The module list is unavailable."
            }
            val service = checkNotNull(manager.service) { "Module service unavailable" }
            val modules = checkNotNull(service.getInstalledModules(userId)) { "Module list unavailable" }.map { module ->
                val info = module.applicationInfo
                XpModuleBean(
                    packageName = module.packageName,
                    name = label(info, module.packageName),
                    versionName = module.versionName,
                    description = module.description,
                    minXposedVersion = module.minXposedVersion,
                    scopeCount = module.scope.size,
                    isEnabled = module.enabled,
                    userId = userId,
                    installed = true,
                    targets = module.scope.toSet(),
                    supported = module.supported,
                    error = module.error,
                    pendingLegacySelection = module.pendingLegacySelection,
                    modern = module.modern,
                    recommendedScope = module.recommendedScope.toSet(),
                    staticScope = module.staticScope,
                    iconKey = info?.let { AppIconLoader.key(it, owner, module.versionCode) }
                )
            }.sortedBy { it.name.lowercase() }
            val targets = if (includeTargets) ScopeAppRepository(context).load(userId, includeModules = true)
                .map { app -> TargetApp(app.packageName, app.label, versionName = app.versionName,
                    iconKey = app.iconKey, isModule = app.isModule) }
                else emptyList()
            check(UsersRepository.changes.value == revision) { "Module applications changed while loading" }
            return@trace Snapshot(modules, targets, if (includeTargets) checkNotNull(service.getModuleStates(userId)) { "Module reports unavailable" } else emptyList(),
                status, legacy, owner)
        }
    }

    @Suppress("DEPRECATION")
    override fun hostCandidates(): List<XpModuleBean> {
        return NexLog.trace(TAG, "scanHostModules", "") { operation ->
            com.nexbox.app.util.AppListPermission.requireHostAccess(context)
            return@trace pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .mapNotNull { info ->
                if (info.packageName == context.packageName) return@mapNotNull null
                val legacy = XposedModulePolicy.hasLegacyDeclaration(info)
                var modern = false
                val parsed = runCatching {
                    ZipFile(info.sourceDir).use { archive ->
                        modern = archive.getEntry(ModuleMetadata.MODERN_ENTRY) != null
                        if (legacy || modern) ModuleMetadata.readArchive(archive) else null
                    }
                }
                if (!legacy && !modern) return@mapNotNull null
                val packageInfo = runCatching { pm.getPackageInfo(info.packageName, 0) }.getOrNull()
                val metadata = parsed.getOrNull()
                val minimum = runCatching { if (metadata?.modern == true) metadata.minApi
                    else ModuleMetadata.legacyMinimum(info.metaData?.get("xposedminversion")) }
                val minVersion = minimum.getOrNull()
                val failure = parsed.exceptionOrNull()?.message ?: minimum.exceptionOrNull()?.message
                (parsed.exceptionOrNull() ?: minimum.exceptionOrNull())?.let {
                    NexLog.w(TAG, "action=inspectHostModule package=${info.packageName} supported=false", it)
                }
                XpModuleBean(
                    packageName = info.packageName,
                    name = label(info, info.packageName),
                    versionName = packageInfo?.versionName,
                    description = null,
                    minXposedVersion = minVersion,
                    scopeCount = 0,
                    isEnabled = false,
                    supported = failure == null,
                    error = failure,
                    modern = modern,
                    recommendedScope = metadata?.scope.orEmpty().toSet(),
                    staticScope = metadata?.staticScope ?: false,
                    iconKey = packageInfo?.let { AppIconLoader.key(info, null,
                        PackageInfoCompat.getLongVersionCode(it), it.lastUpdateTime) }
                )
            }.sortedBy { it.name.lowercase() }.also {
                com.nexbox.app.util.AppListPermission.requireHostAccess(context)
            }
        }
    }

    override fun save(userId: Int, modulePackage: String, enabled: Boolean, targets: Set<String>) =
        save(UserIdentityResolver.captureTarget(userId), modulePackage, enabled, targets)

    override fun save(owner: CloneTarget, modulePackage: String, enabled: Boolean, targets: Set<String>) {
        val userId = owner.userId
        UserIdentityResolver.validateTarget(owner)
        NexLog.trace(TAG, "saveModuleScope", "userId=$userId generation=${owner.createTime} module=$modulePackage enabled=$enabled targetCount=${targets.size}") { operation ->
            require(!enabled || targets.isNotEmpty()) { "Choose at least one target application." }
            val scope = targets.sorted()
            NexLog.i(TAG) { "setModuleConfig u$userId $modulePackage enabled=$enabled scope=$scope" }
            check(manager.setModuleConfig(userId, modulePackage, enabled, scope, owner.createTime)) {
                "The module configuration could not be saved. Refresh and try again."
            }
        }
    }

    /** Copy all APKs, rather than retaining references to host installation paths. */
    override fun importHost(packageName: String, userId: Int): String = importHost(packageName, UserIdentityResolver.captureTarget(userId))

    fun importHost(packageName: String, owner: CloneTarget): String {
        UserIdentityResolver.validateTarget(owner)
        return NexLog.trace(TAG, "importHostModule", "userId=${owner.userId} generation=${owner.createTime} package=$packageName") { operation ->
            val info = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            require(declaresModule(info)) { "Not an Xposed module." }
            val base = File(info.sourceDir)
            validateApk(base)
            val splits = info.splitSourceDirs.orEmpty().map(::File)
            require(splits.all { it.isFile }) { "The module has missing split APKs." }
            val snapshot = File.createTempFile("xp-host-", if (splits.isEmpty()) ".apk" else ".apks", context.cacheDir)
            try {
                if (splits.isEmpty()) {
                    base.inputStream().use { input -> snapshot.outputStream().use { input.copyTo(it) } }
                } else {
                    ZipOutputStream(snapshot.outputStream().buffered()).use { zip ->
                        (listOf(base) + splits).forEachIndexed { index, source ->
                            zip.putNextEntry(ZipEntry(if (index == 0) "base.apk" else source.name))
                            source.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
                return@trace install(snapshot, owner)
            } finally {
                snapshot.delete()
            }
        }
    }

    override fun importApk(uri: Uri, userId: Int): String = importApk(uri, UserIdentityResolver.captureTarget(userId))

    fun importApk(uri: Uri, owner: CloneTarget): String {
        UserIdentityResolver.validateTarget(owner)
        return NexLog.trace(TAG, "importModuleDocument", "userId=${owner.userId} generation=${owner.createTime} source=document") { operation ->
            val staged = File.createTempFile("xp-import-", ".apk", context.cacheDir)
            try {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open this document.")
                stream.use { input -> staged.outputStream().use { input.copyTo(it) } }
                val isApk = ZipFile(staged).use { it.getEntry("AndroidManifest.xml") != null }
                if (isApk) {
                    validateApk(staged)
                } else {
                    // Extract only to an application-created temporary filename, never an archive path.
                    val base = File.createTempFile("xp-validate-", ".apk", context.cacheDir)
                    try {
                        ZipFile(staged).use { zip ->
                            val entries = zip.entries()
                            var baseEntry: ZipEntry? = null
                            var fallback: ZipEntry? = null
                            while (entries.hasMoreElements()) {
                                val entry = entries.nextElement()
                                if (entry.isDirectory) continue
                                when (entry.name.substringAfterLast('/')) {
                                    "base.apk" -> {
                                        baseEntry = entry
                                        break
                                    }
                                    "base-master.apk" -> if (fallback == null) fallback = entry
                                }
                            }
                            val entry = baseEntry ?: fallback
                                ?: throw IOException("The APKS archive does not contain a base APK.")
                            require(entry.size in 1..MAX_BASE_APK_BYTES) { "Invalid base APK size." }
                            zip.getInputStream(entry).use { input ->
                                base.outputStream().use { output ->
                                    val buffer = ByteArray(32 * 1024)
                                    var count = 0L
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read == -1) break
                                        count += read
                                        require(count <= MAX_BASE_APK_BYTES) { "Base APK is too large." }
                                        output.write(buffer, 0, read)
                                    }
                                }
                            }
                        }
                        validateApk(base)
                    } finally {
                        base.delete()
                    }
                }
                // The core detects APK bundles by content, including when staged with an .apk suffix.
                return@trace install(staged, owner)
            } finally {
                staged.delete()
            }
        }
    }

    override fun installExisting(packageName: String, userId: Int) = installExisting(packageName, UserIdentityResolver.captureTarget(userId))

    override fun installExisting(packageName: String, owner: CloneTarget) {
        UserIdentityResolver.validateTarget(owner)
        NexLog.trace(TAG, "installExistingModule", "userId=${owner.userId} generation=${owner.createTime} package=$packageName") { operation ->
            val moduleService = checkNotNull(manager.service) { "Module service unavailable" }
            check(UsersRepository.users().any { user ->
                checkNotNull(moduleService.getInstalledModules(user.id)) { "Module list unavailable" }.any { it.packageName == packageName }
            }) { "The package is not an imported Xposed module" }
            val result = checkNotNull(NexBoxCore.getBPackageManager().service)
                .installExistingPackageAsUserIfMatches(packageName, owner.userId, owner.createTime)
            check(result.success && result.userId == owner.userId && result.userCreateTime == owner.createTime && result.packageName == packageName) {
                result.msg ?: "Could not install this module in the profile."
            }
            UserIdentityResolver.validateTarget(owner)
            UsersRepository.notifyChanged()
        }
    }

    private fun install(file: File, owner: CloneTarget): String {
        val userId = owner.userId
        UserIdentityResolver.validateTarget(owner)
        return NexLog.trace(TAG, "installModule", "userId=$userId generation=${owner.createTime}") { operation ->
            val result = checkNotNull(NexBoxCore.getBPackageManager().service)
                .installPackageUpdateAsUserIfMatches(file.absolutePath, InstallOption.installByStorage(), userId, owner.createTime)
            NexLog.i(TAG, "install u$userId ${result.packageName} success=${result.success} msg=${result.msg}")
            check(result.success && result.userId == userId && result.userCreateTime == owner.createTime) { result.msg ?: "Module installation failed." }
            UserIdentityResolver.validateTarget(owner)
            UsersRepository.notifyChanged()
            val installed = try {
                val module = checkNotNull(checkNotNull(manager.service).getInstalledModules(userId)).firstOrNull { it.packageName == result.packageName }
                    ?: throw IOException("The installed APK is not an Xposed module.")
                check(module.supported) { module.error ?: "This module is unsupported." }
                module
            } catch (failure: Exception) {
                throw ModulePostInstallException(owner, result.packageName, failure)
            }
            NexLog.i(TAG, "inspected ${installed.packageName} minApi=${installed.minXposedVersion} " +
                    "supported=${installed.supported} error=${installed.error}")
            return@trace installed.packageName
        }
    }

    /**
     * A cheap pre-flight so the user gets an immediate message instead of a silent no-op.
     * It is deliberately looser than the server-side inspector and the guest loader, which both
     * parse the same file through `ModuleEntries`; only their verdict is authoritative. Do not
     * "fix" a rejection here by relaxing the other two.
     */
    @Suppress("DEPRECATION")
    private fun validateApk(file: File) {
        val packageInfo = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_META_DATA)
            ?: throw IOException("The APK cannot be parsed.")
        require(packageInfo.packageName != context.packageName) { "NexBox cannot import itself." }
        val info = packageInfo.applicationInfo ?: throw IOException("The APK has no application.")
        val metadata = ModuleMetadata.read(file.absolutePath)
        require(metadata.modern || ModuleMetadata.declaresLegacy(info.metaData?.get("xposedmodule"))) {
            "The APK does not declare an Xposed module."
        }
        if (!metadata.modern) ModuleMetadata.legacyMinimum(info.metaData?.get("xposedminversion"))
    }

    /**
     * Resolve the module's configuration screen via the virtual package manager (no host launcher
     * fallback).
     *
     * Many modules offer a "hide launcher icon" switch. Once hidden, the launcher entry is gone and
     * the traditional [Intent.CATEGORY_LAUNCHER] lookup finds nothing. The long-standing Xposed
     * convention for exactly this case is a dedicated settings activity that declares
     * `de.robv.android.xposed.category.MODULE_SETTINGS`; that entry stays resolvable regardless of
     * the launcher icon, so try it first, then fall back to the normal info/launcher categories.
     */
    override fun configurationIntent(packageName: String, owner: CloneTarget): Intent? = synchronized(UsersRepository.mutationLock) {
        require(owner.createTime > 0L)
        UserIdentityResolver.validateTarget(owner)
        val userId = owner.userId
        NexLog.trace(TAG, "resolveModuleSettings", "userId=$userId generation=${owner.createTime} package=$packageName") { operation ->
            val packages = checkNotNull(NexBoxCore.getBPackageManager().service) { "Package service unavailable" }
            // Never resolve a similarly named host app or a stale uninstalled module copy.
            if (!packages.isInstalled(packageName, userId)) return@trace null
            val application = packages.getApplicationInfo(packageName, PackageManager.GET_META_DATA, userId)
                ?: return@trace null
            if (!application.enabled || !XposedModulePolicy.isCandidate(application)) return@trace null
            val categories = listOf(XPOSED_MODULE_SETTINGS_CATEGORY, Intent.CATEGORY_INFO, Intent.CATEGORY_LAUNCHER)
            for (category in categories) {
                val query = Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(packageName)
                val resolved = checkNotNull(packages.queryIntentActivities(query, 0, null, userId)) {
                    "Module activity list unavailable"
                }.asSequence().mapNotNull { it?.activityInfo }.firstOrNull { activity ->
                    activity.packageName == packageName && !activity.name.isNullOrBlank() &&
                        activity.enabled && activity.exported && activity.applicationInfo?.enabled != false &&
                        (activity.permission.isNullOrBlank() ||
                            context.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED)
                }
                    ?: continue
                NexLog.i(TAG, "configurationIntent $packageName resolved via '$category' -> ${resolved.name}")
                return@trace Intent(query).setClassName(resolved.packageName, resolved.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            NexLog.w(TAG, "configurationIntent $packageName: no config activity found (module hidden and no MODULE_SETTINGS entry?)")
            operation.finish(false, "reason=no_configuration_activity")
            return@trace null
        }.also { UserIdentityResolver.validateTarget(owner) }
    }

    /** Accepted means the captured user's launch was dispatched, not that its activity completed startup. */
    override fun openConfiguration(packageName: String, owner: CloneTarget) = synchronized(UsersRepository.mutationLock) {
        NexLog.trace(TAG, "openModuleSettings", "userId=${owner.userId} generation=${owner.createTime} package=$packageName") { operation ->
            val intent = checkNotNull(configurationIntent(packageName, owner)) {
                "The module has no management activity"
            }
            NexBoxCore.get().startActivity(intent, owner.userId, owner.createTime)
            operation.finish(detail = "requestAccepted=true startupComplete=unknown")
        }
    }

    override fun restartTargets(userId: Int, targets: Set<String>) = stopTargets(UserIdentityResolver.captureTarget(userId), targets)

    fun stopTargets(owner: CloneTarget, targets: Set<String>) {
        UserIdentityResolver.validateTarget(owner)
        NexLog.trace(TAG, "stopModuleTargets", "userId=${owner.userId} generation=${owner.createTime} targetCount=${targets.size}") { operation ->
            require(targets.isNotEmpty()) { "Choose at least one target application" }
            val snapshot = load(owner)
            val eligible = snapshot.targets.filter { app ->
                !app.isModule || snapshot.modules.any { module ->
                    module.staticScope && app.packageName in module.recommendedScope
                }
            }.mapTo(hashSetOf()) { it.packageName }
            require(eligible.containsAll(targets)) { "A stop target is unavailable or is outside declared static module scopes" }
            check(checkNotNull(NexBoxCore.getBPackageManager().service)
                .stopPackagesIfMatches(targets.sorted(), owner.userId, owner.createTime)) { "Module target stops were not confirmed" }
            operation.finish(detail = "stopBatchConfirmed=true launched=false")
        }
    }

    private fun label(info: ApplicationInfo?, fallback: String): String =
        runCatching { info?.loadLabel(pm)?.toString() ?: fallback }.getOrDefault(fallback)

    @Suppress("DEPRECATION")
    private fun declaresModule(info: ApplicationInfo): Boolean =
        XposedModulePolicy.isCandidate(info)

    private companion object {
        const val TAG = "XpModule"
        // Traditional Xposed settings entry point; a module keeps this activity reachable even when
        // its launcher shortcut is hidden. Recognised by Xposed Installer / LSPosed as well.
        const val XPOSED_MODULE_SETTINGS_CATEGORY = "de.robv.android.xposed.category.MODULE_SETTINGS"
        const val MAX_BASE_APK_BYTES = 512L * 1024 * 1024
    }
}
