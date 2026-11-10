package com.nexbox.core.core.system;

import android.os.IBinder;

import java.util.HashMap;
import java.util.Map;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.system.accounts.BAccountManagerService;
import com.nexbox.core.core.system.am.BActivityManagerService;
import com.nexbox.core.core.system.am.BJobManagerService;
import com.nexbox.core.core.system.location.BLocationManagerService;
import com.nexbox.core.core.system.notification.BNotificationManagerService;
import com.nexbox.core.core.system.os.BStorageManagerService;
import com.nexbox.core.core.system.pm.BPackageManagerService;
import com.nexbox.core.core.system.user.BUserManagerService;
import com.nexbox.core.core.system.xposed.BXposedManagerService;


public class ServiceManager {
    private static volatile ServiceManager sServiceManager = null;
    public static final String ACTIVITY_MANAGER = "activity_manager";
    public static final String JOB_MANAGER = "job_manager";
    public static final String PACKAGE_MANAGER = "package_manager";
    public static final String STORAGE_MANAGER = "storage_manager";
    public static final String USER_MANAGER = "user_manager";

    public static final String ACCOUNT_MANAGER = "account_manager";
    public static final String LOCATION_MANAGER = "location_manager";
    public static final String NOTIFICATION_MANAGER = "notification_manager";
    public static final String XPOSED_MANAGER = "xposed_manager";

    private final Map<String, IBinder> mCaches = new HashMap<>();

    public static ServiceManager get() {
        if (sServiceManager == null) {
            synchronized (ServiceManager.class) {
                if (sServiceManager == null) {
                    sServiceManager = new ServiceManager();
                }
            }
        }
        return sServiceManager;
    }

    public static IBinder getService(String name) {
        return get().getServiceInternal(name);
    }

    private ServiceManager() {
        mCaches.put(ACTIVITY_MANAGER, BActivityManagerService.get());
        mCaches.put(JOB_MANAGER, BJobManagerService.get());
        mCaches.put(PACKAGE_MANAGER, BPackageManagerService.get());
        mCaches.put(STORAGE_MANAGER, BStorageManagerService.get());
        mCaches.put(USER_MANAGER, BUserManagerService.get());

        mCaches.put(ACCOUNT_MANAGER, BAccountManagerService.get());
        mCaches.put(LOCATION_MANAGER, BLocationManagerService.get());
        mCaches.put(NOTIFICATION_MANAGER, BNotificationManagerService.get());
        mCaches.put(XPOSED_MANAGER, BXposedManagerService.get());
    }

    public IBinder getServiceInternal(String name) {
        return mCaches.get(name);
    }

    public static void initCoreServiceManager() {
        NexBoxCore.get().waitForServicesAvailable(10_000L);
    }
}
