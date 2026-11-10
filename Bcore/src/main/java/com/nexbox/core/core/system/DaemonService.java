package com.nexbox.core.core.system;

import com.nexbox.core.utils.Slog;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.R;


public class DaemonService extends Service {
    public static final String TAG = "DaemonService";
    private static final int NOTIFY_ID = NexBoxCore.getHostPkg().hashCode();
    private static final String CHANNEL_ID = "nexbox_daemon_channel";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Slog.d(TAG, "DaemonService onCreate");
        
        
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Slog.d(TAG, "DaemonService onStartCommand");
        
        try {
            
            Intent innerIntent = new Intent(this, DaemonInnerService.class);
            startService(innerIntent);
            
            
            if (!startForegroundService()) {
                Slog.w(TAG, "Failed to start foreground service, falling back to regular service");
                return START_STICKY;
            }
            
            Slog.d(TAG, "DaemonService started successfully");
            return START_STICKY;
            
        } catch (Exception e) {
            Slog.e(TAG, "Error starting DaemonService: " + e.getMessage(), e);
            
            return START_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        Slog.d(TAG, "DaemonService onDestroy");
        super.onDestroy();
    }

    
    private void createNotificationChannel() {
        try {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.nexbox_core_channel_name),
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.nexbox_core_channel_description));
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
                
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                Slog.d(TAG, "Notification channel created successfully");
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to create notification channel: " + e.getMessage(), e);
        }
    }

    
    private boolean startForegroundService() {
        try {
            Notification notification = createNotification();
            if (notification != null) {
                startForeground(NOTIFY_ID, notification);
                Slog.d(TAG, "Foreground service started successfully");
                return true;
            } else {
                Slog.e(TAG, "Failed to create notification");
                return false;
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to start foreground service: " + e.getMessage(), e);
            return false;
        }
    }

    
    private Notification createNotification() {
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.nexbox_core_notification_title))
                .setContentText(getString(R.string.nexbox_core_notification_running))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false);
            
            return builder.build();
        } catch (Exception e) {
            Slog.e(TAG, "Failed to create notification: " + e.getMessage(), e);
            return null;
        }
    }

    
    public static class DaemonInnerService extends Service {
        @Override
        public void onCreate() {
            Slog.i(TAG, "DaemonInnerService -> onCreate");
            super.onCreate();
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            Slog.i(TAG, "DaemonInnerService -> onStartCommand");
            
            try {
                
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.cancel(NOTIFY_ID);
                    Slog.d(TAG, "Notification cancelled successfully");
                }
                
                
                stopSelf();
                return START_NOT_STICKY;
                
            } catch (Exception e) {
                Slog.e(TAG, "Error in DaemonInnerService: " + e.getMessage(), e);
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        @Override
        public void onDestroy() {
            Slog.i(TAG, "DaemonInnerService -> onDestroy");
            super.onDestroy();
        }
    }
}
