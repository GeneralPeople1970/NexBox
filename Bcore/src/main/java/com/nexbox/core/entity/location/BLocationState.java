package com.nexbox.core.entity.location;

import android.os.Parcel;
import android.os.Parcelable;

/** Lightweight management snapshot. It does not change the persisted BLocationConfig format. */
public final class BLocationState implements Parcelable {
    public final String packageName;
    public final int pattern;
    public final BLocation location;
    public final boolean eligible;

    public BLocationState(String packageName, int pattern, BLocation location, boolean eligible) {
        this.packageName = packageName;
        this.pattern = pattern;
        this.location = location;
        this.eligible = eligible;
    }

    private BLocationState(Parcel in) {
        packageName = in.readString();
        pattern = in.readInt();
        // BLocation's historical storage constructor reads its last three values in another
        // order. This new IPC contract must match writeToParcel without changing old storage.
        location = in.readInt() == 0 ? null : BLocation.createValue(
                in.readDouble(), in.readDouble(), in.readDouble(),
                in.readFloat(), in.readFloat(), in.readFloat());
        eligible = in.readInt() != 0;
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(packageName);
        out.writeInt(pattern);
        out.writeInt(location == null ? 0 : 1);
        if (location != null) location.writeToParcel(out, flags);
        out.writeInt(eligible ? 1 : 0);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<BLocationState> CREATOR = new Creator<BLocationState>() {
        @Override public BLocationState createFromParcel(Parcel in) { return new BLocationState(in); }
        @Override public BLocationState[] newArray(int size) { return new BLocationState[size]; }
    };
}
