package com.nexbox.core.core.system.xposed;

/** Pure policy, separated from Binder/AMS so the privilege boundary can be unit tested. */
public final class XposedAccessPolicy {
    private XposedAccessPolicy() {}

    public static boolean canManage(int callingPid, int callingUid, int hostUid,
                                    int processPid, int processUid, String processName,
                                    String hostPackage, boolean registeredGuest) {
        return !registeredGuest && callingPid > 0 && callingPid == processPid
                && hostUid >= 0 && callingUid == hostUid && processUid == hostUid
                && XposedConfigState.isPackage(hostPackage)
                && hostPackage.equals(processName);
    }

    public static boolean canReadGuest(int callingUid, int hostUid, int userId,
                                      String packageName, String hostPackage) {
        return callingUid == hostUid && userId >= 0 && XposedConfigState.isPackage(packageName)
                && !packageName.equals(hostPackage);
    }
}
