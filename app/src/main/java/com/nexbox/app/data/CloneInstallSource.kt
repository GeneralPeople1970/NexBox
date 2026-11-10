package com.nexbox.app.data

import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.CancellationSignal
import android.os.Looper
import com.nexbox.app.app.App
import com.nexbox.app.util.NexLog
import com.nexbox.app.util.LogPrivacy
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nexbox.core.NexBoxCore
import com.nexbox.core.core.system.pm.ApkSourcePolicy
import com.nexbox.core.core.system.pm.IBCloneInstallSession

/** Owns a server preparation until the request is committed, cancelled, or cleared. */
data class CloneInstallSource(
    val source: String,
    val packageName: String,
    private val temporary: File? = null,
    val fromInstalledPackage: Boolean = false,
    val preparedSession: IBCloneInstallSession? = null,
    private val sessionOwner: IBinder? = null
) : AutoCloseable {
    private val released = AtomicBoolean()

    override fun close() {
        if (!released.compareAndSet(false, true)) return
        // ViewModel.onCleared and dialog dismissal are main-thread callbacks. The queued
        // cleanup owns this source until its Binder cancellation has finished.
        if (preparedSession != null && Looper.myLooper() == Looper.getMainLooper()) {
            sourceCleanup.execute { releaseNow() }
        } else releaseNow()
    }

    private fun releaseNow() {
        try {
            preparedSession?.cancel()
        } catch (error: Exception) {
            NexLog.w("Install", "action=releasePreparedSession package=$packageName success=false", error)
        }
        temporary?.let {
            if (it.exists() && !it.delete()) NexLog.w("Install", "action=releaseStagedSource package=$packageName success=false")
        }
    }
}

private val sourceCleanup = Executors.newSingleThreadExecutor { work ->
    Thread(work, "clone-source-cleanup").apply { isDaemon = true }
}

/** Compatibility entry for existing synchronous callers; preparation remains completed on return. */
fun prepareCloneSource(source: String): CloneInstallSource = prepareCloneSource(source, null)

/** The watcher owns only cancellation; the request itself performs all preparation and cleanup. */
suspend fun prepareCloneSourceCancellable(source: String): CloneInstallSource =
    prepareCloneSourceCancellable(source, ::prepareCloneSource)

internal suspend fun prepareCloneSourceCancellable(source: String,
    prepare: (String, CancellationSignal) -> CloneInstallSource): CloneInstallSource {
    var undelivered: CloneInstallSource? = null
    try {
        val result = coroutineScope {
            val cancellation = CancellationSignal()
            val completed = CompletableDeferred<Unit>()
            launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try {
                    completed.await()
                } catch (cancelled: CancellationException) {
                    // Android invokes the cancellation listener synchronously. Never issue
                    // the parallel Binder cancel call from the UI cancellation thread.
                    withContext(NonCancellable + Dispatchers.IO) { cancellation.cancel() }
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    prepare(source, cancellation).also { undelivered = it }
                }
            } finally {
                // A normal return must not cancel a ready session awaiting user confirmation.
                completed.complete(Unit)
            }
        }
        currentCoroutineContext().ensureActive()
        undelivered = null
        return result
    } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        throw failure
    } finally {
        // A prompt cancellation can discard a withContext result after the server returned it.
        undelivered?.let { pending -> withContext(NonCancellable + Dispatchers.IO) { pending.close() } }
    }
}

private fun prepareCloneSource(source: String, cancellation: CancellationSignal?): CloneInstallSource =
    NexLog.trace("Install", "prepareSource", "source=${LogPrivacy.source(source)}") { operation ->
    cancellation?.throwIfCanceled()
    val context = App.getContext()
    val normalized = source.trim()
    val uri = Uri.parse(normalized)
    val fromInstalledPackage = uri.scheme == null && !normalized.contains('/') && !normalized.contains('\\') &&
        !ApkSourcePolicy.isArchiveFileName(normalized)
    val resolvedSource = if (fromInstalledPackage) {
        val info = context.packageManager.getPackageInfo(normalized, 0)
        require(info.packageName != NexBoxCore.getHostPkg()) { "Cannot clone NexBox itself" }
        operation.step("hostPackageValidated", "package=${info.packageName}")
        requireNotNull(info.applicationInfo?.sourceDir)
    } else when (uri.scheme) {
        "content" -> normalized
        "file" -> requireNotNull(uri.path)
        null -> normalized
        else -> error("Unsupported APK source")
    }
    val option = if (fromInstalledPackage) com.nexbox.core.entity.pm.InstallOption.installBySystem()
        else com.nexbox.core.entity.pm.InstallOption.installByStorage().also {
            if (uri.scheme == "content") it.makeUriFile()
        }
    val owner = Binder()
    cancellation?.throwIfCanceled()
    val session = checkNotNull(NexBoxCore.getBPackageManager().service) { "The core service is unavailable" }
        .prepareCloneInstall(resolvedSource, option, owner)
    try {
        // This private overload is used only by the IO watcher above; its listener also
        // executes on IO when cancellation raced the quick session creation.
        cancellation?.setOnCancelListener {
            try { session.cancel() }
            catch (failure: Exception) { NexLog.w("Install", "action=cancelPreparingSource success=false", failure) }
        }
        cancellation?.throwIfCanceled()
        val packageName = requireNotNull(session.prepare())
        cancellation?.throwIfCanceled()
        operation.step("sourcePrepared", "package=$packageName")
        CloneInstallSource(resolvedSource, packageName, fromInstalledPackage = fromInstalledPackage,
            preparedSession = session, sessionOwner = owner)
    } catch (error: Throwable) {
        try { session.cancel() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
        throw error
    } finally {
        cancellation?.setOnCancelListener(null)
    }
}
