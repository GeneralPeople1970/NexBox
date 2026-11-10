package com.nexbox.core.media;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.utils.Slog;

/** Preserves legacy files and IDs with the existing durable migration journal. */
final class SharedMediaLegacyMigration {
    private static final String TAG = "NexBoxMediaStore";
    private static final int SHARED_USER_ID = NexBoxMediaContract.SHARED_USER_ID;
    private static boolean sLegacyImported;

    private SharedMediaLegacyMigration() {
    }

    /** Rebase the index before any missing-file cleanup can mistake moved media for deletions. */
    static void relocateStoragePathsLocked(SQLiteDatabase database) {
        if (BEnvironment.getLegacyRootMappings().isEmpty()) return;
        database.beginTransaction();
        try {
            String[] pathColumns = {NexBoxMediaContract.COL_DATA,
                    NexBoxMediaContract.COL_CANONICAL_PATH, NexBoxMediaContract.COL_ROOT_PATH};
            try (Cursor cursor = database.query(NexBoxMediaContract.TABLE_MEDIA,
                    new String[]{NexBoxMediaContract.COL_ID, pathColumns[0], pathColumns[1], pathColumns[2]},
                    null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    ContentValues values = new ContentValues();
                    for (int i = 0; i < pathColumns.length; i++) {
                        String oldPath = cursor.getString(i + 1);
                        String relocated = BEnvironment.relocateLegacyPath(oldPath);
                        if (oldPath != null && !oldPath.equals(relocated)) values.put(pathColumns[i], relocated);
                    }
                    if (values.size() > 0) database.update(NexBoxMediaContract.TABLE_MEDIA, values,
                            NexBoxMediaContract.COL_ID + "=?", new String[]{String.valueOf(cursor.getLong(0))});
                }
            }
            try (Cursor cursor = database.query(NexBoxMediaContract.TABLE_SCAN_STATE,
                    new String[]{NexBoxMediaContract.STATE_COL_USER_ID, NexBoxMediaContract.STATE_COL_ROOT_PATH},
                    null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    String oldPath = cursor.getString(1);
                    String relocated = BEnvironment.relocateLegacyPath(oldPath);
                    if (oldPath.equals(relocated)) continue;
                    ContentValues values = new ContentValues();
                    values.put(NexBoxMediaContract.STATE_COL_ROOT_PATH, relocated);
                    database.update(NexBoxMediaContract.TABLE_SCAN_STATE, values,
                            NexBoxMediaContract.STATE_COL_USER_ID + "=? AND "
                                    + NexBoxMediaContract.STATE_COL_ROOT_PATH + "=?",
                            new String[]{String.valueOf(cursor.getInt(0)), oldPath});
                }
            }
            SharedMediaMigrationJournal.relocateStoragePaths(database);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    /** Caller holds the process monitor and file lock, including while setting sLegacyImported. */
    static void prepareSharedStoreLocked() throws IOException {
        SharedMediaPaths.ensureRoots(SHARED_USER_ID);
        File marker = new File(SharedMediaPaths.getSharedMediaRoot(), ".nomedia");
        if (!marker.exists() && !marker.createNewFile()) throw new IOException("Could not protect the shared gallery from media scanning");
        SQLiteDatabase database = SharedMediaDatabase.getDatabaseLocked();
        if (!sLegacyImported) {
            migrateLegacyMediaLocked(database);
            sLegacyImported = true;
        }
    }

    static void migrateLegacyMediaLocked(SQLiteDatabase database) throws IOException {
        migrateLegacyMediaLocked(database, null);
    }

    static void migrateLegacyMediaLocked(SQLiteDatabase database, Integer onlyUserId) throws IOException {
        // Keep both the original files and original indexed IDs. A durable source manifest
        // prevents a deleted shared item from reappearing from its old backup on next scan.
        List<NexBoxMediaEntry> legacyEntries = SharedMediaIndex.queryEntriesLocked(database,
                NexBoxMediaContract.COL_USER_ID + "<>?", new String[]{String.valueOf(SHARED_USER_ID)}, null);
        for (NexBoxMediaEntry entry : legacyEntries) {
            if (onlyUserId != null && entry.getUserId() != onlyUserId) continue;
            File source = new File(entry.getFilePath());
            if (source.isFile()) migrateLegacyFileLocked(database, entry.getUserId(), source, entry);
        }
        File externalRoot = BEnvironment.getExternalVirtualRoot();
        if (externalRoot == null) return;
        File userRoot = new File(externalRoot, "storage/emulated");
        File[] userDirs = userRoot.listFiles();
        if (userDirs == null) {
            if (userRoot.exists()) throw new IOException("Legacy gallery directories are unreadable");
            return;
        }
        for (File userDir : userDirs) {
            int userId;
            try { userId = Integer.parseInt(userDir.getName()); }
            catch (NumberFormatException ignored) { continue; }
            if (onlyUserId != null && userId != onlyUserId) continue;
            if (userId < 0 || !userDir.isDirectory()
                    || !SharedMediaPaths.canonicalPath(userDir).equals(new File(SharedMediaPaths.canonicalPath(userRoot), userDir.getName()).getPath())) continue;
            for (String root : new String[]{"DCIM", "Pictures", "Movies", "Download"}) {
                for (File source : SharedMediaScanner.collectMediaFiles(new File(userDir, root),
                        MediaStore.Files.FileColumns.MEDIA_TYPE_NONE, "Download".equals(root))) {
                    migrateLegacyFileLocked(database, userId, source, null);
                }
            }
        }
    }

    private static void migrateLegacyFileLocked(SQLiteDatabase database, int userId, File source,
                                                NexBoxMediaEntry existing) throws IOException {
        File external = BEnvironment.getExternalVirtualRoot();
        if (external == null) throw new IOException("Legacy gallery storage is unavailable");
        String sourcePath = source.getCanonicalPath();
        String relative = null;
        String sourceKind = null;
        for (String legacyRoot : new String[]{NexBoxMediaContract.LEGACY_ROOT_IMAGES,
                NexBoxMediaContract.LEGACY_ROOT_VIDEOS, "DCIM/", "Pictures/", "Movies/",
                NexBoxMediaContract.ROOT_DOWNLOADS}) {
            String root = new File(BEnvironment.getExternalUserDir(userId), legacyRoot).getCanonicalPath() + File.separator;
            if (sourcePath.startsWith(root)) {
                relative = sourcePath.substring(root.length());
                sourceKind = legacyRoot.equals(NexBoxMediaContract.LEGACY_ROOT_IMAGES) ? "images"
                        : legacyRoot.equals(NexBoxMediaContract.LEGACY_ROOT_VIDEOS) ? "videos" : legacyRoot.replace("/", "");
                break;
            }
        }
        if (relative == null || !sourcePath.startsWith(external.getCanonicalPath() + File.separator)) {
            throw new IOException("Legacy media path is outside the container gallery");
        }
        if (SharedMediaMigrationJournal.contains(database, sourcePath)) return;
        int mediaType = existing != null ? existing.getMediaType()
                : NexBoxMediaContract.mediaTypeForMime(NexBoxMediaContract.inferMimeType(source));
        File root = "Download".equals(sourceKind) ? SharedMediaPaths.getDownloadsRoot()
                : mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ? SharedMediaPaths.getVideosRoot(SHARED_USER_ID) : SharedMediaPaths.getImagesRoot(SHARED_USER_ID);
        File target = SharedMediaPaths.requireSharedFile(new File(root, "Imported/user_" + userId + "/" + sourceKind + "/" + relative));
        SharedMediaPaths.ensureRootExists(target.getParentFile());
        // A crash can leave a completed copy before its database transaction commits.
        // Reuse only byte-identical copies; never overwrite an unrelated name collision.
        if (target.exists() && (SharedMediaIndex.findRowIdByCanonicalPathLocked(database, SHARED_USER_ID, SharedMediaPaths.canonicalPath(target)) != null
                || !SharedMediaFiles.sameContent(source, target))) target = SharedMediaPaths.uniqueFile(target.getParentFile(), target.getName());
        if (!target.exists()) {
            long size = source.length();
            long modified = source.lastModified();
            try (InputStream input = new FileInputStream(source)) { SharedMediaPublication.copyAtomically(input, target); }
            if (source.length() != size || source.lastModified() != modified || target.length() != size) {
                if (!target.delete()) Slog.w(TAG, "Could not remove an interrupted legacy copy");
                throw new IOException("Legacy media changed while being copied");
            }
            if (!target.setLastModified(modified)) Slog.w(TAG, "Could not preserve a migrated media timestamp");
        }
        final File migratedTarget = target;
        SharedMediaMigrationJournal.commit(database, sourcePath, target.getAbsolutePath(), () -> {
            if (existing != null) {
                ContentValues moved = new ContentValues();
                moved.put(NexBoxMediaContract.COL_USER_ID, SHARED_USER_ID);
                moved.put(NexBoxMediaContract.COL_DATA, migratedTarget.getAbsolutePath());
                moved.put(NexBoxMediaContract.COL_CANONICAL_PATH, migratedTarget.getCanonicalPath());
                moved.put(NexBoxMediaContract.COL_ROOT_PATH, SharedMediaPaths.scanRootFor(migratedTarget).getAbsolutePath());
                String relativePath = SharedMediaPaths.buildRelativePath(SHARED_USER_ID, migratedTarget, mediaType);
                moved.put(NexBoxMediaContract.COL_RELATIVE_PATH, relativePath);
                moved.put(NexBoxMediaContract.COL_BUCKET_DISPLAY_NAME, "NexBox");
                moved.put(NexBoxMediaContract.COL_BUCKET_ID, "nexbox".hashCode());
                database.update(NexBoxMediaContract.TABLE_MEDIA, moved,
                        NexBoxMediaContract.COL_ID + "=?", new String[]{String.valueOf(existing.getId())});
            } else SharedMediaIndex.upsertFileLocked(database, SHARED_USER_ID, migratedTarget, mediaType, null);
        });
    }
}
