package com.nexbox.core.core.system.xposed;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Binder;

import java.util.List;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.system.BProcessManagerService;
import com.nexbox.core.core.system.ProcessRecord;
import com.nexbox.core.utils.Slog;

public final class XposedCallerGuard {
    private XposedCallerGuard() {}

    /** Legacy manager entry points also serve direct calls made by the core during startup/cleanup. */
    public static void enforceHostManagerOrCore() {
        if (NexBoxCore.get().isServerProcess()
                && Binder.getCallingPid() == android.os.Process.myPid()
                && Binder.getCallingUid() == NexBoxCore.getHostUid()) {
            return;
        }
        // A direct Java call on a Binder thread retains its remote caller. Do not trust the
        // receiving core process alone, or a guest could become a manager via nested calls.
        enforceHostManager();
    }

    /** UID alone is not a boundary: all virtual processes use the host's Linux UID. */
    public static void enforceHostManager() {
        int pid = Binder.getCallingPid();
        int uid = Binder.getCallingUid();
        int hostUid = NexBoxCore.getHostUid();
        ProcessRecord guest = BProcessManagerService.get().findProcessByPid(pid);
        // Local service fallbacks must never query a guest-hooked ActivityManager. Only the
        // unhooked host main process or the real :nexbox_core server may evaluate manager callers.
        boolean untrustedReceiver = !NexBoxCore.get().isMainProcess() && !NexBoxCore.get().isServerProcess();
        ActivityManager.RunningAppProcessInfo process = uid == hostUid && guest == null && !untrustedReceiver
                ? systemProcess(pid) : null;
        if (!XposedAccessPolicy.canManage(pid, uid, hostUid,
                process == null ? -1 : process.pid, process == null ? -1 : process.uid,
                process == null ? null : process.processName, NexBoxCore.getHostPkg(), guest != null || untrustedReceiver)) {
            Slog.w("XposedCallerGuard", "Rejected manager caller pid=" + pid + " uid=" + uid
                    + " process=" + (process == null ? "unresolved" : process.processName)
                    + " registeredGuest=" + (guest != null) + " untrustedReceiver=" + untrustedReceiver);
            throw new SecurityException("Only the NexBox main process can manage container state");
        }
    }

    public static ProcessRecord requireGuest() {
        int pid = Binder.getCallingPid();
        ProcessRecord record = BProcessManagerService.get().findProcessByPid(pid);
        if (record == null || record.pid != pid
                || !XposedAccessPolicy.canReadGuest(Binder.getCallingUid(), NexBoxCore.getHostUid(),
                record.userId, record.getPackageName(), NexBoxCore.getHostPkg())) {
            throw new SecurityException("An initialized virtual app process is required");
        }
        return record;
    }

    /**
     * Ask system_server for the Binder caller's identity, not this server's process name.
     * Reading another process's /proc/cmdline depends on dumpability/SELinux and can fail for
     * a non-debuggable Release build even when both processes share a UID. The framework API
     * works for our own UID in either build type and does not depend on obfuscated class names.
     * Never cache a PID: after process death it may identify a different host/guest process.
     */
    private static ActivityManager.RunningAppProcessInfo systemProcess(int pid) {
        if (pid <= 0) return null;
        return CallingIdentityScope.read(Binder::clearCallingIdentity, Binder::restoreCallingIdentity, () -> {
            try {
                ActivityManager manager = (ActivityManager) NexBoxCore.getContext()
                        .getSystemService(Context.ACTIVITY_SERVICE);
                if (manager == null) return null;
                // Bcore's virtual getRunningAppProcesses hook is guest-only. A manager call is
                // handled in :nexbox_core or the unhooked host main process, so this is the real AMS list.
                List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
                if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes) {
                    if (process != null && process.pid == pid) return process;
                }
                return null;
            } catch (RuntimeException e) {
                Slog.w("XposedCallerGuard", "Cannot resolve manager caller pid=" + pid, e);
                return null; // Fail closed; never promote an unknown same-UID process to the manager.
            }
        });
    }
}
