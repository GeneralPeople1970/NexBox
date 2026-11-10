package com.nexbox.core.media;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.MediaStore;
import java.io.FileNotFoundException;
import com.nexbox.core.utils.Slog;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class NexBoxMediaProvider extends ContentProvider {
    public static final String METHOD_RESCAN = "rescan";
    public static final String EXTRA_USER_ID = "user_id";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri,
                        @Nullable String[] projection,
                        @Nullable String selection,
                        @Nullable String[] selectionArgs,
                        @Nullable String sortOrder) {
        if (Binder.getCallingUid() != Process.myUid()) {
            // A share grants one item, never a SQL filter over the whole private library.
            // Android checks the URI grant; ignore external query expressions as well.
            if (NexBoxMediaContract.getIdFromUri(uri) <= 0) throw new SecurityException("Only a shared item can be queried");
            selection = null;
            selectionArgs = null;
            sortOrder = null;
        }
        Cursor cursor = NexBoxMediaStore.query(NexBoxMediaContract.SHARED_USER_ID, uri, projection, selection, selectionArgs, sortOrder);
        if (cursor != null && getContext() != null) cursor.setNotificationUri(getContext().getContentResolver(),
                NexBoxMediaContract.buildPrivateCollectionUri(MediaStore.Files.FileColumns.MEDIA_TYPE_NONE, NexBoxMediaContract.SHARED_USER_ID));
        return cursor;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return NexBoxMediaStore.getType(NexBoxMediaContract.getUserId(uri), uri);
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        enforceHostWrite();
        try {
            NexBoxMediaOwner owner = NexBoxMediaOwnerResolver.forInsert();
            return NexBoxMediaStore.insert(NexBoxMediaContract.SHARED_USER_ID, uri,
                    values != null ? values : new ContentValues(), owner);
        } catch (Exception e) {
            Slog.e("SharedMediaProvider", "Could not insert media", e);
            return null;
        }
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        enforceHostWrite();
        int deleted = NexBoxMediaStore.delete(NexBoxMediaContract.getUserId(uri), uri, selection, selectionArgs);
        return deleted;
    }

    @Override
    public int update(@NonNull Uri uri,
                      @Nullable ContentValues values,
                      @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        enforceHostWrite();
        int updated = NexBoxMediaStore.update(NexBoxMediaContract.getUserId(uri), uri, values != null ? values : new ContentValues(), selection, selectionArgs);
        return updated;
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) enforceHostWrite();
        try {
            return NexBoxMediaStore.openFile(NexBoxMediaContract.getUserId(uri), uri, mode);
        } catch (Exception e) {
            Slog.w("SharedMediaProvider", "Could not open media", e);
            FileNotFoundException failure = new FileNotFoundException("Media item is unavailable");
            failure.initCause(e);
            throw failure;
        }
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (METHOD_RESCAN.equals(method)) {
            enforceHostWrite();
            int userId = extras != null ? extras.getInt(EXTRA_USER_ID, 0) : 0;
            NexBoxMediaStore.rescanUser(userId);
            Bundle result = new Bundle();
            result.putBoolean("success", true);
            return result;
        }
        return super.call(method, arg, extras);
    }

    private static void enforceHostWrite() {
        if (Binder.getCallingUid() != Process.myUid()) throw new SecurityException("Shared gallery grants are read-only");
    }

}
