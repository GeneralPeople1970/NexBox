package com.nexbox.core.media;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.TextUtils;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps media rows, compatibility projections and root fingerprints without owning filesystem scans.
 * Locked methods require the database monitor; filesystem/index mutations also hold the store file lock.
 */
final class SharedMediaIndex {
    private static final int SHARED_USER_ID = NexBoxMediaContract.SHARED_USER_ID;

    private SharedMediaIndex() {
    }

    static NexBoxMediaEntry getIndexedEntryLocked(SQLiteDatabase database, long id) {
        List<NexBoxMediaEntry> entries = queryEntriesLocked(database,
                NexBoxMediaContract.COL_USER_ID + "=? AND " + NexBoxMediaContract.COL_ID + "=?",
                new String[]{String.valueOf(SHARED_USER_ID), String.valueOf(id)}, null);
        return entries.isEmpty() ? null : entries.get(0);
    }

    static String[] normalizeProjection(String[] projection) {
        if (projection == null || projection.length == 0) {
            return null;
        }
        ArrayList<String> supported = new ArrayList<>();
        for (String column : projection) {
            if (isStoredColumn(column)) {
                supported.add(column);
            }
        }
        if (supported.size() == projection.length) {
            return projection;
        }
        return null;
    }

    private static boolean isStoredColumn(String column) {
        if (TextUtils.isEmpty(column)) {
            return false;
        }
        return NexBoxMediaContract.COL_ID.equals(column)
                || NexBoxMediaContract.COL_USER_ID.equals(column)
                || NexBoxMediaContract.COL_MEDIA_TYPE.equals(column)
                || NexBoxMediaContract.COL_DISPLAY_NAME.equals(column)
                || NexBoxMediaContract.COL_TITLE.equals(column)
                || NexBoxMediaContract.COL_MIME_TYPE.equals(column)
                || NexBoxMediaContract.COL_SIZE.equals(column)
                || NexBoxMediaContract.COL_DATE_ADDED.equals(column)
                || NexBoxMediaContract.COL_DATE_MODIFIED.equals(column)
                || NexBoxMediaContract.COL_DATE_TAKEN.equals(column)
                || NexBoxMediaContract.COL_RELATIVE_PATH.equals(column)
                || NexBoxMediaContract.COL_BUCKET_DISPLAY_NAME.equals(column)
                || NexBoxMediaContract.COL_BUCKET_ID.equals(column)
                || NexBoxMediaContract.COL_WIDTH.equals(column)
                || NexBoxMediaContract.COL_HEIGHT.equals(column)
                || NexBoxMediaContract.COL_DURATION.equals(column)
                || NexBoxMediaContract.COL_IS_PENDING.equals(column)
                || NexBoxMediaContract.COL_DATA.equals(column)
                || NexBoxMediaContract.COL_CANONICAL_PATH.equals(column)
                || NexBoxMediaContract.COL_ROOT_PATH.equals(column)
                || NexBoxMediaContract.COL_OWNER_PACKAGE_NAME.equals(column)
                || NexBoxMediaContract.COL_OWNER_USER_ID.equals(column)
                || NexBoxMediaContract.COL_OWNER_USER_GENERATION.equals(column);
    }

    static Cursor buildCompatCursor(String[] projection, List<NexBoxMediaEntry> entries) {
        String[] columns = projection != null && projection.length > 0 ? projection : defaultProjection();
        MatrixCursor cursor = new MatrixCursor(columns);
        if (entries == null) {
            return cursor;
        }
        for (NexBoxMediaEntry entry : entries) {
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                row[i] = valueForColumn(entry, columns[i]);
            }
            cursor.addRow(row);
        }
        return cursor;
    }

    private static String[] defaultProjection() {
        return new String[]{
                NexBoxMediaContract.COL_ID,
                NexBoxMediaContract.COL_MEDIA_TYPE,
                NexBoxMediaContract.COL_DISPLAY_NAME,
                NexBoxMediaContract.COL_MIME_TYPE,
                NexBoxMediaContract.COL_SIZE,
                NexBoxMediaContract.COL_DATE_MODIFIED,
                NexBoxMediaContract.COL_DATA,
                NexBoxMediaContract.COL_OWNER_PACKAGE_NAME
        };
    }

    private static Object valueForColumn(NexBoxMediaEntry entry, String column) {
        if (entry == null || column == null) return null;
        switch (column) {
            case NexBoxMediaContract.COL_ID:
                return entry.getId();
            case NexBoxMediaContract.COL_USER_ID:
                return entry.getUserId();
            case NexBoxMediaContract.COL_MEDIA_TYPE:
                return entry.getMediaType();
            case NexBoxMediaContract.COL_DISPLAY_NAME:
                return entry.getDisplayName();
            case NexBoxMediaContract.COL_TITLE:
                return entry.getTitle();
            case NexBoxMediaContract.COL_MIME_TYPE:
                return entry.getMimeType();
            case NexBoxMediaContract.COL_SIZE:
                return entry.getSize();
            case NexBoxMediaContract.COL_DATE_ADDED:
                return entry.getDateAdded();
            case NexBoxMediaContract.COL_DATE_MODIFIED:
                return entry.getDateModified();
            case NexBoxMediaContract.COL_DATE_TAKEN:
                return entry.getDateTaken();
            case NexBoxMediaContract.COL_RELATIVE_PATH:
                return entry.getRelativePath();
            case NexBoxMediaContract.COL_BUCKET_DISPLAY_NAME:
                return entry.getBucketDisplayName();
            case NexBoxMediaContract.COL_BUCKET_ID:
                return entry.getBucketId();
            case NexBoxMediaContract.COL_WIDTH:
                return entry.getWidth();
            case NexBoxMediaContract.COL_HEIGHT:
                return entry.getHeight();
            case NexBoxMediaContract.COL_DURATION:
                return entry.getDuration();
            case NexBoxMediaContract.COL_IS_PENDING:
                return entry.getIsPending();
            case NexBoxMediaContract.COL_DATA:
                return entry.getFilePath();
            case NexBoxMediaContract.COL_CANONICAL_PATH:
                return entry.getCanonicalPath();
            case NexBoxMediaContract.COL_ROOT_PATH:
                return entry.getRootPath();
            case "orientation":
            case "is_favorite":
            case "is_trashed":
                return 0;
            case "volume_name":
                return NexBoxMediaContract.EXTERNAL_VOLUME;
            case NexBoxMediaContract.COL_OWNER_PACKAGE_NAME:
                return entry.getOwnerPackageName();
            case NexBoxMediaContract.COL_OWNER_USER_ID:
                return entry.getOwner() != null && entry.getOwner().isGuest() ? entry.getOwner().getUserId() : null;
            case NexBoxMediaContract.COL_OWNER_USER_GENERATION:
                return entry.getOwner() != null && entry.getOwner().isGuest() ? entry.getOwner().getUserGeneration() : null;
            default:
                return null;
        }
    }

    static NexBoxMediaEntry upsertFileLocked(SQLiteDatabase database, int userId, File file, int mediaType,
                                                    String forcedMimeType) throws IOException {
        SharedMediaPaths.ensureRootExists(file.getParentFile());
        SharedMediaSnapshot.FileState state = SharedMediaSnapshot.FileState.capture(file, mediaType);
        Long existingId = findRowIdByCanonicalPathLocked(database, SHARED_USER_ID, state.getCanonicalPath());
        NexBoxMediaEntry existing = existingId != null ? getIndexedEntryLocked(database, existingId) : null;
        if (existing != null && state.matches(existing)) return existing;
        return upsertFileSnapshotLocked(database, userId, state, forcedMimeType, null, existing, null, 0L);
    }

    static NexBoxMediaEntry upsertFileSnapshotLocked(SQLiteDatabase database, int userId,
            SharedMediaSnapshot.FileState state, String forcedMimeType, NexBoxMediaOwner creationOwner,
            NexBoxMediaEntry existing, SharedMediaScanner.MediaMetadata preparedMetadata, long sourceDateTaken) {
        File file = state.getFile();
        String canonicalPath = state.getCanonicalPath();
        Long existingId = existing != null ? existing.getId() : null;
        int mediaType = state.getMediaType();
        if (existing != null) {
            mediaType = existing.getMediaType();
            if (TextUtils.isEmpty(forcedMimeType)) forcedMimeType = existing.getMimeType();
        }
        String relativePath = SharedMediaPaths.buildRelativePath(userId, file, mediaType);
        SharedMediaScanner.MediaMetadata metadata = preparedMetadata != null ? preparedMetadata : SharedMediaScanner.readMetadata(file, mediaType, forcedMimeType);
        long nowSeconds = state.getModifiedSeconds();
        // Publishing/closing a guest write updates file mtime, not its original capture time.
        long captureTime = existing != null && existing.getDateTaken() > 0
                ? existing.getDateTaken() : sourceDateTaken > 0 ? sourceDateTaken : state.getLastModified();

        ContentValues values = new ContentValues();
        values.put(NexBoxMediaContract.COL_USER_ID, SHARED_USER_ID);
        values.put(NexBoxMediaContract.COL_MEDIA_TYPE, mediaType);
        values.put(NexBoxMediaContract.COL_DISPLAY_NAME, file.getName());
        values.put(NexBoxMediaContract.COL_TITLE, SharedMediaPaths.titleFromDisplayName(file.getName()));
        values.put(NexBoxMediaContract.COL_MIME_TYPE, metadata.mimeType);
        values.put(NexBoxMediaContract.COL_SIZE, state.getSize());
        values.put(NexBoxMediaContract.COL_DATE_ADDED, existing != null ? existing.getDateAdded() : nowSeconds);
        values.put(NexBoxMediaContract.COL_DATE_MODIFIED, nowSeconds);
        values.put(NexBoxMediaContract.COL_DATE_TAKEN, captureTime);
        values.put(NexBoxMediaContract.COL_RELATIVE_PATH, relativePath);
        values.put(NexBoxMediaContract.COL_BUCKET_DISPLAY_NAME, SharedMediaPaths.bucketDisplayName(relativePath));
        values.put(NexBoxMediaContract.COL_BUCKET_ID, SharedMediaPaths.bucketId(relativePath));
        values.put(NexBoxMediaContract.COL_WIDTH, metadata.width);
        values.put(NexBoxMediaContract.COL_HEIGHT, metadata.height);
        values.put(NexBoxMediaContract.COL_DURATION, metadata.duration);
        values.put(NexBoxMediaContract.COL_IS_PENDING, existing != null ? existing.getIsPending() : 0);
        values.put(NexBoxMediaContract.COL_DATA, file.getAbsolutePath());
        values.put(NexBoxMediaContract.COL_CANONICAL_PATH, canonicalPath);
        values.put(NexBoxMediaContract.COL_ROOT_PATH, SharedMediaPaths.scanRootFor(file).getAbsolutePath());
        // A scan cannot know who created an unindexed file. Existing ownership is immutable,
        // including UNKNOWN; only the fresh host-import path supplies creationOwner here.
        if (existingId == null) putCreationOwner(values, creationOwner);

        long rowId;
        if (existingId != null) {
            database.update(
                    NexBoxMediaContract.TABLE_MEDIA,
                    values,
                    NexBoxMediaContract.COL_ID + "=?",
                    new String[]{String.valueOf(existingId)}
            );
            rowId = existingId;
        } else {
            rowId = database.insertOrThrow(NexBoxMediaContract.TABLE_MEDIA, null, values);
        }
        return new NexBoxMediaEntry(
                rowId,
                SHARED_USER_ID,
                mediaType,
                file.getName(),
                SharedMediaPaths.titleFromDisplayName(file.getName()),
                metadata.mimeType,
                state.getSize(),
                existing != null ? existing.getDateAdded() : nowSeconds,
                nowSeconds,
                captureTime,
                relativePath,
                SharedMediaPaths.bucketDisplayName(relativePath),
                SharedMediaPaths.bucketId(relativePath),
                metadata.width,
                metadata.height,
                metadata.duration,
                existing != null ? existing.getIsPending() : 0,
                file.getAbsolutePath(),
                canonicalPath,
                values.getAsString(NexBoxMediaContract.COL_ROOT_PATH),
                existing == null ? creationOwner : existing.getOwner()
        );
    }

    static List<NexBoxMediaEntry> queryEntriesLocked(SQLiteDatabase database, String selection, String[] selectionArgs, String sortOrder) {
        List<NexBoxMediaEntry> entries = new ArrayList<>();
        try (Cursor cursor = database.query(
                NexBoxMediaContract.TABLE_MEDIA,
                null,
                selection,
                selectionArgs,
                null,
                null,
                sortOrder
        )) {
            while (cursor.moveToNext()) {
                entries.add(readEntry(cursor));
            }
        }
        return entries;
    }

    private static NexBoxMediaEntry readEntry(Cursor cursor) {
        return new NexBoxMediaEntry(
                cursor.getLong(cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_ID)),
                cursor.getInt(cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_USER_ID)),
                cursor.getInt(cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_MEDIA_TYPE)),
                cursor.getString(cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_DISPLAY_NAME)),
                cursor.getString(cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_TITLE)),
                cursor.getString(cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_MIME_TYPE)),
                cursor.getLong(cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_SIZE)),
                cursor.getLong(cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_DATE_ADDED)),
                cursor.getLong(cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_DATE_MODIFIED)),
                cursor.getLong(cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_DATE_TAKEN)),
                cursor.getString(cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_RELATIVE_PATH)),
                cursor.getString(cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_BUCKET_DISPLAY_NAME)),
                cursor.getInt(cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_BUCKET_ID)),
                cursor.getInt(cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_WIDTH)),
                cursor.getInt(cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_HEIGHT)),
                cursor.getLong(cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_DURATION)),
                cursor.getInt(cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_IS_PENDING)),
                cursor.getString(cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_DATA)),
                cursor.getString(cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_CANONICAL_PATH)),
                cursor.getString(cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_ROOT_PATH)),
                readOwner(cursor)
        );
    }

    private static NexBoxMediaOwner readOwner(Cursor cursor) {
        int packageIndex = cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_OWNER_PACKAGE_NAME);
        int userIndex = cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_OWNER_USER_ID);
        int generationIndex = cursor.getColumnIndexOrThrow(NexBoxMediaContract.COL_OWNER_USER_GENERATION);
        return NexBoxMediaOwner.fromStored(cursor.getString(packageIndex),
                cursor.isNull(userIndex) ? null : cursor.getInt(userIndex),
                cursor.isNull(generationIndex) ? null : cursor.getLong(generationIndex));
    }

    static void putCreationOwner(ContentValues values, NexBoxMediaOwner owner) {
        // This helper is used only for new rows. Never retain a caller-supplied tuple,
        // or let a host/unknown creator inherit virtual-user fields from reused values.
        values.putNull(NexBoxMediaContract.COL_OWNER_PACKAGE_NAME);
        values.putNull(NexBoxMediaContract.COL_OWNER_USER_ID);
        values.putNull(NexBoxMediaContract.COL_OWNER_USER_GENERATION);
        if (owner == null) return;
        values.put(NexBoxMediaContract.COL_OWNER_PACKAGE_NAME, owner.getPackageName());
        if (owner.isGuest()) {
            values.put(NexBoxMediaContract.COL_OWNER_USER_ID, owner.getUserId());
            values.put(NexBoxMediaContract.COL_OWNER_USER_GENERATION, owner.getUserGeneration());
        }
    }

    static String buildSelection(Uri uri, int userId, int mediaType, String selection) {
        requireSupportedUri(uri);
        StringBuilder builder = new StringBuilder();
        builder.append(NexBoxMediaContract.COL_USER_ID).append("=").append(SHARED_USER_ID);
        if (NexBoxMediaContract.isDownloadsUri(uri)) {
            builder.append(" AND ").append(NexBoxMediaContract.COL_ROOT_PATH).append("=")
                    .append(DatabaseUtils.sqlEscapeString(SharedMediaPaths.getDownloadsRoot().getAbsolutePath()));
        }
        if (mediaType != MediaStore.Files.FileColumns.MEDIA_TYPE_NONE) {
            builder.append(" AND ").append(NexBoxMediaContract.COL_MEDIA_TYPE).append("=").append(mediaType);
        }
        long rowId = NexBoxMediaContract.getIdFromUri(uri);
        if (rowId > 0) {
            builder.append(" AND ").append(NexBoxMediaContract.COL_ID).append("=").append(rowId);
        }
        if (!TextUtils.isEmpty(selection)) {
            builder.append(" AND (").append(selection).append(")");
        }
        return builder.toString();
    }

    static String[] buildSelectionArgs(Uri uri, int userId, String[] selectionArgs) {
        return selectionArgs;
    }

    static int resolveMediaType(Uri uri) {
        requireSupportedUri(uri);
        if (NexBoxMediaContract.isImagesUri(uri)) {
            return MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;
        }
        if (NexBoxMediaContract.isVideosUri(uri)) {
            return MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
        }
        return MediaStore.Files.FileColumns.MEDIA_TYPE_NONE;
    }

    private static void requireSupportedUri(Uri uri) {
        if (!NexBoxMediaContract.isSupportedCollectionUri(uri)) {
            throw new IllegalArgumentException("Unsupported media collection URI");
        }
    }

    static Long findRowIdByCanonicalPathLocked(SQLiteDatabase database, int userId, String canonicalPath) {
        try (Cursor cursor = database.query(
                NexBoxMediaContract.TABLE_MEDIA,
                new String[]{NexBoxMediaContract.COL_ID},
                NexBoxMediaContract.COL_USER_ID + "=? AND " + NexBoxMediaContract.COL_CANONICAL_PATH + "=?",
                new String[]{String.valueOf(userId), canonicalPath},
                null,
                null,
                null
        )) {
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        }
        return null;
    }

    static RootState loadStoredStateLocked(SQLiteDatabase database, int userId, String rootPath) {
        try (Cursor cursor = database.query(
                NexBoxMediaContract.TABLE_SCAN_STATE,
                null,
                NexBoxMediaContract.STATE_COL_USER_ID + "=? AND " + NexBoxMediaContract.STATE_COL_ROOT_PATH + "=?",
                new String[]{String.valueOf(userId), rootPath},
                null,
                null,
                null
        )) {
            if (cursor.moveToFirst()) {
                return new RootState(
                        cursor.getLong(cursor.getColumnIndexOrThrow(NexBoxMediaContract.STATE_COL_ROOT_LAST_MODIFIED)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(NexBoxMediaContract.STATE_COL_FILE_COUNT)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(NexBoxMediaContract.STATE_COL_MAX_MODIFIED))
                );
            }
        }
        return RootState.EMPTY;
    }

    static void saveRootStateLocked(SQLiteDatabase database, int userId, String rootPath, RootState state) {
        ContentValues values = new ContentValues();
        values.put(NexBoxMediaContract.STATE_COL_USER_ID, userId);
        values.put(NexBoxMediaContract.STATE_COL_ROOT_PATH, rootPath);
        values.put(NexBoxMediaContract.STATE_COL_ROOT_LAST_MODIFIED, state.rootLastModified);
        values.put(NexBoxMediaContract.STATE_COL_FILE_COUNT, state.fileCount);
        values.put(NexBoxMediaContract.STATE_COL_MAX_MODIFIED, state.maxModified);
        int updated = database.update(
                NexBoxMediaContract.TABLE_SCAN_STATE,
                values,
                NexBoxMediaContract.STATE_COL_USER_ID + "=? AND " + NexBoxMediaContract.STATE_COL_ROOT_PATH + "=?",
                new String[]{String.valueOf(userId), rootPath}
        );
        if (updated == 0) {
            database.insert(NexBoxMediaContract.TABLE_SCAN_STATE, null, values);
        }
    }

    static void refreshStoredState(int userId, int mediaType) {
        synchronized (SharedMediaDatabase.DB_LOCK) {
            SQLiteDatabase database = SharedMediaDatabase.getDatabaseLocked();
            // A guest can write a new file directly while an import is in progress.
            // Invalidate rather than declaring that unindexed filesystem snapshot fresh.
            database.delete(NexBoxMediaContract.TABLE_SCAN_STATE,
                    NexBoxMediaContract.STATE_COL_USER_ID + "=?", new String[]{String.valueOf(SHARED_USER_ID)});
        }
    }

    static final class RootState {
        private static final RootState EMPTY = new RootState(0L, 0, 0L);

        private final long rootLastModified;
        private final int fileCount;
        private final long maxModified;

        RootState(long rootLastModified, int fileCount, long maxModified) {
            this.rootLastModified = rootLastModified;
            this.fileCount = fileCount;
            this.maxModified = maxModified;
        }

        boolean sameAs(RootState other) {
            return other != null
                    && rootLastModified == other.rootLastModified
                    && fileCount == other.fileCount
                    && maxModified == other.maxModified;
        }
    }
}
