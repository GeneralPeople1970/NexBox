package com.nexbox.core.entity;

import android.app.job.JobInfo;
import android.app.job.JobService;
import android.content.pm.ServiceInfo;
import android.os.Parcel;
import android.os.Parcelable;

import com.nexbox.core.utils.compat.ParcelCompat;



public class JobRecord implements Parcelable {

    public JobInfo mJobInfo;
    public ServiceInfo mServiceInfo;
    public int hostId;
    public int userId;
    public long generation;
    public String packageName;
    public String namespace;
    public String nonce;

    public JobService mJobService;

    public JobRecord() {
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(this.mJobInfo, flags);
        dest.writeParcelable(this.mServiceInfo, flags);
        dest.writeInt(hostId);
        dest.writeInt(userId);
        dest.writeLong(generation);
        dest.writeString(packageName);
        dest.writeString(namespace);
        dest.writeString(nonce);
    }

    protected JobRecord(Parcel in) {
        this.mJobInfo = ParcelCompat.readParcelable(in,
                JobInfo.class.getClassLoader(), JobInfo.class);
        this.mServiceInfo = ParcelCompat.readParcelable(in,
                ServiceInfo.class.getClassLoader(), ServiceInfo.class);
        hostId = in.readInt();
        userId = in.readInt();
        generation = in.readLong();
        packageName = in.readString();
        namespace = in.readString();
        nonce = in.readString();
    }

    public static final Creator<JobRecord> CREATOR = new Creator<JobRecord>() {
        @Override
        public JobRecord createFromParcel(Parcel source) {
            return new JobRecord(source);
        }

        @Override
        public JobRecord[] newArray(int size) {
            return new JobRecord[size];
        }
    };
}
