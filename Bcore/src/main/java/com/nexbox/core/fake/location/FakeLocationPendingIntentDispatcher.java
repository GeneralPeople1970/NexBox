package com.nexbox.core.fake.location;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.entity.location.BLocation;
import com.nexbox.core.fake.frameworks.BLocationManager;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.ActiveSubscriptions;
import com.nexbox.core.utils.IdleScheduler;

public final class FakeLocationPendingIntentDispatcher {
    private static final String TAG = "FakeLocationPI";

    // Keep this conservative: frequent enough for UX, but not a battery hog.
    private static final long TICK_MS = 1000L;
    private static final long FORCE_RESEND_MS = 3000L;

    private static final ScheduledThreadPoolExecutor EXEC = IdleScheduler.create("nexbox-location-pi");
    private static final ActiveSubscriptions<PendingIntent, Record> RECORDS = new ActiveSubscriptions<>(() -> {
        var task = EXEC.scheduleWithFixedDelay(FakeLocationPendingIntentDispatcher::tick, 0, TICK_MS, TimeUnit.MILLISECONDS);
        return () -> task.cancel(false);
    });

    private static final class Record {
        final int userId;
        final String packageName;
        boolean hasLast;
        double lastLat;
        double lastLng;
        long lastSentAt;

        Record(int userId, String packageName) {
            this.userId = userId;
            this.packageName = packageName;
        }
    }

    private FakeLocationPendingIntentDispatcher() {
    }

    public static void register(PendingIntent pendingIntent) {
        if (pendingIntent == null) {
            return;
        }
        String pkg = BActivityThread.getAppPackageName();
        if (pkg == null) {
            return;
        }
        int userId = BActivityThread.getUserId();
        RECORDS.add(pendingIntent, new Record(userId, pkg));
    }

    public static void unregister(PendingIntent pendingIntent) {
        if (pendingIntent == null) {
            return;
        }
        RECORDS.remove(pendingIntent);
    }

    private static void tick() {
        Context ctx = NexBoxCore.getContext();
        if (ctx == null) {
            return;
        }

        Map<String, BLocation> locations = new HashMap<>();
        for (Map.Entry<PendingIntent, Record> e : RECORDS.snapshot()) {
            PendingIntent pi = e.getKey();
            Record r = e.getValue();
            if (pi == null || r == null) {
                continue;
            }

            BLocation loc;
            try {
                String key = r.userId + ":" + r.packageName;
                if (!locations.containsKey(key)) {
                    locations.put(key, BLocationManager.get().getLocation(r.userId, r.packageName));
                }
                loc = locations.get(key);
            } catch (Throwable t) {
                Slog.w(TAG, "getLocation failed: " + t.getMessage(), t);
                continue;
            }
            if (loc == null) {
                r.hasLast = false;
                continue;
            }

            long now = System.nanoTime() / 1_000_000L;
            boolean changed = !r.hasLast
                    || Math.abs(loc.getLatitude() - r.lastLat) > 1e-7
                    || Math.abs(loc.getLongitude() - r.lastLng) > 1e-7;
            boolean force = (now - r.lastSentAt) >= FORCE_RESEND_MS;
            if (!changed && !force) {
                continue;
            }

            try {
                if (!RECORDS.isCurrent(pi, r)) continue;
                Location sys = loc.convert2SystemLocation();
                Intent fillIn = new Intent();
                fillIn.putExtra(LocationManager.KEY_LOCATION_CHANGED, sys);
                // Keep batched locations optional; many apps only read KEY_LOCATION_CHANGED.
                fillIn.putExtra(LocationManager.KEY_LOCATIONS, new Location[]{sys});
                pi.send(ctx, 0, fillIn);

                r.hasLast = true;
                r.lastLat = loc.getLatitude();
                r.lastLng = loc.getLongitude();
                r.lastSentAt = now;
            } catch (PendingIntent.CanceledException ex) {
                // App unregistered/died; drop it.
                RECORDS.remove(pi, r);
            } catch (Throwable t) {
                Slog.w(TAG, "PendingIntent send failed: " + t.getMessage(), t);
            }
        }
    }
}
