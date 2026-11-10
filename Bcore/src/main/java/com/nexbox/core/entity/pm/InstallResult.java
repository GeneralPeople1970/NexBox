package com.nexbox.core.entity.pm;

import android.os.Parcel;
import android.os.Parcelable;

import com.nexbox.core.utils.Slog;


public class InstallResult implements Parcelable {
    public static final String TAG = "InstallResult";

    // An allocated result (or a parsed package name) is not a completed installation.
    public boolean success = false;
    public String packageName;
    public String msg;
    // Only a strict committed receipt binds the numeric id to a particular user lifetime.
    public int userId = -1;
    public long userCreateTime = 0;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte(this.success ? (byte) 1 : (byte) 0);
        dest.writeString(this.packageName);
        dest.writeString(this.msg);
        dest.writeInt(this.userId);
        dest.writeLong(this.userCreateTime);
    }

    public InstallResult() {
    }

    /** Call only after the package and its user installation have been committed. */
    public InstallResult installSuccess(String packageName) {
        this.userId = -1;
        this.userCreateTime = 0;
        if (packageName == null || packageName.trim().isEmpty()) {
            return installError("A completed installation requires a package name");
        }
        this.packageName = packageName;
        this.msg = null;
        this.success = true;
        return this;
    }

    public InstallResult installSuccess(String packageName, int userId, long userCreateTime) {
        if (userId < 0 || userCreateTime <= 0) {
            return installError(packageName, "A completed installation requires a current user identity");
        }
        installSuccess(packageName);
        if (this.success) {
            this.userId = userId;
            this.userCreateTime = userCreateTime;
        }
        return this;
    }

    protected InstallResult(Parcel in) {
        this.success = in.readByte() != 0;
        this.packageName = in.readString();
        this.msg = in.readString();
        this.userId = in.readInt();
        this.userCreateTime = in.readLong();
    }

    public InstallResult installError(String packageName, String msg) {
        this.msg = msg;
        this.success = false;
        this.userId = -1;
        this.userCreateTime = 0;
        this.packageName = packageName;
        Slog.d(TAG, msg);
        return this;
    }

    public InstallResult installError(String msg) {
        this.msg = msg;
        this.success = false;
        this.userId = -1;
        this.userCreateTime = 0;
        Slog.d(TAG, msg);
        return this;
    }

    public static final Parcelable.Creator<InstallResult> CREATOR = new Parcelable.Creator<InstallResult>() {
        @Override
        public InstallResult createFromParcel(Parcel source) {
            return new InstallResult(source);
        }

        @Override
        public InstallResult[] newArray(int size) {
            return new InstallResult[size];
        }
    };
}
