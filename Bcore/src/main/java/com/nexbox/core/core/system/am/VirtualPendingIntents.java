package com.nexbox.core.core.system.am;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.AtomicFile;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.core.system.ProcessRecord;
import com.nexbox.core.core.system.pm.BPackageManagerService;
import com.nexbox.core.core.system.user.BUserManagerService;
import com.nexbox.core.core.system.xposed.XposedCallerGuard;
import com.nexbox.core.proxy.PendingDispatchActivity;
import com.nexbox.core.proxy.PendingDispatchReceiver;
import com.nexbox.core.proxy.PendingDispatchService;
import com.nexbox.core.utils.compat.BundleCompat;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Core-process registry. Persistent OS tokens carry no unvalidated guest routing extras. */
public final class VirtualPendingIntents {
    private static final int MAX_RUNTIME_PAYLOADS = 512;
    private static final int MAX_RUNTIME_BYTES = 8 * 1024 * 1024;
    private static JSONObject records;
    private static final Map<String, PendingIntentPayload> runtimePayloads = new HashMap<>();
    private static final AtomicFile file = new AtomicFile(new File(BEnvironment.getSystemDir(), "pending-intents.json"));
    private VirtualPendingIntents() { }
    private static Object lock() { return BPackageManagerService.get().getLifecycleLock(); }
    private static JSONObject records() throws Exception {
        if (records == null) {
            JSONObject loaded = file.getBaseFile().exists()
                    ? new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8)) : new JSONObject();
            JSONObject next = new JSONObject(loaded.toString());
            boolean changed = false;
            for (String id : keys(loaded)) {
                JSONObject entry = loaded.getJSONObject(id);
                String runtimeId = entry.optString("runtimePayload", "");
                if (!runtimeId.isEmpty() && !runtimePayloads.containsKey(runtimeId)) {
                    // A core restart also ends its account sessions. Never restore a token
                    // whose original callback objects no longer exist in this process.
                    PendingIntent pending = token(id, entry, PendingIntent.FLAG_NO_CREATE);
                    if (pending != null) pending.cancel();
                    next.remove(id);
                    changed = true;
                }
            }
            if (changed) save(next);
            else records = loaded;
        }
        return records;
    }
    private static void save(JSONObject next) throws Exception {
        Set<String> retained = new HashSet<>();
        for (String id : keys(next)) {
            String runtimeId = next.getJSONObject(id).optString("runtimePayload", "");
            if (!runtimeId.isEmpty()) retained.add(runtimeId);
        }
        FileOutputStream output = null;
        try {
            output = file.startWrite(); output.write(next.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output); records = next;
            runtimePayloads.keySet().retainAll(retained);
        } catch (Exception failure) { if (output != null) file.failWrite(output); records = null; throw failure; }
    }
    private static boolean current(JSONObject e) throws Exception {
        int user = e.getInt("user");
        com.nexbox.core.core.system.user.BUserInfo info = BUserManagerService.get().getUserInfo(user);
        return info != null && info.createTime == e.getLong("generation") &&
                BPackageManagerService.get().getPackageInfo(e.getString("package"), 0, user) != null;
    }
    private static boolean owned(JSONObject e, ProcessRecord caller) throws Exception {
        return e.getInt("user") == caller.userId && e.getLong("generation") == caller.userGeneration
                && e.getString("package").equals(caller.getPackageName()) && current(e);
    }
    public static Bundle operation(String action, Bundle request) {
        synchronized (lock()) {
            try {
                ProcessRecord caller = XposedCallerGuard.requireGuest();
                com.nexbox.core.core.system.user.BUserInfo user = BUserManagerService.get().getUserInfo(caller.userId);
                if (user == null || user.createTime != caller.userGeneration || caller.bActivityThread == null
                        || !caller.bActivityThread.asBinder().isBinderAlive()) throw new SecurityException("Expired PendingIntent caller");
                Bundle result = new Bundle();
                if ("info".equals(action)) {
                    android.os.IBinder target = request.getBinder("target");
                    for (String id : keys(records())) {
                        JSONObject entry = records().getJSONObject(id);
                        if (entry.getInt("user") != caller.userId || entry.getLong("generation") != caller.userGeneration || !current(entry)) continue;
                        PendingIntent pending = token(id, entry, PendingIntent.FLAG_NO_CREATE);
                        android.os.IInterface sender = pending == null ? null : com.nexbox.core.utils.Reflector.with(pending).method("getTarget").call();
                        if (sender == null || !sender.asBinder().equals(target)) continue;
                        result.putString("package", entry.getString("package"));
                        result.putInt("uid", BPackageManagerService.get().getApplicationInfo(entry.getString("package"), 0, caller.userId).uid);
                        result.putInt("type", entry.getInt("type")); result.putBoolean("immutable", (entry.getInt("flags") & PendingIntent.FLAG_IMMUTABLE) != 0);
                        return result;
                    }
                    throw new SecurityException("Unknown virtual PendingIntent");
                }
                if ("validate".equals(action)) {
                    PendingIntent candidate = BundleCompat.getParcelable(request, "pending", PendingIntent.class);
                    for (String id : keys(records())) {
                        JSONObject e = records().getJSONObject(id);
                        if (owned(e, caller) && candidate != null && candidate.equals(token(id, e, PendingIntent.FLAG_NO_CREATE))) {
                            result.putBoolean("valid", true); return result;
                        }
                    }
                    throw new SecurityException("PendingIntent is not owned by this app lifetime");
                }
                if (!"create".equals(action)) throw new IllegalArgumentException("Unknown PendingIntent operation");
                int type = request.getInt("type"), flags = request.getInt("flags");
                if (type == 5) throw new UnsupportedOperationException("Foreground-service PendingIntents require foreground service lifecycle support");
                if (type != 1 && type != 2 && type != 4) throw new UnsupportedOperationException("Unsupported sender type");
                Parcelable[] intents = BundleCompat.getParcelableArray(request, "intents");
                if (intents == null || intents.length == 0 || intents.length > 32) throw new IllegalArgumentException("Missing intents");
                String[] types = request.getStringArray("types");
                Intent last = (Intent) intents[intents.length - 1];
                if (last == null) throw new IllegalArgumentException("Missing intent");
                String resolved = types == null || types.length == 0 ? last.getType() : types[types.length - 1];
                String key = last.cloneFilter().toUri(0) + "|" + resolved + "|" + type + "|" + request.getInt("requestCode")
                        + "|" + (flags & (PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_MUTABLE));
                String id = null; JSONObject entry = null;
                for (String existing : keys(records())) {
                    JSONObject saved = records().getJSONObject(existing);
                    if (owned(saved, caller) && key.equals(saved.getString("key"))) { id = existing; entry = saved; break; }
                }
                if (entry != null && token(id, entry, PendingIntent.FLAG_NO_CREATE) == null) {
                    JSONObject stale = new JSONObject(records().toString()); stale.remove(id); save(stale); entry = null;
                }
                if ((flags & PendingIntent.FLAG_NO_CREATE) != 0) {
                    if (entry != null) result.putParcelable("pending", token(id, entry, PendingIntent.FLAG_NO_CREATE));
                    return result;
                }
                PendingIntentPayload payload = null;
                if (entry == null || (flags & (PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_UPDATE_CURRENT)) != 0) {
                    payload = PendingIntentPayload.capture(request);
                    checkRuntimeCapacity(payload, entry);
                }
                JSONObject next = new JSONObject(records().toString());
                if (entry != null && (flags & PendingIntent.FLAG_CANCEL_CURRENT) != 0) {
                    PendingIntent old = token(id, entry, PendingIntent.FLAG_NO_CREATE);
                    if (old != null) old.cancel();
                    next.remove(id); entry = null;
                }
                if (entry == null || (flags & PendingIntent.FLAG_UPDATE_CURRENT) != 0) {
                    if (entry == null) { id = UUID.randomUUID().toString(); entry = new JSONObject(); }
                    else entry = new JSONObject(entry.toString());
                    entry.put("user", caller.userId).put("generation", caller.userGeneration).put("package", caller.getPackageName())
                            .put("type", type).put("flags", flags).put("key", key)
                            .put("payload", payload.encoded).put("runtimePayload", payload.runtimeId);
                    next.put(id, entry); save(next);
                    if (payload.runtimeId != null) runtimePayloads.put(payload.runtimeId, payload);
                }
                PendingIntent pending = token(id, entry, flags & ~PendingIntent.FLAG_CANCEL_CURRENT);
                if (pending == null) throw new IllegalStateException("Could not create the virtual PendingIntent");
                result.putParcelable("pending", pending);
                return result;
            } catch (SecurityException | IllegalArgumentException | IllegalStateException | UnsupportedOperationException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException("PendingIntent registry failed", failure); }
        }
    }
    private static PendingIntent token(String id, JSONObject entry, int flags) throws Exception {
        Context context = NexBoxCore.getContext(); int type = entry.getInt("type");
        Class<?> component = type == 2 ? PendingDispatchActivity.class : type == 1 ? PendingDispatchReceiver.class : PendingDispatchService.class;
        Intent carrier = new Intent(context, component);
        if (android.os.Build.VERSION.SDK_INT >= 29) carrier.setIdentifier("nexbox-pending:" + id);
        else carrier.setData(Uri.parse("nexbox-pending://dispatch/" + id));
        flags = (flags & (PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_UPDATE_CURRENT)) |
                (entry.getInt("flags") & (PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_MUTABLE));
        if ((flags & (PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_MUTABLE)) == 0) flags |= PendingIntent.FLAG_MUTABLE;
        if (type == 2) return PendingIntent.getActivity(context, 0, carrier, flags);
        if (type == 1) return PendingIntent.getBroadcast(context, 0, carrier, flags);
        if (type == 5 && android.os.Build.VERSION.SDK_INT >= 26) return PendingIntent.getForegroundService(context, 0, carrier, flags);
        return PendingIntent.getService(context, 0, carrier, flags);
    }
    public static void dispatch(Intent carrier) {
        try {
            final Intent[] intents;
            final int user;
            final int type;
            final long generation;
            final String creator;
            synchronized (lock()) {
                if (carrier == null) return;
                String identifier = android.os.Build.VERSION.SDK_INT >= 29 ? carrier.getIdentifier() : null;
                String id = identifier != null && identifier.startsWith("nexbox-pending:") ? identifier.substring(15) :
                        carrier.getData() != null && "nexbox-pending".equals(carrier.getScheme()) ? carrier.getData().getLastPathSegment() : null;
                if (id == null) return;
                JSONObject e = records().optJSONObject(id);
                if (e == null || !current(e)) return;
                String runtimeId = e.optString("runtimePayload", "");
                PendingIntentPayload runtime = runtimeId.isEmpty() ? null : runtimePayloads.get(runtimeId);
                if (!runtimeId.isEmpty() && runtime == null) throw new IllegalStateException("PendingIntent callback lifetime expired");
                Bundle payload = runtime == null ? PendingIntentPayload.decode(e.getString("payload")) : runtime.copy();
                Parcelable[] raw = BundleCompat.getParcelableArray(payload, "intents");
                intents = new Intent[raw.length];
                for (int i = 0; i < raw.length; i++) intents[i] = new Intent((Intent) raw[i]);
                Intent last = intents[intents.length - 1];
                String[] types = payload.getStringArray("types");
                if (types != null) for (int i = 0; i < intents.length && i < types.length; i++) intents[i].setDataAndType(intents[i].getData(), types[i]);
                if ((e.getInt("flags") & PendingIntent.FLAG_IMMUTABLE) == 0) {
                    Intent fill = new Intent();
                    fill.setAction(carrier.getAction());
                    if (identifier != null) fill.setDataAndType(carrier.getData(), carrier.getType());
                    if (carrier.getCategories() != null) for (String category : carrier.getCategories()) fill.addCategory(category);
                    fill.setSourceBounds(carrier.getSourceBounds());
                    last.fillIn(fill, e.getInt("flags") & (Intent.FILL_IN_ACTION | Intent.FILL_IN_DATA | Intent.FILL_IN_CATEGORIES | Intent.FILL_IN_SOURCE_BOUNDS));
                    Bundle extras = carrier.getExtras();
                    if (extras != null) { Bundle merged = new Bundle(extras); if (last.getExtras() != null) merged.putAll(last.getExtras()); last.replaceExtras(merged); }
                    if (carrier.getClipData() != null && last.getClipData() == null) last.setClipData(carrier.getClipData());
                }
                if (carrier.hasExtra(Intent.EXTRA_ALARM_COUNT)) last.putExtra(Intent.EXTRA_ALARM_COUNT, carrier.getIntExtra(Intent.EXTRA_ALARM_COUNT, 1));
                user = e.getInt("user");
                type = e.getInt("type");
                generation = e.getLong("generation");
                creator = e.getString("package");
                // Preserve one-shot consumption even if permission checks or connection fail.
                // No guest connection is made while the registry/lifecycle lock is held.
                if ((e.getInt("flags") & PendingIntent.FLAG_ONE_SHOT) != 0) {
                    JSONObject next = new JSONObject(records().toString()); next.remove(id); save(next);
                }
            }
            Intent last = intents[intents.length - 1];
            if (type == 1) {
                BActivityManagerService.get().dispatchPendingBroadcast(last, user, generation, creator);
            } else if (type == 2) {
                for (Intent target : intents) {
                    target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    BActivityManagerService.get().startPendingActivity(target, user, generation, creator);
                }
            } else if (BActivityManagerService.get().startPendingService(last, last.getType(), type == 5,
                    user, generation, creator) == null) {
                throw new IllegalStateException("Pending service could not start");
            }
        } catch (Exception failure) {
            com.nexbox.core.utils.Slog.e("PendingIntent", "Dispatch failed", failure);
        }
    }
    public static void removeOwner(int user, String pkg) {
        synchronized (lock()) {
            try {
                JSONObject next = new JSONObject(records().toString());
                for (String id : keys(records())) {
                    JSONObject e = records().getJSONObject(id);
                    if (e.getInt("user") != user || pkg != null && !pkg.equals(e.getString("package"))) continue;
                    PendingIntent pending = token(id, e, PendingIntent.FLAG_NO_CREATE);
                    if (pending != null) pending.cancel(); next.remove(id);
                }
                save(next);
            } catch (Exception failure) { throw new IllegalStateException("Could not clean PendingIntents", failure); }
        }
    }
    private static ArrayList<String> keys(JSONObject object) {
        ArrayList<String> result = new ArrayList<>(); object.keys().forEachRemaining(result::add); return result;
    }
    private static void checkRuntimeCapacity(PendingIntentPayload payload, JSONObject previous) {
        if (payload.runtimeId == null) return;
        String replaced = previous == null ? "" : previous.optString("runtimePayload", "");
        int count = 1;
        long bytes = payload.byteCount;
        for (Map.Entry<String, PendingIntentPayload> entry : runtimePayloads.entrySet()) {
            if (entry.getKey().equals(replaced)) continue;
            count++;
            bytes += entry.getValue().byteCount;
        }
        if (count > MAX_RUNTIME_PAYLOADS || bytes > MAX_RUNTIME_BYTES) {
            throw new IllegalStateException("Too many live PendingIntent callbacks");
        }
    }
}
