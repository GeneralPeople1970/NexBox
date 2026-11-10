package com.nexbox.app.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import com.nexbox.app.app.App
import com.nexbox.core.core.system.xposed.XposedModulePolicy
import com.nexbox.core.fake.frameworks.BPackageManager

/** The same eligible cloned-app list is used by location, device and module scopes. */
data class ScopeApp(
    val packageName: String,
    val label: String,
    val versionName: String? = null,
    val iconKey: AppIconKey? = null,
    val isModule: Boolean = false
)

/** IO-only. A failed package-service read must not be mistaken for an empty scope. */
class ScopeAppRepository(private val context: Context = App.getContext()) {
    fun load(
        userId: Int,
        allowedPackages: Set<String>? = null,
        excludedPackages: Set<String> = emptySet(),
        includeModules: Boolean = false
    ): List<ScopeApp> {
        val owner = CloneTarget(userId, checkNotNull(UserIdentityResolver.userIdentity(userId)) { "Scope user unavailable" })
        val revision = UsersRepository.changes.value
        val apps = packages(userId, allowedPackages, excludedPackages, includeModules).map { info ->
            val application = checkNotNull(info.applicationInfo)
            ScopeApp(
                packageName = info.packageName,
                label = runCatching { application.loadLabel(context.packageManager).toString() }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: info.packageName,
                versionName = info.versionName,
                iconKey = AppIconLoader.key(application, owner, PackageInfoCompat.getLongVersionCode(info),
                    info.lastUpdateTime),
                isModule = includeModules && isModule(application)
            )
        }.sortedWith(compareBy<ScopeApp> { it.label.lowercase() }.thenBy { it.packageName })
        check(UserIdentityResolver.userIdentity(userId) == owner.createTime && UsersRepository.changes.value == revision) {
            "Scope user changed while loading"
        }
        return apps
    }

    /** Applying a saved scope does not need to decode every app icon. */
    fun packageNames(userId: Int): Set<String> = packages(userId).mapTo(linkedSetOf()) { it.packageName }

    fun isModule(info: ApplicationInfo): Boolean = XposedModulePolicy.isCandidate(info)

    private fun packages(
        userId: Int,
        allowedPackages: Set<String>? = null,
        excludedPackages: Set<String> = emptySet(),
        includeModules: Boolean = false
    ): List<PackageInfo> = clonedPackages(userId).filter { info ->
        info.packageName !in excludedPackages &&
            (allowedPackages == null || info.packageName in allowedPackages) &&
            (includeModules || !isModule(checkNotNull(info.applicationInfo)))
    }

    private fun clonedPackages(userId: Int): List<PackageInfo> {
        require(userId >= 0)
        val service = checkNotNull(BPackageManager.get().service) { "Package service unavailable" }
        val installed = checkNotNull(service.getInstalledPackages(PackageManager.GET_META_DATA, userId)) {
            "Cloned application list unavailable"
        }
        return installed.map { info ->
            checkNotNull(info) { "Incomplete cloned application list" }.also {
                check(!it.packageName.isNullOrBlank() && it.applicationInfo != null) {
                    "Incomplete cloned application metadata"
                }
            }
        }.filter { info ->
            info.packageName != context.packageName &&
                // Legacy hosts installed this infrastructure package; upgrades must not treat it as a user clone.
                info.packageName != "com.nexbox.gallery"
        }.distinctBy { it.packageName }
    }
}
