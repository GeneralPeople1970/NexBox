package com.nexbox.core.proxy;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import com.nexbox.core.core.system.am.VirtualPendingIntents;
public final class PendingDispatchService extends Service {
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try { VirtualPendingIntents.dispatch(intent); } finally { stopSelf(startId); }
        return START_NOT_STICKY;
    }
}
