package com.nexbox.core.proxy;

import android.app.Service;
import android.app.job.JobParameters;
import android.app.job.JobWorkItem;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.system.am.IBJobManagerService;
import com.nexbox.core.utils.JobCompat;
import com.nexbox.core.utils.Slog;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Host-only OS endpoint. The guest JobServiceEngine owns acknowledgements and completion. */
public class ProxyJobService extends Service {
    private static final String DESCRIPTOR = "android.app.job.IJobService";
    private final ExecutorService bridge = Executors.newSingleThreadExecutor();
    private final Map<Integer, String> transactions = loadTransactions();

    private static Map<Integer, String> loadTransactions() {
        try {
            return JobCompat.transactions(DESCRIPTOR + "$Stub");
        } catch (RuntimeException unavailable) {
            Slog.w("ProxyJobService", "Job endpoint protocol unavailable", unavailable);
            return java.util.Collections.emptyMap();
        }
    }
    private final IBinder endpoint = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(DESCRIPTOR); return true; }
            // Only system_server holds the JobScheduler endpoint. Guests never dispatch OS jobs.
            if (Binder.getCallingUid() != android.os.Process.SYSTEM_UID) throw new SecurityException("Non-system Job caller");
            String event = transactions.get(code);
            boolean start = "startJob".equals(event);
            boolean stop = "stopJob".equals(event);
            boolean progress = "getTransferredDownloadBytes".equals(event) || "getTransferredUploadBytes".equals(event);
            if (!start && !stop && !progress && !"onNetworkChanged".equals(event)) {
                throw new UnsupportedOperationException("Unsupported OS Job event " + event);
            }
            data.enforceInterface(DESCRIPTOR);
            JobParameters params = data.readInt() == 0 ? null : JobParameters.CREATOR.createFromParcel(data);
            JobWorkItem work = progress && data.readInt() != 0 ? JobWorkItem.CREATOR.createFromParcel(data) : null;
            if (params == null || data.dataAvail() != 0) throw new IllegalArgumentException("Malformed Job event");
            bridge.execute(() -> {
                try {
                    IBJobManagerService jobs = NexBoxCore.getBJobManager().getService();
                    if (start || stop) {
                        int result = jobs == null ? 2 : jobs.dispatchJob(params, start);
                        if (result == 2 && start) retry(params);
                        else if (result != 1) JobCompat.acknowledge(params, start, false);
                        else if (start) {
                            android.content.SharedPreferences attempts = getSharedPreferences("job-bridge-retries", MODE_PRIVATE);
                            String key = retryKey(params);
                            if (attempts.contains(key)) attempts.edit().remove(key).apply();
                        }
                    } else if (jobs != null) jobs.dispatchJobEvent(params, work, event);
                } catch (Exception failure) {
                    Slog.w("ProxyJobService", "Job bridge failed for " + event, failure);
                    if (start) retry(params);
                    else if (stop) JobCompat.acknowledge(params, false, false);
                }
            });
            if (reply != null) reply.writeNoException();
            return true;
        }
    };
    private void retry(JobParameters params) {
        android.content.SharedPreferences attempts = getSharedPreferences("job-bridge-retries", MODE_PRIVATE);
        String key = retryKey(params);
        int count = attempts.getInt(key, 0) + 1;
        // A backend outage must not silently complete one-shot work or loop forever across restarts.
        boolean retry = count <= 3 && attempts.edit().putInt(key, count).commit();
        JobCompat.acknowledge(params, true, retry);
        if (retry) JobCompat.finish(params, true);
    }
    private static String retryKey(JobParameters params) {
        return params.getJobId() + ":" + params.getExtras().getString(com.nexbox.core.core.system.am.BJobManagerService.EXTRA_NONCE);
    }
    @Override public IBinder onBind(Intent intent) {
        return transactions.containsValue("startJob") && transactions.containsValue("stopJob") ? endpoint : null;
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_NOT_STICKY; }
    @Override public void onDestroy() { bridge.shutdown(); super.onDestroy(); }

    public static class P0 extends ProxyJobService {

    }

    public static class P1 extends ProxyJobService {

    }

    public static class P2 extends ProxyJobService {

    }

    public static class P3 extends ProxyJobService {

    }

    public static class P4 extends ProxyJobService {

    }

    public static class P5 extends ProxyJobService {

    }

    public static class P6 extends ProxyJobService {

    }

    public static class P7 extends ProxyJobService {

    }

    public static class P8 extends ProxyJobService {

    }

    public static class P9 extends ProxyJobService {

    }

    public static class P10 extends ProxyJobService {

    }

    public static class P11 extends ProxyJobService {

    }

    public static class P12 extends ProxyJobService {

    }

    public static class P13 extends ProxyJobService {

    }

    public static class P14 extends ProxyJobService {

    }

    public static class P15 extends ProxyJobService {

    }

    public static class P16 extends ProxyJobService {

    }

    public static class P17 extends ProxyJobService {

    }

    public static class P18 extends ProxyJobService {

    }

    public static class P19 extends ProxyJobService {

    }

    public static class P20 extends ProxyJobService {

    }

    public static class P21 extends ProxyJobService {

    }

    public static class P22 extends ProxyJobService {

    }

    public static class P23 extends ProxyJobService {

    }

    public static class P24 extends ProxyJobService {

    }

    public static class P25 extends ProxyJobService {

    }

    public static class P26 extends ProxyJobService {

    }

    public static class P27 extends ProxyJobService {

    }

    public static class P28 extends ProxyJobService {

    }

    public static class P29 extends ProxyJobService {

    }

    public static class P30 extends ProxyJobService {

    }

    public static class P31 extends ProxyJobService {

    }

    public static class P32 extends ProxyJobService {

    }

    public static class P33 extends ProxyJobService {

    }

    public static class P34 extends ProxyJobService {

    }

    public static class P35 extends ProxyJobService {

    }

    public static class P36 extends ProxyJobService {

    }

    public static class P37 extends ProxyJobService {

    }

    public static class P38 extends ProxyJobService {

    }

    public static class P39 extends ProxyJobService {

    }

    public static class P40 extends ProxyJobService {

    }

    public static class P41 extends ProxyJobService {

    }

    public static class P42 extends ProxyJobService {

    }

    public static class P43 extends ProxyJobService {

    }

    public static class P44 extends ProxyJobService {

    }

    public static class P45 extends ProxyJobService {

    }

    public static class P46 extends ProxyJobService {

    }

    public static class P47 extends ProxyJobService {

    }

    public static class P48 extends ProxyJobService {

    }

    public static class P49 extends ProxyJobService {

    }
}
