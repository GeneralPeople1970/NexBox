package com.nexbox.app.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import com.nexbox.app.util.NexLog
import com.nexbox.app.util.HostLifecycleLogging
import com.nexbox.app.util.InjectionUtil
import com.nexbox.core.NexBoxCore

class App : Application() {

    companion object {

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private lateinit var mContext: Context

        @JvmStatic
        fun getContext(): Context {
            return mContext
        }
    }

    override fun attachBaseContext(base: Context?) {
        try {
            super.attachBaseContext(base)

            if (base != null) NexLog.install(base)

            try {
                NexBoxCore.get().closeCodeInit()
            } catch (e: Exception) {
                NexLog.e("App", "Error in closeCodeInit: ${e.message}", e)
            }

            try {
                NexBoxCore.get().onBeforeMainApplicationAttach(this, base)
            } catch (e: Exception) {
                NexLog.e("App", "Error in onBeforeMainApplicationAttach: ${e.message}", e)
            }

            mContext = base!!

            try {
                AppManager.doAttachBaseContext(base)
            } catch (e: Exception) {
                NexLog.e("App", "Error in doAttachBaseContext: ${e.message}", e)
            }

            try {
                NexBoxCore.get().onAfterMainApplicationAttach(this, base)
            } catch (e: Exception) {
                NexLog.e("App", "Error in onAfterMainApplicationAttach: ${e.message}", e)
            }
        } catch (e: Exception) {
            NexLog.e("App", "Critical error in attachBaseContext: ${e.message}", e)
            if (base != null) {
                mContext = base
            }
        }
    }

    override fun onCreate() {
        try {
            super.onCreate()

            NexLog.install(this)
            HostLifecycleLogging.install(this)

            AppManager.doOnCreate()
            NexLog.i("App", "stage=applicationReady pid=${android.os.Process.myPid()}")
        } catch (e: Exception) {
            NexLog.e("App", "Error in onCreate", e)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        InjectionUtil.trimAppIcons(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            NexLog.d("App", "stage=uiHidden")
        } else {
            NexLog.w("App", "stage=memoryPressure level=$level")
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        InjectionUtil.trimAppIcons(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        InjectionUtil.invalidateIconConfiguration()
    }
}
