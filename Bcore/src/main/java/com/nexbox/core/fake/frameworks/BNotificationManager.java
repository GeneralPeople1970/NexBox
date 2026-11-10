package com.nexbox.core.fake.frameworks;

import com.nexbox.core.utils.Slog;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;

import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.core.system.ServiceManager;
import com.nexbox.core.core.system.notification.IBNotificationManagerService;


public class BNotificationManager extends CoreServiceManager<IBNotificationManagerService> {
    private static final BNotificationManager sNotificationManager = new BNotificationManager();

    public static BNotificationManager get() {
        return sNotificationManager;
    }

    public List<android.service.notification.StatusBarNotification> getActiveNotifications() {
        try { return getService().getActiveNotifications(BActivityThread.getUserId()); }
        catch (RemoteException failure) { throw new IllegalStateException("Notification service unavailable", failure); }
    }

    public void cancelAllNotifications() {
        try { getService().cancelAllNotifications(BActivityThread.getUserId()); }
        catch (RemoteException failure) { throw new IllegalStateException("Notification service unavailable", failure); }
    }

    @Override
    protected String getServiceName() {
        return ServiceManager.NOTIFICATION_MANAGER;
    }

    public NotificationChannel getNotificationChannel(String channelId) {
        try {
            return getService().getNotificationChannel(channelId, BActivityThread.getUserId());
        } catch (RemoteException e) {
            Slog.e("BNotificationManager", "Container operation failed", e);
        }
        return null;
    }

    public List<NotificationChannelGroup> getNotificationChannelGroups(String packageName) {
        try {
            return getService().getNotificationChannelGroups(packageName, BActivityThread.getUserId());
        } catch (RemoteException e) {
            Slog.e("BNotificationManager", "Container operation failed", e);
        }
        return null;
    }

    public void createNotificationChannel(NotificationChannel notificationChannel) {
        try {
            getService().createNotificationChannel(notificationChannel, BActivityThread.getUserId());
        } catch (RemoteException e) {
            Slog.e("BNotificationManager", "Container operation failed", e);
        }
    }

    public void deleteNotificationChannel(String channelId) {
        try {
            getService().deleteNotificationChannel(channelId, BActivityThread.getUserId());
        } catch (RemoteException e) {
            Slog.e("BNotificationManager", "Container operation failed", e);
        }
    }

    public void createNotificationChannelGroup(NotificationChannelGroup notificationChannelGroup) {
        try {
            getService().createNotificationChannelGroup(notificationChannelGroup, BActivityThread.getUserId());
        } catch (RemoteException e) {
            Slog.e("BNotificationManager", "Container operation failed", e);
        }
    }

    public void deleteNotificationChannelGroup(String groupId) {
        try {
            getService().deleteNotificationChannelGroup(groupId, BActivityThread.getUserId());
        } catch (RemoteException e) {
            Slog.e("BNotificationManager", "Container operation failed", e);
        }
    }

    public void enqueueNotificationWithTag(int id, String tag, Notification notification) {
        try {
            getService().enqueueNotificationWithTag(id, tag, notification, BActivityThread.getUserId());
        } catch (RemoteException e) {
            Slog.e("BNotificationManager", "Container operation failed", e);
        }
    }

    public void cancelNotificationWithTag(int id, String tag) {
        try {
            getService().cancelNotificationWithTag(id, tag, BActivityThread.getUserId());
        } catch (RemoteException e) {
            Slog.e("BNotificationManager", "Container operation failed", e);
        }
    }

    public List<NotificationChannel> getNotificationChannels(String packageName) {
        try {
            return getService().getNotificationChannels(packageName, BActivityThread.getUserId());
        } catch (RemoteException e) {
            Slog.e("BNotificationManager", "Container operation failed", e);
        }
        return new ArrayList<>();
    }
}
