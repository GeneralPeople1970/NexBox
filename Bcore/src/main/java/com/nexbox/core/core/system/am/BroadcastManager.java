package com.nexbox.core.core.system.am;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.system.BProcessManagerService;
import com.nexbox.core.core.system.ProcessRecord;
import com.nexbox.core.core.system.pm.BPackage;
import com.nexbox.core.core.system.pm.BPackageSettings;
import com.nexbox.core.core.system.pm.BPackageManagerService;
import com.nexbox.core.core.system.pm.PackageMonitor;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.core.system.user.BUserManagerService;
import com.nexbox.core.entity.am.PendingResultData;
import com.nexbox.core.entity.am.ReceiverData;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.compat.PackageSignaturesCompat;

/** Owns host carrier results and individual virtual receiver lifetimes. */
public final class BroadcastManager implements PackageMonitor {
    public static final int TIMEOUT = 9000;
    private static BroadcastManager instance;
    private final BPackageManagerService packages;
    private final Handler timer = new Handler(Looper.getMainLooper());
    private final Handler dispatch;
    private final String carrierAction = NexBoxCore.getHostPkg() + ".broadcast." + UUID.randomUUID();
    private final Map<String, Request> tickets = new HashMap<>();
    private final Map<String, List<BroadcastReceiver>> registrations = new HashMap<>();
    private final Map<IBinder, Dynamic> dynamic = new HashMap<>();
    private final Map<IBinder, Delivery> deliveries = new HashMap<>();

    public static synchronized BroadcastManager startSystem(BActivityManagerService ams, BPackageManagerService pm) {
        if (instance == null) instance = new BroadcastManager(pm);
        return instance;
    }

    private BroadcastManager(BPackageManagerService pm) {
        packages = pm;
        HandlerThread thread = new HandlerThread("NexBoxBroadcasts");
        thread.start();
        dispatch = new Handler(thread.getLooper());
    }

    public void startup() {
        BroadcastReceiver carrier = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                Request request;
                synchronized (BroadcastManager.this) { request = tickets.remove(intent.getStringExtra("ticket")); }
                if (request == null || !valid(request.userId, request.generation)
                        || request.origin == null || !live(request.origin)) return;
                enqueueHost(request, this, null);
            }
        };
        Context context = NexBoxCore.getContext();
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(carrier, new IntentFilter(carrierAction), Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(carrier, new IntentFilter(carrierAction));
        packages.addPackageMonitor(this);
        for (BPackageSettings setting : packages.getBPackageSettings()) dispatch.post(() -> refreshPackage(setting.pkg.packageName));
    }

    private boolean valid(int userId, long generation) {
        BUserInfo user = BUserManagerService.get().getUserInfo(userId);
        return user != null && generation > 0 && user.createTime == generation;
    }

    private ProcessRecord caller() {
        ProcessRecord process = BProcessManagerService.get().findProcessByPid(Binder.getCallingPid());
        if (Binder.getCallingUid() != NexBoxCore.getHostUid() || process == null || !live(process)) throw new SecurityException("Invalid broadcast caller");
        return process;
    }

    private boolean live(ProcessRecord process) {
        return process.bActivityThread != null && process.bActivityThread.asBinder().isBinderAlive()
                && BProcessManagerService.get().findProcessByPid(process.pid) == process
                && valid(process.userId, process.userGeneration)
                && packages.getPackageInfo(process.getPackageName(), 0, process.userId) != null;
    }

    public Intent prepare(Intent intent, String type, String[] permissions, String[] excludedPermissions, String[] excludedPackages, int userId) {
        ProcessRecord sender = caller();
        if (userId != sender.userId || intent == null) throw new SecurityException("Broadcast user mismatch");
        if (isProtected(intent.getAction())) throw new SecurityException("Protected broadcast");
        Request request = new Request(intent, type, permissions, sender.userId, sender.userGeneration, sender.getPackageName(), excludedPermissions, excludedPackages);
        request.origin = sender;
        String ticket = UUID.randomUUID().toString();
        synchronized (this) {
            if (tickets.size() >= 256) throw new IllegalStateException("Too many pending broadcasts");
            tickets.put(ticket, request);
        }
        timer.postDelayed(() -> { synchronized (BroadcastManager.this) { tickets.remove(ticket); } }, TIMEOUT);
        return new Intent(carrierAction).setPackage(NexBoxCore.getHostPkg()).putExtra("ticket", ticket);
    }

    private boolean isProtected(String action) {
        try {
            Object pm = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
            return Boolean.TRUE.equals(Class.forName("android.content.pm.IPackageManager")
                    .getMethod("isProtectedBroadcast", String.class).invoke(pm, action));
        } catch (ReflectiveOperationException failure) {
            throw new SecurityException("Cannot verify broadcast protection", failure);
        }
    }

    public void registerDynamic(IBinder binder, IntentFilter filter, String permission, int flags) throws RemoteException {
        ProcessRecord process = caller();
        if (binder == null || filter == null) throw new IllegalArgumentException("Missing receiver");
        synchronized (this) {
            Dynamic current = dynamic.get(binder);
            if (current != null) {
                if (current.owner != process) throw new SecurityException("Receiver owner mismatch");
                for (Filter previous : current.filters) {
                    if (previous.flags == flags && Objects.equals(previous.permission, permission)
                            && sameFilter(previous.intent, filter)) return;
                }
                if (current.filters.size() >= 128) throw new IllegalStateException("Too many receiver filters");
                current.filters.add(new Filter(filter, permission, flags));
                return;
            }
            Dynamic registration = new Dynamic(binder, process);
            registration.filters.add(new Filter(filter, permission, flags));
            registration.death = () -> { synchronized (BroadcastManager.this) { dynamic.remove(binder); } };
            binder.linkToDeath(registration.death, 0);
            dynamic.put(binder, registration);
        }
    }

    void dispatchPending(Intent intent, int userId, long generation, String creator) {
        if (!valid(userId, generation) || isProtected(intent.getAction())) throw new SecurityException("Invalid pending broadcast");
        enqueue(new Request(intent, intent.getType(), null, userId, generation, creator), new PendingResultData(), null);
    }

    private static boolean sameFilter(IntentFilter first, IntentFilter second) {
        Parcel a = Parcel.obtain();
        Parcel b = Parcel.obtain();
        try {
            first.writeToParcel(a, 0);
            second.writeToParcel(b, 0);
            return Arrays.equals(a.marshall(), b.marshall());
        } finally {
            a.recycle();
            b.recycle();
        }
    }

    public void unregisterDynamic(IBinder binder) {
        ProcessRecord process = caller();
        synchronized (this) {
            Dynamic registration = dynamic.get(binder);
            if (registration != null && registration.owner != process) throw new SecurityException("Receiver owner mismatch");
            dynamic.remove(binder);
            if (registration != null) binder.unlinkToDeath(registration.death, 0);
        }
    }

    boolean hasPermission(String packageName, String permission, int userId) {
        if (permission == null) return true;
        PackageInfo info = packages.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS, userId);
        if (info == null || info.requestedPermissions == null || !Arrays.asList(info.requestedPermissions).contains(permission)) return false;
        PermissionInfo platformPermission = null;
        try {
            platformPermission = NexBoxCore.getPackageManager().getPermissionInfo(permission, 0);
            if (NexBoxCore.getPackageManager().checkPermission(permission, NexBoxCore.getHostPkg()) == PackageManager.PERMISSION_GRANTED) return true;
            if ("android".equals(platformPermission.packageName)) return false;
        } catch (PackageManager.NameNotFoundException virtualPermission) {
            // Permissions owned solely by an installed virtual package are checked below.
        }
        for (BPackageSettings owner : packages.getBPackageSettings()) {
            if (!owner.getInstalled(userId)) continue;
            PackageInfo issuer = packages.getPackageInfo(owner.pkg.packageName,
                    PackageManager.GET_PERMISSIONS | PackageSignaturesCompat.LEGACY_QUERY_FLAG, userId);
            if (issuer == null || issuer.permissions == null) continue;
            for (PermissionInfo declared : issuer.permissions) {
                if (!permission.equals(declared.name)) continue;
                Signature[] issuerSignatures = PackageSignaturesCompat.readLegacy(issuer);
                if (platformPermission != null) {
                    // A cloned permission owner may grant its own signature permission inside
                    // the group, but another guest cannot squat on a host permission name.
                    if (!issuer.packageName.equals(platformPermission.packageName)
                            || declared.getProtection() != platformPermission.getProtection()
                            || declared.getProtectionFlags() != platformPermission.getProtectionFlags()) continue;
                    try {
                        PackageInfo realIssuer = NexBoxCore.getPackageManager().getPackageInfo(
                                issuer.packageName, PackageManager.GET_SIGNING_CERTIFICATES);
                        if (issuerSignatures == null || issuerSignatures.length == 0
                                || !Arrays.equals(issuerSignatures,
                                        PackageSignaturesCompat.platformLegacyView(realIssuer.signingInfo))) return false;
                    } catch (PackageManager.NameNotFoundException missingIssuer) { return false; }
                }
                int protection = declared.getProtection();
                if (protection == PermissionInfo.PROTECTION_NORMAL) return true;
                if (protection == PermissionInfo.PROTECTION_SIGNATURE) {
                    PackageInfo recipient = packages.getPackageInfo(packageName, PackageSignaturesCompat.LEGACY_QUERY_FLAG, userId);
                    Signature[] recipientSignatures = PackageSignaturesCompat.readLegacy(recipient);
                    return recipient != null && issuerSignatures != null && issuerSignatures.length > 0
                            && recipientSignatures != null && Arrays.equals(issuerSignatures, recipientSignatures);
                }
                return false; // No synthetic dangerous/privileged grants.
            }
        }
        return false;
    }

    private boolean permitted(Request request, String targetPackage, boolean exported, String permission) {
        if (request.excludedPackages != null && Arrays.asList(request.excludedPackages).contains(targetPackage)) return false;
        if (request.excludedPermissions != null) for (String excluded : request.excludedPermissions) {
            if (excluded != null && hasPermission(targetPackage, excluded, request.userId)) return false;
        }
        if (request.sender == null) return true; // Only local service/system bridges create these.
        if (!request.sender.equals(targetPackage) && (!exported || !hasPermission(request.sender, permission, request.userId))) return false;
        if (request.permissions != null) for (String required : request.permissions) {
            if (!hasPermission(targetPackage, required, request.userId)) return false;
        }
        return true;
    }

    private void enqueue(Request request, PendingResultData pending, ComponentName only) {
        enqueue(request, pending, only, null);
    }

    private void enqueueHost(Request request, BroadcastReceiver receiver, ComponentName only) {
        HostBroadcastResult host = new HostBroadcastResult(receiver.goAsync());
        PendingResultData pending;
        try {
            pending = host.snapshot();
        } catch (RuntimeException | Error failure) {
            finishHost(host, null, "snapshotFailed");
            Slog.e("BroadcastManager", "action=deliverBroadcast stage=hostSnapshot result=failed", failure);
            return;
        }
        enqueue(request, pending, only, host);
    }

    private void enqueue(Request request, PendingResultData pending, ComponentName only, HostBroadcastResult host) {
        boolean queued = false;
        try {
            enqueueResolved(request, pending, only, host);
            queued = true;
        } catch (RuntimeException | android.os.RemoteException failure) {
            Slog.e("BroadcastManager", "Cannot resolve virtual broadcast", failure);
        } finally {
            if (!queued) finishHost(host, pending, "enqueueFailed");
        }
    }

    private void enqueueResolved(Request request, PendingResultData pending, ComponentName only,
                                 HostBroadcastResult host) throws android.os.RemoteException {
        ArrayList<Target> targets = new ArrayList<>();
        if (valid(request.userId, request.generation)) {
            List<ResolveInfo> resolved = (request.intent.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) != 0
                    ? java.util.Collections.emptyList()
                    : packages.queryBroadcastReceivers(request.intent, PackageManager.GET_META_DATA, request.type, request.userId);
            Set<ComponentName> seen = new HashSet<>();
            for (ResolveInfo resolve : resolved) {
                ActivityInfo info = resolve.activityInfo;
                if (info == null) continue;
                ComponentName component = new ComponentName(info.packageName, info.name);
                if ((only != null && !only.equals(component)) || !seen.add(component)
                        || !permitted(request, info.packageName, info.exported, info.permission)) continue;
                targets.add(new Target(info, null, resolve.priority));
            }
            if (only == null && request.intent.getComponent() == null) {
                Map<Dynamic, List<Filter>> snapshot = new HashMap<>();
                synchronized (this) {
                    for (Dynamic receiver : dynamic.values()) snapshot.put(receiver, new ArrayList<>(receiver.filters));
                }
                for (Map.Entry<Dynamic, List<Filter>> entry : snapshot.entrySet()) {
                    Dynamic receiver = entry.getKey();
                    if (receiver.owner.userId != request.userId || receiver.owner.userGeneration != request.generation
                            || (request.intent.getPackage() != null && !request.intent.getPackage().equals(receiver.owner.getPackageName()))) continue;
                    Integer priority = null;
                    for (Filter filter : entry.getValue()) {
                        if (filter.intent.match(request.intent.getAction(), request.type, request.intent.getScheme(), request.intent.getData(), request.intent.getCategories(), "NexBox") >= 0
                                && permitted(request, receiver.owner.getPackageName(), (filter.flags & Context.RECEIVER_EXPORTED) != 0, filter.permission)) {
                            priority = priority == null ? filter.intent.getPriority() : Math.max(priority, filter.intent.getPriority());
                        }
                    }
                    if (priority != null) targets.add(new Target(null, receiver, priority));
                }
            }
        }
        targets.sort((a, b) -> Integer.compare(b.priority, a.priority));
        Batch batch = new Batch(request, pending, targets, host);
        if (!timer.postDelayed(batch.timeout, TIMEOUT)) throw new IllegalStateException("Broadcast timer unavailable");
        if (!dispatch.post(() -> advance(batch))) {
            timer.removeCallbacks(batch.timeout);
            throw new IllegalStateException("Broadcast dispatch queue unavailable");
        }
    }

    private void advance(Batch batch) {
        ArrayList<Delivery> ready = new ArrayList<>();
        boolean shouldClose;
        synchronized (this) {
            if (batch.finished) return;
            shouldClose = !valid(batch.request.userId, batch.request.generation)
                    || (batch.pending.mOrderedHint && batch.pending.mAbortBroadcast);
            while (!shouldClose && batch.next < batch.targets.size()) {
                Delivery delivery = new Delivery(batch, batch.targets.get(batch.next++));
                deliveries.put(delivery.token, delivery);
                batch.active.add(delivery);
                ready.add(delivery);
                if (batch.pending.mOrderedHint) break;
            }
            shouldClose |= batch.active.isEmpty() && batch.next == batch.targets.size();
        }
        if (shouldClose) { close(batch); return; }
        for (Delivery delivery : ready) deliver(delivery);
    }

    private void deliver(Delivery delivery) {
        Request request = delivery.batch.request;
        try {
            synchronized (this) {
                if (delivery.batch.finished || deliveries.get(delivery.token) != delivery) return;
                if (delivery.target.dynamic != null && dynamic.get(delivery.target.dynamic.binder) != delivery.target.dynamic) {
                    complete(delivery, null);
                    return;
                }
            }
            ActivityInfo receiverInfo = delivery.target.info;
            synchronized (packages.getLifecycleLock()) {
                if (!valid(request.userId, request.generation)) { complete(delivery, null); return; }
                if (receiverInfo != null) {
                    receiverInfo = resolveDeliveryReceiver(request, receiverInfo);
                    if (receiverInfo == null) { complete(delivery, null); return; }
                }
            }

            ProcessRecord process = delivery.target.dynamic == null
                    ? BProcessManagerService.get().startProcessLocked(receiverInfo.packageName,
                    receiverInfo.processName, request.userId, -1, android.os.Process.myPid(), request.generation)
                    : delivery.target.dynamic.owner;

            ReceiverData data = new ReceiverData();
            synchronized (packages.getLifecycleLock()) {
                if (!valid(request.userId, request.generation)) { complete(delivery, null); return; }
                // Manifest permissions and receiver definitions may change while the
                // process connects. The queue token alone does not authorize a stale target.
                if (receiverInfo != null) {
                    receiverInfo = resolveDeliveryReceiver(request, receiverInfo);
                    if (receiverInfo == null || process == null
                            || !Objects.equals(receiverInfo.processName, process.processName)) {
                        complete(delivery, null);
                        return;
                    }
                }
                synchronized (this) {
                    if (delivery.batch.finished || deliveries.get(delivery.token) != delivery) return;
                    if (delivery.target.dynamic != null
                            && (dynamic.get(delivery.target.dynamic.binder) != delivery.target.dynamic
                            || !dynamicDeliveryPermitted(request, delivery.target.dynamic))) {
                        complete(delivery, null);
                        return;
                    }
                    if (process == null || process.userId != request.userId
                            || process.userGeneration != request.generation || !live(process)) {
                        complete(delivery, null);
                        return;
                    }
                    delivery.owner = process;
                    delivery.processBinder = process.bActivityThread.asBinder();
                    delivery.death = () -> complete(delivery, null);
                    delivery.processBinder.linkToDeath(delivery.death, 0);
                    data.intent = new Intent(request.intent);
                    data.activityInfo = receiverInfo;
                    data.data = delivery.batch.pending.copyForReceiver(delivery.token);
                    data.data.mSendingUser = request.userId;
                    delivery.resultId = data.data.mBToken;
                }
            }
            if (delivery.target.dynamic == null) process.bActivityThread.scheduleReceiver(data);
            else IBroadcastDelivery.Stub.asInterface(delivery.target.dynamic.binder).receive(data);
        } catch (Throwable failure) {
            Slog.e("BroadcastManager", "Virtual receiver delivery failed", failure);
            complete(delivery, null);
        }
    }

    private ActivityInfo resolveDeliveryReceiver(Request request, ActivityInfo expected) throws RemoteException {
        ComponentName component = new ComponentName(expected.packageName, expected.name);
        for (ResolveInfo candidate : packages.queryBroadcastReceivers(request.intent,
                PackageManager.GET_META_DATA, request.type, request.userId)) {
            ActivityInfo info = candidate == null ? null : candidate.activityInfo;
            if (info != null && component.equals(new ComponentName(info.packageName, info.name))) {
                return permitted(request, info.packageName, info.exported, info.permission) ? info : null;
            }
        }
        return null;
    }

    private boolean dynamicDeliveryPermitted(Request request, Dynamic receiver) {
        for (Filter filter : receiver.filters) {
            if (filter.intent.match(request.intent.getAction(), request.type, request.intent.getScheme(),
                    request.intent.getData(), request.intent.getCategories(), "NexBox") >= 0
                    && permitted(request, receiver.owner.getPackageName(),
                    (filter.flags & Context.RECEIVER_EXPORTED) != 0, filter.permission)) return true;
        }
        return false;
    }

    public void finish(PendingResultData result) {
        if (result == null) return;
        ProcessRecord caller = BProcessManagerService.get().findProcessByPid(Binder.getCallingPid());
        if (Binder.getCallingUid() != NexBoxCore.getHostUid() || caller == null || !live(caller)) return;
        synchronized (this) {
            Delivery delivery = deliveries.get(result.mToken);
            if (delivery == null || delivery.owner != caller
                    || !Objects.equals(delivery.resultId, result.mBToken)
                    || !valid(caller.userId, caller.userGeneration)) return;
            complete(delivery, result);
        }
    }

    private synchronized void complete(Delivery delivery, PendingResultData result) {
        if (deliveries.remove(delivery.token) != delivery) return;
        if (delivery.processBinder != null && delivery.death != null) delivery.processBinder.unlinkToDeath(delivery.death, 0);
        Batch batch = delivery.batch;
        batch.active.remove(delivery);
        if (result != null && batch.pending.mOrderedHint) {
            batch.pending.mResultCode = result.mResultCode;
            batch.pending.mResultData = result.mResultData;
            batch.pending.mResultExtras = result.mResultExtras;
            batch.pending.mAbortBroadcast = result.mAbortBroadcast && (batch.request.intent.getFlags() & Intent.FLAG_RECEIVER_NO_ABORT) == 0;
        }
        if (batch.pending.mOrderedHint || batch.active.isEmpty()) dispatch.post(() -> advance(batch));
    }

    private void close(Batch batch) {
        synchronized (this) {
            if (batch.finished) return;
            batch.finished = true;
            timer.removeCallbacks(batch.timeout);
            for (Delivery delivery : new ArrayList<>(batch.active)) {
                deliveries.remove(delivery.token);
                if (delivery.processBinder != null && delivery.death != null) delivery.processBinder.unlinkToDeath(delivery.death, 0);
            }
            batch.active.clear();
        }
        // The OS Binder call must not hold the virtual broadcast registry lock.
        finishHost(batch.host, batch.pending, "batchClosed");
    }

    private static void finishHost(HostBroadcastResult host, PendingResultData state, String reason) {
        if (host == null) return;
        try {
            if (host.finish(state)) {
                Slog.dLazy("BroadcastManager", () -> "action=finishBroadcast stage=hostFinishRequested reason=" + reason);
            }
        } catch (Throwable failure) {
            Slog.e("BroadcastManager", "action=finishBroadcast stage=hostFinish reason=" + reason + " result=failed", failure);
        }
    }

    /** Local server entry; deliberately absent from the Binder interface. */
    public void sendSystemBroadcast(Intent intent, int userId, long generation) {
        if (valid(userId, generation)) enqueue(new Request(intent, intent.getType(), null, userId, generation, null), new PendingResultData(), null);
    }

    private void registerPackage(BPackageSettings setting) {
        unregisterPackage(setting.pkg.packageName);
        List<BroadcastReceiver> list = new ArrayList<>();
        registrations.put(setting.pkg.packageName, list);
        for (BUserInfo user : BUserManagerService.get().getUsers()) {
            if (!setting.getInstalled(user.id)) continue;
            for (BPackage.Activity receiver : setting.pkg.receivers) {
                ActivityInfo info = packages.getReceiverInfo(new ComponentName(setting.pkg.packageName, receiver.info.name), 0, user.id);
                if (info == null) continue;
                final Set<String> protectedActions = new HashSet<>();
                for (BPackage.ActivityIntentInfo filter : receiver.intents) {
                    for (int i = 0; i < filter.intentFilter.countActions(); i++) {
                        String action = filter.intentFilter.getAction(i);
                        if (Build.VERSION.SDK_INT < 33 && !info.exported && isProtected(action)) protectedActions.add(action);
                    }
                }
                if (Build.VERSION.SDK_INT < 33 && !info.exported && protectedActions.isEmpty()) continue;
                final ComponentName target = new ComponentName(info.packageName, info.name);
                // Reusing one receiver binder lets Android deduplicate overlapping filters.
                BroadcastReceiver proxy = new BroadcastReceiver() {
                    @Override public void onReceive(Context context, Intent intent) {
                        if (!valid(user.id, user.createTime)) return;
                        // Before Android 13 the OS has no NOT_EXPORTED registration flag;
                        // only protected system actions can safely cross this boundary.
                        if (Build.VERSION.SDK_INT < 33 && !info.exported && !protectedActions.contains(intent.getAction())) return;
                        enqueueHost(new Request(intent, intent.getType(), null, user.id, user.createTime, null), this, target);
                    }
                };
                boolean registered = false;
                for (BPackage.ActivityIntentInfo filter : receiver.intents) {
                    boolean hasProtected = false;
                    for (int i = 0; i < filter.intentFilter.countActions(); i++) {
                        if (protectedActions.contains(filter.intentFilter.getAction(i))) hasProtected = true;
                    }
                    if (Build.VERSION.SDK_INT < 33 && !info.exported && !hasProtected) continue;
                    IntentFilter system = new IntentFilter(filter.intentFilter);
                    Context context = NexBoxCore.getContext();
                    try {
                        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(proxy, system, info.permission, null,
                                info.exported ? Context.RECEIVER_EXPORTED : Context.RECEIVER_NOT_EXPORTED);
                        else context.registerReceiver(proxy, system, info.permission, null);
                        if (!registered) { list.add(proxy); registered = true; }
                    } catch (RuntimeException failure) { Slog.e("BroadcastManager", "Cannot register system receiver", failure); }
                }
            }
        }
    }

    private void unregisterPackage(String packageName) {
        List<BroadcastReceiver> old = registrations.remove(packageName);
        if (old == null) return;
        for (BroadcastReceiver receiver : old) {
            try { NexBoxCore.getContext().unregisterReceiver(receiver); }
            catch (IllegalArgumentException alreadyRemoved) { Slog.w("BroadcastManager", "Receiver already removed"); }
        }
    }

    @Override public void onPackageInstalled(String packageName, int userId) {
        dispatch.post(() -> refreshPackage(packageName));
    }
    @Override public void onPackageUninstalled(String packageName, boolean removeApp, int userId) {
        dispatch.post(() -> refreshPackage(packageName));
    }
    private void refreshPackage(String packageName) {
        unregisterPackage(packageName);
        BPackageSettings setting = packages.getBPackageSetting(packageName);
        if (setting != null) {
            try { registerPackage(setting); }
            catch (RuntimeException failure) { Slog.e("BroadcastManager", "Cannot refresh broadcast registrations", failure); }
        }
    }

    private static final class Request {
        final Intent intent; final String type; final String[] permissions; final int userId; final long generation; final String sender;
        final String[] excludedPermissions; final String[] excludedPackages;
        ProcessRecord origin; // Captured server-side; never taken from caller-supplied extras.
        Request(Intent intent, String type, String[] permissions, int userId, long generation, String sender) {
            this(intent, type, permissions, userId, generation, sender, null, null);
        }
        Request(Intent intent, String type, String[] permissions, int userId, long generation, String sender, String[] excludedPermissions, String[] excludedPackages) {
            this.intent = new Intent(intent); this.type = type; this.permissions = permissions == null ? null : permissions.clone();
            this.userId = userId; this.generation = generation; this.sender = sender;
            this.excludedPermissions = excludedPermissions == null ? null : excludedPermissions.clone();
            this.excludedPackages = excludedPackages == null ? null : excludedPackages.clone();
        }
    }
    private static final class Filter {
        final IntentFilter intent; final String permission; final int flags;
        Filter(IntentFilter intent, String permission, int flags) { this.intent = new IntentFilter(intent); this.permission = permission; this.flags = flags; }
    }
    private static final class Dynamic {
        final IBinder binder; final ProcessRecord owner; final List<Filter> filters = new ArrayList<>();
        IBinder.DeathRecipient death;
        Dynamic(IBinder binder, ProcessRecord owner) { this.binder = binder; this.owner = owner; }
    }
    private static final class Target {
        final ActivityInfo info; final Dynamic dynamic; final int priority;
        Target(ActivityInfo info, Dynamic dynamic, int priority) { this.info = info; this.dynamic = dynamic; this.priority = priority; }
    }
    private final class Batch {
        final Request request; final PendingResultData pending; final List<Target> targets; final Set<Delivery> active = new HashSet<>();
        final HostBroadcastResult host;
        int next; volatile boolean finished;
        final Runnable timeout;
        Batch(Request request, PendingResultData pending, List<Target> targets, HostBroadcastResult host) {
            this.request = request; this.pending = pending; this.targets = targets;
            this.host = host;
            timeout = () -> {
                Slog.w("BroadcastManager", "Broadcast deadline expired user=" + request.userId);
                close(this);
            };
        }
    }
    private static final class Delivery {
        final IBinder token = new Binder(); final Batch batch; final Target target;
        ProcessRecord owner; IBinder processBinder; IBinder.DeathRecipient death; String resultId;
        Delivery(Batch batch, Target target) { this.batch = batch; this.target = target; }
    }
}
