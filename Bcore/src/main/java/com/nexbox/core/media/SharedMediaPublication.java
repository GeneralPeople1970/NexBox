package com.nexbox.core.media;

import com.nexbox.core.utils.OperationLog;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.provider.MediaStore;
import android.text.TextUtils;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import com.nexbox.core.utils.BzFileUtils;
import com.nexbox.core.utils.Slog;

/** Stages host imports and publishes new files/index rows under the shared store lock. */
final class SharedMediaPublication {
    private static final String TAG = "NexBoxMediaStore";
    private static final int SHARED_USER_ID = NexBoxMediaContract.SHARED_USER_ID;

    private SharedMediaPublication() {
    }

    static NexBoxMediaEntry importMedia(Context context, int userId, Uri sourceUri, String mimeHint) throws IOException {
        return importMedia(context, userId, sourceUri, mimeHint, null);
    }

    static NexBoxMediaEntry importMedia(Context context, int userId, Uri sourceUri, String mimeHint,
                                               CancellationSignal cancellation) throws IOException {
        return OperationLog.call(TAG, "importMedia", "library=shared source=content-provider", () -> {
            if (sourceUri == null) {
                throw new IOException("Missing source URI");
            }
            SharedMediaImportStage.checkCancelled(cancellation);
            String resolvedMime = context.getContentResolver().getType(sourceUri);
            if (TextUtils.isEmpty(resolvedMime)) {
                resolvedMime = mimeHint;
            }
            SourceMetadata source = querySourceMetadata(context, sourceUri, cancellation);
            String displayName = source.displayName;
            if (TextUtils.isEmpty(displayName)) {
                displayName = "shared_" + System.currentTimeMillis();
            }
            if (TextUtils.isEmpty(resolvedMime)) {
                resolvedMime = SharedMediaPaths.guessMimeFromName(displayName);
            }
            int mediaType = NexBoxMediaContract.mediaTypeForMime(resolvedMime);
            if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_NONE) {
                throw new IOException("Only images and videos are supported");
            }

            File requestedDirectory = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    ? SharedMediaPaths.getVideosRoot(userId)
                    : SharedMediaPaths.getImagesRoot(userId);
            String safeName = SharedMediaPaths.buildSafeDisplayName(displayName, resolvedMime, mediaType);
            SharedMediaImportStage pending = SharedMediaDatabase.withStoreLock(() -> {
                SharedMediaLegacyMigration.prepareSharedStoreLocked();
                SharedMediaImportStage.checkCancelled(cancellation);
                return SharedMediaImportStage.create(SharedMediaPaths.getSharedMediaRoot(), requestedDirectory);
            });
            try (SharedMediaImportStage staged = pending) {
                // Neither an external provider nor a decoder runs while another media operation waits for the store lock.
                try (AssetFileDescriptor descriptor = context.getContentResolver()
                        .openAssetFileDescriptor(sourceUri, "r", cancellation)) {
                    if (descriptor == null) throw new IOException("Unable to open source URI");
                    try (InputStream input = descriptor.createInputStream()) {
                        staged.copyFrom(input, cancellation);
                    }
                }
                SharedMediaScanner.MediaMetadata metadata = SharedMediaScanner.readMetadata(staged.getFile(), mediaType, resolvedMime);
                SharedMediaImportStage.checkCancelled(cancellation);
                NexBoxMediaEntry entry = SharedMediaDatabase.withStoreLock(() -> {
                    SharedMediaLegacyMigration.prepareSharedStoreLocked();
                    SharedMediaImportStage.checkCancelled(cancellation);
                    staged.verifyReady();
                    File target = SharedMediaPaths.requireSharedFile(SharedMediaPaths.uniqueFile(staged.getFile().getParentFile(), safeName));
                    SQLiteDatabase database = SharedMediaDatabase.getDatabaseLocked();
                    boolean published = false;
                    try {
                        NexBoxMediaEntry imported;
                        database.beginTransaction();
                        try {
                            staged.publish(target);
                            published = true;
                            SharedMediaImportStage.checkCancelled(cancellation);
                            imported = SharedMediaIndex.upsertFileSnapshotLocked(database, SHARED_USER_ID,
                                    SharedMediaSnapshot.FileState.capture(target, mediaType), metadata.mimeType,
                                    NexBoxMediaOwner.host(com.nexbox.core.NexBoxCore.getHostPkg()), null,
                                    metadata, source.dateTaken);
                            SharedMediaIndex.refreshStoredState(SHARED_USER_ID, mediaType);
                            SharedMediaImportStage.checkCancelled(cancellation);
                            database.setTransactionSuccessful();
                        } finally {
                            database.endTransaction();
                        }
                        return imported;
                    } catch (IOException | RuntimeException | Error failure) {
                        if (published) {
                            try { staged.rollbackPublished(target); }
                            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                        }
                        throw failure;
                    }
                });
                NexBoxMediaStore.notifyChanged();
                return entry;
            }
        });
    }

    static Uri insert(int userId, Uri uri, ContentValues values, NexBoxMediaOwner owner) throws IOException {
        if (owner == null) throw new IOException("Media creator could not be confirmed");
        return SharedMediaDatabase.withStoreLock(() -> {
            SharedMediaLegacyMigration.prepareSharedStoreLocked();
            Uri inserted = insertLocked(SHARED_USER_ID, uri, values, owner);
            NexBoxMediaStore.notifyChanged();
            return inserted;
        });
    }

    private static Uri insertLocked(int userId, Uri uri, ContentValues values, NexBoxMediaOwner owner) throws IOException {
        int mediaType = SharedMediaIndex.resolveMediaType(uri);
        if (NexBoxMediaContract.getIdFromUri(uri) > 0) throw new IllegalArgumentException("Insert requires a collection URI");
        SharedMediaPaths.ensureRoots(userId);

        String displayName = values.getAsString(NexBoxMediaContract.COL_DISPLAY_NAME);
        if (TextUtils.isEmpty(displayName)) {
            displayName = "file_" + System.currentTimeMillis();
        }
        String mimeType = values.getAsString(NexBoxMediaContract.COL_MIME_TYPE);
        if (TextUtils.isEmpty(mimeType)) {
            mimeType = SharedMediaPaths.guessMimeFromName(displayName);
        }
        if (TextUtils.isEmpty(mimeType)) {
            mimeType = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE ? "image/jpeg"
                    : mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ? "video/mp4" : "application/octet-stream";
        }
        int actualType = NexBoxMediaContract.mediaTypeForMime(mimeType);
        if (mediaType != MediaStore.Files.FileColumns.MEDIA_TYPE_NONE && mediaType != actualType) {
            throw new IllegalArgumentException("MIME type does not match the media collection");
        }
        mediaType = actualType;
        String safeName = SharedMediaPaths.buildSafeDisplayName(displayName, mimeType, mediaType);
        String requestedPath = values.getAsString(NexBoxMediaContract.COL_RELATIVE_PATH);
        boolean downloads = NexBoxMediaContract.isDownloadsUri(uri);
        if (downloads && TextUtils.isEmpty(requestedPath)) requestedPath = NexBoxMediaContract.ROOT_DOWNLOADS;
        String relativePath = SharedMediaPaths.normalizeRelativePath(requestedPath, mediaType);
        if (downloads && !relativePath.startsWith(NexBoxMediaContract.ROOT_DOWNLOADS)) {
            throw new IllegalArgumentException("Downloads must stay inside the Download directory");
        }
        File targetDir = SharedMediaPaths.resolveTargetDir(userId, relativePath, mediaType);
        BzFileUtils.mkdirs(targetDir);
        File targetFile = SharedMediaPaths.requireSharedFile(SharedMediaPaths.uniqueFile(targetDir, safeName));
        if (!targetFile.exists() && !targetFile.createNewFile()) {
            throw new IOException("Failed to create media file");
        }

        long nowSeconds = System.currentTimeMillis() / 1000L;
        ContentValues rowValues = new ContentValues();
        rowValues.put(NexBoxMediaContract.COL_USER_ID, userId);
        rowValues.put(NexBoxMediaContract.COL_MEDIA_TYPE, mediaType);
        rowValues.put(NexBoxMediaContract.COL_DISPLAY_NAME, targetFile.getName());
        rowValues.put(NexBoxMediaContract.COL_TITLE, SharedMediaPaths.titleFromDisplayName(targetFile.getName()));
        rowValues.put(NexBoxMediaContract.COL_MIME_TYPE, mimeType);
        rowValues.put(NexBoxMediaContract.COL_SIZE, targetFile.length());
        rowValues.put(NexBoxMediaContract.COL_DATE_ADDED, values.getAsLong(NexBoxMediaContract.COL_DATE_ADDED) != null
                ? values.getAsLong(NexBoxMediaContract.COL_DATE_ADDED)
                : nowSeconds);
        rowValues.put(NexBoxMediaContract.COL_DATE_MODIFIED, values.getAsLong(NexBoxMediaContract.COL_DATE_MODIFIED) != null
                ? values.getAsLong(NexBoxMediaContract.COL_DATE_MODIFIED)
                : nowSeconds);
        rowValues.put(NexBoxMediaContract.COL_DATE_TAKEN, values.getAsLong(NexBoxMediaContract.COL_DATE_TAKEN) != null
                ? values.getAsLong(NexBoxMediaContract.COL_DATE_TAKEN)
                : targetFile.lastModified());
        rowValues.put(NexBoxMediaContract.COL_RELATIVE_PATH, relativePath);
        rowValues.put(NexBoxMediaContract.COL_BUCKET_DISPLAY_NAME, SharedMediaPaths.bucketDisplayName(relativePath));
        rowValues.put(NexBoxMediaContract.COL_BUCKET_ID, SharedMediaPaths.bucketId(relativePath));
        rowValues.put(NexBoxMediaContract.COL_WIDTH, values.getAsInteger(NexBoxMediaContract.COL_WIDTH) != null
                ? values.getAsInteger(NexBoxMediaContract.COL_WIDTH)
                : 0);
        rowValues.put(NexBoxMediaContract.COL_HEIGHT, values.getAsInteger(NexBoxMediaContract.COL_HEIGHT) != null
                ? values.getAsInteger(NexBoxMediaContract.COL_HEIGHT)
                : 0);
        rowValues.put(NexBoxMediaContract.COL_DURATION, values.getAsLong(NexBoxMediaContract.COL_DURATION) != null
                ? values.getAsLong(NexBoxMediaContract.COL_DURATION)
                : 0L);
        rowValues.put(NexBoxMediaContract.COL_IS_PENDING, values.getAsInteger(NexBoxMediaContract.COL_IS_PENDING) != null
                ? values.getAsInteger(NexBoxMediaContract.COL_IS_PENDING)
                : 0);
        rowValues.put(NexBoxMediaContract.COL_DATA, targetFile.getAbsolutePath());
        rowValues.put(NexBoxMediaContract.COL_CANONICAL_PATH, SharedMediaPaths.canonicalPath(targetFile));
        rowValues.put(NexBoxMediaContract.COL_ROOT_PATH, SharedMediaPaths.scanRootFor(targetFile).getAbsolutePath());
        // Only the provider's verified creator reaches this parameter. Caller ContentValues,
        // URI parameters, attribution package and filename never claim media ownership.
        SharedMediaIndex.putCreationOwner(rowValues, owner);

        synchronized (SharedMediaDatabase.DB_LOCK) {
            SQLiteDatabase database = SharedMediaDatabase.getDatabaseLocked();
            long rowId = database.insertOrThrow(NexBoxMediaContract.TABLE_MEDIA, null, rowValues);
            SharedMediaIndex.refreshStoredState(userId, mediaType);
            return Uri.withAppendedPath(NexBoxMediaContract.toPrivateUri(uri, userId), String.valueOf(rowId));
        }
    }

    static void copyAtomically(InputStream input, File target) throws IOException {
        SharedMediaPaths.requireSharedFile(target);
        SharedMediaFiles.copyAtomically(input, SharedMediaPaths.getSharedMediaRoot(), target,
                (temporary, destination) -> com.nexbox.core.core.NativeCore.publishMediaFile(
                        temporary.getAbsolutePath(), destination.getAbsolutePath()));
    }

    private static SourceMetadata querySourceMetadata(Context context, Uri uri, CancellationSignal cancellation) {
        SourceMetadata source = new SourceMetadata();
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null, cancellation)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                if (nameColumn >= 0) source.displayName = cursor.getString(nameColumn);
                source.dateTaken = positiveColumn(cursor, MediaStore.MediaColumns.DATE_TAKEN);
                if (source.dateTaken == 0) source.dateTaken = positiveColumn(cursor,
                        android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                if (source.dateTaken == 0) source.dateTaken = positiveColumn(cursor,
                        MediaStore.MediaColumns.DATE_MODIFIED) * 1000L;
            }
        } catch (OperationCanceledException cancelled) {
            throw cancelled;
        } catch (Exception e) {
            Slog.w(TAG, "Source provider did not expose media metadata", e);
        }
        if ("file".equals(uri.getScheme()) && source.dateTaken == 0 && uri.getPath() != null)
            source.dateTaken = new File(uri.getPath()).lastModified();
        String lastSegment = uri.getLastPathSegment();
        if (TextUtils.isEmpty(source.displayName) && !TextUtils.isEmpty(lastSegment)) {
            int slash = lastSegment.lastIndexOf('/');
            source.displayName = slash >= 0 ? lastSegment.substring(slash + 1) : lastSegment;
        }
        return source;
    }

    private static long positiveColumn(Cursor cursor, String name) {
        int column = cursor.getColumnIndex(name);
        return column >= 0 && !cursor.isNull(column) ? Math.max(0, cursor.getLong(column)) : 0;
    }

    private static final class SourceMetadata {
        String displayName;
        long dateTaken;
    }
}
