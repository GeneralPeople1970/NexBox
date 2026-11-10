package com.nexbox.core.core.system.pm

import android.os.Binder
import android.os.IBinder
import android.os.CancellationSignal
import android.os.OperationCanceledException
import com.nexbox.core.core.system.xposed.XposedCallerGuard
import com.nexbox.core.entity.pm.InstallOption
import com.nexbox.core.entity.pm.InstallResult
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Preparation is outside the lifecycle lock; only the existing checked commit mutates users. */
class CloneInstallSession private constructor(
    private val service: BPackageManagerService,
    private val owner: IBinder,
    private val ownerPid: Int,
    private val ownerUid: Int,
    private val source: String,
    private val option: InstallOption
) : IBCloneInstallSession.Stub(), IBinder.DeathRecipient {
    private val lock = Any()
    private val cancelled = AtomicBoolean()
    private val released = AtomicBoolean()
    private val cancellation = CancellationSignal()
    private var preparing = false
    private var committing = false
    @Volatile private var deathLinked = false
    private var prepared: PreparedCloneSource? = null

    // Legacy clients still obtain a fully prepared source when reading this property.
    override fun getPackageName(): String = prepare()

    override fun prepare(): String {
        enforceOwner()
        synchronized(lock) {
            check(!cancelled.get() && owner.isBinderAlive && !preparing && !committing) { "The preparation is not available" }
            prepared?.let { return it.packageName }
            preparing = true
        }
        var staged: PreparedCloneSource? = null
        try {
            checkActive()
            val ready = PreparedCloneSource.prepare(source, option, this::checkActive, cancellation)
            staged = ready
            synchronized(lock) {
                checkActive()
                prepared = ready
                preparing = false
                return ready.packageName
            }
        } catch (error: Throwable) {
            val wasCancelled = cancelled.get() || error is OperationCanceledException
            synchronized(lock) { preparing = false; cancelled.set(true) }
            try { staged?.close() } finally { releaseOwner() }
            if (wasCancelled) {
                throw IllegalStateException("The clone preparation was cancelled", error)
            }
            if (error is IOException) throw IllegalArgumentException(error.message, error)
            throw error
        }
    }

    override fun commit(userId: Int, expectedUserCreateTime: Long): InstallResult {
        enforceOwner()
        prepare()
        val source = synchronized(lock) {
            check(!cancelled.get() && !preparing && !committing) { "The preparation is not available" }
            checkNotNull(prepared) { "The preparation is closed" }.also { committing = true }
        }
        try {
            checkActive()
            return service.commitPreparedClone(source, userId, expectedUserCreateTime)
        } catch (error: IOException) {
            return InstallResult().installError(source.packageName, error.message)
        } finally {
            synchronized(lock) { prepared = null; committing = false; cancelled.set(true) }
            try { source.close() } finally { releaseOwner() }
        }
    }

    override fun cancel() {
        enforceOwner()
        closeWhenIdle()
    }

    override fun binderDied() = closeWhenIdle()

    private fun enforceOwner() {
        XposedCallerGuard.enforceHostManager()
        if (Binder.getCallingPid() != ownerPid || Binder.getCallingUid() != ownerUid) {
            throw SecurityException("This preparation belongs to another caller")
        }
    }

    private fun checkActive() {
        if (cancelled.get() || !owner.isBinderAlive || Thread.currentThread().isInterrupted) {
            throw IOException("The clone preparation was cancelled")
        }
    }

    private fun closeWhenIdle() {
        val source: PreparedCloneSource?
        val busy: Boolean
        synchronized(lock) {
            cancelled.set(true)
            // A copy/commit owns its files until it leaves its critical lifetime. Death
            // cannot delete APKs while the installer is reading them.
            busy = preparing || committing
            source = if (busy) null else prepared.also { prepared = null }
        }
        try { cancellation.cancel() }
        finally {
            if (!busy) try { source?.close() } finally { releaseOwner() }
        }
    }

    private fun releaseOwner() {
        if (released.compareAndSet(false, true)) {
            try {
                if (deathLinked) owner.unlinkToDeath(this, 0)
            } catch (_: java.util.NoSuchElementException) {
                // A delivered death notification may already have removed the recipient.
            } finally {
                openSessions.decrementAndGet()
            }
        }
    }

    companion object {
        private const val MAX_OPEN_SESSIONS = 8
        private val openSessions = AtomicInteger()

        @JvmStatic
        fun prepare(service: BPackageManagerService, source: String, option: InstallOption,
                    owner: IBinder): IBCloneInstallSession {
            XposedCallerGuard.enforceHostManager()
            require(source.isNotBlank()) { "An installation source is required" }
            val capturedOption = InstallOption().also { it.flags = option.flags }
            val error = ApkSourcePolicy.sourceError(capturedOption.isFlag(InstallOption.FLAG_SYSTEM),
                capturedOption.isFlag(InstallOption.FLAG_STORAGE), capturedOption.isFlag(InstallOption.FLAG_URI_FILE))
            require(error == null) { error.orEmpty() }
            if (openSessions.incrementAndGet() > MAX_OPEN_SESSIONS) {
                openSessions.decrementAndGet()
                throw IllegalStateException("Too many open clone preparations")
            }
            val session = CloneInstallSession(service, owner, Binder.getCallingPid(), Binder.getCallingUid(),
                source, capturedOption)
            try {
                owner.linkToDeath(session, 0)
                session.deathLinked = true
                session.checkActive()
                // Return before provider reads, parsing or expansion so a second Binder call can cancel them.
                return session
            } catch (error: Throwable) {
                session.cancelled.set(true)
                session.releaseOwner()
                if (error is IOException) throw IllegalArgumentException(error.message, error)
                throw error
            }
        }
    }
}
