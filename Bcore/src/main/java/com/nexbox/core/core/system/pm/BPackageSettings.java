package com.nexbox.core.core.system.pm;

import com.nexbox.core.utils.Slog;

import android.os.Parcel;
import android.os.Parcelable;
import android.os.BadParcelableException;
import android.content.pm.ApplicationInfo;
import android.util.AtomicFile;

import black.android.content.pm.BRApplicationInfoL;
import black.android.content.pm.BRApplicationInfoN;

import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.core.env.MetadataParcel;
import com.nexbox.core.core.system.user.BUserHandle;
import com.nexbox.core.entity.pm.InstallOption;
import com.nexbox.core.utils.CloseUtils;
import com.nexbox.core.utils.BzFileUtils;


public class BPackageSettings implements Parcelable {
    public BPackage pkg;
    public int appId;
    public InstallOption installOption;
    public Map<Integer, BPackageUserState> userState = new HashMap<>();
    static final BPackageUserState DEFAULT_USER_STATE = new BPackageUserState();

    public BPackageSettings() {
    }

    public List<BPackageUserState> getUserState() {
        return new ArrayList<>(userState.values());
    }

    public List<Integer> getUserIds() {
        return new ArrayList<>(userState.keySet());
    }

    public void setInstalled(boolean inst, int userId) {
        modifyUserState(userId).installed = inst;
    }

    public boolean getInstalled(int userId) {
        return readUserState(userId).installed;
    }

    public boolean getStopped(int userId) {
        return readUserState(userId).stopped;
    }

    public void setStopped(boolean stop, int userId) {
        modifyUserState(userId).stopped = stop;
    }

    public boolean getHidden(int userId) {
        return readUserState(userId).hidden;
    }

    public void setHidden(boolean hidden, int userId) {
        modifyUserState(userId).hidden = hidden;
    }

    public void removeUser(int userId) {
        userState.remove(userId);
    }

    public BPackageUserState readUserState(int userId) {
        BPackageUserState state = userState.get(userId);
        if (state == null) {
            state = new BPackageUserState();
        }
        state = new BPackageUserState(state);
        

        if (userId == BUserHandle.USER_ALL) {
            state.installed = true;
        }
        return state;
    }

    private BPackageUserState modifyUserState(int userId) {
        BPackageUserState state = userState.get(userId);
        if (state == null) {
            state = new BPackageUserState();
            userState.put(userId, state);
        }
        return state;
    }

    public boolean save() {
        synchronized (this) {
            Parcel parcel = Parcel.obtain();
            AtomicFile atomicFile = new AtomicFile(BEnvironment.getPackageConf(pkg.packageName));
            FileOutputStream fileOutputStream = null;
            try {
                writeToParcel(parcel, 0);
                parcel.setDataPosition(0);
                fileOutputStream = atomicFile.startWrite();
                BzFileUtils.writeParcelToOutput(parcel, fileOutputStream);
                atomicFile.finishWrite(fileOutputStream);
                return true;
            } catch (Throwable e) {
                Slog.e("BPackageSettings", "Container operation failed", e);
                atomicFile.failWrite(fileOutputStream);
                return false;
            } finally {
                parcel.recycle();
                CloseUtils.close(fileOutputStream);
            }
        }
    }

    /** Rebase only paths owned by container metadata; never search/replace guest data. */
    public boolean relocateStoragePaths() {
        StoredPaths paths = new StoredPaths();
        pkg.baseCodePath = paths.path(pkg.baseCodePath);
        paths.application(pkg.applicationInfo);
        for (BPackage.Activity entry : pkg.activities) paths.application(entry.info.applicationInfo);
        for (BPackage.Activity entry : pkg.receivers) paths.application(entry.info.applicationInfo);
        for (BPackage.Service entry : pkg.services) paths.application(entry.info.applicationInfo);
        for (BPackage.Provider entry : pkg.providers) paths.application(entry.info.applicationInfo);
        for (BPackage.Instrumentation entry : pkg.instrumentation) {
            entry.info.sourceDir = paths.path(entry.info.sourceDir);
            entry.info.publicSourceDir = paths.path(entry.info.publicSourceDir);
            entry.info.dataDir = paths.path(entry.info.dataDir);
        }
        return paths.changed;
    }

    private static final class StoredPaths {
        boolean changed;
        String path(String value) {
            String relocated = BEnvironment.relocateLegacyPath(value);
            changed |= !Objects.equals(value, relocated);
            return relocated;
        }
        void array(String[] values) {
            if (values != null) for (int i = 0; i < values.length; i++) values[i] = path(values[i]);
        }
        void application(ApplicationInfo info) {
            if (info == null) return;
            info.sourceDir = path(info.sourceDir);
            info.publicSourceDir = path(info.publicSourceDir);
            info.nativeLibraryDir = path(info.nativeLibraryDir);
            String nativeLibraryRoot = BRApplicationInfoL.get(info).nativeLibraryRootDir();
            if (nativeLibraryRoot != null) {
                BRApplicationInfoL.get(info)._set_nativeLibraryRootDir(path(nativeLibraryRoot));
            }
            String scanSourceDir = BRApplicationInfoL.get(info).scanSourceDir();
            if (scanSourceDir != null) {
                BRApplicationInfoL.get(info)._set_scanSourceDir(path(scanSourceDir));
            }
            String scanPublicSourceDir = BRApplicationInfoL.get(info).scanPublicSourceDir();
            if (scanPublicSourceDir != null) {
                BRApplicationInfoL.get(info)._set_scanPublicSourceDir(path(scanPublicSourceDir));
            }
            info.dataDir = path(info.dataDir);
            info.deviceProtectedDataDir = path(info.deviceProtectedDataDir);
            if (BRApplicationInfoN.get(info)._check_credentialProtectedDataDir() != null) {
                BRApplicationInfoN.get(info)._set_credentialProtectedDataDir(
                        path(BRApplicationInfoN.get(info).credentialProtectedDataDir()));
            }
            array(info.splitSourceDirs);
            array(info.splitPublicSourceDirs);
            array(info.sharedLibraryFiles);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(this.pkg, flags);
        dest.writeInt(this.appId);
        dest.writeParcelable(this.installOption, flags);
        dest.writeInt(this.userState.size());
        for (Map.Entry<Integer, BPackageUserState> entry : this.userState.entrySet()) {
            dest.writeValue(entry.getKey());
            dest.writeParcelable(entry.getValue(), flags);
        }
    }

    protected BPackageSettings(Parcel in) {
        this.pkg = MetadataParcel.read(in, BPackage.class, BPackage.CREATOR);
        this.appId = in.readInt();
        this.installOption = MetadataParcel.read(in, InstallOption.class, InstallOption.CREATOR);
        int userStateSize = in.readInt();
        if (userStateSize < 0 || userStateSize > 100000 || userStateSize > in.dataAvail() / 8) {
            throw new BadParcelableException("Invalid package user-state count");
        }
        this.userState = new HashMap<Integer, BPackageUserState>(userStateSize);
        for (int i = 0; i < userStateSize; i++) {
            Integer key = MetadataParcel.readIntegerValue(in);
            BPackageUserState value = MetadataParcel.read(in, BPackageUserState.class, BPackageUserState.CREATOR);
            if (key == null || value == null || this.userState.containsKey(key)) {
                throw new BadParcelableException("Invalid package user-state identity");
            }
            this.userState.put(key, value);
        }
    }

    public static final Creator<BPackageSettings> CREATOR = new Creator<BPackageSettings>() {
        @Override
        public BPackageSettings createFromParcel(Parcel source) {
            return new BPackageSettings(source);
        }

        @Override
        public BPackageSettings[] newArray(int size) {
            return new BPackageSettings[size];
        }
    };
}
