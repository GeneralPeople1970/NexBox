package com.nexbox.core.media;

import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.system.am.IBActivityManagerService;
import com.nexbox.core.fake.frameworks.BActivityManager;

/** Runs in the host media provider, while the original incoming Binder identity is intact. */
final class NexBoxMediaOwnerResolver {
    private NexBoxMediaOwnerResolver() { }

    static NexBoxMediaOwner forInsert() throws RemoteException {
        final int callerUid = Binder.getCallingUid();
        final int callerPid = Binder.getCallingPid();
        if (callerUid != NexBoxCore.getHostUid() || callerPid <= 0) {
            throw new SecurityException("Only a confirmed container writer may create media");
        }
        if (callerPid == Process.myPid()) {
            return NexBoxMediaOwner.host(NexBoxCore.getHostPkg());
        }
        // ProviderCallIdentity correctly sends the host package to Android. That package is
        // transport identity, not media ownership: only the core's PID registry knows the guest.
        IBActivityManagerService service = BActivityManager.get().getService();
        if (service == null) throw new IllegalStateException("Media creator service unavailable");
        Bundle creator = service.resolveMediaCreationOwner(callerPid);
        if (creator == null || !creator.containsKey(NexBoxMediaContract.COL_OWNER_PACKAGE_NAME)
                || !creator.containsKey(NexBoxMediaContract.COL_OWNER_USER_ID)
                || !creator.containsKey(NexBoxMediaContract.COL_OWNER_USER_GENERATION)) {
            throw new SecurityException("Media writer is no longer a registered guest");
        }
        return NexBoxMediaOwner.guest(
                creator.getString(NexBoxMediaContract.COL_OWNER_PACKAGE_NAME),
                creator.getInt(NexBoxMediaContract.COL_OWNER_USER_ID, -1),
                creator.getLong(NexBoxMediaContract.COL_OWNER_USER_GENERATION, 0L));
    }
}
