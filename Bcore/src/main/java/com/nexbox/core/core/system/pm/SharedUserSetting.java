

package com.nexbox.core.core.system.pm;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.AtomicFile;

import java.io.FileOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.core.env.MetadataParcel;
import com.nexbox.core.utils.BzFileUtils;
import com.nexbox.core.utils.Slog;


public final class SharedUserSetting implements Parcelable {
    public static final String TAG = "SharedUserSetting";
    public static final Map<String, SharedUserSetting> sSharedUsers = new HashMap<>();
    private static volatile IllegalStateException sLoadError;

    public static void requireReadable() {
        if (sLoadError != null) throw sLoadError;
    }

    static boolean isReadable() { return sLoadError == null; }

    static void rememberPackageIdentity(String name, int appId) {
        if (name == null || sLoadError != null) return;
        synchronized (sSharedUsers) {
            SharedUserSetting existing = sSharedUsers.get(name);
            if (existing != null && existing.userId != appId) {
                sLoadError = new IllegalStateException("Conflicting persisted shared UID identities need recovery");
                Slog.w(TAG, "Shared UID identity conflict; original metadata retained");
            } else if (existing == null) {
                SharedUserSetting restored = new SharedUserSetting(name);
                restored.userId = appId;
                sSharedUsers.put(name, restored);
            }
        }
    }

    String name;
    int userId;

    
    
    int seInfoTargetSdkVersion;

    SharedUserSetting(String _name) {
        name = _name;
    }

    SharedUserSetting() {
        
    }

    @Override
    public String toString() {
        return "SharedUserSetting{" + Integer.toHexString(System.identityHashCode(this)) + " "
                + name + "/" + userId + "}";
    }

    public static void saveSharedUsers() {
        requireReadable();
        Parcel parcel = Parcel.obtain();
        FileOutputStream fileOutputStream = null;
        AtomicFile atomicFile = new AtomicFile(BEnvironment.getSharedUserConf());
        try {
            parcel.writeMap(sSharedUsers);

            fileOutputStream = atomicFile.startWrite();
            BzFileUtils.writeParcelToOutput(parcel, fileOutputStream);
            atomicFile.finishWrite(fileOutputStream);
        } catch (Exception e) {
            Slog.e("SharedUserSetting", "Container operation failed", e);
            atomicFile.failWrite(fileOutputStream);
        } finally {
            parcel.recycle();
        }
    }

    public static void loadSharedUsers() {
        Parcel parcel = Parcel.obtain();
        try {
            File file = BEnvironment.getSharedUserConf();
            if (!file.exists() && !new File(file.getPath() + ".bak").exists()) {
                return;
            }
            byte[] sharedUsersBytes = MetadataParcel.readBytes(file);
            if (sharedUsersBytes.length < 4) throw new IOException("Truncated shared UID registry");
            parcel.unmarshall(sharedUsersBytes, 0, sharedUsersBytes.length);
            parcel.setDataPosition(0);

            HashMap<String, SharedUserSetting> hashMap;
            try (MetadataParcel metadata = MetadataParcel.open(file, parcel)) {
                hashMap = metadata.readMap(String.class, SharedUserSetting.class, CREATOR);
                for (Map.Entry<String, SharedUserSetting> entry : hashMap.entrySet()) {
                    if (!entry.getKey().equals(entry.getValue().name) || entry.getValue().userId <= 0) {
                        throw new IOException("Shared UID registry identity mismatch");
                    }
                }
                metadata.finish(file, sharedUsersBytes);
            }
            synchronized (sSharedUsers) {
                sSharedUsers.clear();
                sSharedUsers.putAll(hashMap);
            }
            sLoadError = null;
        } catch (Exception e) {
            sLoadError = new IllegalStateException("Shared UID registry needs recovery", e);
            Slog.e(TAG, "Could not load shared UIDs; original metadata retained", e);
        } finally {
            parcel.recycle();
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.name);
        dest.writeInt(this.userId);
        dest.writeInt(this.seInfoTargetSdkVersion);
    }

    public void readFromParcel(Parcel source) {
        this.name = source.readString();
        this.userId = source.readInt();
        this.seInfoTargetSdkVersion = source.readInt();
    }

    public static final Parcelable.Creator<SharedUserSetting> CREATOR = new Parcelable.Creator<SharedUserSetting>() {
        @Override
        public SharedUserSetting createFromParcel(Parcel source) {
            SharedUserSetting setting = new SharedUserSetting();
            setting.readFromParcel(source);
            return setting;
        }

        @Override
        public SharedUserSetting[] newArray(int size) {
            return new SharedUserSetting[size];
        }
    };
}
