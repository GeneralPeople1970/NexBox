package com.nexbox.core.core.system.pm;

import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ParcelFileDescriptor;
import android.util.AtomicFile;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.core.system.BProcessManagerService;
import com.nexbox.core.core.system.ProcessRecord;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.core.system.user.BUserManagerService;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.compat.BundleCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Guest-only update sessions. No method delegates to the device PackageInstaller. */
public final class GuestPackageInstallerService extends IBGuestPackageInstaller.Stub {
    private static final String STORE = "com.android.vending";
    private static final long LIFETIME = 24L * 60 * 60 * 1000;
    private static final ExecutorService COMMITS = Executors.newSingleThreadExecutor();
    private static final ExecutorService EVENTS = Executors.newSingleThreadExecutor();
    private final Map<Integer, Session> sessions = new LinkedHashMap<>();
    private final ArrayList<Callback> callbacks = new ArrayList<>();
    private boolean loaded;
    private static final class Holder { static final GuestPackageInstallerService INSTANCE = new GuestPackageInstallerService(); }
    static GuestPackageInstallerService get() { return Holder.INSTANCE; }

    /** Called under the package lifecycle lock before deleting owner data or a group. */
    void removeOwnerSessions(int userId, String packageName) {
        try {
            load();
            java.util.Iterator<Session> iterator = sessions.values().iterator();
            while (iterator.hasNext()) {
                Session session = iterator.next();
                if (session.user != userId || (packageName != null && !packageName.equals(session.owner))) continue;
                session.cancelled = true;
                for (SessionFileBridge writer : session.writers.values()) writer.cancel();
                PackageUpdateStore.removeTree(session.root());
                iterator.remove();
            }
            callbacks.removeIf(item -> item.user == userId && (packageName == null || packageName.equals(item.owner)));
        } catch (IOException failure) { throw new IllegalStateException("Cannot clear installer owner state", failure); }
    }

    @Override public Bundle call(int id, String operation, Bundle arguments) {
        synchronized (BPackageManagerService.get().getLifecycleLock()) {
            try {
                ProcessRecord owner = caller();
                load();
                expire();
                Bundle args = arguments == null ? new Bundle() : arguments;
                args.setClassLoader(PackageInstaller.class.getClassLoader());
                Bundle result = new Bundle();
                if ("create".equals(operation)) {
                    if (!STORE.equals(owner.getPackageName())) throw new SecurityException("Guest updates require the container store");
                    if (!owner.getPackageName().equals(args.getString("installer"))) throw new SecurityException("Invalid installer identity");
                    makeRoom();
                    PackageInstaller.SessionParams params = BundleCompat.getParcelable(args, "params", PackageInstaller.SessionParams.class);
                    validateParams(params);
                    Session session = new Session();
                    do { session.id = UUID.randomUUID().hashCode() & Integer.MAX_VALUE; }
                    while (session.id == 0 || sessions.containsKey(session.id));
                    session.owner = owner.getPackageName();
                    session.user = owner.userId;
                    session.generation = owner.userGeneration;
                    session.created = System.currentTimeMillis();
                    session.mode = (Integer) field(params, "mode", -1);
                    session.target = (String) field(params, "appPackageName", null);
                    session.requiredVersion = (Long) field(params, "requiredInstalledVersionCode", -1L);
                    if (session.target != null && !installed(session.target, session.user)) throw new SecurityException("Update target is not installed in this group");
                    if (session.mode == PackageInstaller.SessionParams.MODE_INHERIT_EXISTING && session.target == null) {
                        throw new IllegalArgumentException("Inherit sessions require a target package");
                    }
                    if (!session.files().mkdirs()) throw new IOException("Cannot create session storage");
                    session.save();
                    sessions.put(session.id, session);
                    result.putInt("id", session.id);
                    event(session, "onSessionCreated", session.id);
                    return result;
                }
                if ("list".equals(operation)) {
                    ArrayList<Parcelable> infos = new ArrayList<>();
                    for (Session session : sessions.values()) if (session.ownedBy(owner)) infos.add(session.info());
                    result.putParcelableArrayList("infos", infos);
                    return result;
                }
                if ("register".equals(operation) || "unregister".equals(operation)) {
                    IBinder binder = args.getBinder("callback");
                    if (binder == null) throw new IllegalArgumentException("Missing installer callback");
                    callbacks.removeIf(item -> !item.binder.isBinderAlive() || (item.binder.equals(binder) && item.ownedBy(owner)));
                    if ("register".equals(operation)) {
                        if (callbacks.size() >= 64) throw new IllegalStateException("Too many installer callbacks");
                        callbacks.add(new Callback(owner, binder));
                    }
                    return result;
                }
                Session session = sessions.get(id);
                if (session == null) {
                    if ("info".equals(operation)) return result;
                    throw new IllegalArgumentException("Unknown installer session");
                }
                if (!session.ownedBy(owner)) throw new SecurityException("Installer session belongs to another owner");
                if ("info".equals(operation)) { result.putParcelable("info", session.info()); return result; }
                if ("commit".equals(operation)) {
                    session.mutable();
                    if (args.getBoolean("transferred")) throw new SecurityException("Transferred sessions are not supported");
                    IntentSender receiver = BundleCompat.getParcelable(args, "receiver", IntentSender.class);
                    if (receiver == null) throw new IllegalArgumentException("Missing commit status receiver");
                    long closeDeadline = android.os.SystemClock.elapsedRealtime() + 2000;
                    for (SessionFileBridge writer : session.writers.values()) {
                        if (!writer.awaitClosed(closeDeadline)) throw new IllegalStateException("APK write stream is still open");
                        if (writer.failed) throw new IllegalStateException("APK write failed");
                    }
                    session.sealed = true;
                    session.committed = true;
                    session.save();
                    COMMITS.execute(() -> commit(session, receiver));
                    return result;
                }
                if ("abandon".equals(operation)) {
                    session.mutable();
                    for (SessionFileBridge writer : session.writers.values()) writer.cancel();
                    session.sealed = true;
                    session.status = PackageInstaller.STATUS_FAILURE_ABORTED;
                    session.save();
                    PackageUpdateStore.removeTree(session.files());
                    event(session, "onSessionFinished", id, false);
                    return result;
                }
                if ("open".equals(operation) || "close".equals(operation)) {
                    session.openCount = Math.max(0, session.openCount + ("open".equals(operation) ? 1 : -1));
                    session.active = session.openCount > 0 || (session.committed && session.status < 0);
                    event(session, "onSessionActiveChanged", id, session.active);
                    return result;
                }
                if ("names".equals(operation)) {
                    String[] names = session.files().list();
                    result.putStringArray("names", names == null ? new String[0] : names);
                    return result;
                }
                if ("read".equals(operation)) {
                    result.putParcelable("fd", ParcelFileDescriptor.open(session.file(args.getString("name")), ParcelFileDescriptor.MODE_READ_ONLY));
                    return result;
                }
                session.mutable();
                if ("icon".equals(operation) || "label".equals(operation)) {
                    if ("icon".equals(operation)) session.icon = BundleCompat.getParcelable(args, "icon", android.graphics.Bitmap.class);
                    else session.label = args.getString("label");
                    event(session, "onSessionBadgingChanged", id);
                    return result;
                }
                if ("write".equals(operation)) {
                    String name = args.getString("name");
                    File file = session.file(name);
                    SessionFileBridge old = session.writers.get(name);
                    if (old != null && !old.closed) throw new IllegalStateException("APK is already open for writing");
                    if (session.writers.size() >= 128 && old == null) throw new IllegalStateException("Too many APK files");
                    SessionFileBridge writer = new SessionFileBridge(file, args.getLong("offset"), args.getLong("length", -1),
                            extent -> session.reserve(name, extent));
                    try {
                        writer.bindOwner(owner.bActivityThread.asBinder());
                        ParcelFileDescriptor descriptor = writer.open(NexBoxCore.getContext(), args.getLong("offset"));
                        session.writers.put(name, writer);
                        result.putParcelable("fd", descriptor);
                    } catch (Throwable error) { writer.cancel(); throw error; }
                    return result;
                }
                if ("removeSplit".equals(operation)) {
                    if (session.mode != PackageInstaller.SessionParams.MODE_INHERIT_EXISTING) throw new IllegalStateException("Split removal requires inherit mode");
                    String name = args.getString("name");
                    if (name == null || name.isEmpty() || name.length() > 255) throw new IllegalArgumentException("Invalid split name");
                    if (!session.removed.contains(name)) session.removed.add(name);
                    session.save();
                    return result;
                }
                if ("progress".equals(operation)) {
                    float progress = args.getFloat("progress");
                    if (args.getBoolean("add")) progress += session.progress;
                    if (Float.isNaN(progress) || Float.isInfinite(progress)) throw new IllegalArgumentException("Invalid progress");
                    session.progress = Math.max(0, Math.min(1, progress));
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - session.lastProgressEvent >= 100 || session.progress == 1) {
                        session.lastProgressEvent = now;
                        event(session, "onSessionProgressChanged", id, session.progress);
                    }
                    return result;
                }
                throw new UnsupportedOperationException("Unsupported container installer operation: " + operation);
            } catch (RuntimeException failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("Container installer operation failed", failure); }
        }
    }

    private static ProcessRecord caller() {
        if (Binder.getCallingUid() != NexBoxCore.getHostUid()) throw new SecurityException("Invalid installer transport");
        ProcessRecord record = BProcessManagerService.get().findProcessByPid(Binder.getCallingPid());
        if (record == null || record.userGeneration <= 0 || record.bActivityThread == null || !record.bActivityThread.asBinder().isBinderAlive()) {
            throw new SecurityException("Installer caller is not a live guest");
        }
        BUserInfo user = BUserManagerService.get().getUserInfo(record.userId);
        if (user == null || user.createTime != record.userGeneration || !installed(record.getPackageName(), record.userId)) {
            throw new SecurityException("Stale installer caller");
        }
        return record;
    }

    private static boolean installed(String name, int user) {
        BPackageSettings ps = BPackageManagerService.get().mPackages.get(name);
        return ps != null && ps.getInstalled(user);
    }

    private static void validateParams(PackageInstaller.SessionParams params) throws Exception {
        if (params == null) throw new IllegalArgumentException("Missing session parameters");
        int mode = (Integer) field(params, "mode", -1);
        if (mode != PackageInstaller.SessionParams.MODE_FULL_INSTALL && mode != PackageInstaller.SessionParams.MODE_INHERIT_EXISTING) {
            throw new UnsupportedOperationException("Unsupported installation mode");
        }
        if ((Boolean) field(params, "isStaged", false) || (Boolean) field(params, "isMultiPackage", false)
                || field(params, "dataLoaderParams", null) != null || field(params, "mDataLoaderParams", null) != null) {
            throw new UnsupportedOperationException("Staged, multi-package and data-loader sessions are not supported");
        }
        // These flags describe a normal internal full-app replacement. Other install
        // privileges (APEX, rollback, permissions, instant apps, downgrades) are rejected.
        int flags = (Integer) field(params, "installFlags", 0);
        // The public SessionParams constructor adds the restricted-permission allowlist
        // flag by default. This update path preserves existing permission policy and does
        // not grant/allowlist real-device permissions based on that default bit.
        if ((flags & ~(0x2 | 0x10 | 0x4000 | 0x00400000)) != 0) throw new UnsupportedOperationException("Unsupported installation flags");
        if (field(params, "abiOverride", null) != null || field(params, "volumeUuid", null) != null) {
            throw new UnsupportedOperationException("Unsupported ABI or volume constraint");
        }
        if ((Long) field(params, "requiredInstalledVersionCode", -1L) < -1L) throw new IllegalArgumentException("Invalid installed version constraint");
        if ((Integer) field(params, "requireUserAction", 0) == 1
                || (Boolean) field(params, "forceQueryableOverride", false)
                || (Boolean) field(params, "applicationEnabledSettingPersistent", false)
                || (Integer) field(params, "developmentInstallFlags", 0) != 0
                || (Integer) field(params, "unarchiveId", -1) != -1) {
            throw new UnsupportedOperationException("Unsupported installation policy");
        }
        int location = (Integer) field(params, "installLocation", 1);
        if ((location != 0 && location != 1) || (Long) field(params, "sizeBytes", -1L) > ApkBundle.MAX_DOCUMENT_BYTES) {
            throw new UnsupportedOperationException("Unsupported installation location or size");
        }
        Object permissionStates = field(params, "mPermissionStates", null);
        if (permissionStates instanceof Map && !((Map<?, ?>) permissionStates).isEmpty()) {
            throw new UnsupportedOperationException("Session permission changes are not supported");
        }
    }

    private void commit(Session session, IntentSender receiver) {
        int status = PackageInstaller.STATUS_FAILURE;
        String message = "Container update failed";
        synchronized (BPackageManagerService.get().getLifecycleLock()) {
            try {
                if (session.cancelled) throw new SecurityException("Installer owner was removed");
                BUserInfo user = BUserManagerService.get().getUserInfo(session.user);
                if (user == null || user.createTime != session.generation || !installed(session.owner, session.user)) {
                    throw new SecurityException("Installer owner no longer exists");
                }
                File cluster = session.cluster();
                assemble(session, cluster);
                BPackageManagerService.get().commitSessionUpdate(session.target, cluster, session.user, session.generation, session.requiredVersion,
                        codePath -> { session.committedCodePath = codePath; session.save(); });
                status = PackageInstaller.STATUS_SUCCESS;
                message = "Container application updated";
            } catch (SecurityException failure) { status = PackageInstaller.STATUS_FAILURE_CONFLICT; message = failure.getMessage(); }
            catch (IllegalArgumentException failure) { status = PackageInstaller.STATUS_FAILURE_INVALID; message = failure.getMessage(); }
            catch (Throwable failure) { Slog.w("GuestPackageInstaller", "Container update failed", failure); }
            session.status = status;
            session.active = false;
            if (status == PackageInstaller.STATUS_SUCCESS) session.progress = 1;
            try { if (!session.cancelled) session.save(); }
            catch (IOException failure) { Slog.w("GuestPackageInstaller", "Session receipt persistence failed", failure); }
            try { PackageUpdateStore.removeTree(session.files()); PackageUpdateStore.removeTree(session.cluster()); }
            catch (IOException failure) { Slog.w("GuestPackageInstaller", "Session staging cleanup deferred", failure); }
            event(session, "onSessionActiveChanged", session.id, false);
            if (status == PackageInstaller.STATUS_SUCCESS) event(session, "onSessionProgressChanged", session.id, 1f);
            event(session, "onSessionFinished", session.id, status == PackageInstaller.STATUS_SUCCESS);
        }
        Intent result = new Intent().putExtra(PackageInstaller.EXTRA_SESSION_ID, session.id)
                .putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, session.target)
                .putExtra(PackageInstaller.EXTRA_STATUS, status)
                .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, message)
                .putExtra("android.content.pm.extra.LEGACY_STATUS", status == PackageInstaller.STATUS_SUCCESS ? 1 : -110);
        try { receiver.sendIntent(NexBoxCore.getContext(), 0, result, null, null); }
        catch (IntentSender.SendIntentException failure) { Slog.w("GuestPackageInstaller", "Installer status receiver unavailable", failure); }
    }

    private static void assemble(Session session, File cluster) throws IOException {
        File[] files = session.files().listFiles();
        if (files == null || files.length == 0) throw new IOException("Session contains no APKs");
        Map<String, File> selected = new LinkedHashMap<>();
        long total = 0;
        for (File file : files) {
            total += file.length();
            if (total > ApkBundle.MAX_DOCUMENT_BYTES) throw new IOException("Session exceeds the 4 GiB limit");
            ApkBundle.Manifest manifest = ApkManifest.read(file);
            if (session.target == null) session.target = manifest.packageName;
            if (!session.target.equals(manifest.packageName)) throw new IOException("Session mixes package identities");
            String split = manifest.splitName == null ? "" : manifest.splitName;
            if (selected.put(split, file) != null) throw new IOException("Duplicate split identity");
        }
        BPackageSettings old = BPackageManagerService.get().mPackages.get(session.target);
        if (old == null || !old.getInstalled(session.user)) throw new SecurityException("Update target is not installed in this group");
        if (session.mode == PackageInstaller.SessionParams.MODE_INHERIT_EXISTING) {
            selected.putIfAbsent("", new File(old.pkg.baseCodePath));
            String[] names = old.pkg.applicationInfo.splitNames;
            String[] paths = old.pkg.applicationInfo.splitSourceDirs;
            if (paths != null) {
                if (names == null || names.length != paths.length) throw new IOException("Installed split metadata is inconsistent");
                for (int i = 0; i < names.length; i++) if (!session.removed.contains(names[i])) selected.putIfAbsent(names[i], new File(paths[i]));
            }
        }
        if (!selected.containsKey("")) throw new IOException("Session is missing its base APK");
        for (String removed : session.removed) {
            if (selected.containsKey(removed)) throw new IOException("A removed split was also written");
        }
        ApkBundle.Manifest base = ApkManifest.read(selected.get(""));
        if (base.splitRequired && selected.size() == 1) throw new IOException("Required APK splits are missing");
        for (Map.Entry<String, File> entry : selected.entrySet()) {
            ApkBundle.Manifest manifest = ApkManifest.read(entry.getValue());
            if (!base.packageName.equals(manifest.packageName) || base.versionCode != manifest.versionCode) {
                throw new IOException("APK versions or package identities do not match");
            }
            if ((manifest.usesSplitName != null && !selected.containsKey(manifest.usesSplitName))
                    || (manifest.configForSplit != null && !manifest.configForSplit.isEmpty()
                    && !selected.containsKey(manifest.configForSplit))) {
                throw new IOException("Required split dependency is missing");
            }
        }
        if (!cluster.mkdir()) throw new IOException("Cannot create commit staging");
        int index = 0;
        for (Map.Entry<String, File> entry : selected.entrySet()) {
            PackageUpdateStore.copy(entry.getValue(), new File(cluster, entry.getKey().isEmpty() ? "base.apk" : "split-" + index++ + ".apk"));
        }
    }

    private void load() throws IOException {
        if (loaded) return;
        File root = BEnvironment.getPackageInstallerSessionsDir();
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot open session storage");
        File[] directories = root.listFiles();
        if (directories == null) throw new IOException("Cannot enumerate sessions");
        for (File directory : directories) {
            try {
                byte[] data = new AtomicFile(new File(directory, "state")).readFully();
                Parcel parcel = Parcel.obtain();
                Session session;
                try { parcel.unmarshall(data, 0, data.length); parcel.setDataPosition(0); session = Session.restore(parcel.readBundle()); }
                finally { parcel.recycle(); }
                if (!directory.equals(session.root())) throw new IOException("Invalid session identity");
                // The package metadata, not the existence of staged APKs, proves commit.
                // Uncommitted writes cannot be resumed after losing their close receipts.
                if (session.status == -1) {
                    BPackageSettings actual = BPackageManagerService.get().mPackages.get(session.target);
                    BUserInfo user = BUserManagerService.get().getUserInfo(session.user);
                    boolean applied = session.sealed && session.committedCodePath != null && actual != null
                            && user != null && user.createTime == session.generation && actual.getInstalled(session.user)
                            && session.committedCodePath.equals(actual.pkg.baseCodePath) && new File(actual.pkg.baseCodePath).isFile();
                    session.sealed = true;
                    session.status = applied ? PackageInstaller.STATUS_SUCCESS : PackageInstaller.STATUS_FAILURE_ABORTED;
                    session.save();
                }
                sessions.put(session.id, session);
            } catch (Throwable failure) { Slog.w("GuestPackageInstaller", "Unreadable session retained for cleanup", failure); }
        }
        loaded = true;
    }

    private void expire() {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(session -> {
            BUserInfo user = BUserManagerService.get().getUserInfo(session.user);
            if (user != null && user.createTime == session.generation && now - session.created < LIFETIME) return false;
            if (session.sealed && session.status == -1) return false;
            for (SessionFileBridge writer : session.writers.values()) writer.cancel();
            try { PackageUpdateStore.removeTree(session.root()); return true; }
            catch (IOException failure) { return false; }
        });
    }

    private void makeRoom() throws IOException {
        if (sessions.size() < 32) return;
        java.util.Iterator<Session> iterator = sessions.values().iterator();
        while (iterator.hasNext()) {
            Session session = iterator.next();
            if (session.status < 0) continue;
            PackageUpdateStore.removeTree(session.root());
            iterator.remove();
            if (sessions.size() < 32) return;
        }
        throw new IllegalStateException("Too many active installer sessions");
    }

    private void event(Session session, String method, Object... args) {
        ArrayList<Callback> targets = new ArrayList<>();
        callbacks.removeIf(callback -> !callback.binder.isBinderAlive());
        for (Callback callback : callbacks) if (callback.matches(session)) targets.add(callback);
        EVENTS.execute(() -> {
            for (Callback callback : targets) {
                try {
                    Class<?> stub = Class.forName("android.content.pm.IPackageInstallerCallback$Stub");
                    Object remote = stub.getMethod("asInterface", IBinder.class).invoke(null, callback.binder);
                    Class<?> contract = Class.forName("android.content.pm.IPackageInstallerCallback");
                    for (Method item : contract.getMethods()) if (item.getName().equals(method)) { item.invoke(remote, args); break; }
                } catch (Throwable failure) { Slog.w("GuestPackageInstaller", "Installer callback unavailable"); }
            }
        });
    }

    private static Object field(Object target, String name, Object absent) throws Exception {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (NoSuchFieldException missing) { return absent; }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (NoSuchFieldException missing) { /* Newer diagnostic fields are absent on older Android releases. */ }
    }

    private static final class Callback {
        final String owner; final int user; final long generation; final IBinder binder;
        Callback(ProcessRecord record, IBinder binder) { owner = record.getPackageName(); user = record.userId; generation = record.userGeneration; this.binder = binder; }
        boolean ownedBy(ProcessRecord record) { return user == record.userId && generation == record.userGeneration && owner.equals(record.getPackageName()); }
        boolean matches(Session session) { return user == session.user && generation == session.generation && owner.equals(session.owner); }
    }

    private static final class Session {
        int id, user, mode, openCount, status = -1;
        long generation, created, lastProgressEvent, requiredVersion = -1;
        String owner, target, committedCodePath;
        android.graphics.Bitmap icon;
        String label;
        boolean sealed, active, cancelled, committed;
        float progress;
        ArrayList<String> removed = new ArrayList<>();
        final Map<String, SessionFileBridge> writers = new HashMap<>();
        final Map<String, Long> extents = new HashMap<>();
        long totalExtent;
        synchronized void reserve(String name, long extent) throws IOException {
            long previous = extents.containsKey(name) ? extents.get(name) : 0L;
            if (extent <= previous) return;
            long growth = extent - previous;
            if (growth > ApkBundle.MAX_DOCUMENT_BYTES - totalExtent) throw new IOException("Session exceeds the 4 GiB limit");
            totalExtent += growth;
            extents.put(name, extent);
        }
        File root() { return new File(BEnvironment.getPackageInstallerSessionsDir(), Integer.toString(id)); }
        File files() { return new File(root(), "files"); }
        File cluster() { return new File(root(), "cluster"); }
        File file(String name) throws IOException {
            if (name == null || !name.matches("[A-Za-z0-9_.-]{1,128}\\.apk") || !new File(name).getName().equals(name)) throw new IllegalArgumentException("Invalid APK name");
            File file = new File(files(), name);
            if (!file.getCanonicalFile().getParentFile().equals(files().getCanonicalFile())) throw new SecurityException("Invalid session path");
            return file;
        }
        boolean ownedBy(ProcessRecord record) { return user == record.userId && generation == record.userGeneration && owner.equals(record.getPackageName()); }
        void mutable() { if (sealed) throw new IllegalStateException("Session is sealed"); }
        Parcelable info() throws Exception {
            Object info = PackageInstaller.SessionInfo.class.getConstructor().newInstance();
            set(info, "sessionId", id); set(info, "userId", user); set(info, "installerPackageName", owner);
            set(info, "appPackageName", target); set(info, "mode", mode); set(info, "progress", progress);
            set(info, "sealed", sealed); set(info, "active", active); set(info, "createdMillis", created);
            set(info, "isCommitted", committed); set(info, "isSessionApplied", status == PackageInstaller.STATUS_SUCCESS);
            set(info, "isSessionFailed", status > 0);
            set(info, "appIcon", icon); set(info, "appLabel", label);
            return (Parcelable) info;
        }
        void save() throws IOException {
            Bundle bundle = new Bundle();
            bundle.putInt("id", id); bundle.putInt("user", user); bundle.putLong("generation", generation);
            bundle.putString("owner", owner); bundle.putString("target", target); bundle.putInt("mode", mode);
            bundle.putString("committedCodePath", committedCodePath);
            bundle.putLong("requiredVersion", requiredVersion);
            bundle.putInt("status", status); bundle.putLong("created", created); bundle.putBoolean("sealed", sealed);
            bundle.putBoolean("committed", committed);
            bundle.putStringArrayList("removed", removed);
            AtomicFile state = new AtomicFile(new File(root(), "state"));
            Parcel parcel = Parcel.obtain(); FileOutputStream output = null;
            try { parcel.writeBundle(bundle); output = state.startWrite(); output.write(parcel.marshall()); state.finishWrite(output); }
            catch (IOException failure) { state.failWrite(output); throw failure; }
            finally { parcel.recycle(); }
        }
        static Session restore(Bundle bundle) throws IOException {
            if (bundle == null) throw new IOException("Missing session record");
            Session session = new Session();
            session.id = bundle.getInt("id"); session.user = bundle.getInt("user"); session.generation = bundle.getLong("generation");
            session.owner = bundle.getString("owner"); session.target = bundle.getString("target"); session.mode = bundle.getInt("mode");
            session.committedCodePath = bundle.getString("committedCodePath");
            session.requiredVersion = bundle.getLong("requiredVersion", -1);
            session.status = bundle.getInt("status", -1); session.created = bundle.getLong("created"); session.sealed = bundle.getBoolean("sealed");
            session.committed = bundle.getBoolean("committed");
            if (session.status == PackageInstaller.STATUS_SUCCESS) session.progress = 1;
            session.removed = bundle.getStringArrayList("removed");
            if (session.id <= 0 || session.user < 0 || session.generation <= 0 || !STORE.equals(session.owner) || session.removed == null) throw new IOException("Invalid session record");
            return session;
        }
    }
}
