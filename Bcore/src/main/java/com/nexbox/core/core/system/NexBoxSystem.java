package com.nexbox.core.core.system;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.env.AppSystemEnv;
import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.core.system.accounts.BAccountManagerService;
import com.nexbox.core.core.system.am.BActivityManagerService;
import com.nexbox.core.core.system.am.BJobManagerService;
import com.nexbox.core.core.system.location.BLocationManagerService;
import com.nexbox.core.core.system.notification.BNotificationManagerService;
import com.nexbox.core.core.system.os.BStorageManagerService;
import com.nexbox.core.core.system.pm.BPackageInstallerService;
import com.nexbox.core.core.system.pm.BPackageManagerService;

import com.nexbox.core.core.system.user.BUserHandle;
import com.nexbox.core.core.system.user.BUserManagerService;
import com.nexbox.core.core.system.xposed.BXposedManagerService;
import com.nexbox.core.entity.pm.InstallOption;
import com.nexbox.core.utils.BzFileUtils;

import com.nexbox.core.core.system.JarManager;


public class NexBoxSystem {
    private static NexBoxSystem sNexBoxSystem;
    private final List<ISystemService> mServices = new ArrayList<>();
    private final static AtomicBoolean isStartup = new AtomicBoolean(false);
    private RuntimeException mStartupFailure;

    public static NexBoxSystem getSystem() {
        if (sNexBoxSystem == null) {
            synchronized (NexBoxSystem.class) {
                if (sNexBoxSystem == null) {
                    sNexBoxSystem = new NexBoxSystem();
                }
            }
        }
        return sNexBoxSystem;
    }

    public synchronized void startup() {
        if (isStartup.get()) return;
        if (mStartupFailure != null) throw mStartupFailure;
        try {
            startServices();
            isStartup.set(true);
        } catch (RuntimeException failure) {
            // A second caller must not mistake a partial startup/recovery for readiness,
            // or replay the already-started services and their storage mutations.
            mStartupFailure = failure;
            throw failure;
        }
    }

    private void startServices() {
        BEnvironment.load();

        mServices.add(BProcessManagerService.get());
        mServices.add(BPackageManagerService.get());
        mServices.add(BUserManagerService.get());
        mServices.add(BActivityManagerService.get());
        mServices.add(BJobManagerService.get());
        mServices.add(BStorageManagerService.get());
        mServices.add(BPackageInstallerService.get());

        mServices.add(BAccountManagerService.get());
        mServices.add(BLocationManagerService.get());
        mServices.add(BNotificationManagerService.get());
        // Registered last: its systemReady() attaches a PackageMonitor to the package manager
        // service above, so that service must already be initialised.
        mServices.add(BXposedManagerService.get());

        for (ISystemService service : mServices) {
            service.systemReady();
        }

        List<String> preInstallPackages = AppSystemEnv.getPreInstallPackages();
        for (String preInstallPackage : preInstallPackages) {
            try {
                if (!BPackageManagerService.get().isInstalled(preInstallPackage, BUserHandle.USER_ALL)) {
                    PackageInfo packageInfo = NexBoxCore.getPackageManager().getPackageInfo(preInstallPackage, 0);
                    BPackageManagerService.get().installPackageAsUser(packageInfo.applicationInfo.sourceDir, InstallOption.installBySystem(), BUserHandle.USER_ALL);
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        
        JarManager.getInstance().initializeAsync();
        
        
     
    }
}
