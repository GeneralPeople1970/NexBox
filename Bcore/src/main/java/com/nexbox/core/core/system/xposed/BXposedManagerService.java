package com.nexbox.core.core.system.xposed;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.core.system.BProcessManagerService;
import com.nexbox.core.core.system.ISystemService;
import com.nexbox.core.core.system.ProcessRecord;
import com.nexbox.core.core.system.pm.BPackageManagerService;
import com.nexbox.core.core.system.pm.PackageMonitor;
import com.nexbox.core.core.system.user.BUserManagerService;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.entity.pm.InstalledModule;
import com.nexbox.core.entity.pm.ModuleLoadState;
import com.nexbox.core.entity.pm.XposedRuntimeStatus;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.xposed.RuntimeCapabilities;

/** The server is the only owner of persistent module selections. Guest processes only read. */
public final class BXposedManagerService extends IBXposedManagerService.Stub implements ISystemService {
    private static final String TAG = "BXposedManager";
    private static final int MAX_REPORTS = 512;
    private static final int MAX_DETAIL = 2048;

    private static final class Holder {
        static final BXposedManagerService INSTANCE = new BXposedManagerService();
    }

    public static BXposedManagerService get() { return Holder.INSTANCE; }

    private final XposedConfigStore store = new XposedConfigStore(
            new AtomicXposedConfigStorage(BEnvironment.getXPStateConf()));
    private final Object reportLock = new Object();
    private final LinkedHashMap<String, ModuleLoadState> reports = new LinkedHashMap<>();
    private final Map<Integer, Map<String, IssuedModuleState>> issuedModules = new HashMap<>();
    // ProcessRecord has a mutable value hashCode. Bind services to object identity, never that hash.
    private final Map<ProcessRecord, ModuleAppService> moduleAppServices = new java.util.IdentityHashMap<>();
    private volatile boolean ready;

    private BXposedManagerService() {}

    @Override public synchronized void systemReady() {
        if (ready) return;
        store.load();
        if (!store.snapshot().legacyMigrated && store.error() == null) {
            TreeSet<String> legacy = new TreeSet<>();
            File oldFile = BEnvironment.getXPModuleConf();
            try {
                if (oldFile.isFile() && oldFile.length() <= XposedConfigState.MAX_BYTES) {
                    legacy.addAll(XposedConfigState.parseLegacy(new String(Files.readAllBytes(oldFile.toPath()), StandardCharsets.UTF_8)));
                }
                Set<String> oldPrefs = NexBoxCore.getContext()
                        .getSharedPreferences("xp_modules", Context.MODE_PRIVATE)
                        .getStringSet("enabled", Collections.emptySet());
                if (oldPrefs != null) for (String name : oldPrefs) if (XposedConfigState.isPackage(name)) legacy.add(name);
                if (!store.migrateLegacy(legacy)) Slog.w(TAG, store.error());
            } catch (IOException | RuntimeException e) {
                // Leave migration unfinished, so a later launch can retry. Nothing is auto-enabled.
                Slog.w(TAG, "Cannot read legacy Xposed selections: " + e.getMessage(), e);
            }
        }
        BPackageManagerService.get().addPackageMonitor(new PackageMonitor() {
            @Override public void onPackageUninstalled(String packageName, boolean isRemove, int userId) {
                if (!XposedConfigState.isPackage(packageName)) return;
                boolean allUsers = isRemove || userId < 0;
                if (!store.removePackage(userId, packageName, allUsers)) Slog.w(TAG, store.error());
                markRestartRequired(packageName, allUsers ? -1 : userId, true);
            }

            @Override public void onPackageInstalled(String packageName, int userId) {
                // APKs are shared across virtual users; replacing one can affect every scope.
                markRestartRequired(packageName, -1, false);
            }
        });
        ready = true;
    }

    private void ensureReady() { if (!ready) systemReady(); }

    @Override public List<InstalledModule> getInstalledModules(int userId) {
        XposedCallerGuard.enforceHostManager();
        XposedConfigState.requireUser(userId);
        ensureReady();
        XposedConfigState state = store.snapshot();
        Map<String, XposedConfigState.ModuleConfig> selected = state.modules(userId);
        ArrayList<InstalledModule> result = new ArrayList<>();
        for (PackageInfo info : BPackageManagerService.get().getInstalledPackageSnapshots(userId)) {
            if (!XposedModuleInspector.isCandidate(info) && !selected.containsKey(info.packageName)) continue;
            InstalledModule module = XposedModuleInspector.inspect(info, userId);
            applySelection(module, selected.get(module.packageName), state);
            result.add(module);
        }
        result.sort(Comparator.comparing(module -> module.packageName));
        return result;
    }

    @Override public Bundle setModuleConfig(int userId, String modulePackage, boolean enabled, List<String> scope,
                                            long expectedUserCreateTime) {
        XposedCallerGuard.enforceHostManager();
        ensureReady();
        Bundle result = new Bundle();
        result.putLong("createTime", expectedUserCreateTime);
        Set<String> affected;
        try {
            synchronized (BPackageManagerService.get().getLifecycleLock()) {
                XposedConfigState.requireUser(userId);
                XposedConfigState.requirePackage(modulePackage);
                BUserInfo user = BUserManagerService.get().getUserInfo(userId);
                if (user == null || expectedUserCreateTime <= 0L || user.createTime != expectedUserCreateTime
                        || NexBoxCore.getHostPkg().equals(modulePackage)) return result;
                if (scope == null || scope.size() > 1000) return result;
                if (enabled && scope.isEmpty()) return result;
                PackageInfo info = BPackageManagerService.get().getInstalledPackageSnapshot(modulePackage, userId);
                if (info == null) return result;
                InstalledModule module = XposedModuleInspector.inspect(info, userId);
                if (enabled && !module.supported) return result;
                TreeSet<String> targets = new TreeSet<>();
                for (String target : scope) {
                    XposedConfigState.requirePackage(target);
                    PackageInfo targetInfo = BPackageManagerService.get().getInstalledPackageSnapshot(target, userId);
                    if (NexBoxCore.getHostPkg().equals(target) || "com.nexbox.gallery".equals(target) || targetInfo == null) return result;
                    boolean targetIsModule = XposedModuleInspector.isCandidate(targetInfo);
                    if (!XposedModulePolicy.allowsTarget(modulePackage, target, targetIsModule,
                            module.staticScope, module.recommendedScope)) {
                        // A narrowed declaration must still allow disabling a previously saved config.
                        if (enabled) return result;
                        if (targetIsModule || modulePackage.equals(target)) continue;
                    }
                    targets.add(target);
                }
                XposedConfigState.ModuleConfig previous = store.snapshot().modules(userId).get(modulePackage);
                XposedConfigState.ModuleConfig next = new XposedConfigState.ModuleConfig(enabled, targets);
                if (previous != null && previous.enabled == next.enabled && previous.scope.equals(next.scope)) {
                    result.putBoolean("saved", true);
                    return result;
                }
                affected = changedInjectionTargets(previous, next);
                // Never hold the configuration/report lock while calling PMS. Package callbacks run under its lock.
                if (!store.setModule(userId, modulePackage, enabled, targets)) {
                    Slog.w(TAG, store.error());
                    return result;
                }
                result.putBoolean("saved", true);
                markChangedTargets(modulePackage, userId, affected);
            }
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Invalid Xposed module selection: " + e.getMessage(), e);
            return result;
        }
        // Binder death cleanup also enters this service; never wait while holding store/report locks.
        result.putStringArrayList("failedTargets", stopChangedTargets(userId, expectedUserCreateTime, affected));
        return result;
    }

    static Set<String> changedInjectionTargets(XposedConfigState.ModuleConfig previous,
                                               XposedConfigState.ModuleConfig next) {
        Set<String> before = previous != null && previous.enabled ? previous.scope : Collections.emptySet();
        Set<String> after = next.enabled ? next.scope : Collections.emptySet();
        Set<String> changed = new TreeSet<>(before);
        changed.addAll(after);
        Set<String> unchanged = new TreeSet<>(before);
        unchanged.retainAll(after);
        changed.removeAll(unchanged);
        return changed;
    }

    private ArrayList<String> stopChangedTargets(int userId, long generation, Set<String> affected) {
        ArrayList<String> targets = new ArrayList<>();
        if (affected.isEmpty()) return targets;
        try {
            synchronized (BPackageManagerService.get().getLifecycleLock()) {
                BUserInfo user = BUserManagerService.get().getUserInfo(userId);
                if (user == null || user.createTime != generation) return targets;
                for (String target : affected) {
                    PackageInfo info = BPackageManagerService.get().getInstalledPackageSnapshot(target, userId);
                    if (info != null && !NexBoxCore.getHostPkg().equals(target) && !"com.nexbox.gallery".equals(target)) targets.add(target);
                }
                if (targets.isEmpty()) return targets;
                if (BProcessManagerService.get().stopPackagesIfMatches(targets, userId, generation)) {
                    return new ArrayList<>();
                }
            }
        } catch (RuntimeException failure) {
            // All failures after commit describe an unconfirmed stop, never a failed save.
            targets = new ArrayList<>(affected);
            Slog.w(TAG, "configurationSaved=true targetStopFailed=true userId=" + userId, failure);
        }
        Slog.w(TAG, "configurationSaved=true targetStopUnconfirmed=true userId=" + userId
                + " generation=" + generation + " targets=" + targets);
        return targets;
    }

    private void markChangedTargets(String modulePackage, int userId, Set<String> affected) {
        synchronized (reportLock) {
            for (Map<String, IssuedModuleState> issued : issuedModules.values()) {
                for (IssuedModuleState module : issued.values()) {
                    if (module.userId == userId && module.modulePackage.equals(modulePackage)
                            && affected.contains(module.targetPackage)) module.invalidate(modulePackage, userId, false);
                }
            }
            for (ModuleLoadState report : reports.values()) {
                if (report.userId == userId && modulePackage.equals(report.modulePackage)
                        && affected.contains(report.targetPackage)) report.restartRequired = true;
            }
        }
    }

    @Override public List<String> getPendingLegacySelections() {
        XposedCallerGuard.enforceHostManager();
        ensureReady();
        return new ArrayList<>(store.snapshot().pendingLegacy);
    }

    @Override public XposedRuntimeStatus getRuntimeStatus() {
        XposedCallerGuard.enforceHostManager();
        ensureReady();
        XposedRuntimeStatus result = new XposedRuntimeStatus();
        result.backend = "LSPlant";
        result.apiVersion = XposedModuleInspector.API_VERSION;
        result.configRevision = store.snapshot().revision;
        result.available = RuntimeCapabilities.supportsAndroid(Build.VERSION.SDK_INT)
                && RuntimeCapabilities.supportsDeviceAbi(Build.SUPPORTED_ABIS) && store.error() == null;
        if (store.error() != null) {
            result.state = "configuration_error";
            result.message = store.error();
        } else if (!RuntimeCapabilities.supportsAndroid(Build.VERSION.SDK_INT)) {
            result.state = "unsupported_android";
            result.message = "This runtime accepts Android API 31–37 only.";
        } else if (!RuntimeCapabilities.supportsDeviceAbi(Build.SUPPORTED_ABIS)) {
            result.state = "unsupported_abi";
            result.message = "Native ARM is required for ART hooks. An x86 emulator with ARM translation cannot hook its x86 ART runtime.";
        } else {
            result.state = "available_unverified";
            result.message = "Legacy API 93 / libxposed API 101–102 Java hooks in scoped guest apps. No system/resource hooks, privileged remote service or hot reload. Changes require restarting target apps; per-process reports show load attempts.";
        }
        return result;
    }

    @Override public List<InstalledModule> getModulesForCurrentProcess() {
        ProcessRecord process = XposedCallerGuard.requireGuest();
        ensureReady();
        if (store.error() != null) {
            Slog.w(TAG, "Skipping modules because configuration is unavailable: " + store.error());
            return Collections.emptyList();
        }
        PackageInfo target = BPackageManagerService.get()
                .getInstalledPackageSnapshot(process.getPackageName(), process.userId);
        if (target == null || NexBoxCore.getHostPkg().equals(process.getPackageName())
                || "com.nexbox.gallery".equals(process.getPackageName())) {
            // Enforce even for configurations saved by an older version of the manager.
            synchronized (reportLock) { issuedModules.remove(process.pid); }
            return Collections.emptyList();
        }
        XposedConfigState state = store.snapshot();
        ArrayList<InstalledModule> result = new ArrayList<>();
        Map<String, IssuedModuleState> issued = new HashMap<>();
        for (Map.Entry<String, XposedConfigState.ModuleConfig> entry : state.modules(process.userId).entrySet()) {
            if (!entry.getValue().enabled || !entry.getValue().scope.contains(process.getPackageName())) continue;
            PackageInfo info = BPackageManagerService.get().getInstalledPackageSnapshot(entry.getKey(), process.userId);
            if (info == null) continue; // No user-0 or host fallback, even during uninstall races.
            InstalledModule module = XposedModuleInspector.inspect(info, process.userId);
            if (!XposedModulePolicy.allowsTarget(module.packageName, process.getPackageName(),
                    XposedModuleInspector.isCandidate(target), module.staticScope, module.recommendedScope)) continue;
            applySelection(module, entry.getValue(), state);
            result.add(module);
            issued.put(module.packageName, new IssuedModuleState(process.userId, module.packageName,
                    process.getPackageName(), entry.getValue()));
        }
        synchronized (reportLock) { issuedModules.put(process.pid, issued); }
        return result;
    }

    /** Official module-app service handshake. No caller-supplied package or virtual user. */
    @Override public IBinder getModuleServiceForCurrentProcess() {
        ProcessRecord process = XposedCallerGuard.requireGuest();
        ensureReady();
        if (enabledModuleFor(process) == null) return null;
        synchronized (moduleAppServices) {
            return moduleAppServices.computeIfAbsent(process,
                    owner -> new ModuleAppService(owner, this)).asBinder();
        }
    }

    /** Each service transaction revalidates the live process and its current enabled selection. */
    XposedConfigState.ModuleConfig requireModuleApp(ProcessRecord owner) {
        ProcessRecord current = XposedCallerGuard.requireGuest();
        if (current != owner) throw new SecurityException("The Xposed service belongs to another module process");
        XposedConfigState.ModuleConfig config = enabledModuleFor(current);
        if (config == null) throw new SecurityException("This Xposed module is not enabled in this user");
        return config;
    }

    private XposedConfigState.ModuleConfig enabledModuleFor(ProcessRecord process) {
        if (store.error() != null) return null;
        XposedConfigState.ModuleConfig config = store.snapshot().modules(process.userId).get(process.getPackageName());
        if (config == null || !config.enabled) return null;
        PackageInfo info = BPackageManagerService.get().getInstalledPackageSnapshot(process.getPackageName(), process.userId);
        if (info == null || !XposedModuleInspector.isCandidate(info)
                || !XposedModuleInspector.inspect(info, process.userId).supported) return null;
        return config;
    }

    /** A module can narrow its own selection, but cannot approve new hooks without the host UI. */
    void removeModuleAppScope(ProcessRecord owner, List<String> packages) {
        Set<String> affected;
        synchronized (BPackageManagerService.get().getLifecycleLock()) {
            XposedConfigState.ModuleConfig config = requireModuleApp(owner);
            BUserInfo user = BUserManagerService.get().getUserInfo(owner.userId);
            if (user == null || user.createTime != owner.userGeneration) {
                throw new SecurityException("The module scope belongs to another user lifetime");
            }
            if (packages == null || packages.size() > 1000) throw new IllegalArgumentException("Invalid scope");
            for (String name : packages) XposedConfigState.requirePackage(name);
            TreeSet<String> remaining = new TreeSet<>(config.scope);
            if (!remaining.removeAll(packages)) return;
            affected = changedInjectionTargets(config, new XposedConfigState.ModuleConfig(config.enabled, remaining));
            if (!store.removeScopeIfUnchanged(owner.userId, owner.getPackageName(), config, packages)) {
                throw new IllegalStateException(store.error() == null ? "Module configuration changed; try again" : store.error());
            }
            markChangedTargets(owner.getPackageName(), owner.userId, affected);
        }
        if (!stopChangedTargets(owner.userId, owner.userGeneration, affected).isEmpty()) {
            throw new IllegalStateException("Module scope was saved, but target stops could not be confirmed");
        }
    }

    @Override public void reportModuleLoad(String modulePackage, boolean loaded, String detail) {
        ProcessRecord process = XposedCallerGuard.requireGuest();
        ensureReady();
        XposedConfigState current = store.snapshot();
        synchronized (reportLock) {
            Map<String, IssuedModuleState> issued = issuedModules.get(process.pid);
            IssuedModuleState issuedState = issued == null ? null : issued.get(modulePackage);
            if (issuedState == null) throw new SecurityException("Module was not selected for this guest process");
            ModuleLoadState report = new ModuleLoadState();
            report.userId = process.userId;
            report.pid = process.pid;
            report.targetPackage = process.getPackageName();
            report.processName = process.processName;
            report.modulePackage = modulePackage;
            report.loaded = loaded;
            report.detail = detail == null ? "" : detail.substring(0, Math.min(detail.length(), MAX_DETAIL));
            report.timestamp = System.currentTimeMillis();
            report.processAlive = true;
            report.restartRequired = issuedState.requiresRestart(current);
            String key = process.pid + ":" + modulePackage;
            reports.remove(key);
            reports.put(key, report);
            while (reports.size() > MAX_REPORTS) reports.remove(reports.keySet().iterator().next());
            String diagnostic = "action=moduleLoad module=" + modulePackage + " target=" + report.targetPackage
                    + " userId=" + report.userId + " pid=" + report.pid + " process=" + report.processName
                    + " loaded=" + loaded + " restartRequired=" + report.restartRequired + " detail=" + report.detail;
            if (loaded) Slog.i(TAG, diagnostic); else Slog.e(TAG, diagnostic);
        }
    }

    @Override public List<ModuleLoadState> getModuleStates(int userId) {
        XposedCallerGuard.enforceHostManager();
        XposedConfigState.requireUser(userId);
        ensureReady();
        ArrayList<ModuleLoadState> result = new ArrayList<>();
        synchronized (reportLock) {
            for (ModuleLoadState state : reports.values()) if (state.userId == userId) result.add(new ModuleLoadState(state));
        }
        for (ModuleLoadState state : result) {
            ProcessRecord record = BProcessManagerService.get().findProcessByPid(state.pid);
            state.processAlive = state.processAlive && record != null && record.userId == userId
                    && state.targetPackage.equals(record.getPackageName()) && state.processName.equals(record.processName);
        }
        result.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        return result;
    }

    /** Local server lifecycle hooks; never exposed as Binder mutations. */
    public void onProcessDied(int pid) {
        synchronized (moduleAppServices) { moduleAppServices.keySet().removeIf(owner -> owner.pid == pid); }
        synchronized (reportLock) {
            issuedModules.remove(pid);
            for (ModuleLoadState report : reports.values()) if (report.pid == pid) report.processAlive = false;
        }
    }

    public void onUserDeleted(int userId) {
        if (!ready || userId < 0) return;
        if (!store.removeUser(userId)) throw new IllegalStateException(store.error());
        synchronized (moduleAppServices) { moduleAppServices.keySet().removeIf(owner -> owner.userId == userId); }
        synchronized (reportLock) { reports.entrySet().removeIf(entry -> entry.getValue().userId == userId); }
    }

    private void markRestartRequired(String packageName, int userId, boolean includeTarget) {
        synchronized (reportLock) {
            // Also invalidate an in-progress load that has not produced its first report yet.
            for (Map<String, IssuedModuleState> issued : issuedModules.values())
                for (IssuedModuleState module : issued.values()) module.invalidate(packageName, userId, includeTarget);
            for (ModuleLoadState report : reports.values()) {
                if ((userId < 0 || report.userId == userId)
                        && (packageName.equals(report.modulePackage)
                        || (includeTarget && packageName.equals(report.targetPackage)))) report.restartRequired = true;
            }
        }
    }

    private static void applySelection(InstalledModule module, XposedConfigState.ModuleConfig config, XposedConfigState state) {
        if (config != null) {
            module.enabled = config.enabled;
            module.scope.addAll(config.scope);
        }
        module.pendingLegacySelection = state.pendingLegacy.contains(module.packageName);
    }
}
