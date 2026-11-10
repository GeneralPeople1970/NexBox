package com.nexbox.core.media;

import com.nexbox.core.utils.OperationLog;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.text.TextUtils;
import java.io.File;
import java.io.IOException;
import java.util.List;
import com.nexbox.core.utils.Slog;

/** Stable provider/guest API; collaborators share one store lock and database lifecycle. */
public final class NexBoxMediaStore {
    private static final String TAG = "NexBoxMediaStore";
    private static final int SHARED_USER_ID = NexBoxMediaContract.SHARED_USER_ID;
    private static Handler sWriterHandler;

    private NexBoxMediaStore() {
    }

    public static File getSharedMediaRoot() {
        return SharedMediaPaths.getSharedMediaRoot();
    }

    public static File getImagesRoot(int userId) {
        return SharedMediaPaths.getImagesRoot(userId);
    }

    public static File getVideosRoot(int userId) {
        return SharedMediaPaths.getVideosRoot(userId);
    }

    public static File getDownloadsRoot() {
        return SharedMediaPaths.getDownloadsRoot();
    }

    public static NexBoxMediaEntry getEntry(int userId, long id) {
        return SharedMediaScanner.getEntry(userId, id);
    }

    public static NexBoxMediaEntry importMedia(Context context, int userId, Uri sourceUri, String mimeHint) throws IOException {
        return SharedMediaPublication.importMedia(context, userId, sourceUri, mimeHint);
    }

    public static NexBoxMediaEntry importMedia(Context context, int userId, Uri sourceUri, String mimeHint,
                                               CancellationSignal cancellation) throws IOException {
        return SharedMediaPublication.importMedia(context, userId, sourceUri, mimeHint, cancellation);
    }

    public static Uri insert(int userId, Uri uri, ContentValues values, NexBoxMediaOwner owner) throws IOException {
        return SharedMediaPublication.insert(userId, uri, values, owner);
    }

    public static void removeUser(int userId) {
        // BUserManagerService calls this BEFORE removing the old external user tree.
        // A failed copy aborts that cleanup instead of losing the only copy of old photos.
        SharedMediaDatabase.withStoreLockUnchecked(() -> {
            SharedMediaLegacyMigration.migrateLegacyMediaLocked(SharedMediaDatabase.getDatabaseLocked(), userId);
            return null;
        });
    }

    /** Preserve legacy user files before enabling shared filesystem aliases. */
    public static void prepareSharedStorage() {
        SharedMediaDatabase.withStoreLockUnchecked(() -> {
            SharedMediaLegacyMigration.prepareSharedStoreLocked();
            return null;
        });
    }

    public static List<NexBoxMediaEntry> listMediaEntries(int userId) {
        return SharedMediaScanner.loadMediaEntries(false);
    }

    /** Explicit reconciliation and its list result share one filesystem snapshot and store lock. */
    public static List<NexBoxMediaEntry> refreshMediaEntries(int userId) {
        return SharedMediaScanner.loadMediaEntries(true);
    }

    public static void rescanUser(int userId) {
        OperationLog.call(TAG, "rescanMedia", "library=shared", () -> SharedMediaDatabase.withStoreLockUnchecked(() -> {
            SharedMediaLegacyMigration.prepareSharedStoreLocked();
            SQLiteDatabase database = SharedMediaDatabase.getDatabaseLocked();
            for (File root : SharedMediaPaths.getScanRoots()) SharedMediaScanner.reconcileRootLocked(database, root, true);
            return null;
        }));
    }

    public static Cursor query(int userId, Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        int mediaType = SharedMediaIndex.resolveMediaType(uri);
        long id = NexBoxMediaContract.getIdFromUri(uri);
        if (id > 0) SharedMediaScanner.getEntry(userId, id);
        else SharedMediaScanner.ensureFresh(userId, mediaType);
        synchronized (SharedMediaDatabase.DB_LOCK) {
            SQLiteDatabase database = SharedMediaDatabase.getDatabaseLocked();
            String finalSelection = SharedMediaIndex.buildSelection(uri, userId, mediaType, selection);
            String[] finalSelectionArgs = SharedMediaIndex.buildSelectionArgs(uri, userId, selectionArgs);
            String orderBy = TextUtils.isEmpty(sortOrder)
                    ? NexBoxMediaContract.COL_DATE_MODIFIED + " DESC, " + NexBoxMediaContract.COL_ID + " DESC"
                    : sortOrder;
            try {
                return database.query(
                        NexBoxMediaContract.TABLE_MEDIA,
                        SharedMediaIndex.normalizeProjection(projection),
                        finalSelection,
                        finalSelectionArgs,
                        null,
                        null,
                        orderBy
                );
            } catch (Throwable firstError) {
                Slog.w(TAG, "Media query fallback for projection/selection: " + firstError.getMessage(), firstError);
                try {
                    List<NexBoxMediaEntry> entries = SharedMediaIndex.queryEntriesLocked(database, finalSelection, finalSelectionArgs, null);
                    return SharedMediaIndex.buildCompatCursor(projection, entries);
                } catch (Throwable secondError) {
                    Slog.w(TAG, "Media query broad fallback: " + secondError.getMessage(), secondError);
                    String broadSelection = SharedMediaIndex.buildSelection(uri, userId, mediaType, null);
                    List<NexBoxMediaEntry> entries = SharedMediaIndex.queryEntriesLocked(database, broadSelection, null, null);
                    return SharedMediaIndex.buildCompatCursor(projection, entries);
                }
            }
        }
    }

    public static int update(int userId, Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return SharedMediaDatabase.withStoreLockUnchecked(() -> updateLocked(uri, values, selection, selectionArgs));
    }

    private static int updateLocked(Uri uri, ContentValues values, String selection, String[] selectionArgs) throws IOException {
        SharedMediaLegacyMigration.prepareSharedStoreLocked();
        int mediaType = SharedMediaIndex.resolveMediaType(uri);
        synchronized (SharedMediaDatabase.DB_LOCK) {
            SQLiteDatabase database = SharedMediaDatabase.getDatabaseLocked();
            String finalSelection = SharedMediaIndex.buildSelection(uri, SHARED_USER_ID, mediaType, selection);
            String[] finalSelectionArgs = SharedMediaIndex.buildSelectionArgs(uri, SHARED_USER_ID, selectionArgs);
            // Never let guest metadata rewrite owner provenance, _data, user_id, canonical_path or the row id.
            // Those fields otherwise turn a private media provider into an arbitrary file provider.
            ContentValues safeValues = new ContentValues();
            for (String key : new String[]{NexBoxMediaContract.COL_IS_PENDING,
                    NexBoxMediaContract.COL_DATE_TAKEN, NexBoxMediaContract.COL_TITLE}) {
                if (values.containsKey(key)) safeValues.put(key, values.getAsString(key));
            }
            if (safeValues.size() == 0) return 0;
            int updated = database.update(NexBoxMediaContract.TABLE_MEDIA, safeValues, finalSelection, finalSelectionArgs);
            if (updated > 0) {
                SharedMediaIndex.refreshStoredState(SHARED_USER_ID, mediaType);
                notifyChanged();
            }
            return updated;
        }
    }

    public static int delete(int userId, Uri uri, String selection, String[] selectionArgs) {
        return OperationLog.call(TAG, "deleteMedia", "library=shared", () -> SharedMediaDatabase.withStoreLockUnchecked(() -> {
            SharedMediaLegacyMigration.prepareSharedStoreLocked();
            int mediaType = SharedMediaIndex.resolveMediaType(uri);
            synchronized (SharedMediaDatabase.DB_LOCK) {
                SQLiteDatabase database = SharedMediaDatabase.getDatabaseLocked();
                String finalSelection = SharedMediaIndex.buildSelection(uri, userId, mediaType, selection);
                String[] finalSelectionArgs = SharedMediaIndex.buildSelectionArgs(uri, userId, selectionArgs);
                List<NexBoxMediaEntry> entries = SharedMediaIndex.queryEntriesLocked(database, finalSelection, finalSelectionArgs, null);
                int deleted = 0;
                for (NexBoxMediaEntry entry : entries) {
                    File file = SharedMediaPaths.requireSharedFile(new File(entry.getFilePath()));
                    // Do not report success for an undeleted file or recurse into directories.
                    if (file.isDirectory() || (file.exists() && !file.delete())) continue;
                    deleted += database.delete(NexBoxMediaContract.TABLE_MEDIA,
                            NexBoxMediaContract.COL_ID + "=?", new String[]{String.valueOf(entry.getId())});
                }
                if (deleted > 0) {
                    SharedMediaIndex.refreshStoredState(SHARED_USER_ID, mediaType);
                    notifyChanged();
                }
                return deleted;
            }
        }));
    }

    public static ParcelFileDescriptor openFile(int userId, Uri uri, String mode) throws IOException {
        return SharedMediaDatabase.withStoreLock(() -> openFileLocked(userId, uri, mode));
    }

    private static ParcelFileDescriptor openFileLocked(int userId, Uri uri, String mode) throws IOException {
        NexBoxMediaEntry entry = getEntryForUri(userId, uri);
        if (entry == null) {
            throw new IOException("Media entry not found");
        }
        File file = SharedMediaPaths.requireSharedFile(new File(entry.getFilePath()));
        if (!file.exists()) {
            throw new IOException("Media file is missing");
        }
        int flags = ParcelFileDescriptor.parseMode(mode);
        if ((flags & ParcelFileDescriptor.MODE_WRITE_ONLY) == 0) return ParcelFileDescriptor.open(file, flags);
        MediaConditions.exposeWriter(SharedMediaDatabase.getDatabaseLocked(), file);
        return ParcelFileDescriptor.open(file, flags, writerHandler(), error -> {
            if (error != null) Slog.w(TAG, "Media write closed with an error", error);
            try {
                SharedMediaDatabase.withStoreLock(() -> {
                    if (file.isFile()) {
                        SharedMediaIndex.upsertFileLocked(SharedMediaDatabase.getDatabaseLocked(), SHARED_USER_ID, file, entry.getMediaType(), entry.getMimeType());
                        SharedMediaIndex.refreshStoredState(SHARED_USER_ID, entry.getMediaType());
                        notifyChanged();
                    }
                    return null;
                });
            } catch (Exception failure) {
                Slog.e(TAG, "Could not index completed media write", failure);
            }
        });
    }

    /** FileProvider and the document bridge must share descriptor-issuance and namespace exclusion. */
    public static boolean isSharedPath(File file) throws IOException {
        String path = file.getCanonicalPath();
        String root = SharedMediaPaths.getSharedMediaRoot().getCanonicalPath();
        return path.equals(root) || path.startsWith(root + File.separator);
    }

    public static ParcelFileDescriptor openSharedPath(File file, int flags) throws IOException {
        return SharedMediaDatabase.withStoreLock(() -> {
            File checked = SharedMediaPaths.requireSharedFile(file);
            if ((flags & ParcelFileDescriptor.MODE_WRITE_ONLY) != 0) {
                MediaConditions.exposeWriter(SharedMediaDatabase.getDatabaseLocked(), checked);
            }
            return ParcelFileDescriptor.open(checked, flags);
        });
    }

    public static int deleteSharedPath(File file) throws IOException {
        return SharedMediaDatabase.withStoreLock(() -> {
            File checked = SharedMediaPaths.requireSharedFile(file);
            if (!checked.delete()) return 0;
            SharedMediaDatabase.getDatabaseLocked().delete(NexBoxMediaContract.TABLE_MEDIA,
                    NexBoxMediaContract.COL_CANONICAL_PATH + "=?", new String[]{checked.getCanonicalPath()});
            notifyChanged();
            return 1;
        });
    }

    public static String getType(int userId, Uri uri) {
        NexBoxMediaEntry entry = getEntryForUri(userId, uri);
        if (entry == null) {
            if (NexBoxMediaContract.getIdFromUri(uri) > 0) return null;
            if (NexBoxMediaContract.isDownloadsUri(uri)) return "vnd.android.cursor.dir/download";
            int mediaType = SharedMediaIndex.resolveMediaType(uri);
            if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) {
                return "vnd.android.cursor.dir/image";
            }
            if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                return "vnd.android.cursor.dir/video";
            }
            return "vnd.android.cursor.dir/file";
        }
        return entry.getMimeType();
    }

    private static NexBoxMediaEntry getEntryForUri(int userId, Uri uri) {
        int mediaType = SharedMediaIndex.resolveMediaType(uri);
        long id = NexBoxMediaContract.getIdFromUri(uri);
        if (id <= 0) return null;
        NexBoxMediaEntry entry = SharedMediaScanner.getEntry(userId, id);
        if (entry == null || (mediaType != MediaStore.Files.FileColumns.MEDIA_TYPE_NONE
                && entry.getMediaType() != mediaType)) return null;
        if (NexBoxMediaContract.isDownloadsUri(uri)
                && !SharedMediaPaths.getDownloadsRoot().getAbsolutePath().equals(entry.getRootPath())) return null;
        return entry;
    }

    private static Handler writerHandler() {
        synchronized (SharedMediaDatabase.DB_LOCK) {
            if (sWriterHandler == null) {
                HandlerThread thread = new HandlerThread("NexBoxMediaWriter");
                thread.start();
                sWriterHandler = new Handler(thread.getLooper());
            }
            return sWriterHandler;
        }
    }

    public static void notifyChanged() {
        try {
            Context context = com.nexbox.core.NexBoxCore.getContext();
            context.getContentResolver().notifyChange(NexBoxMediaContract.buildPrivateCollectionUri(MediaStore.Files.FileColumns.MEDIA_TYPE_NONE, SHARED_USER_ID), null);
            context.getContentResolver().notifyChange(NexBoxMediaContract.buildPrivateCollectionUri(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE, SHARED_USER_ID), null);
            context.getContentResolver().notifyChange(NexBoxMediaContract.buildPrivateCollectionUri(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO, SHARED_USER_ID), null);
            context.getContentResolver().notifyChange(NexBoxMediaContract.toPrivateUri(
                    NexBoxMediaContract.buildPublicDownloadsUri(), SHARED_USER_ID), null);
        } catch (Exception failure) { Slog.w(TAG, "Could not notify gallery observers", failure); }
    }
}
