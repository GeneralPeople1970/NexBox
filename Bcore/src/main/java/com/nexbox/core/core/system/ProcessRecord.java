package com.nexbox.core.core.system;

import com.nexbox.core.utils.Slog;

import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.ConditionVariable;
import android.os.IInterface;
import android.os.Process;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.nexbox.core.core.IBActivityThread;
import com.nexbox.core.entity.AppConfig;
import com.nexbox.core.proxy.ProxyManifest;

public class ProcessRecord extends Binder {
    public final ApplicationInfo info;
    final public String processName;
    /** Captured under the package/user lifecycle lock, never inferred from a later reused id. */
    public final long userGeneration;
    /** Accessed under the process-manager lock; a shared UID/process can host multiple packages. */
    final Set<String> hostedPackages = new HashSet<>();
    public IBActivityThread bActivityThread;
    public IInterface appThread;
    public int uid;
    public int pid;
    public int buid;
    public int bpid;
    public int callingBUid;
    public int userId;

    public ConditionVariable initLock = new ConditionVariable();

    public ProcessRecord(ApplicationInfo info, String processName) {
        this(info, processName, 0L);
    }

    public ProcessRecord(ApplicationInfo info, String processName, long userGeneration) {
        this.info = info;
        this.processName = processName;
        this.userGeneration = userGeneration;
        this.hostedPackages.add(info.packageName);
    }

    public int getCallingBUid() {
        return callingBUid;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[]{processName, pid, buid, bpid, uid, pid, userId});
    }

    public String getProviderAuthority() {
        return ProxyManifest.getProxyAuthorities(bpid);
    }

    public AppConfig getClientConfig() {
        AppConfig config = new AppConfig();
        config.packageName = info.packageName;
        config.processName = processName;
        config.bpid = bpid;
        config.buid = buid;
        config.uid = uid;
        config.callingBUid = callingBUid;
        config.userId = userId;
        config.userGeneration = userGeneration;
        config.token = this;
        return config;
    }

    public void kill() {
        if (pid > 0) {
            try {
                Process.killProcess(pid);
            } catch (Throwable e) {
                Slog.e("ProcessRecord", "Container operation failed", e);
            }
        }
    }

    public String getPackageName() {
        return info.packageName;
    }
}
