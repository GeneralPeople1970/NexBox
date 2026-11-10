package com.nexbox.core.core.system.pm

import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.content.pm.ApplicationInfo
import com.nexbox.core.NexBoxCore
import com.nexbox.core.core.env.BEnvironment
import com.nexbox.core.entity.pm.InstallOption
import com.nexbox.core.utils.Slog
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** The server owns all imported bytes; no staging path or mutable options cross Binder. */
class PreparedCloneSource private constructor(
    @JvmField val baseApk: File,
    @JvmField val parseSource: File,
    @JvmField val packageName: String,
    @JvmField val fromSystem: Boolean,
    @JvmField val splitCount: Int,
    private val root: File?,
    private val versions: List<FileVersion>,
    private val activeCheck: () -> Unit
) : AutoCloseable {
    private val expectedPaths = versions.map { it.path }.toSet()
    private val cluster = if (!fromSystem && parseSource != baseApk) parseSource.toPath() else null
    private val clusterIdentity = cluster?.let {
        Files.readAttributes(it, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).also { attributes ->
            if (!attributes.isDirectory) throw IOException("The prepared APK set is not a directory")
        }
    }

    fun installOption(): InstallOption = if (fromSystem) InstallOption.installBySystem()
        else InstallOption.installByStorage()

    @Throws(IOException::class)
    fun checkActive() = activeCheck()

    @Throws(IOException::class)
    fun verifyUnchanged() {
        checkActive()
        verifyCluster()
        for (version in versions) version.verify(activeCheck)
        verifyCluster()
        checkActive()
    }

    @Throws(IOException::class)
    fun verifyInstalledSource(application: ApplicationInfo) {
        checkActive()
        if (!fromSystem) throw IOException("This preparation is not an installed-package source")
        val current = (listOfNotNull(application.sourceDir) + application.splitSourceDirs.orEmpty())
            .map { File(it).canonicalFile.toPath() }.toSet()
        if (current != expectedPaths) {
            throw IOException("The host APK/split set changed; select the application again")
        }
    }

    private fun verifyCluster() {
        val directory = cluster ?: return
        val current = Files.readAttributes(directory, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!current.isDirectory || current.fileKey() != clusterIdentity?.fileKey()) {
            throw IOException("The prepared APK directory changed")
        }
        val files = directory.toFile().listFiles() ?: throw IOException("Cannot inspect the prepared APK set")
        if (files.map { it.toPath() }.toSet() != expectedPaths) {
            throw IOException("The prepared APK/split set changed; select the source again")
        }
    }

    override fun close() {
        root?.let { removeOwnedTree(it) }
    }

    internal class FileVersion(file: File, fingerprint: Boolean, checkActive: () -> Unit) {
        val path: Path = file.toPath()
        private val attributes = readAttributes(path)
        private val checksum = if (fingerprint) digest(file, checkActive) else null

        init {
            if (!matches(readAttributes(path))) throw IOException("The APK changed while preparing it")
        }

        fun verify(checkActive: () -> Unit) {
            checkActive()
            val current = readAttributes(path)
            if (!matches(current) || (checksum != null && !MessageDigest.isEqual(checksum, digest(path.toFile(), checkActive)))
                || !matches(readAttributes(path))) {
                throw IOException("The prepared APK changed; select the source again")
            }
            checkActive()
        }

        private fun matches(current: BasicFileAttributes): Boolean = attributes.size() == current.size() &&
            attributes.lastModifiedTime() == current.lastModifiedTime() && attributes.fileKey() == current.fileKey()
    }

    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun prepare(source: String, option: InstallOption, checkActive: () -> Unit): PreparedCloneSource =
            prepare(source, option, checkActive, null)

        @JvmStatic
        @Throws(IOException::class)
        fun prepare(source: String, option: InstallOption, checkActive: () -> Unit,
                    cancellation: CancellationSignal?): PreparedCloneSource {
            val fromSystem = option.isFlag(InstallOption.FLAG_SYSTEM)
            val fromUri = option.isFlag(InstallOption.FLAG_URI_FILE)
            require(source.isNotBlank()) { "An installation source is required" }
            val sourceError = ApkSourcePolicy.sourceError(fromSystem,
                option.isFlag(InstallOption.FLAG_STORAGE), fromUri)
            require(sourceError == null) { sourceError.orEmpty() }
            checkActive()
            val cache = BEnvironment.getCacheDir()
            check(cache.isDirectory || cache.mkdirs()) { "Cannot create installation cache" }
            val root = if (fromSystem) null else Files.createTempDirectory(cache.toPath(), "clone-session-").toFile()
            var accepted = false
            try {
                val staged = if (fromSystem) File(source).canonicalFile else File(checkNotNull(root), "source.apk").also { target ->
                    if (fromUri) {
                        NexBoxCore.getContext().contentResolver.openAssetFileDescriptor(Uri.parse(source), "r", cancellation)
                            .use { descriptor ->
                                if (descriptor == null) throw IOException("The document provider returned no data")
                                descriptor.createInputStream().use { copyToStaging(it, target, checkActive) }
                            }
                    } else {
                        File(source).inputStream().use { copyToStaging(it, target, checkActive) }
                    }
                }
                checkActive()
                val bundle = if (ApkSourcePolicy.isApksBundle(staged)) {
                    require(!fromSystem) { "An installed-package source cannot be an APK bundle" }
                    val abis = if (NexBoxCore.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
                    ApkBundle.prepare(staged, checkNotNull(root), abis, object : ApkBundle.Progress {
                        override fun step(stage: String, details: String) {
                            checkActive()
                            Slog.i("Install", "action=prepareClone stage=$stage $details")
                        }
                        override fun checkCancelled() = checkActive()
                    })
                } else null
                val base = bundle?.baseApk ?: staged
                val parseSource = bundle?.packageDirectory ?: staged
                val manager = NexBoxCore.getPackageManager()
                val info = manager.getPackageArchiveInfo(base.path, 0)
                    ?: throw IOException("Cannot read this APK; import the complete APK/split set")
                val packageName = info.packageName
                require(!packageName.isNullOrBlank() && packageName != NexBoxCore.getHostPkg()) {
                    "Cannot clone an invalid package or NexBox itself"
                }
                require(bundle == null || packageName == bundle.packageName) { "The bundle package changed" }
                val files = if (fromSystem) {
                    val installed = manager.getPackageInfo(packageName, 0).applicationInfo
                        ?: throw IOException("The host application no longer exists")
                    require(base == File(checkNotNull(installed.sourceDir)).canonicalFile) {
                        "The source no longer matches the host installation"
                    }
                    listOf(base) + installed.splitSourceDirs.orEmpty().map { File(it).canonicalFile }
                } else if (bundle != null) {
                    val parts = bundle.packageDirectory.listFiles()
                        ?: throw IOException("Cannot inspect the prepared APK set")
                    parts.sortedBy { it.name }
                } else listOf(base)
                // Imported bytes are untrusted even in private cache. Hash the selected set,
                // then recheck it at commit in addition to Android's signature verification.
                val versions = files.map { checkActive(); FileVersion(it, !fromSystem, checkActive) }
                checkActive()
                val completeSource = if (fromSystem && files.size > 1) base.parentFile else parseSource
                val prepared = PreparedCloneSource(base, completeSource, packageName, fromSystem,
                    bundle?.splitCount ?: (files.size - 1), root, versions, checkActive)
                accepted = true
                return prepared
            } finally {
                if (!accepted && root != null) removeOwnedTree(root)
            }
        }

        private fun readAttributes(path: Path): BasicFileAttributes {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!attributes.isRegularFile) throw IOException("The prepared APK is not a regular file")
            return attributes
        }

        private fun copyToStaging(input: InputStream, target: File, checkActive: () -> Unit) {
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var bytes = 0L
                while (true) {
                    checkActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    checkActive()
                    bytes += read
                    if (bytes > ApkBundle.MAX_DOCUMENT_BYTES) throw IOException("The APK document exceeds the 4 GiB import limit")
                    output.write(buffer, 0, read)
                }
            }
        }

        private fun digest(file: File, checkActive: () -> Unit): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest()
        }

        private fun removeOwnedTree(root: File) {
            try {
                if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return
                // walkFileTree does not follow symlinks; cleanup can never descend outside
                // this freshly allocated session directory, including after a failed import.
                Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }
                    override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                        if (error != null) throw error
                        Files.delete(directory)
                        return FileVisitResult.CONTINUE
                    }
                })
            } catch (error: IOException) {
                Slog.w("Install", "action=releaseCloneSession success=false", error)
            }
        }
    }
}
