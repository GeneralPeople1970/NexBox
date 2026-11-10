package com.nexbox.app.data

import android.content.ComponentCallbacks2
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.nexbox.app.app.App
import com.nexbox.app.bean.AppInfo
import com.nexbox.app.bean.InstalledAppBean
import com.nexbox.app.util.AppListPermission
import com.nexbox.app.util.HostAppListAccessException
import com.nexbox.app.util.NexLog
import com.nexbox.core.NexBoxCore
import com.nexbox.core.core.system.pm.PackageCodeAvailability
import com.nexbox.core.utils.AbiUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

// Android 12-13 still send these running-process pressure levels; Android 14+ no longer does.
private const val LEGACY_RUNNING_TRIM_MODERATE = 5
private const val LEGACY_RUNNING_TRIM_CRITICAL = 15

class AppsRepository {
    private val tag = "AppsRepository"
    private val metadataLock = Any()
    private val hostScanMutex = Mutex()
    private val hostVersion = HostPreviewVersion()
    private var hostMetadata: Map<String, CachedHostApp> = emptyMap()
    private var hostPreview: List<InstalledAppBean> = emptyList()
    private var hostConfiguration = ""
    private val vmLoadVersion = AtomicLong()
    private var vmSnapshot: VmSnapshot? = null
    private val icons get() = AppIconLoader.cache

    private data class CachedHostApp(
        val revision: HostPackageRevision,
        val application: ApplicationInfo,
        val preview: InstalledAppBean?
    )

    private data class CachedVmApp(val application: ApplicationInfo, val preview: AppInfo)
    private data class VmSnapshot(
        val owner: CloneTarget,
        val changeVersion: Long,
        val apps: Map<String, CachedVmApp>
    )

    private fun configurationKey(): String = App.getContext().resources.configuration.toString()

    fun isHostPreviewLoaded(): Boolean = synchronized(metadataLock) {
        hostVersion.isLoaded && hostConfiguration == configurationKey()
    }

    fun isHostPreviewEmpty(): Boolean = synchronized(metadataLock) { hostPreview.isEmpty() }

    /**
     * Debug/automation callers need the same host snapshot as the picker, but must not
     * decode or expose icons.  The caller is responsible for running a fresh scan first.
     */
    fun hostPreviewSnapshot(): List<InstalledAppBean> = synchronized(metadataLock) {
        check(isHostPreviewLoaded()) { "Host application list needs a fresh scan" }
        hostPreview.toList()
    }

    /** Invalidation never waits for an APK scan or icon decoder. */
    fun invalidateHostPreview() = synchronized(metadataLock) {
        hostVersion.invalidate()
        icons.invalidate { it.owner == null }
    }

    fun invalidateUserIcons(userId: Int, createTime: Long?) = synchronized(metadataLock) {
        fun matches(owner: CloneTarget?): Boolean = owner != null && owner.userId == userId &&
            (createTime == null || owner.createTime == createTime)
        if (matches(vmSnapshot?.owner)) {
            vmLoadVersion.incrementAndGet()
            vmSnapshot = null
        }
        icons.invalidate { matches(it.owner) }
    }

    fun onConfigurationChanged() = synchronized(metadataLock) {
        hostVersion.invalidate()
        vmLoadVersion.incrementAndGet()
        vmSnapshot = null
        icons.trimTo(0)
    }

    fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    level in LEGACY_RUNNING_TRIM_CRITICAL until ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) -> icons.trimTo(0)
            // UI_HIDDEN signals a visibility change, not memory pressure. Retain a small
            // warm cache for returning from a guest or the system permission screen.
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> icons.trimTo(icons.maxBytes / 2)
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                level >= LEGACY_RUNNING_TRIM_MODERATE -> icons.trimTo(icons.maxBytes / 2)
        }
    }

    private fun safeLoadAppLabel(application: ApplicationInfo): String = try {
        NexBoxCore.getPackageManager().getApplicationLabel(application).toString()
    } catch (error: Exception) {
        NexLog.w(tag, "action=loadLabel package=${application.packageName}", error)
        application.packageName
    }

    private fun safeLoadAppIcon(application: ApplicationInfo): Bitmap? = AppIconLoader.decode(application)

    private fun iconSources(application: ApplicationInfo): List<AppIconSource> = AppIconLoader.sources(application)

    suspend fun previewInstallList(): Boolean = hostScanMutex.withLock {
        currentCoroutineContext().ensureActive()
        val operation = NexLog.operation(tag, "scanHostApps")
        try {
            AppListPermission.requireHostAccess(App.getContext())
            val version = hostVersion.current
            val configuration = configurationKey()
            val previous = synchronized(metadataLock) { hostMetadata }
            val installedPackages: List<PackageInfo?> = NexBoxCore.getPackageManager().getInstalledPackages(0)
            val nextMetadata = linkedMapOf<String, CachedHostApp>()
            var reused = 0
            var unavailable = 0

            for (installedPackage in validPackageEntries(installedPackages) { it.packageName }) {
                currentCoroutineContext().ensureActive()
                val application = installedPackage.applicationInfo ?: continue
                if (NexBoxCore.get().isNexBoxApp(installedPackage.packageName)) continue
                try {
                    val source = application.sourceDir ?: continue
                    if (!hostSourcesReadable(application, source)) {
                        unavailable++
                        continue
                    }
                    val sources = iconSources(application)
                    val versionCode = PackageInfoCompat.getLongVersionCode(installedPackage)
                    val revision = HostPackageRevision(
                        source, application.splitSourceDirs.orEmpty().toList(),
                        installedPackage.firstInstallTime, installedPackage.lastUpdateTime,
                        application.flags, App.getContext().resources.configuration.locales.toLanguageTags(),
                        versionCode, sources, configuration
                    )
                    val key = AppIconKey(installedPackage.packageName, sources, versionCode,
                        installedPackage.lastUpdateTime, null, version, configuration)
                    val cached = previous[installedPackage.packageName]?.takeIf { it.revision == revision }
                    val preview = if (cached != null) {
                        reused++
                        cached.preview?.copy(iconKey = key)
                    } else if (supportsInstalledAbi(application, source)) {
                        InstalledAppBean(
                            safeLoadAppLabel(application), null, installedPackage.packageName, source,
                            isSystemApp = (application.flags and
                                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
                            apkSizeBytes = installedApkSize(source, application.splitSourceDirs),
                            firstInstallTime = installedPackage.firstInstallTime.takeIf { it > 0 },
                            lastUpdateTime = installedPackage.lastUpdateTime.takeIf { it > 0 },
                            iconKey = key
                        )
                    } else null
                    nextMetadata[installedPackage.packageName] = CachedHostApp(revision, application, preview)
                } catch (error: Exception) {
                    NexLog.e(tag, "action=readHostApp package=${installedPackage.packageName}", error)
                }
            }
            AppListPermission.requireHostAccess(App.getContext())
            currentCoroutineContext().ensureActive()
            val apps = nextMetadata.values.mapNotNull { it.preview }
            synchronized(metadataLock) {
                check(hostVersion.current == version && configurationKey() == configuration) {
                    "Host application access changed during scan"
                }
                hostMetadata = nextMetadata
                hostPreview = apps
                hostConfiguration = configuration
                hostVersion.markLoaded(version)
            }
            operation.finish(detail = "packages=${installedPackages.size} compatible=${apps.size} reused=$reused unavailable=$unavailable")
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: HostAppListAccessException) {
            invalidateHostPreview()
            operation.fail(error)
            throw error
        } catch (error: Exception) {
            operation.fail(error)
            false
        }
    }

    private fun supportsInstalledAbi(application: ApplicationInfo, source: String): Boolean {
        // An installed ABI match avoids opening its APK; a mismatch still needs the archive.
        val knownCompatible = runCatching {
            val abis = AbiUtils.getInstalledAbis(application)
            hostAbiKnownCompatible(abis.getOrNull(0), abis.getOrNull(1), NexBoxCore.is64Bit())
        }.getOrDefault(false)
        if (knownCompatible) return true
        // Native code can live only in a split. A readable, Java-only base is not
        // sufficient evidence that the complete installed package is compatible.
        var hasNativeCode = false
        var compatible = false
        for (path in listOf(source) + application.splitSourceDirs.orEmpty()) {
            val archive = AbiUtils(File(path))
            if (!archive.isArchiveReadable()) return false
            hasNativeCode = hasNativeCode || !archive.isEmptyAib()
            compatible = compatible || if (NexBoxCore.is64Bit()) archive.is64Bit() else archive.is32Bit()
        }
        return !hasNativeCode || compatible
    }

    private fun hostSourcesReadable(application: ApplicationInfo, source: String): Boolean =
        (listOf(source) + application.splitSourceDirs.orEmpty()).all { path ->
            path.isNotBlank() && File(path).let { it.isFile && it.canRead() }
        }

    private fun applicationForIcon(key: AppIconKey): ApplicationInfo? = synchronized(metadataLock) {
        if (key.configuration != configurationKey()) return@synchronized null
        if (key.owner == null) {
            if (!hostVersion.isLoaded || key.accessGeneration != hostVersion.current) return@synchronized null
            hostMetadata[key.packageName]?.takeIf { it.preview?.iconKey == key }?.application
        } else {
            vmSnapshot?.takeIf { it.owner == key.owner && it.changeVersion == UsersRepository.changes.value }
                ?.apps?.get(key.packageName)?.takeIf { it.preview.iconKey == key }?.application
        }
    }

    /** No bitmap is published back into metadata; only visible rows retain the result. */
    suspend fun loadAppIcon(key: AppIconKey?): Bitmap? = withContext(Dispatchers.IO) {
        if (key == null) return@withContext null
        if (key.owner == null) AppListPermission.requireHostAccess(App.getContext())
        val application = applicationForIcon(key) ?: return@withContext null
        val bitmap = icons.getOrLoad(key) {
            currentCoroutineContext().ensureActive()
            if (applicationForIcon(key) == null) null else safeLoadAppIcon(application)
        }
        currentCoroutineContext().ensureActive()
        if (key.owner == null) AppListPermission.requireHostAccess(App.getContext())
        bitmap.takeIf { applicationForIcon(key) != null }
    }

    fun getInstalledAppList(userID: Int): List<InstalledAppBean> {
        AppListPermission.requireHostAccess(App.getContext())
        val (version, apps) = synchronized(metadataLock) {
            check(isHostPreviewLoaded()) { "Host application list needs a fresh scan" }
            hostVersion.current to hostPreview
        }
        // One Binder snapshot, outside the metadata lock, replaces per-package badge IPC.
        val service = checkNotNull(NexBoxCore.getBPackageManager().service) { "Package manager service unavailable" }
        val clonedApps = checkNotNull(service.getInstalledApplications(0, userID)) { "Could not read cloned applications" }
        val installedNames = validPackageEntries(clonedApps) { it.packageName }.mapTo(hashSetOf()) { it.packageName }
        val result = apps.map { it.copy(isInstalled = it.packageName in installedNames) }
        AppListPermission.requireHostAccess(App.getContext())
        synchronized(metadataLock) {
            check(hostVersion.current == version && hostPreview === apps && isHostPreviewLoaded()) {
                "Host application access changed during badge loading"
            }
        }
        return result
    }

    suspend fun getVmInstallList(owner: CloneTarget): List<AppInfo> {
        currentCoroutineContext().ensureActive()
        val request = vmLoadVersion.incrementAndGet()
        val changeVersion = UsersRepository.changes.value
        val configuration = configurationKey()
        val (previous, hostApps) = synchronized(metadataLock) {
            vmSnapshot?.takeIf { it.owner == owner } to hostMetadata
        }
        // PackageInfo adds a version identity in the same single list IPC.
        val service = checkNotNull(NexBoxCore.getBPackageManager().service) { "Package service unavailable" }
        val packages: List<PackageInfo?> = checkNotNull(service.getInstalledPackages(0, owner.userId)) {
            "Virtual application list unavailable"
        }
        val next = linkedMapOf<String, CachedVmApp>()
        // AppList$userId is the persisted clone-install order. PackageManager's
        // backing map is not an ordering contract, so using its result directly
        // can make a freshly cloned app appear at the top on a refresh. Keep the
        // stored order (oldest first) and put entries missing from legacy
        // preferences at the end with a deterministic package-name tie-breaker.
        val persistedOrder = CloneAppOrder.positions(owner)
        val orderedPackages = validPackageEntries(packages) { it.packageName }
            .sortedWith(compareBy<PackageInfo> { persistedOrder[it.packageName] ?: Int.MAX_VALUE }
                .thenBy { it.packageName })
        for (pkg in orderedPackages) {
            currentCoroutineContext().ensureActive()
            val application = pkg.applicationInfo ?: continue
            try {
                val key = AppIconKey(pkg.packageName, iconSources(application),
                    PackageInfoCompat.getLongVersionCode(pkg), pkg.lastUpdateTime, owner, 0, configuration)
                val cached = previous?.apps?.get(pkg.packageName)?.preview?.takeIf { it.iconKey == key }
                val host = hostApps[pkg.packageName]?.preview?.takeIf {
                    val hostKey = it.iconKey
                    hostKey != null && hostKey.sources == key.sources && hostKey.versionCode == key.versionCode &&
                        hostKey.configuration == key.configuration
                }
                // A removed host APK must remain manageable; source state can change
                // independently of the cached label/icon metadata.
                val preview = (cached ?: AppInfo(host?.name ?: safeLoadAppLabel(application),
                    null, pkg.packageName, application.sourceDir.orEmpty(), iconKey = key))
                    .copy(sourceAvailable = PackageCodeAvailability.isAvailable(application))
                next[pkg.packageName] = CachedVmApp(application, preview)
            } catch (error: Exception) {
                NexLog.w(tag, "action=readVirtualApp package=${pkg.packageName}", error)
            }
        }
        currentCoroutineContext().ensureActive()
        synchronized(metadataLock) {
            check(vmLoadVersion.get() == request && configurationKey() == configuration &&
                UsersRepository.changes.value == changeVersion) { "Application snapshot changed during loading" }
            vmSnapshot = VmSnapshot(owner, changeVersion, next)
        }
        return next.values.map { it.preview }
    }

    /** One shared-code refresh per foreground refresh; it never rewrites user ownership. */
    fun syncHostSources(): android.os.Bundle {
        val service = checkNotNull(NexBoxCore.getBPackageManager().service) { "Package service unavailable" }
        return checkNotNull(service.syncHostPackages(null)).also { result ->
            NexLog.i("Install", "action=syncHostSources updated=${result.getInt("updated")} failed=${result.getInt("failed")}")
        }
    }

    /** Read all groups without disturbing the selected group's icon/metadata cache. */
    suspend fun getMissingSourceApps(owners: List<CloneTarget>): Map<CloneTarget, List<AppInfo>> {
        val service = checkNotNull(NexBoxCore.getBPackageManager().service) { "Package service unavailable" }
        val result = linkedMapOf<CloneTarget, List<AppInfo>>()
        for (owner in owners) {
            currentCoroutineContext().ensureActive()
            UserIdentityResolver.validateTarget(owner)
            val packages = checkNotNull(service.getInstalledPackages(0, owner.userId)) { "Virtual application list unavailable" }
            val missing = validPackageEntries(packages) { it.packageName }.mapNotNull { pkg ->
                val application = pkg.applicationInfo ?: return@mapNotNull null
                if (PackageCodeAvailability.isAvailable(application)) null
                else AppInfo(safeLoadAppLabel(application), null, pkg.packageName, application.sourceDir.orEmpty(),
                    sourceAvailable = false)
            }
            UserIdentityResolver.validateTarget(owner)
            if (missing.isNotEmpty()) result[owner] = missing
        }
        return result
    }

    fun updateApkOrder(owner: CloneTarget, dataList: List<AppInfo>): Boolean {
        val operation = NexLog.operation(tag, "reorderApps",
            "userId=${owner.userId} generation=${owner.createTime} count=${dataList.size}")
        return try {
            CloneAppOrder.replace(owner, dataList.map { it.packageName })
            operation.finish(detail = "persistence=asynchronous")
            true
        } catch (e: Exception) {
            operation.fail(e)
            false
        }
    }
}

/** Metadata/ABI/icon cache identity; a refresh still queries the live PackageManager list. */
internal data class HostPackageRevision(
    val sourcePath: String,
    val splitPaths: List<String>,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val flags: Int,
    val localeTags: String,
    val versionCode: Long = 0,
    val sources: List<AppIconSource> = emptyList(),
    val configuration: String = localeTags
)

internal fun hostAbiKnownCompatible(primary: String?, secondary: String?, is64Bit: Boolean): Boolean {
    val supported = if (is64Bit) setOf("arm64-v8a") else setOf("armeabi-v7a", "armeabi")
    return primary in supported || secondary in supported
}

/** A stale scan cannot revalidate the cache after access was revoked, even at publication time. */
internal class HostPreviewVersion {
    private val revision = AtomicLong()
    @Volatile private var loadedRevision = -1L
    val current: Long get() = revision.get()
    val isLoaded: Boolean get() = loadedRevision == current
    fun invalidate() { revision.incrementAndGet() }
    fun markLoaded(version: Long) { loadedRevision = version }
}

/** Binder/PackageManager lists are platform types and may contain malformed null entries. */
internal inline fun <T : Any> validPackageEntries(
    entries: Iterable<T?>,
    packageName: (T) -> String?
): List<T> = entries.mapNotNull { entry -> entry?.takeIf { !packageName(it).isNullOrBlank() } }
