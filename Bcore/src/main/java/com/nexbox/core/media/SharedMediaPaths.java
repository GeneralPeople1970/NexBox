package com.nexbox.core.media;

import android.provider.MediaStore;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import com.nexbox.core.core.env.BEnvironment;

/** Shared roots, filename policy and canonical path boundaries; no index or migration state. */
final class SharedMediaPaths {
    private SharedMediaPaths() {
    }

    static File getSharedMediaRoot() {
        return new File(BEnvironment.getVirtualRoot(), "shared-media");
    }

    static File getImagesRoot(int userId) {
        return new File(getSharedMediaRoot(), NexBoxMediaContract.ROOT_IMAGES);
    }

    static File getVideosRoot(int userId) {
        return new File(getSharedMediaRoot(), NexBoxMediaContract.ROOT_VIDEOS);
    }

    static File getDownloadsRoot() {
        return new File(getSharedMediaRoot(), NexBoxMediaContract.ROOT_DOWNLOADS);
    }

    static File[] getScanRoots() {
        File[] roots = new File[]{new File(getSharedMediaRoot(), "DCIM"),
                new File(getSharedMediaRoot(), "Pictures"), new File(getSharedMediaRoot(), "Movies"), getDownloadsRoot()};
        String base = canonicalPath(getSharedMediaRoot()) + File.separator;
        for (File root : roots) if (!canonicalPath(root).startsWith(base)) {
            throw new SecurityException("Media directory leaves the shared gallery");
        }
        return roots;
    }

    static File scanRootFor(File file) {
        String path = canonicalPath(file);
        for (File root : getScanRoots()) {
            if (path.startsWith(canonicalPath(root) + File.separator)) return root;
        }
        throw new SecurityException("Media path is outside the shared gallery");
    }

    static File requireSharedFile(File file) throws IOException {
        File canonical = SharedMediaFiles.requireInside(getSharedMediaRoot(), file);
        scanRootFor(canonical);
        return canonical;
    }

    static void ensureRoots(int userId) {
        ensureRootExists(getImagesRoot(userId));
        ensureRootExists(getVideosRoot(userId));
        for (File root : getScanRoots()) ensureRootExists(root);
    }

    static void ensureRootExists(File root) {
        if (root == null) throw new IllegalStateException("Missing gallery directory");
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) throw new IllegalStateException("Gallery directory is unavailable");
    }

    static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    static boolean isDownloadsRoot(File root) {
        return root != null && canonicalPath(root).equals(canonicalPath(getDownloadsRoot()));
    }

    static String guessMimeFromName(String displayName) {
        if (TextUtils.isEmpty(displayName)) {
            return null;
        }
        String extension = MimeTypeMap.getFileExtensionFromUrl(displayName);
        if (TextUtils.isEmpty(extension)) {
            int dotIndex = displayName.lastIndexOf('.');
            if (dotIndex >= 0 && dotIndex < displayName.length() - 1) {
                extension = displayName.substring(dotIndex + 1);
            }
        }
        if (TextUtils.isEmpty(extension)) {
            return null;
        }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT));
    }

    static String buildSafeDisplayName(String displayName, String mimeType, int mediaType) {
        String cleaned = TextUtils.isEmpty(displayName) ? "media_" + System.currentTimeMillis() : displayName.trim();
        cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|]+", "_");
        if (TextUtils.isEmpty(cleaned) || cleaned.equals(".") || cleaned.equals("..")) {
            cleaned = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    ? "video_" + System.currentTimeMillis()
                    : mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                    ? "image_" + System.currentTimeMillis() : "file_" + System.currentTimeMillis();
        }
        if (cleaned.startsWith(".")) cleaned = "media" + cleaned;
        if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_NONE) return cleaned;
        if (NexBoxMediaContract.mediaTypeForMime(guessMimeFromName(cleaned)) != mediaType) {
            String extension = TextUtils.isEmpty(mimeType) ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
            if (TextUtils.isEmpty(extension)) extension = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ? "mp4" : "jpg";
            cleaned = cleaned + "." + extension;
        }
        return cleaned;
    }

    static File uniqueFile(File directory, String displayName) {
        File candidate = new File(directory, displayName);
        if (!candidate.exists()) {
            return candidate;
        }
        String baseName = displayName;
        String extension = "";
        int dotIndex = displayName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = displayName.substring(0, dotIndex);
            extension = displayName.substring(dotIndex);
        }
        int suffix = 1;
        while (candidate.exists()) {
            candidate = new File(directory, baseName + "_" + suffix + extension);
            suffix++;
        }
        return candidate;
    }

    static String titleFromDisplayName(String displayName) {
        if (TextUtils.isEmpty(displayName)) {
            return "";
        }
        int dotIndex = displayName.lastIndexOf('.');
        return dotIndex > 0 ? displayName.substring(0, dotIndex) : displayName;
    }

    static String normalizeRelativePath(String relativePath, int mediaType) {
        String fallback = NexBoxMediaContract.defaultRelativePathForMediaType(mediaType);
        if (TextUtils.isEmpty(relativePath)) {
            return fallback;
        }
        String normalized = relativePath.replace('\\', '/');
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        boolean downloads = normalized.startsWith(NexBoxMediaContract.ROOT_DOWNLOADS);
        boolean visualDirectory = normalized.startsWith("DCIM/") || normalized.startsWith("Pictures/")
                || normalized.startsWith("Movies/");
        if ((!downloads && (!visualDirectory || mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_NONE))
                || normalized.indexOf('\0') >= 0 || normalized.contains("//")) {
            throw new IllegalArgumentException("Unsupported shared file directory");
        }
        for (String segment : normalized.split("/")) {
            if (segment.startsWith(".")) throw new IllegalArgumentException("Hidden or relative file directories are not supported");
        }
        return normalized;
    }

    static File resolveTargetDir(int userId, String relativePath, int mediaType) throws IOException {
        String normalized = normalizeRelativePath(relativePath, mediaType);
        File directory = SharedMediaFiles.requireInside(getSharedMediaRoot(), new File(getSharedMediaRoot(), normalized));
        scanRootFor(new File(directory, ".path-check"));
        return directory;
    }

    static String buildRelativePath(int userId, File file, int mediaType) {
        File root = getSharedMediaRoot();
        String rootPath = root.getAbsolutePath();
        String filePath = file.getParentFile() != null ? file.getParentFile().getAbsolutePath() : rootPath;
        String suffix = filePath.startsWith(rootPath) ? filePath.substring(rootPath.length()) : "";
        if (suffix.startsWith(File.separator)) {
            suffix = suffix.substring(1);
        }
        String base = "";
        if (TextUtils.isEmpty(suffix)) {
            return base;
        }
        return base + suffix.replace(File.separatorChar, '/') + "/";
    }

    static String bucketDisplayName(String relativePath) {
        if (TextUtils.isEmpty(relativePath)) {
            return "NexBox";
        }
        String normalized = relativePath;
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    static int bucketId(String relativePath) {
        return bucketDisplayName(relativePath).toLowerCase(Locale.ROOT).hashCode();
    }
}
