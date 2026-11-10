package com.nexbox.core.core.system.pm;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.GmsCore;
import com.nexbox.core.core.system.BProcessManagerService;
import com.nexbox.core.core.system.accounts.BAccountManagerService;
import com.nexbox.core.entity.pm.InstallOption;
import com.nexbox.core.entity.pm.InstallResult;
import com.nexbox.core.utils.MutationReceipt;
import com.nexbox.core.utils.MutationReceipt.Rejected;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.compat.BundleCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** One core lifecycle section covers the condition and every step. No mutation is retried. */
final class ConditionalGmsLifecycle {
    private final Backend backend;

    interface Backend {
        Object lifecycleLock();
        Map<String, String> requireInventory(int user, long generation, String token);
        Map<String, String> inventory(int user, long generation);
        Bundle snapshot(int user, long generation);
        ApplicationInfo source(String name) throws PackageManager.NameNotFoundException;
        InstallResult install(String name, ApplicationInfo source, int user, long generation);
        boolean hasAccounts(int user, long generation);
        boolean beginTraceFree(int user, long generation);
        boolean beginReset(int user, long generation);
        void endReset(int user, long generation);
        boolean stop(List<String> names, int user, long generation);
        boolean clearAccounts(int user, long generation);
        boolean clear(String name, int user, long generation);
        boolean uninstall(String name, int user, long generation);
    }

    ConditionalGmsLifecycle(BPackageManagerService packages) { this(new CoreBackend(packages)); }
    ConditionalGmsLifecycle(Backend backend) { this.backend = backend; }

    private static final class CoreBackend implements Backend {
        private final BPackageManagerService packages;
        CoreBackend(BPackageManagerService packages) { this.packages = packages; }
        public Object lifecycleLock() { return packages.getLifecycleLock(); }
        public Map<String, String> requireInventory(int user, long generation, String token) { return packages.requireInventoryLocked(user, generation, token); }
        public Map<String, String> inventory(int user, long generation) { return packages.rawInventoryLocked(user, generation); }
        public Bundle snapshot(int user, long generation) { return packages.getRawPackageInventory(user, generation); }
        public ApplicationInfo source(String name) throws PackageManager.NameNotFoundException { return NexBoxCore.getPackageManager().getApplicationInfo(name, 0); }
        public InstallResult install(String name, ApplicationInfo source, int user, long generation) {
            return packages.installPackageAsUserIfMatches(source.sourceDir, InstallOption.installBySystem(), user, generation, name);
        }
        public boolean hasAccounts(int user, long generation) { return BAccountManagerService.get().hasGoogleAccountsForUser(user, generation); }
        public boolean beginTraceFree(int user, long generation) { return BAccountManagerService.get().beginTraceFreeGoogleInstall(user, generation); }
        public boolean beginReset(int user, long generation) { return BAccountManagerService.get().beginGoogleAccountReset(user, generation); }
        public void endReset(int user, long generation) { BAccountManagerService.get().endGoogleAccountReset(user, generation); }
        public boolean stop(List<String> names, int user, long generation) { return BProcessManagerService.get().stopPackagesForGmsReset(names, user, generation); }
        public boolean clearAccounts(int user, long generation) { return BAccountManagerService.get().clearGoogleAccountsForReset(user, generation); }
        public boolean clear(String name, int user, long generation) { return packages.clearPackageIfMatches(name, user, generation); }
        public boolean uninstall(String name, int user, long generation) { return packages.uninstallPackageAsUserIfMatches(name, user, generation); }
    }

    Bundle install(int userId, long generation, String token, boolean traceFree, String requestId) {
        MutationReceipt receipt = receipt(userId, generation, requestId);
        ArrayList<Bundle> created = new ArrayList<>();
        ArrayList<Bundle> failed = new ArrayList<>();
        ArrayList<Bundle> skipped = new ArrayList<>();
        receipt.data.putParcelableArrayList("createdComponents", created);
        receipt.data.putParcelableArrayList("existingComponents", new ArrayList<Bundle>());
        receipt.data.putParcelableArrayList("failedComponents", failed);
        receipt.data.putParcelableArrayList("skippedComponents", skipped);
        boolean accountGuard = false;
        synchronized (backend.lifecycleLock()) {
            try {
                Map<String, String> inventory = backend.requireInventory(userId, generation, token);
                Map<String, String> existing = environment(inventory);
                receipt.data.putParcelableArrayList("existingComponents", entries(existing));
                if (traceFree && (!existing.isEmpty() || backend.hasAccounts(userId, generation))) {
                    throw new Rejected("gms_traces_present");
                }
                Map<String, ApplicationInfo> sources = new LinkedHashMap<>();
                for (String name : GmsCore.environmentInstallPackages()) {
                    if (existing.containsKey(name)) continue;
                    try {
                        ApplicationInfo info = backend.source(name);
                        if (info == null || !name.equals(info.packageName) || !info.enabled
                                || info.sourceDir == null || info.sourceDir.isEmpty()) throw new PackageManager.NameNotFoundException();
                        sources.put(name, info);
                        Bundle pending = component(name, null);
                        pending.putString("reason", "not_attempted");
                        skipped.add(pending);
                    } catch (PackageManager.NameNotFoundException unavailable) {
                        Bundle component = component(name, null);
                        component.putString("reason", "host_component_unavailable");
                        if (GmsCore.GMS_PKG.equals(name) || GmsCore.VENDING_PKG.equals(name)) {
                            failed.add(component);
                        } else skipped.add(component);
                    }
                }
                if (!failed.isEmpty()) throw new Rejected("gms_source_unavailable");
                // Source lookup does not authorize writes. Recheck the exact inventory at the boundary.
                backend.requireInventory(userId, generation, token);
                if (traceFree) {
                    receipt.begin("trace_free_guard");
                    accountGuard = backend.beginTraceFree(userId, generation);
                    if (!accountGuard) {
                        // The account API returns false only before changing the gate or any session.
                        receipt.data.putString("mutationState", "none");
                        throw new Rejected("gms_traces_present");
                    }
                    receipt.confirmed("googleWritesSuspended");
                }
                for (Map.Entry<String, ApplicationInfo> source : sources.entrySet()) {
                    String name = source.getKey();
                    if (!inventory.equals(backend.inventory(userId, generation))) {
                        return receipt.failure("inventory_changed");
                    }
                    skipped.removeIf(entry -> name.equals(entry.getString("packageName")));
                    Bundle uncertain = component(name, null);
                    uncertain.putString("reason", "installation_unconfirmed");
                    failed.add(uncertain);
                    receipt.begin("install_component");
                    receipt.data.putBoolean("stopRequested", true);
                    InstallResult installed = backend.install(name, source.getValue(), userId, generation);
                    if (installed == null || !installed.success || !name.equals(installed.packageName)
                            || installed.userId != userId || installed.userCreateTime != generation) {
                        return receipt.failure("installation_unconfirmed");
                    }
                    Map<String, String> after = backend.inventory(userId, generation);
                    String version = after.get(name);
                    if (version == null) return receipt.failure("installation_unconfirmed");
                    Bundle entry = component(name, version);
                    created.add(entry);
                    failed.remove(uncertain);
                    receipt.confirmed("componentCreated", entry);
                    receipt.data.putBoolean("saved", true);
                    Map<String, String> expectedAfter = new TreeMap<>(inventory);
                    expectedAfter.put(name, version);
                    if (!expectedAfter.equals(after)) return receipt.failure("inventory_changed");
                    inventory = after;
                }
                receipt.data.putBoolean("requiresRelaunch", !created.isEmpty());
                return receipt.success();
            } catch (Rejected failure) {
                return receipt.failure(failure.code);
            } catch (Exception failure) {
                return receipt.failure("state_unavailable");
            } finally {
                if (accountGuard) backend.endReset(userId, generation);
                finish(receipt, userId, generation);
            }
        }
    }

    Bundle remove(int userId, long generation, String token, List<Bundle> expected,
                  String requestId, boolean reset) {
        MutationReceipt receipt = receipt(userId, generation, requestId);
        ArrayList<Bundle> completed = new ArrayList<>();
        ArrayList<Bundle> failed = new ArrayList<>();
        receipt.data.putParcelableArrayList(reset ? "clearedComponents" : "removedComponents", completed);
        receipt.data.putParcelableArrayList("failedComponents", failed);
        boolean accountGuard = false;
        synchronized (backend.lifecycleLock()) {
            try {
                Map<String, String> inventory = backend.requireInventory(userId, generation, token);
                Map<String, String> components = environment(inventory);
                requireComponents(components, expected);
                receipt.begin("close_accounts");
                accountGuard = backend.beginReset(userId, generation);
                if (!accountGuard) return receipt.failure("account_reset_unconfirmed");
                receipt.confirmed("accountSessionsClosed");
                receipt.data.putBoolean("accountSessionsClosed", true);
                receipt.begin("stop_components");
                receipt.data.putBoolean("stopRequested", true);
                boolean stopped = backend.stop(new ArrayList<>(components.keySet()), userId, generation);
                receipt.data.putBoolean("stopConfirmed", stopped);
                if (!stopped) return receipt.failure("stop_unconfirmed");
                receipt.confirmed("componentsStopped");
                receipt.data.putBoolean("requiresRelaunch", !components.isEmpty());
                if (reset) {
                    receipt.begin("clear_accounts");
                    if (!backend.clearAccounts(userId, generation)) {
                        return receipt.failure("account_clear_unconfirmed");
                    }
                    receipt.confirmed("googleAccountsCleared");
                    receipt.data.putBoolean("accountsCleared", true);
                }
                for (Map.Entry<String, String> entry : components.entrySet()) {
                    // The whole batch holds the lifecycle lock. Still verify the exact component before its write.
                    if (!inventory.equals(backend.inventory(userId, generation))) {
                        return receipt.failure("inventory_changed");
                    }
                    receipt.begin(reset ? "clear_component" : "uninstall_component");
                    Bundle uncertain = component(entry.getKey(), entry.getValue());
                    uncertain.putString("reason", "component_mutation_unconfirmed");
                    failed.add(uncertain);
                    boolean changed = reset
                            ? backend.clear(entry.getKey(), userId, generation)
                            : backend.uninstall(entry.getKey(), userId, generation);
                    if (!changed) return receipt.failure("component_mutation_unconfirmed");
                    Bundle fact = component(entry.getKey(), entry.getValue());
                    completed.add(fact);
                    failed.remove(uncertain);
                    receipt.confirmed(reset ? "componentDataCleared" : "componentRemoved", fact);
                    receipt.data.putBoolean("saved", true);
                    Map<String, String> after = backend.inventory(userId, generation);
                    Map<String, String> expectedAfter = new TreeMap<>(inventory);
                    if (reset) {
                        String version = after.get(entry.getKey());
                        if (version == null) return receipt.failure("component_mutation_unconfirmed");
                        expectedAfter.put(entry.getKey(), version);
                    } else expectedAfter.remove(entry.getKey());
                    if (!expectedAfter.equals(after)) return receipt.failure("inventory_changed");
                    inventory = after;
                }
                return receipt.success();
            } catch (Rejected failure) {
                return receipt.failure(failure.code);
            } catch (Exception failure) {
                return receipt.failure("state_unavailable");
            } finally {
                if (accountGuard) backend.endReset(userId, generation);
                finish(receipt, userId, generation);
            }
        }
    }

    private void finish(MutationReceipt receipt, int userId, long generation) {
        try {
            Bundle after = backend.snapshot(userId, generation);
            receipt.data.putBundle("inventoryAfter", after);
            if (after.getBoolean("complete")) {
                ArrayList<Bundle> remaining = new ArrayList<>();
                ArrayList<?> entries = BundleCompat.getParcelableArrayList(after, "packages");
                for (Object value : entries) {
                    Bundle entry = Bundle.class.cast(value);
                    if (GmsCore.isGmsEnvironmentComponent(entry.getString("packageName"))) remaining.add(entry);
                }
                receipt.data.putParcelableArrayList("remainingComponents", remaining);
                receipt.data.putBoolean("remainingComponentsComplete", true);
            }
        } catch (Exception unavailable) {
            receipt.data.putBoolean("remainingComponentsComplete", false);
        }
        Slog.i("ConditionalGms", "requestId=" + receipt.data.getString("requestId")
                + " userId=" + userId + " generation=" + generation + " stage=" + receipt.data.getString("stage")
                + " mutationState=" + receipt.data.getString("mutationState")
                + " success=" + receipt.data.getBoolean("success") + " reason=" + receipt.data.getString("errorCode"));
    }

    private static MutationReceipt receipt(int userId, long generation, String requestId) {
        MutationReceipt value = new MutationReceipt(requestId);
        value.data.putInt("userId", userId);
        value.data.putLong("createTime", generation);
        value.data.putBoolean("saved", false);
        value.data.putBoolean("stopRequested", false);
        value.data.putBoolean("stopConfirmed", false);
        value.data.putBoolean("requiresRelaunch", false);
        // Conditional batches keep confirmed partial work; they never reinstall or roll it back implicitly.
        value.data.putBoolean("rollbackAttempted", false);
        value.data.putBoolean("rollbackConfirmed", false);
        value.data.putBoolean("remainingComponentsComplete", false);
        return value;
    }

    static Map<String, String> environment(Map<String, String> inventory) {
        Map<String, String> result = new TreeMap<>();
        for (Map.Entry<String, String> entry : inventory.entrySet()) {
            if (GmsCore.isGmsEnvironmentComponent(entry.getKey())) result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static ArrayList<Bundle> entries(Map<String, String> values) {
        ArrayList<Bundle> result = new ArrayList<>();
        for (Map.Entry<String, String> value : values.entrySet()) result.add(component(value.getKey(), value.getValue()));
        return result;
    }

    static void requireComponents(Map<String, String> current, List<Bundle> expected) {
        if (expected == null || expected.size() > 1024) throw new Rejected("invalid_request");
        Map<String, String> supplied = new TreeMap<>();
        for (Bundle entry : expected) {
            if (entry == null || entry.size() != 2) throw new Rejected("invalid_request");
            String name = entry.getString("packageName");
            String version = entry.getString("installationVersion");
            if (name == null || version == null || version.length() > 128
                    || !GmsCore.isGmsEnvironmentComponent(name) || supplied.put(name, version) != null) {
                throw new Rejected("invalid_request");
            }
        }
        if (!current.equals(supplied)) throw new Rejected("inventory_changed");
    }

    private static Bundle component(String name, String version) {
        Bundle entry = new Bundle();
        entry.putString("packageName", name);
        entry.putString("installationVersion", version);
        return entry;
    }
}
