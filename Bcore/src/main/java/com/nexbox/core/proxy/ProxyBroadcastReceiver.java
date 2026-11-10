package com.nexbox.core.proxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;


public class ProxyBroadcastReceiver extends BroadcastReceiver {
    public static final String TAG = "ProxyBroadcastReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        // Retained manifest component for old PendingIntents. Intent extras supplied to this
        // exported component are not routing authority; current deliveries use server tickets.
    }
}
