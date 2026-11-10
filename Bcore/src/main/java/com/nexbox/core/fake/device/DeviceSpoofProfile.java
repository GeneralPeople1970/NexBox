package com.nexbox.core.fake.device;

import java.util.Locale;

public class DeviceSpoofProfile {
    public static final String MODE_HOST = "host", MODE_TEMPLATE = "template", MODE_BASIC = "basic", MODE_LEGACY = "legacy";
    public final String manufacturer;
    public final String brand;
    public final String model;
    public final String device;
    public final String product;
    public final String fingerprint;
    public final String serial;
    public final String androidId;
    public final String imei;
    public final String macAddress;
    public final String oaid;
    public final String catalogId, buildMode;
    public final SimProfile simProfile;

    public DeviceSpoofProfile(
            String manufacturer,
            String brand,
            String model,
            String device,
            String product,
            String fingerprint,
            String serial,
            String androidId
    ) {
        this(manufacturer, brand, model, device, product, fingerprint, serial, androidId, null, null, null);
    }

    public DeviceSpoofProfile(
            String manufacturer,
            String brand,
            String model,
            String device,
            String product,
            String fingerprint,
            String serial,
            String androidId,
            String imei,
            String macAddress,
            String oaid
    ) {
        this(manufacturer, brand, model, device, product, fingerprint, serial, androidId, imei, macAddress,
                oaid, "", MODE_LEGACY, SimProfile.disabled());
    }

    public DeviceSpoofProfile(String manufacturer, String brand, String model, String device, String product,
            String fingerprint, String serial, String androidId, String imei, String macAddress, String oaid,
            String catalogId, String buildMode, SimProfile simProfile) {
        this.manufacturer = manufacturer;
        this.brand = brand;
        this.model = model;
        this.device = device;
        this.product = product;
        this.fingerprint = fingerprint;
        String seed = manufacturer + "|" + model + "|" + device + "|" + serial + "|" + androidId;
        this.androidId = DeviceIdentifierGenerator.isValidAndroidId(androidId)
                ? androidId.toLowerCase(Locale.ROOT) : DeviceIdentifierGenerator.stableAndroidId(seed);
        this.serial = DeviceIdentifierGenerator.isValidSerial(serial)
                ? serial : DeviceIdentifierGenerator.stableSerial(this.androidId);
        this.imei = DeviceIdentifierGenerator.isValidImei(imei)
                ? imei : DeviceIdentifierGenerator.stableImei(this.androidId);
        this.macAddress = DeviceIdentifierGenerator.isValidMac(macAddress)
                ? macAddress.toUpperCase(Locale.ROOT) : DeviceIdentifierGenerator.stableMac(this.androidId);
        this.oaid = DeviceIdentifierGenerator.isValidOaid(oaid)
                ? oaid : DeviceIdentifierGenerator.stableOaid(this.androidId);
        this.catalogId = catalogId == null ? "" : catalogId;
        this.buildMode = buildMode == null ? MODE_LEGACY : buildMode;
        this.simProfile = simProfile == null ? SimProfile.disabled() : simProfile;
    }

    public boolean isBasicOnly() { return MODE_BASIC.equals(buildMode); }
    /** Missing firmware values are explicitly unspoofed, never borrowed from another preset. */
    public String effectiveProduct() { return isBasicOnly() ? HostDeviceInfo.get().product : product; }
    public String effectiveFingerprint() { return isBasicOnly() ? HostDeviceInfo.get().fingerprint : fingerprint; }
    public DeviceSpoofProfile withSimProfile(SimProfile sim) {
        return new DeviceSpoofProfile(manufacturer, brand, model, device, product, fingerprint, serial,
                androidId, imei, macAddress, oaid, catalogId, buildMode, sim);
    }
}
