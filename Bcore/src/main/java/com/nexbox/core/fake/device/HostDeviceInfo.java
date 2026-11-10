package com.nexbox.core.fake.device;

import android.os.Build;

/** Snapshot taken during host attachment, before guest code or hooks can alter Build. */
public final class HostDeviceInfo {
    private static final HostDeviceInfo INSTANCE = new HostDeviceInfo();

    public final String manufacturer = Build.MANUFACTURER;
    public final String brand = Build.BRAND;
    public final String model = Build.MODEL;
    public final String device = Build.DEVICE;
    public final String product = Build.PRODUCT;
    public final String fingerprint = Build.FINGERPRINT;

    private HostDeviceInfo() {}

    public static HostDeviceInfo get() {
        return INSTANCE;
    }
}
