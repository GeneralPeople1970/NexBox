package com.nexbox.app.data

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.graphics.drawable.toBitmap
import com.nexbox.app.app.App
import com.nexbox.app.util.AppListPermission
import com.nexbox.app.util.MemoryManager
import com.nexbox.app.util.NexLog
import com.nexbox.core.NexBoxCore
import com.nexbox.core.fake.frameworks.BPackageManager
import com.nexbox.core.fake.frameworks.BUserManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** One bitmap budget and decoder queue for home, picker, scopes and modules. */
internal object AppIconLoader {
    val cache = BoundedIconCache<AppIconKey, Bitmap>(
        appIconCacheBudget(Runtime.getRuntime().maxMemory())
    ) { it.allocationByteCount.toLong() }

    fun sources(application: ApplicationInfo): List<AppIconSource> =
        (listOfNotNull(application.sourceDir) + application.splitSourceDirs.orEmpty())
            .distinct().map { path ->
                val file = File(path)
                AppIconSource(path, file.length(), file.lastModified())
            }

    fun key(application: ApplicationInfo, owner: CloneTarget?, versionCode: Long,
            lastUpdateTime: Long = 0L, accessGeneration: Long = 0L): AppIconKey =
        AppIconKey(application.packageName, sources(application), versionCode, lastUpdateTime,
            owner, accessGeneration, App.getContext().resources.configuration.toString())

    fun decode(application: ApplicationInfo): Bitmap? {
        if (MemoryManager.shouldSkipIconLoading()) return null
        return try {
            NexBoxCore.getPackageManager().getApplicationIcon(application).toBitmap(width = 96, height = 96)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: OutOfMemoryError) {
            cache.trimTo(0)
            NexLog.w("AppIcon") { "action=loadIcon deferred=memory package=${application.packageName}" }
            null
        } catch (error: Exception) {
            NexLog.w("AppIcon", error) { "action=loadIcon package=${application.packageName}" }
            null
        }
    }

    /** Scope snapshots contain only resource identities, never a decoded image or UI Context. */
    suspend fun load(key: AppIconKey?): Bitmap? = withContext(Dispatchers.IO) {
        if (key == null) return@withContext null
        try {
            val bitmap = cache.getOrLoad(key) {
                currentCoroutineContext().ensureActive()
                resolve(key)?.let(::decode)
            }
            currentCoroutineContext().ensureActive()
            // Revalidate cache hits too: immutable old APK directories may survive an update.
            val current = resolve(key) != null
            // A late metadata snapshot may describe an older version. It may retire its
            // own stale key, never cancel a different version's current visible request.
            if (!current) cache.invalidate { it == key }
            bitmap.takeIf { current }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            NexLog.w("AppIcon", error) { "action=loadVisibleIcon package=${key.packageName}" }
            null
        }
    }

    private fun resolve(key: AppIconKey): ApplicationInfo? {
        if (!isCurrentOwner(key)) return null
        val owner = key.owner
        val info = if (owner == null) {
            NexBoxCore.getPackageManager().getPackageInfo(key.packageName, 0)
        } else {
            checkNotNull(BPackageManager.get().service) { "Package service unavailable" }
                .getPackageInfo(key.packageName, 0, owner.userId)
        }
        val application = info?.applicationInfo ?: return null
        // Resource stats are read once, between the two live owner checks.
        if (PackageInfoCompat.getLongVersionCode(info) != key.versionCode ||
            (key.lastUpdateTime != 0L && info.lastUpdateTime != key.lastUpdateTime) ||
            sources(application) != key.sources || !isCurrentOwner(key)) return null
        return application
    }

    private fun isCurrentOwner(key: AppIconKey): Boolean {
        if (key.configuration != App.getContext().resources.configuration.toString()) return false
        val owner = key.owner
        if (owner == null) {
            AppListPermission.requireHostAccess(App.getContext())
        } else {
            val user = checkNotNull(BUserManager.get().service) { "User service unavailable" }
                .getUserInfo(owner.userId)
            if (user?.createTime != owner.createTime) return false
        }
        return true
    }
}
