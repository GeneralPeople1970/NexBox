package com.nexbox.core.media;

import android.database.sqlite.SQLiteDatabase;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.provider.MediaStore;
import android.text.TextUtils;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.nexbox.core.utils.Slog;

/** Reconciles one file snapshot per root and reads image/video metadata. */
final class SharedMediaScanner {
    private static final String TAG = "NexBoxMediaStore";
    private static final int SHARED_USER_ID = NexBoxMediaContract.SHARED_USER_ID;

    private SharedMediaScanner() {
    }

    static List<NexBoxMediaEntry> loadMediaEntries(boolean force) {
        return SharedMediaDatabase.withStoreLockUnchecked(() -> {
            SharedMediaLegacyMigration.prepareSharedStoreLocked();
            SQLiteDatabase database = SharedMediaDatabase.getDatabaseLocked();
            for (File root : SharedMediaPaths.getScanRoots()) reconcileRootLocked(database, root, force);
            return SharedMediaIndex.queryEntriesLocked(database,
                    NexBoxMediaContract.COL_USER_ID + "=? AND " + NexBoxMediaContract.COL_MEDIA_TYPE
                            + " IN (" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE + ","
                            + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ")",
                    new String[]{String.valueOf(SHARED_USER_ID)},
                    NexBoxMediaContract.COL_DATE_MODIFIED + " DESC, " + NexBoxMediaContract.COL_ID + " DESC");
        });
    }

    static NexBoxMediaEntry getEntry(int userId, long id) {
        if (id <= 0) return null;
        return SharedMediaDatabase.withStoreLockUnchecked(() -> {
            SharedMediaLegacyMigration.prepareSharedStoreLocked();
            SQLiteDatabase database = SharedMediaDatabase.getDatabaseLocked();
            NexBoxMediaEntry entry = SharedMediaIndex.getIndexedEntryLocked(database, id);
            if (entry == null) return null;
            File file = SharedMediaPaths.requireSharedFile(new File(entry.getFilePath()));
            if (!file.isFile() || !file.getPath().equals(entry.getCanonicalPath())) {
                database.delete(NexBoxMediaContract.TABLE_MEDIA, NexBoxMediaContract.COL_ID + "=?",
                        new String[]{String.valueOf(id)});
                SharedMediaIndex.refreshStoredState(SHARED_USER_ID, entry.getMediaType());
                return null;
            }
            SharedMediaSnapshot.FileState state = SharedMediaSnapshot.FileState.capture(file, entry.getMediaType());
            if (state.matches(entry)) return entry;
            NexBoxMediaEntry updated = SharedMediaIndex.upsertFileSnapshotLocked(database, SHARED_USER_ID, state,
                    entry.getMimeType(), null, entry, null, 0L);
            SharedMediaIndex.refreshStoredState(SHARED_USER_ID, entry.getMediaType());
            return updated;
        });
    }

    static void ensureFresh(int userId, int mediaType) {
        SharedMediaDatabase.withStoreLockUnchecked(() -> {
            SharedMediaLegacyMigration.prepareSharedStoreLocked();
            SQLiteDatabase database = SharedMediaDatabase.getDatabaseLocked();
            for (File root : SharedMediaPaths.getScanRoots()) reconcileRootLocked(database, root, false);
            return null;
        });
    }

    static void reconcileRootLocked(SQLiteDatabase database, File root, boolean force) throws IOException {
        SharedMediaPaths.ensureRootExists(root);
        SharedMediaSnapshot snapshot = SharedMediaSnapshot.capture(root, SharedMediaPaths.isDownloadsRoot(root));
        SharedMediaIndex.RootState currentState = new SharedMediaIndex.RootState(snapshot.getRootLastModified(), snapshot.getFiles().size(),
                snapshot.getFingerprint());
        if (!force && currentState.sameAs(SharedMediaIndex.loadStoredStateLocked(database, SHARED_USER_ID, root.getAbsolutePath()))) return;
        Map<String, NexBoxMediaEntry> indexed = new HashMap<>();
        for (NexBoxMediaEntry entry : SharedMediaIndex.queryEntriesLocked(database,
                NexBoxMediaContract.COL_USER_ID + "=? AND " + NexBoxMediaContract.COL_ROOT_PATH + "=?",
                new String[]{String.valueOf(SHARED_USER_ID), root.getAbsolutePath()}, null)) {
            indexed.put(entry.getCanonicalPath(), entry);
        }
        database.beginTransaction();
        try {
            for (SharedMediaSnapshot.FileState file : snapshot.getFiles()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Media scan interrupted");
                NexBoxMediaEntry existing = indexed.remove(file.getCanonicalPath());
                if (existing == null || !file.matches(existing)) {
                    SharedMediaIndex.upsertFileSnapshotLocked(database, SHARED_USER_ID, file, null, null, existing, null, 0L);
                }
            }
            for (NexBoxMediaEntry missing : indexed.values()) {
                database.delete(NexBoxMediaContract.TABLE_MEDIA, NexBoxMediaContract.COL_ID + "=?",
                        new String[]{String.valueOf(missing.getId())});
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Media scan interrupted");
            // Saving this exact snapshot cannot mark an unrelated, later filesystem walk as indexed.
            SharedMediaIndex.saveRootStateLocked(database, SHARED_USER_ID, root.getAbsolutePath(), currentState);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    static List<File> collectMediaFiles(File root, int mediaType, boolean includeOrdinaryFiles) throws IOException {
        List<File> files = new ArrayList<>();
        for (SharedMediaSnapshot.FileState state : SharedMediaSnapshot.capture(root, includeOrdinaryFiles).getFiles()) {
            if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_NONE || mediaType == state.getMediaType()) {
                files.add(state.getFile());
            }
        }
        return files;
    }

    static MediaMetadata readMetadata(File file, int mediaType, String forcedMimeType) {
        MediaMetadata metadata = new MediaMetadata();
        metadata.mimeType = !TextUtils.isEmpty(forcedMimeType) ? forcedMimeType : NexBoxMediaContract.inferMimeType(file);
        if (TextUtils.isEmpty(metadata.mimeType)) metadata.mimeType = "application/octet-stream";
        if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            metadata.width = Math.max(options.outWidth, 0);
            metadata.height = Math.max(options.outHeight, 0);
            return metadata;
        }

        if (mediaType != MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) return metadata;

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            metadata.width = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            metadata.height = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            metadata.duration = parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (Exception e) {
            Slog.w(TAG, "Failed to read video metadata for " + file.getName() + ": " + e.getMessage(), e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        return metadata;
    }

    private static int parseInt(String value) {
        if (TextUtils.isEmpty(value)) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static long parseLong(String value) {
        if (TextUtils.isEmpty(value)) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    static final class MediaMetadata {
        String mimeType;
        int width;
        int height;
        long duration;
    }
}
