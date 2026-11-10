package com.nexbox.core.utils;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import com.nexbox.core.entity.JobRecord;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Preserve platform parcel layouts; only explicitly supported identity fields are changed. */
public final class JobCompat {
    private JobCompat() { }
    public static Object get(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unsupported Job field " + name, failure); }
    }
    public static void set(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
            if (!java.util.Objects.equals(field.get(target), value)) throw new IllegalStateException("Job field was not changed: " + name);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unsupported Job field " + name, failure); }
    }
    public static JobInfo copy(JobInfo info) {
        Parcel p = Parcel.obtain();
        try { info.writeToParcel(p, 0); p.setDataPosition(0); return JobInfo.CREATOR.createFromParcel(p); }
        finally { p.recycle(); }
    }
    public static Map<Integer, String> transactions(String stub) {
        Map<Integer, String> names = new HashMap<>();
        try {
            for (Field field : Class.forName(stub).getDeclaredFields()) {
                if (field.getName().startsWith("TRANSACTION_")) {
                    field.setAccessible(true);
                    names.put(field.getInt(null), field.getName().substring(12));
                }
            }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unsupported Job protocol", failure); }
        return names;
    }
    public static JobParameters restore(JobRecord record, JobParameters host, Runnable finished) {
        Parcel p = Parcel.obtain();
        try {
            host.writeToParcel(p, 0);
            p.setDataPosition(0);
            JobParameters guest = JobParameters.CREATOR.createFromParcel(p);
            set(guest, "jobId", record.mJobInfo.getId());
            set(guest, "extras", new android.os.PersistableBundle(record.mJobInfo.getExtras()));
            if (android.os.Build.VERSION.SDK_INT >= 34) set(guest, "mJobNamespace", record.namespace);
            set(guest, "callback", new Callback((IBinder) get(host, "callback"), record.mJobInfo.getId(), record.hostId, finished, () -> {
                try { com.nexbox.core.NexBoxCore.getBJobManager().getService().markJobStarted(record.hostId, record.nonce); }
                catch (Exception failure) { Slog.w("JobCompat", "Cannot register job start", failure); }
            }));
            return guest;
        } finally { p.recycle(); }
    }
    public static void acknowledge(JobParameters params, boolean start, boolean ongoing) {
        try {
            Class<?> stub = Class.forName("android.app.job.IJobCallback$Stub");
            Object callback = stub.getMethod("asInterface", IBinder.class).invoke(null, get(params, "callback"));
            Class.forName("android.app.job.IJobCallback").getMethod(start ? "acknowledgeStartMessage" : "acknowledgeStopMessage", int.class, boolean.class)
                    .invoke(callback, params.getJobId(), ongoing);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot acknowledge job", failure); }
    }
    public static void finish(JobParameters params, boolean retry) {
        try {
            Object callback = Class.forName("android.app.job.IJobCallback$Stub").getMethod("asInterface", IBinder.class)
                    .invoke(null, get(params, "callback"));
            Class.forName("android.app.job.IJobCallback").getMethod("jobFinished", int.class, boolean.class)
                    .invoke(callback, params.getJobId(), retry);
        } catch (ReflectiveOperationException failure) { Slog.w("JobCompat", "Cannot finish disconnected job", failure); }
    }
    private static final class Callback extends Binder {
        private static final String DESCRIPTOR = "android.app.job.IJobCallback";
        private static final Set<String> SUPPORTED = new HashSet<>(Arrays.asList(
                "acknowledgeStartMessage", "acknowledgeStopMessage", "dequeueWork", "completeWork", "jobFinished",
                "acknowledgeGetTransferredDownloadBytesMessage", "acknowledgeGetTransferredUploadBytesMessage",
                "updateEstimatedNetworkBytes", "updateTransferredNetworkBytes", "setNotification", "handleAbandonedJob"));
        private final Map<Integer, String> names = transactions(DESCRIPTOR + "$Stub");
        private final IBinder base;
        private final int originalId;
        private final int hostId;
        private final Runnable finished;
        private final Runnable started;
        Callback(IBinder base, int originalId, int hostId, Runnable finished, Runnable started) {
            this.base = base; this.originalId = originalId; this.hostId = hostId; this.finished = finished; this.started = started;
        }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(DESCRIPTOR); return true; }
            String name = names.get(code);
            if (!SUPPORTED.contains(name)) throw new SecurityException("Unsupported Job callback " + name);
            data.enforceInterface(DESCRIPTOR);
            if (data.readInt() != originalId) throw new SecurityException("Mismatched guest job ID");
            int tail = data.dataPosition();
            Parcel mapped = Parcel.obtain();
            try {
                mapped.writeInterfaceToken(DESCRIPTOR);
                mapped.writeInt(hostId);
                mapped.appendFrom(data, tail, data.dataSize() - tail);
                boolean result = base.transact(code, mapped, reply, flags);
                if (result && reply != null && reply.dataSize() > 0) {
                    int position = reply.dataPosition();
                    try { reply.setDataPosition(0); reply.readException(); }
                    finally { reply.setDataPosition(position); }
                }
                if (result) {
                    if ("acknowledgeStartMessage".equals(name)) {
                        if (data.readInt() == 0) finished.run(); else started.run();
                    } else if ("jobFinished".equals(name) || "acknowledgeStopMessage".equals(name)) finished.run();
                }
                return result;
            } finally { mapped.recycle(); }
        }
    }
}
