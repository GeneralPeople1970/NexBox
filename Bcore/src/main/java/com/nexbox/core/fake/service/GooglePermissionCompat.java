package com.nexbox.core.fake.service;

import android.Manifest;

import java.util.Set;

import com.nexbox.core.core.GmsCompat;
import com.nexbox.core.fake.device.DeviceSpoofManager;
import com.nexbox.core.fake.frameworks.BLocationManager;

/** Only permissions backed by an enabled, guest-local simulated data source are virtual. */
final class GooglePermissionCompat {
    private GooglePermissionCompat() { }

    static boolean hasVirtualGrant(String packageName, Set<String> requested, String permission) {
        if (!GmsCompat.isCorePackage(packageName) || requested == null || !requested.contains(permission)) return false;
        if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permission)
                || Manifest.permission.ACCESS_COARSE_LOCATION.equals(permission)) {
            return BLocationManager.isFakeLocationEnable();
        }
        if (Manifest.permission.READ_PHONE_STATE.equals(permission)) {
            try {
                return DeviceSpoofManager.isCurrentProcessEnabled()
                        && DeviceSpoofManager.getCurrentProfile().simProfile.enabled;
            } catch (RuntimeException unavailable) {
                return false;
            }
        }
        // STATUS_BAR_SERVICE has no virtual backend. SYSTEM_ALERT_WINDOW authorizes a real
        // window and still requires the host's special access; neither is a blanket grant.
        return false;
    }
}
