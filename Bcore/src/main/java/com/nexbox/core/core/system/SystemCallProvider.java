package com.nexbox.core.core.system;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.nexbox.core.utils.NexBoxIpcKeys;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.compat.BundleCompat;


public class SystemCallProvider extends ContentProvider {
    public static final String TAG = "SystemCallProvider";
    private volatile boolean mSystemReady;

    @Override
    public boolean onCreate() {
        try {
            Slog.d(TAG, "SystemCallProvider onCreate called");
            mSystemReady = initSystem();
            return mSystemReady;
        } catch (Exception e) {
            Slog.e(TAG, "Error in SystemCallProvider onCreate", e);
            return false;
        }
    }

    private boolean initSystem() {
        try {
            Slog.d(TAG, "Initializing NexBox system...");
            NexBoxSystem.getSystem().startup();
            Slog.d(TAG, "NexBox system initialized successfully");
            return true;
        } catch (Exception e) {
            Slog.e(TAG, "Failed to initialize NexBox system", e);
            return false;
        }
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        try {
            Slog.d(TAG, "call: " + method + ", " + extras);
            if ("VM".equals(method)) {
                if (!mSystemReady) return null;
                Bundle bundle = new Bundle();
                if (extras != null) {
                    String name = extras.getString(NexBoxIpcKeys.SYSTEM_SERVICE_NAME);
                    Slog.d(TAG, "Requesting service: " + name);
                    IBinder service = ServiceManager.getService(name);
                    if (service != null) {
                        BundleCompat.putBinder(bundle, NexBoxIpcKeys.SYSTEM_SERVICE_BINDER, service);
                        Slog.d(TAG, "Service " + name + " provided successfully");
                    } else {
                        Slog.w(TAG, "Service " + name + " not found");
                    }
                }
                return bundle;
            }
            return super.call(method, arg, extras);
        } catch (Exception e) {
            Slog.e(TAG, "Error in SystemCallProvider call method: " + method, e);
            
            Bundle errorBundle = new Bundle();
            errorBundle.putString("error", e.getMessage());
            return errorBundle;
        }
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
