package com.nexbox.core.entity.pm;

import android.os.Parcel;
import android.os.Parcelable;

public final class XposedRuntimeStatus implements Parcelable {
    /** Bundled and eligible to initialize; per-process reports prove actual initialization. */
    public boolean available;
    public String state;
    public String message;
    public String backend;
    public int apiVersion;
    public long configRevision;

    public XposedRuntimeStatus() {}

    private XposedRuntimeStatus(Parcel in) {
        available = in.readInt() != 0;
        state = in.readString();
        message = in.readString();
        backend = in.readString();
        apiVersion = in.readInt();
        configRevision = in.readLong();
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(available ? 1 : 0);
        out.writeString(state);
        out.writeString(message);
        out.writeString(backend);
        out.writeInt(apiVersion);
        out.writeLong(configRevision);
    }

    @Override public int describeContents() { return 0; }
    public static final Creator<XposedRuntimeStatus> CREATOR = new Creator<XposedRuntimeStatus>() {
        @Override public XposedRuntimeStatus createFromParcel(Parcel in) { return new XposedRuntimeStatus(in); }
        @Override public XposedRuntimeStatus[] newArray(int size) { return new XposedRuntimeStatus[size]; }
    };
}
