package com.nexbox.core.core;

import java.util.function.IntUnaryOperator;

/** Mapping is confined to real host-UID calls received inside a configured guest process. */
final class CallingUidPolicy {
    private CallingUidPolicy() { }

    static int resolve(int realUid, int hostUid, int callerPid, int selfPid, int selfGuestUid,
            boolean localSystemProvider, IntUnaryOperator guestUidForPid) {
        if (realUid != hostUid || selfGuestUid < 0 || callerPid <= 0) return realUid;
        if (callerPid == selfPid) return localSystemProvider ? realUid : selfGuestUid;
        int callerGuestUid = guestUidForPid.applyAsInt(callerPid);
        return callerGuestUid < 0 ? realUid : callerGuestUid;
    }
}
