package com.nexbox.app.util

import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Single-writer rolling files and separately bounded crash artifacts. Failures are observable. */
internal class LogFileStore(
    val directory: File,
    private val maxBytes: Long = 1024L * 1024,
    private val backups: Int = 3,
    private val crashLimit: Int = 10
) {
    val current = File(directory, "nexbox.log")
    private val clearBarrier = File(directory, "cleared-epoch")
    private val crashLock = processLocks.getOrPut(directory.canonicalPath) { Any() }
    init { require(maxBytes > 0 && backups > 0 && crashLimit > 0) }

    fun append(text: String) = appendBatch(listOf(text))

    @Synchronized fun appendBatch(texts: List<String>) {
        if (texts.isEmpty()) return
        ensureDirectory(directory)
        var length = current.length()
        var output: java.io.BufferedOutputStream? = null
        try {
            for (text in texts) {
                val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
                if (length > 0 && length + bytes.size > maxBytes) {
                    output?.close()
                    output = null
                    rotate()
                    length = 0
                }
                val stream = output ?: FileOutputStream(current, true).buffered().also { output = it }
                stream.write(bytes)
                length += bytes.size
            }
        } finally {
            output?.close()
        }
    }
    private fun rotate() {
        deleteIfPresent(File(directory, "nexbox.log.$backups"))
        for (index in backups - 1 downTo 1) {
            val from = File(directory, "nexbox.log.$index")
            if (from.exists() && !from.renameTo(File(directory, "nexbox.log.${index + 1}")))
                throw IOException("Could not rotate log backup $index")
        }
        if (!current.renameTo(File(directory, "nexbox.log.1"))) throw IOException("Could not rotate active log")
    }
    fun minimumEpoch(): Long = if (!clearBarrier.exists()) 0L else
        clearBarrier.readText().trim().toLongOrNull()?.takeIf { it >= 0 }
            ?: throw IOException("Invalid diagnostic clear barrier")

    fun writeCrash(text: String, pid: Int, timestamp: Long, epoch: Long = 0L): File? = withCrashLock {
        if (epoch < minimumEpoch()) return@withCrashLock null
        val crashes = File(directory, "crashes")
        ensureDirectory(crashes)
        val file = File(crashes, "crash-$timestamp-$pid-e$epoch-${UUID.randomUUID()}.txt")
        val pending = File(crashes, file.name + ".pending")
        try {
            FileOutputStream(pending).use { stream ->
                stream.write(LogPrivacy.clean(text).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { pending.delete() }
        crashFiles().dropLast(crashLimit).forEach(::deleteIfPresent)
        file
    }
    @Synchronized fun files(): List<File> =
        (backups downTo 1).map { File(directory, "nexbox.log.$it") }.filter { it.isFile } +
            listOf(current).filter { it.isFile } + withCrashLock {
                val barrier = minimumEpoch()
                crashFiles().filter { crashEpoch(it) >= barrier }
            }
    private fun crashFiles(): List<File> = listFilesChecked(File(directory, "crashes"))
        .filter { it.isFile && it.name.startsWith("crash-") && it.extension == "txt" }
        .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
    @Synchronized fun clear(beforeEpoch: Long? = null) = withCrashLock {
        if (beforeEpoch != null) {
            val pending = File(directory, "cleared-epoch.pending")
            pending.writeText(maxOf(beforeEpoch, minimumEpoch()).toString())
            Files.move(pending.toPath(), clearBarrier.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        (listOf(current) + (1..backups).map { File(directory, "nexbox.log.$it") }).forEach(::deleteIfPresent)
        // Fatal events captured after the clear request survive a delayed disk clear task.
        crashFiles().filter { beforeEpoch == null || crashEpoch(it) < beforeEpoch }.forEach(::deleteIfPresent)
    }
    private fun crashEpoch(file: File): Long = epochPattern.find(file.name)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    private fun <T> withCrashLock(action: () -> T): T = synchronized(crashLock) {
        ensureDirectory(directory)
        RandomAccessFile(File(directory, "crash.lock"), "rw").use { file ->
            file.channel.lock().use { action() }
        }
    }
    private fun deleteIfPresent(file: File) {
        if (file.exists() && !file.delete() && file.exists()) throw IOException("Could not delete diagnostic file ${file.name}")
    }
    private fun ensureDirectory(file: File) {
        if (!file.isDirectory && !file.mkdirs()) throw IOException("Could not create diagnostic directory")
    }

    companion object {
        private val processLocks = ConcurrentHashMap<String, Any>()
        private val epochPattern = Regex("-e([0-9]+)-")
        private fun listFilesChecked(directory: File): List<File> = directory.listFiles()?.toList()
            ?: if (directory.exists()) throw IOException("Could not enumerate diagnostic directory") else emptyList()
    }
}
