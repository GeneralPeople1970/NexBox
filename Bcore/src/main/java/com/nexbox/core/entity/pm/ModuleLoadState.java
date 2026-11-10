package com.nexbox.core.entity.pm;

import android.os.Parcel;
import android.os.Parcelable;

/** Last load attempt, not a claim that arbitrary module callbacks can never fail later. */
public final class ModuleLoadState implements Parcelable {
    public int userId;
    public int pid;
    public String targetPackage;
    public String processName;
    public String modulePackage;
    public boolean loaded;
    public String detail;
    public long timestamp;
    public boolean processAlive;
    public boolean restartRequired;

    public ModuleLoadState() {}

    public ModuleLoadState(ModuleLoadState other) {
        userId = other.userId;
        pid = other.pid;
        targetPackage = other.targetPackage;
        processName = other.processName;
        modulePackage = other.modulePackage;
        loaded = other.loaded;
        detail = other.detail;
        timestamp = other.timestamp;
        processAlive = other.processAlive;
        restartRequired = other.restartRequired;
    }

    private ModuleLoadState(Parcel in) {
        userId = in.readInt();
        pid = in.readInt();
        targetPackage = in.readString();
        processName = in.readString();
        modulePackage = in.readString();
        loaded = in.readInt() != 0;
        detail = in.readString();
        timestamp = in.readLong();
        processAlive = in.readInt() != 0;
        restartRequired = in.readInt() != 0;
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(userId);
        out.writeInt(pid);
        out.writeString(targetPackage);
        out.writeString(processName);
        out.writeString(modulePackage);
        out.writeInt(loaded ? 1 : 0);
        out.writeString(detail);
        out.writeLong(timestamp);
        out.writeInt(processAlive ? 1 : 0);
        out.writeInt(restartRequired ? 1 : 0);
    }

    @Override public int describeContents() { return 0; }
    public static final Creator<ModuleLoadState> CREATOR = new Creator<ModuleLoadState>() {
        @Override public ModuleLoadState createFromParcel(Parcel in) { return new ModuleLoadState(in); }
        @Override public ModuleLoadState[] newArray(int size) { return new ModuleLoadState[size]; }
    };
}
