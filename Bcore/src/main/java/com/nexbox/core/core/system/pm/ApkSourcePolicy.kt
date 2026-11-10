package com.nexbox.core.core.system.pm

import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile

/** Archive/source checks shared by the host and the checked server importer. */
class ApkSourcePolicy private constructor() {
    companion object {
        @JvmStatic
        fun sourceError(fromSystem: Boolean, fromStorage: Boolean, fromUri: Boolean): String? = when {
            fromSystem == fromStorage -> "Select exactly one installed-package or APK-file source"
            fromSystem && fromUri -> "A document URI cannot be an installed-package source"
            else -> null
        }

        @JvmStatic
        @Throws(IOException::class)
        fun isApksBundle(source: File): Boolean = ZipFile(source).use { archive ->
            // Real APKs can embed Dynamite APKs. A root manifest takes precedence over
            // both filename and nested entries, which must never be treated as splits.
            val manifest = archive.getEntry("AndroidManifest.xml")
            if (manifest != null && !manifest.isDirectory) return@use false
            val name = source.name.lowercase(Locale.ROOT)
            if (name.endsWith(".apks") || name.endsWith(".xapk") || name.endsWith(".apkm")) return@use true
            if (archive.getEntry("toc.pb") != null) return@use true
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory && entry.name.lowercase(Locale.ROOT).endsWith(".apk")) return@use true
            }
            false
        }

        @JvmStatic
        fun isArchiveFileName(source: String): Boolean {
            val name = source.lowercase(Locale.ROOT)
            return name.endsWith(".apk") || name.endsWith(".apks") || name.endsWith(".xapk") || name.endsWith(".apkm")
        }
    }
}
