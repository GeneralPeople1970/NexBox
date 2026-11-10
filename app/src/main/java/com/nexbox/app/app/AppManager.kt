package com.nexbox.app.app

import com.nexbox.app.util.NexLog

import android.content.Context
import android.content.SharedPreferences
import com.nexbox.app.view.main.NexBoxLoader

object AppManager {
    private const val TAG = "AppManager"

    @JvmStatic
    val mNexBoxLoader by lazy {
        try {
            NexBoxLoader()
        } catch (e: Exception) {
            NexLog.e(TAG, "Error creating NexBoxLoader: ${e.message}", e)
            NexBoxLoader()
        }
    }

    @JvmStatic
    val mNexBoxCore by lazy {
        try {
            mNexBoxLoader.getNexBoxCore()
        } catch (e: Exception) {
            NexLog.e(TAG, "Error getting NexBoxCore: ${e.message}", e)
            throw e
        }
    }

    @JvmStatic
    val mRemarkSharedPreferences: SharedPreferences by lazy {
        try {
            App.getContext().getSharedPreferences("UserRemark", Context.MODE_PRIVATE)
        } catch (e: Exception) {
            NexLog.e(TAG, "Error creating SharedPreferences: ${e.message}", e)
            throw e
        }
    }

    fun doAttachBaseContext(context: Context) {
        try {
            mNexBoxLoader.attachBaseContext(context)
            mNexBoxLoader.addLifecycleCallback()
        } catch (e: Exception) {
            NexLog.e(TAG, "Error in doAttachBaseContext: ${e.message}", e)
        }
    }

    fun doOnCreate() {
        try {
            mNexBoxLoader.doOnCreate()
        } catch (e: Exception) {
            NexLog.e(TAG, "Error in doOnCreate: ${e.message}", e)
        }
    }
}
