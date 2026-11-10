package com.nexbox.app.util

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Records host windows, never Intent extras, clipboard contents, view text or account data. */
internal object HostLifecycleLogging : Application.ActivityLifecycleCallbacks {
    fun install(application: Application) = application.registerActivityLifecycleCallbacks(this)
    private fun event(activity: Activity, stage: String) = NexLog.d("HostLifecycle") {
        "stage=$stage activity=${activity.javaClass.name} changingConfiguration=${activity.isChangingConfigurations}"
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = event(activity, "created")
    override fun onActivityStarted(activity: Activity) = event(activity, "started")
    override fun onActivityResumed(activity: Activity) = event(activity, "resumed")
    override fun onActivityPaused(activity: Activity) = event(activity, "paused")
    override fun onActivityStopped(activity: Activity) = event(activity, "stopped")
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = event(activity, "stateSaved")
    override fun onActivityDestroyed(activity: Activity) = event(activity, "destroyed")
}
