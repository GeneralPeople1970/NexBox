package com.nexbox.app.view.main

import com.nexbox.app.util.NexLog

import android.app.Application
import android.content.Context
import java.io.File
import com.nexbox.core.NexBoxCore
import com.nexbox.core.app.BActivityThread
import com.nexbox.core.app.configuration.AppLifecycleCallback
import com.nexbox.core.app.configuration.ClientConfiguration
import com.nexbox.core.fake.device.DeviceSpoofManager
import com.nexbox.core.fake.frameworks.BUserManager
import com.nexbox.app.app.App
import com.nexbox.app.app.AppFreezeButtonManager
import com.nexbox.app.app.AppFreezeManager
import com.nexbox.app.app.rocker.RockerManager
import com.nexbox.app.biz.cache.AppSharedPreferenceDelegate
import com.nexbox.app.data.RuntimeSettingsChanges
import com.nexbox.app.data.RuntimeSettingsRepository


class NexBoxLoader {

    private val mHideRoot: Boolean get() = RuntimeSettingsRepository.isHideRootEnabled()

    private var mDaemonEnable by AppSharedPreferenceDelegate(App.getContext(), false)
    private var mShowShortcutPermissionDialog by AppSharedPreferenceDelegate(App.getContext(), true)

    private var mUseVpnNetwork by AppSharedPreferenceDelegate(App.getContext(), false)

    private var mDisableFlagSecure by AppSharedPreferenceDelegate(App.getContext(), false)

    fun hideRoot(): Boolean {
        return try {
            mHideRoot
        } catch (e: Exception) {
            NexLog.e(TAG, "Error getting hideRoot: ${e.message}", e)
            false
        }
    }

    fun invalidHideRoot(hideRoot: Boolean) {
        RuntimeSettingsRepository.apply(RuntimeSettingsChanges(hideRoot = hideRoot))
    }

    fun disableFlagSecure(): Boolean {
        return try {
            mDisableFlagSecure
        } catch (e: Exception) {
            NexLog.e(TAG, "Error getting disableFlagSecure: ${e.message}", e)
            false
        }
    }

    fun invalidDisableFlagSecure(disable: Boolean) {
        try {
            this.mDisableFlagSecure = disable
        } catch (e: Exception) {
            NexLog.e(TAG, "Error setting disableFlagSecure: ${e.message}", e)
        }
    }

    fun daemonEnable(): Boolean {
        return try {
            mDaemonEnable
        } catch (e: Exception) {
            NexLog.e(TAG, "Error getting daemonEnable: ${e.message}", e)
            false
        }
    }

    fun invalidDaemonEnable(enable: Boolean) {
        try {
            this.mDaemonEnable = enable
        } catch (e: Exception) {
            NexLog.e(TAG, "Error setting daemonEnable: ${e.message}", e)
        }
    }

    fun showShortcutPermissionDialog(): Boolean {
        return try {
            mShowShortcutPermissionDialog
        } catch (e: Exception) {
            NexLog.e(TAG, "Error getting showShortcutPermissionDialog: ${e.message}", e)
            true
        }
    }

    fun invalidShortcutPermissionDialog(show: Boolean) {
        try {
            this.mShowShortcutPermissionDialog = show
        } catch (e: Exception) {
            NexLog.e(TAG, "Error setting showShortcutPermissionDialog: ${e.message}", e)
        }
    }

    fun useVpnNetwork(): Boolean {
        return try {
            mUseVpnNetwork
        } catch (e: Exception) {
            NexLog.e(TAG, "Error getting useVpnNetwork: ${e.message}", e)
            false
        }
    }

    fun invalidUseVpnNetwork(enable: Boolean) {
        try {
            this.mUseVpnNetwork = enable
        } catch (e: Exception) {
            NexLog.e(TAG, "Error setting useVpnNetwork: ${e.message}", e)
        }
    }

    fun getNexBoxCore(): NexBoxCore {
        return try {
            NexBoxCore.get()
        } catch (e: Exception) {
            NexLog.e(TAG, "Error getting NexBoxCore: ${e.message}", e)
            throw e
        }
    }

    fun addLifecycleCallback() {
        try {
            NexBoxCore.get()
                    .addAppLifecycleCallback(
                            object : AppLifecycleCallback() {
                                override fun beforeCreateApplication(
                                        packageName: String?,
                                        processName: String?,
                                        context: Context?,
                                        userId: Int
                                ) {
                                    NexLog.i("GuestLifecycle", "stage=beforeCreateApplication package=$packageName guestProcess=$processName userId=$userId")
                                    try {
                                        DeviceSpoofManager.applyToCurrentProcess(userId)
                                    } catch (e: Exception) {
                                        NexLog.e(TAG, "Error in beforeCreateApplication: ${e.message}", e)
                                    }
                                }

                                override fun beforeApplicationOnCreate(
                                        packageName: String?,
                                        processName: String?,
                                        application: Application?,
                                        userId: Int
                                ) {
                                    NexLog.i("GuestLifecycle", "stage=beforeOnCreate package=$packageName guestProcess=$processName userId=$userId")
                                }

                                override fun afterApplicationOnCreate(
                                        packageName: String?,
                                        processName: String?,
                                        application: Application?,
                                        userId: Int
                                ) {
                                    NexLog.i("GuestLifecycle", "stage=afterOnCreate package=$packageName guestProcess=$processName userId=$userId")
                                    try {
                                        RockerManager.init(application, userId)
                                    } catch (e: Exception) {
                                        NexLog.e(
                                                TAG,
                                                "Error in afterApplicationOnCreate: ${e.message}", e
                                        )
                                    }
                                }

                                override fun onActivityStarted(activity: android.app.Activity) {
                                    NexLog.d("GuestLifecycle", "stage=activityStarted activity=${activity.javaClass.name}")
                                    try {
                                        AppFreezeButtonManager.sync(activity)
                                        AppFreezeManager.onActivityStarted(
                                                BActivityThread.getAppPackageName(),
                                                BActivityThread.getUserId()
                                        )
                                    } catch (e: Exception) {
                                        NexLog.e(TAG, "Error in onActivityStarted: ${e.message}", e)
                                    }
                                }

                                override fun onActivityResumed(activity: android.app.Activity) {
                                    NexLog.d("GuestLifecycle", "stage=activityResumed activity=${activity.javaClass.name}")
                                    try {
                                        AppFreezeButtonManager.sync(activity)
                                        AppFreezeManager.onActivityResumed(
                                                BActivityThread.getAppPackageName(),
                                                BActivityThread.getUserId()
                                        )
                                    } catch (e: Exception) {
                                        NexLog.e(TAG, "Error in onActivityResumed: ${e.message}", e)
                                    }
                                }

                                override fun onActivityStopped(activity: android.app.Activity) {
                                    NexLog.d("GuestLifecycle", "stage=activityStopped activity=${activity.javaClass.name}")
                                    try {
                                        AppFreezeManager.onActivityStopped(
                                                BActivityThread.getAppPackageName(),
                                                BActivityThread.getUserId(),
                                                activity.isChangingConfigurations
                                        )
                                        if (!activity.isChangingConfigurations) {
                                            AppFreezeButtonManager.remove(activity)
                                        }
                                    } catch (e: Exception) {
                                        NexLog.e(TAG, "Error in onActivityStopped: ${e.message}", e)
                                    }
                                }

                                override fun onActivityCreated(
                                        activity: android.app.Activity,
                                        savedInstanceState: android.os.Bundle?
                                ) {
                                    try {
                                        AppFreezeButtonManager.sync(activity)
                                    } catch (e: Exception) {
                                        NexLog.e(TAG, "Error in onActivityCreated: ${e.message}", e)
                                    }
                                }

                                override fun onActivityDestroyed(activity: android.app.Activity) {
                                    try {
                                        AppFreezeButtonManager.remove(activity)
                                    } catch (e: Exception) {
                                        NexLog.e(TAG, "Error in onActivityDestroyed: ${e.message}", e)
                                    }
                                }
                            }
                    )
        } catch (e: Exception) {
            NexLog.e(TAG, "Error adding lifecycle callback: ${e.message}", e)
        }
    }

    fun attachBaseContext(context: Context) {
        try {
            NexBoxCore.get()
                    .doAttachBaseContext(
                            context,
                            object : ClientConfiguration() {
                                override fun getHostPackageName(): String {
                                    return try {
                                        context.packageName
                                    } catch (e: Exception) {
                                        NexLog.e(TAG, "Error getting package name: ${e.message}", e)
                                        "unknown"
                                    }
                                }

                                override fun isHideRoot(): Boolean {
                                    return try {
                                        mHideRoot
                                    } catch (e: Exception) {
                                        NexLog.e(TAG, "Error checking hideRoot: ${e.message}", e)
                                        false
                                    }
                                }

                                override fun isEnableDaemonService(): Boolean {
                                    return try {
                                        mDaemonEnable
                                    } catch (e: Exception) {
                                        NexLog.e(TAG, "Error checking daemonEnable: ${e.message}", e)
                                        false
                                    }
                                }

                                override fun isUseVpnNetwork(): Boolean {
                                    return try {
                                        mUseVpnNetwork
                                    } catch (e: Exception) {
                                        NexLog.e(TAG, "Error checking useVpnNetwork: ${e.message}", e)
                                        false
                                    }
                                }

                                override fun isDisableFlagSecure(): Boolean {
                                    return try {
                                        mDisableFlagSecure
                                    } catch (e: Exception) {
                                        NexLog.e(TAG, "Error checking disableFlagSecure: ${e.message}", e)
                                        false
                                    }
                                }

                                override fun requestInstallPackage(
                                        file: File?,
                                        userId: Int
                                ): Boolean {
                                    if (file == null || !file.exists()) {
                                        return false
                                    }
                                    return try {
                                        // 沙盒内应用发起安装时，把 APK 装进它所在的沙盒分身。
                                        // 返回 true 表示由 Bcore 接管（不弹系统安装器）；
                                        // 失败时返回 false，交由宿主系统安装器兜底，保持原有行为。
                                        val result = NexBoxCore.get()
                                                .installPackageAsUser(file, userId)
                                        val packageName = result.packageName
                                        val confirmed = result.success && !packageName.isNullOrBlank() &&
                                            BUserManager.get().service?.getUserInfo(userId) != null &&
                                            NexBoxCore.getBPackageManager().service
                                                ?.isInstalled(packageName, userId) == true
                                        if (!confirmed) {
                                            NexLog.w(TAG, "requestInstallPackage not confirmed: ${result.msg}")
                                        }
                                        confirmed
                                    } catch (e: Exception) {
                                        NexLog.e(TAG, "Error in requestInstallPackage: ${e.message}", e)
                                        false
                                    }
                                }
                            }
                    )
        } catch (e: Exception) {
            NexLog.e(TAG, "Error in attachBaseContext: ${e.message}", e)
        }
    }

    fun doOnCreate() {
        try {
            NexBoxCore.get().doCreate()
            if (NexBoxCore.get().isMainProcess) try {
                NexBoxCore.get().addServiceAvailableCallback {
                    NexLog.d(TAG, "Services became available")
                }
            } catch (e: Exception) {
                NexLog.e(TAG, "Error registering service available callback: ${e.message}", e)
            }
        } catch (e: Exception) {
            NexLog.e(TAG, "Error in doOnCreate: ${e.message}", e)
        }
    }

    companion object {
        val TAG: String = NexBoxLoader::class.java.simpleName
    }
}
