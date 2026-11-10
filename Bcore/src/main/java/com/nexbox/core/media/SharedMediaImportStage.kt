package com.nexbox.core.media

import android.os.CancellationSignal
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.nexbox.core.core.NativeCore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/** Owns one hidden, same-directory copy; publishing and index changes remain caller-locked. */
class SharedMediaImportStage private constructor(
    private val root: File,
    val file: File,
    private val identity: Identity
) : AutoCloseable {
    private var ready: Identity? = null
    private var copyStarted = false

    @Throws(IOException::class)
    fun copyFrom(input: InputStream, cancellation: CancellationSignal?) {
        checkCancelled(cancellation)
        if (copyStarted) throw IOException("Media staging copy has already started")
        copyStarted = true
        requireOwned(file, identity, checkVersion = true)
        val descriptor = try {
            Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        } catch (failure: ErrnoException) {
            throw IOException("Could not open the pending media copy", failure)
        }
        var failure: Throwable? = null
        try {
            FileOutputStream(descriptor).use { output ->
                try {
                    val opened = Identity(Os.fstat(output.fd))
                    if (!identity.sameFile(opened)) throw IOException("Pending media copy was replaced")
                    Os.ftruncate(output.fd, 0L)
                } catch (error: ErrnoException) {
                    throw IOException("Could not prepare the pending media copy", error)
                }
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled(cancellation)
                    val count = input.read(buffer)
                    if (count < 0) break
                    checkCancelled(cancellation)
                    output.write(buffer, 0, count)
                }
                checkCancelled(cancellation)
                output.fd.sync()
                ready = readIdentity(file).also {
                    if (!identity.sameFile(it)) throw IOException("Pending media copy was replaced")
                }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            // Android's FileOutputStream(FileDescriptor) can borrow rather than own this descriptor.
            try { Os.close(descriptor) }
            catch (error: ErrnoException) {
                if (error.errno != OsConstants.EBADF) {
                    val cleanup = IOException("Could not close the pending media copy", error)
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }

    @Throws(IOException::class)
    fun verifyReady() {
        requireOwned(file, ready ?: throw IOException("Media copy is incomplete"), checkVersion = true)
    }

    @Throws(IOException::class)
    fun publish(target: File) {
        verifyReady()
        if (file.parentFile != target.parentFile || SharedMediaFiles.requireInside(root, target) != target) {
            throw IOException("Media publication directory changed")
        }
        NativeCore.publishMediaFile(file.path, target.path)
    }

    /** An index failure must not delete a name subsequently replaced or modified by another writer. */
    @Throws(IOException::class)
    fun rollbackPublished(target: File) {
        removeOwned(target, ready ?: identity, checkVersion = true)
    }

    @Throws(IOException::class)
    override fun close() {
        removeOwned(file, identity, checkVersion = false)
    }

    private fun requireOwned(target: File, expected: Identity, checkVersion: Boolean) {
        if (SharedMediaFiles.requireInside(root, target) != target) throw IOException("Media directory changed")
        val actual = readIdentity(target)
        if (!expected.sameFile(actual) || (checkVersion && !expected.sameVersion(actual))) {
            throw IOException("Pending media copy was replaced or modified")
        }
    }

    private fun removeOwned(target: File, expected: Identity, checkVersion: Boolean) {
        val actual = try { readIdentity(target) }
        catch (failure: IOException) {
            if ((failure.cause as? ErrnoException)?.errno == OsConstants.ENOENT) return
            throw failure
        }
        if (!expected.sameFile(actual) || (checkVersion && !expected.sameVersion(actual))) {
            throw IOException("Refusing to remove media changed by another writer")
        }
        requireOwned(target, expected, checkVersion)
        if (!target.delete()) throw IOException("Could not remove the pending media copy")
    }

    private class Identity(stat: StructStat) {
        private val device = stat.st_dev
        private val inode = stat.st_ino
        private val size = stat.st_size
        private val modifiedSeconds = stat.st_mtim.tv_sec
        private val modifiedNanos = stat.st_mtim.tv_nsec

        init {
            if (!OsConstants.S_ISREG(stat.st_mode)) throw IOException("Pending media is not a regular file")
        }

        fun sameFile(other: Identity): Boolean = device == other.device && inode == other.inode
        fun sameVersion(other: Identity): Boolean = sameFile(other) && size == other.size &&
            modifiedSeconds == other.modifiedSeconds && modifiedNanos == other.modifiedNanos
    }

    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun create(root: File, directory: File): SharedMediaImportStage {
            val parent = SharedMediaFiles.requireInside(root, directory)
            if (!parent.isDirectory) throw IOException("Media import directory is unavailable")
            val pending = File.createTempFile(".nexbox-import-", ".pending", parent)
            try {
                return SharedMediaImportStage(root, pending, readIdentity(pending))
            } catch (failure: Throwable) {
                if (!pending.delete()) failure.addSuppressed(IOException("Could not remove the pending media copy"))
                throw failure
            }
        }

        @JvmStatic
        @Throws(InterruptedIOException::class)
        fun checkCancelled(cancellation: CancellationSignal?) {
            cancellation?.throwIfCanceled()
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Media import interrupted")
        }

        private fun readIdentity(file: File): Identity = try { Identity(Os.lstat(file.path)) }
        catch (failure: ErrnoException) { throw IOException("Could not inspect the pending media copy", failure) }
    }
}
