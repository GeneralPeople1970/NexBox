package com.nexbox.core.xposed;

/** Shared host/guest eligibility checks; listing module metadata never loads the native engine. */
public final class RuntimeCapabilities {
    private RuntimeCapabilities() {}
    public static boolean supportsAndroid(int api) { return api >= 31 && api <= 37; }
    public static boolean supportsDeviceAbi(String[] abis) {
        if (abis == null || abis.length == 0) return false;
        // Do not accept a secondary ARM ABI exposed by an x86 binary translator: ART is still x86.
        return "arm64-v8a".equals(abis[0]) || "armeabi-v7a".equals(abis[0]);
    }
}
