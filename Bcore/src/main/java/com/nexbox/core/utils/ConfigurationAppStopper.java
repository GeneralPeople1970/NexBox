package com.nexbox.core.utils;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.system.pm.IBPackageManagerService;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.fake.frameworks.BUserManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Set;

/** IO-only post-commit stops. Selectors choose scope; user replacement cannot redirect a stale stop. */
public final class ConfigurationAppStopper {
    private ConfigurationAppStopper() { }

    public static final class Target {
        public final String packageName;
        public final int userId;
        public final long generation;

        public Target(String packageName, int userId, long generation) {
            if (packageName == null || packageName.isEmpty() || userId < 0 || generation <= 0L) {
                throw new IllegalArgumentException("A current app identity is required");
            }
            this.packageName = packageName;
            this.userId = userId;
            this.generation = generation;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Target)) return false;
            Target target = (Target) other;
            return userId == target.userId && generation == target.generation && packageName.equals(target.packageName);
        }

        @Override public int hashCode() { return Objects.hash(packageName, userId, generation); }
    }

    public static void stop(int userId, long generation, Set<String> packages) {
        List<Target> targets = new ArrayList<>();
        for (String packageName : packages) targets.add(new Target(packageName, userId, generation));
        stop(targets);
    }

    public static void stop(Collection<Target> targets) {
        List<Target> failed = new ArrayList<>();
        List<Exception> causes = new ArrayList<>();
        Map<String, List<Target>> groups = new LinkedHashMap<>();
        for (Target target : new LinkedHashSet<>(targets)) {
            String key = target.userId + ":" + target.generation;
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(target);
        }
        long deadline = SystemClock.elapsedRealtime() + 10_000L;
        for (List<Target> group : groups.values()) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                failed.addAll(group);
                continue;
            }
            Target owner = group.get(0);
            try {
                IBPackageManagerService packages = NexBoxCore.getBPackageManager().getService();
                if (packages == null) throw new IllegalStateException("Package service unavailable");
                BUserInfo user = BUserManager.get().getService().getUserInfo(owner.userId);
                if (user == null || user.createTime != owner.generation) continue;
                List<String> names = new ArrayList<>();
                for (Target target : group) {
                    ApplicationInfo app = packages.getApplicationInfo(target.packageName, PackageManager.GET_META_DATA, target.userId);
                    if (app == null || target.packageName.equals(NexBoxCore.getHostPkg())) continue;
                    names.add(target.packageName);
                }
                if (!names.isEmpty() && !packages.stopPackagesIfMatches(names, owner.userId, owner.generation)) {
                    throw new IllegalStateException("App stops could not be confirmed; a process may host an app outside this scope");
                }
            } catch (Exception failure) {
                failed.addAll(group);
                causes.add(failure);
                Slog.w("ConfigurationStop", "saved=true userId=" + owner.userId + " generation="
                        + owner.generation + " unconfirmedTargets=" + group.size(), failure);
            }
        }
        if (!failed.isEmpty()) {
            ConfigurationStopException failure = new ConfigurationStopException(failed);
            for (Exception cause : causes) failure.addSuppressed(cause);
            throw failure;
        }
    }

    /** Capture before saving a truly global runtime setting, including module configuration apps. */
    public static List<Target> captureAllVirtualApps() {
        try {
            List<BUserInfo> users = BUserManager.get().getService().getUsers();
            IBPackageManagerService packages = NexBoxCore.getBPackageManager().getService();
            if (users == null || packages == null) throw new IllegalStateException("Virtual app list unavailable");
            List<Target> targets = new ArrayList<>();
            for (BUserInfo user : users) {
                if (user == null || user.createTime <= 0L) throw new IllegalStateException("Incomplete user identity");
                List<ApplicationInfo> installed = packages.getInstalledApplications(PackageManager.GET_META_DATA, user.id);
                if (installed == null) throw new IllegalStateException("Virtual app list unavailable");
                for (ApplicationInfo app : installed) {
                    if (app == null || app.packageName == null) throw new IllegalStateException("Incomplete app metadata");
                    if (!app.packageName.equals(NexBoxCore.getHostPkg())) targets.add(new Target(app.packageName, user.id, user.createTime));
                }
                BUserInfo confirmed = BUserManager.get().getService().getUserInfo(user.id);
                if (confirmed == null || confirmed.createTime != user.createTime) throw new IllegalStateException("Virtual user changed");
            }
            return targets;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not capture virtual apps for a global setting", failure);
        }
    }
}
