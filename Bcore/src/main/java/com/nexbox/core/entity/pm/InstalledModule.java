package com.nexbox.core.entity.pm;

import android.content.pm.ApplicationInfo;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;

import com.nexbox.core.utils.compat.ParcelCompat;

/** A server-created snapshot. Paths are never accepted from the module-manager UI. */
public final class InstalledModule implements Parcelable {
    public String packageName;
    public int userId;
    public ApplicationInfo applicationInfo;
    public String versionName;
    public long versionCode;
    public boolean enabled;
    public ArrayList<String> scope = new ArrayList<>();
    public boolean supported;
    public String error;
    public int minXposedVersion;
    public String description;
    public ArrayList<String> entryPoints = new ArrayList<>();
    public boolean pendingLegacySelection;
    public boolean modern;
    public boolean staticScope;
    public ArrayList<String> recommendedScope = new ArrayList<>();

    public InstalledModule() {}

    private InstalledModule(Parcel in) {
        packageName = in.readString();
        userId = in.readInt();
        applicationInfo = ParcelCompat.readParcelable(in,
                ApplicationInfo.class.getClassLoader(), ApplicationInfo.class);
        versionName = in.readString();
        versionCode = in.readLong();
        enabled = in.readInt() != 0;
        in.readStringList(scope);
        supported = in.readInt() != 0;
        error = in.readString();
        minXposedVersion = in.readInt();
        description = in.readString();
        in.readStringList(entryPoints);
        pendingLegacySelection = in.readInt() != 0;
        modern = in.readInt() != 0;
        staticScope = in.readInt() != 0;
        in.readStringList(recommendedScope);
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(packageName);
        out.writeInt(userId);
        out.writeParcelable(applicationInfo, flags);
        out.writeString(versionName);
        out.writeLong(versionCode);
        out.writeInt(enabled ? 1 : 0);
        out.writeStringList(scope);
        out.writeInt(supported ? 1 : 0);
        out.writeString(error);
        out.writeInt(minXposedVersion);
        out.writeString(description);
        out.writeStringList(entryPoints);
        out.writeInt(pendingLegacySelection ? 1 : 0);
        out.writeInt(modern ? 1 : 0);
        out.writeInt(staticScope ? 1 : 0);
        out.writeStringList(recommendedScope);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<InstalledModule> CREATOR = new Creator<InstalledModule>() {
        @Override public InstalledModule createFromParcel(Parcel in) { return new InstalledModule(in); }
        @Override public InstalledModule[] newArray(int size) { return new InstalledModule[size]; }
    };
}
