package com.nexbox.core.entity.am;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Parcel;
import android.os.Parcelable;

import com.nexbox.core.utils.compat.ParcelCompat;


public class ReceiverData implements Parcelable {
    public Intent intent;
    public ActivityInfo activityInfo;
    public PendingResultData data;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(this.intent, flags);
        dest.writeParcelable(this.activityInfo, flags);
        dest.writeParcelable(this.data, flags);
    }

    public void readFromParcel(Parcel source) {
        this.intent = ParcelCompat.readParcelable(source, Intent.class.getClassLoader(), Intent.class);
        this.activityInfo = ParcelCompat.readParcelable(source,
                ActivityInfo.class.getClassLoader(), ActivityInfo.class);
        this.data = ParcelCompat.readParcelable(source,
                PendingResultData.class.getClassLoader(), PendingResultData.class);
    }

    public ReceiverData() {
    }

    protected ReceiverData(Parcel in) {
        this.intent = ParcelCompat.readParcelable(in, Intent.class.getClassLoader(), Intent.class);
        this.activityInfo = ParcelCompat.readParcelable(in,
                ActivityInfo.class.getClassLoader(), ActivityInfo.class);
        this.data = ParcelCompat.readParcelable(in,
                PendingResultData.class.getClassLoader(), PendingResultData.class);
    }

    public static final Parcelable.Creator<ReceiverData> CREATOR = new Parcelable.Creator<ReceiverData>() {
        @Override
        public ReceiverData createFromParcel(Parcel source) {
            return new ReceiverData(source);
        }

        @Override
        public ReceiverData[] newArray(int size) {
            return new ReceiverData[size];
        }
    };
}
