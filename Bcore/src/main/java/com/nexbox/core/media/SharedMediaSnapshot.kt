package com.nexbox.core.media

import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.ArrayDeque

/** One directory walk supplies both reconciliation input and the persisted root fingerprint. */
class SharedMediaSnapshot private constructor(
    val files: List<FileState>,
    val rootLastModified: Long,
    val fingerprint: Long
) {
    class FileState(
        val file: File,
        val canonicalPath: String,
        val mediaType: Int,
        val size: Long,
        val lastModified: Long
    ) {
        val modifiedSeconds: Long get() = maxOf(1L, lastModified / 1000L)

        fun matches(entry: NexBoxMediaEntry): Boolean =
            canonicalPath == entry.canonicalPath && size == entry.size && modifiedSeconds == entry.dateModified

        companion object {
            @JvmStatic
            @Throws(IOException::class)
            fun capture(file: File, mediaType: Int): FileState =
                FileState(file, file.canonicalPath, mediaType, file.length(), file.lastModified())
        }
    }

    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun capture(root: File, includeOrdinaryFiles: Boolean): SharedMediaSnapshot {
            if (!root.exists()) return SharedMediaSnapshot(emptyList(), 0L, 0L)
            val rootPath = root.canonicalPath + File.separator
            val rootModified = root.lastModified()
            val files = ArrayList<FileState>()
            val directories = ArrayDeque<File>()
            val visitedDirectories = HashSet<String>()
            val visitedFiles = HashSet<String>()
            var fingerprint = rootModified
            directories.push(root)
            while (directories.isNotEmpty()) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Media scan interrupted")
                val directory = directories.pop()
                if (!visitedDirectories.add(directory.canonicalPath)) continue
                val children = directory.listFiles() ?: throw IOException("Could not read gallery directory")
                for (child in children) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Media scan interrupted")
                    if (child.name.startsWith('.')) continue
                    val canonical = child.canonicalPath
                    if (!canonical.startsWith(rootPath)) continue
                    if (child.isDirectory) {
                        directories.push(child)
                    } else if (child.isFile && visitedFiles.add(canonical)) {
                        val mediaType = NexBoxMediaContract.mediaTypeForMime(NexBoxMediaContract.inferMimeType(child))
                        if (!includeOrdinaryFiles && mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_NONE) continue
                        val state = FileState(child, canonical, mediaType, child.length(), child.lastModified())
                        files.add(state)
                        fingerprint += state.lastModified * 31L + state.size * 17L + canonical.hashCode()
                    }
                }
            }
            return SharedMediaSnapshot(files, rootModified, fingerprint)
        }
    }
}
