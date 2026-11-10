package com.nexbox.core.core.system.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Color;

import com.nexbox.core.NexBoxCore;


public class NotificationChannelManager {
    private final static NotificationChannelManager sManager = new NotificationChannelManager();

    public static NotificationChannel APP_CHANNEL;

    public static NotificationChannelManager get() {
        return sManager;
    }

    public NotificationChannelManager() {
        registerAppChannel();
    }

    private void registerAppChannel() {
        NotificationManager nm = (NotificationManager) NexBoxCore.getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        String CHANNEL_ONE_ID = NexBoxCore.getContext().getPackageName();
        String CHANNEL_ONE_NAME = "NexBox";
        APP_CHANNEL = new NotificationChannel(CHANNEL_ONE_ID,
                CHANNEL_ONE_NAME, NotificationManager.IMPORTANCE_HIGH);
        APP_CHANNEL.enableLights(true);
        APP_CHANNEL.setLightColor(Color.RED);
        APP_CHANNEL.setShowBadge(true);
        APP_CHANNEL.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(APP_CHANNEL);
    }
}
