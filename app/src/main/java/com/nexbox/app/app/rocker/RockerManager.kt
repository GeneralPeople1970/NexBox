package com.nexbox.app.app.rocker

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import com.nexbox.app.util.NexLog
import com.nexbox.app.widget.EnFloatView
import com.nexbox.core.NexBoxCore
import com.nexbox.core.utils.provider.ProviderCall
import kotlinx.coroutines.*

object RockerManager {
    private var application: Application? = null
    private var callbacks: Application.ActivityLifecycleCallbacks? = null
    private var view: EnFloatView? = null
    private var scope: CoroutineScope? = null
    private var movement: Job? = null
    private var attachJob: Job? = null
    private var angle = 0f
    private var strength = 0f

    fun init(app: Application?, userId: Int) {
        if (app == null || application != null) return
        application = app
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        callbacks = object : BaseActivityLifecycleCallback() {
            override fun onActivityResumed(activity: Activity) {
                detach()
                attachJob = scope?.launch {
                    val enabled = withContext(Dispatchers.IO) { request("state", 0f, 0f) }
                    if (!enabled || activity.isFinishing || activity.isDestroyed) return@launch
                    val root = activity.window.decorView as? ViewGroup ?: return@launch
                    val size = (120 * activity.resources.displayMetrics.density).toInt()
                    view = EnFloatView(activity).also { joystick ->
                        joystick.setListener { direction, amount ->
                            angle = direction; strength = amount
                            if (amount == 0f) { movement?.cancel(); movement = null }
                            else if (movement == null) movement = scope?.launch {
                                while (isActive && strength > 0f) {
                                    delay(500)
                                    val directionNow = angle; val distance = strength * 2.5f
                                    if (distance <= 0f) break
                                    val success = withContext(Dispatchers.IO) { request("move", directionNow, distance) }
                                    if (!success) { detach(); break }
                                }
                            }
                        }
                        root.addView(joystick, FrameLayout.LayoutParams(size, size, Gravity.END or Gravity.CENTER_VERTICAL))
                    }
                }
            }
            override fun onActivityPaused(activity: Activity) { detach() }
            override fun onActivityDestroyed(activity: Activity) { if (view?.context === activity) detach() }
        }
        app.registerActivityLifecycleCallbacks(callbacks)
    }
    private fun request(method: String, direction: Float, distance: Float): Boolean = try {
        val payload = Bundle().apply { putFloat("angle", direction); putFloat("distance", distance) }
        ProviderCall.call(NexBoxCore.getHostPkg() + ".rocker", NexBoxCore.getContext(), method, null, payload, 0)
            ?.getBoolean("success", false) == true
    } catch (failure: Exception) {
        NexLog.w("RockerManager", "Joystick request failed", failure)
        false
    }
    private fun detach() {
        attachJob?.cancel(); attachJob = null
        movement?.cancel(); movement = null; strength = 0f
        view?.let { it.cancelInput(); (it.parent as? ViewGroup)?.removeView(it) }
        view = null
    }
    fun isActive(): Boolean = view != null
    fun cleanup() {
        detach(); scope?.cancel(); scope = null
        callbacks?.let { application?.unregisterActivityLifecycleCallbacks(it) }
        callbacks = null; application = null
    }
}
