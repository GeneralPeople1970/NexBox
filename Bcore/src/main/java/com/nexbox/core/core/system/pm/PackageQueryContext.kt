package com.nexbox.core.core.system.pm

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Bundle
import black.android.content.pm.BRApplicationInfoL
import com.nexbox.core.NexBoxCore
import com.nexbox.core.core.env.AppSystemEnv
import com.nexbox.core.core.system.user.BUserInfo
import com.nexbox.core.entity.pm.InstallOption
import com.nexbox.core.utils.BzFileUtils
import com.nexbox.core.utils.compat.PackageSignaturesCompat.LEGACY_QUERY_FLAG
import java.util.IdentityHashMap
import java.util.function.Supplier

/** Single-request metadata only. Nothing here survives the Binder query or retains its caller. */
internal class PackageQueryContext @JvmOverloads constructor(
    @JvmField val flags: Int,
    @JvmField val userId: Int,
    @JvmField val userCreateTime: Long,
    private val host: HostQueries = AndroidHostQueries,
    private val applyVisibilityPolicy: Boolean = true
) {
    interface HostQueries {
        fun hostApplication(flags: Int): ApplicationInfo?
        fun installedApplication(packageName: String): ApplicationInfo?
        fun installedPackage(packageName: String, flags: Int): PackageInfo?
        fun permission(permission: String): Int
        fun sharedLibraryFile(): String
    }

    class Snapshot private constructor(
        @JvmField val code: BPackage,
        @JvmField val state: BPackageUserState,
        @JvmField val application: ApplicationInfo?,
        @JvmField val appMetadata: Bundle?,
        @JvmField val appId: Int,
        @JvmField val systemInstall: Boolean,
        @JvmField val hiddenByPolicy: Boolean,
        @JvmField val signatures: Array<Signature>?,
        @JvmField val signingSignatures: Array<Signature>?,
        @JvmField val signingDetailsAvailable: Boolean
    ) {
        companion object {
            internal fun capture(code: BPackage, state: BPackageUserState, flags: Int, applyVisibilityPolicy: Boolean): Snapshot {
                // Published parser objects are replaced on install/update/scan, not edited in
                // place. Keep that code generation, but never retain its mutable settings map.
                val application = code.applicationInfo?.let(::copyApplication)
                return Snapshot(code, BPackageUserState(state), application,
                    if (flags and PackageManager.GET_META_DATA != 0) code.mAppMetaData?.let(::Bundle) else null,
                    checkNotNull(code.mExtras) { "Package identity is unavailable" }.appId,
                    checkNotNull(code.installOption) { "Package install options are unavailable" }
                        .isFlag(InstallOption.FLAG_SYSTEM),
                    applyVisibilityPolicy && AppSystemEnv.isBlackPackage(code.packageName),
                    if (flags and LEGACY_QUERY_FLAG != 0) code.mSignatures?.clone() else null,
                    if (flags and PackageManager.GET_SIGNING_CERTIFICATES != 0) code.mSigningDetails?.signatures?.clone() else null,
                    flags and PackageManager.GET_SIGNING_CERTIFICATES != 0 && code.mSigningDetails != null)
            }
        }
    }

    private val snapshots = IdentityHashMap<BPackage, Snapshot>()
    private val applications = IdentityHashMap<Snapshot, ApplicationInfo?>()
    private val installedApplications = HashMap<String, ApplicationInfo?>()
    private val signaturePackages = HashMap<String, PackageInfo?>()
    private val permissions = HashMap<String, Int>()
    private var hostResolved = false
    private var hostApplication: ApplicationInfo? = null
    private var sharedLibrary: String? = null

    /** The service calls this under mPackages, including copying the per-user state. */
    fun capture(code: BPackage, state: BPackageUserState): Snapshot =
        snapshots[code] ?: Snapshot.capture(code, state, flags, applyVisibilityPolicy).also { snapshots[code] = it }

    fun hostApplication(): ApplicationInfo? {
        if (!hostResolved) {
            hostApplication = host.hostApplication(flags)
            hostResolved = true
        }
        return hostApplication
    }

    fun hostSignatures(packageName: String): PackageInfo? {
        val signatureFlags = signatureFlags(flags)
        if (signatureFlags == 0) return null
        // Preserve matching flags (for example uninstalled-package visibility) when signing
        // information is requested; the optimization is avoiding this lookup entirely otherwise.
        return cached(signaturePackages, packageName) { host.installedPackage(packageName, flags) }
    }

    fun installedApplication(packageName: String): ApplicationInfo? =
        cached(installedApplications, packageName) { host.installedApplication(packageName) }

    fun permission(permission: String): Int = permissions.getOrPut(permission) { host.permission(permission) }

    fun sharedLibraryFile(): String = sharedLibrary ?: host.sharedLibraryFile().also { sharedLibrary = it }

    /** Cached templates are private to this request; callers receive their own mutable result. */
    fun application(snapshot: Snapshot, generate: Supplier<ApplicationInfo?>): ApplicationInfo? =
        cached(applications, snapshot) { generate.get() }?.let(::copyApplication)

    private fun <K, V> cached(values: MutableMap<K, V?>, key: K, generate: () -> V?): V? {
        if (values.containsKey(key)) return values[key]
        return generate().also { values[key] = it }
    }

    companion object {
        @JvmStatic
        fun raw(flags: Int, userId: Int, createTime: Long): PackageQueryContext =
            PackageQueryContext(flags, userId, createTime, AndroidHostQueries, false)

        @JvmStatic
        fun matchesUser(userId: Int, createTime: Long, current: BUserInfo?): Boolean =
            current != null && current.id == userId && current.createTime == createTime

        @JvmStatic
        fun signatureFlags(flags: Int): Int =
            flags and (LEGACY_QUERY_FLAG or PackageManager.GET_SIGNING_CERTIFICATES)

        @JvmStatic
        fun copyApplication(source: ApplicationInfo): ApplicationInfo = ApplicationInfo(source).apply {
            splitSourceDirs = source.splitSourceDirs?.clone()
            splitPublicSourceDirs = source.splitPublicSourceDirs?.clone()
            splitNames = source.splitNames?.clone()
            sharedLibraryFiles = source.sharedLibraryFiles?.clone()
            // Overlay directories are hidden from SDK stubs; keep the copy detached
            // through the existing, capability-checked framework mirror.
            val hiddenSource = BRApplicationInfoL.getWithException(source)
            if (hiddenSource._check_resourceDirs() != null) {
                BRApplicationInfoL.getWithException(this)._set_resourceDirs(hiddenSource.resourceDirs()?.clone())
            }
            metaData = source.metaData?.let(::Bundle)
        }
    }

    private object AndroidHostQueries : HostQueries {
        override fun hostApplication(flags: Int): ApplicationInfo? = try {
            NexBoxCore.getPackageManager().getApplicationInfo(NexBoxCore.getHostPkg(), flags)
        } catch (_: Exception) {
            null
        }

        override fun installedApplication(packageName: String): ApplicationInfo? =
            PackageManagerCompat.resolveHostInstalledApplicationInfo(packageName)

        override fun installedPackage(packageName: String, flags: Int): PackageInfo? = try {
            NexBoxCore.getContext().packageManager.getPackageInfo(packageName, flags)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

        override fun permission(permission: String): Int =
            NexBoxCore.getPackageManager().checkPermission(permission, NexBoxCore.getHostPkg())

        override fun sharedLibraryFile(): String =
            if (BzFileUtils.isExist("/system/framework/org.apache.http.legacy.jar")) {
                "/system/framework/org.apache.http.legacy.jar"
            } else "/system/framework/org.apache.http.legacy.boot.jar"
    }
}
