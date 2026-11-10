
package com.nexbox.core.entity.location;

import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;


public class BLocation implements Parcelable {

    private double mLatitude = 0.0;
    private double mLongitude = 0.0;
    private double mAltitude = 0.0f;
    private float mSpeed = 0.0f;
    private float mBearing = 0.0f;
    private float mAccuracy = 0.0f;





    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeDouble(this.mLatitude);
        dest.writeDouble(this.mLongitude);
        dest.writeDouble(this.mAltitude);
        dest.writeFloat(this.mSpeed);
        dest.writeFloat(this.mBearing);
        dest.writeFloat(this.mAccuracy);
    }

    public double getLatitude() {
        return mLatitude;
    }

    public double getLongitude() {
        return mLongitude;
    }

    /** Value comparison for no-op persistence; keep Object identity and the Parcel layout unchanged. */
    public boolean sameValueAs(BLocation other) {
        return other != null
                && Double.compare(mLatitude, other.mLatitude) == 0
                && Double.compare(mLongitude, other.mLongitude) == 0
                && Double.compare(mAltitude, other.mAltitude) == 0
                && Float.compare(mSpeed, other.mSpeed) == 0
                && Float.compare(mBearing, other.mBearing) == 0
                && Float.compare(mAccuracy, other.mAccuracy) == 0;
    }

    public BLocation() {
    }

    public BLocation(double latitude, double mLongitude) {
        this.mLatitude = latitude;
        this.mLongitude = mLongitude;
    }

    /** Explicit value factory for new wire contracts; the historical Parcel reader stays intact. */
    public static BLocation createValue(double latitude, double longitude, double altitude,
                                        float speed, float bearing, float accuracy) {
        BLocation location = new BLocation(latitude, longitude);
        location.mAltitude = altitude;
        location.mSpeed = speed;
        location.mBearing = bearing;
        location.mAccuracy = accuracy;
        return location;
    }

    public BLocation(Parcel in) {
        this.mLatitude = in.readDouble();
        this.mLongitude = in.readDouble();
        this.mAltitude = in.readDouble();
        this.mAccuracy = in.readFloat();
        this.mSpeed = in.readFloat();
        this.mBearing = in.readFloat();
    }

    public boolean isEmpty() {
        return mLatitude == 0 && mLongitude == 0;
    }

    public static final Parcelable.Creator<BLocation> CREATOR = new Parcelable.Creator<BLocation>() {
        @Override
        public BLocation createFromParcel(Parcel source) {
            return new BLocation(source);
        }

        @Override
        public BLocation[] newArray(int size) {
            return new BLocation[size];
        }
    };

    @Override
    public String toString() {
        return "BLocation{" +
                "latitude: " + mLatitude +
                ", longitude: " + mLongitude +
                ", altitude: " + mAltitude +
                ", speed: " + mSpeed +
                ", bearing: " + mBearing +
                ", accuracy: " + mAccuracy +
                '}';
    }

    public Location convert2SystemLocation() {
        Location location = new Location(LocationManager.GPS_PROVIDER);
        location.setLatitude(mLatitude);
        location.setLongitude(mLongitude);
        location.setSpeed(mSpeed);
        location.setBearing(mBearing);
        location.setAccuracy(40f);
        location.setTime(System.currentTimeMillis());
        Bundle extraBundle = new Bundle();
        
        int satelliteCount = 10;
        extraBundle.putInt("satellites", satelliteCount);
        extraBundle.putInt("satellitesvalue", satelliteCount);
        location.setExtras(extraBundle);
        return location;
    }
}
