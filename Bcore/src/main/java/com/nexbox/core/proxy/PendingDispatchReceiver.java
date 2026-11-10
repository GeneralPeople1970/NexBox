package com.nexbox.core.proxy;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.nexbox.core.core.system.am.VirtualPendingIntents;
public final class PendingDispatchReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) { VirtualPendingIntents.dispatch(intent); }
}
