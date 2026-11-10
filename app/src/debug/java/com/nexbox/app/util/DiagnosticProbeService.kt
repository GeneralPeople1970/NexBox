package com.nexbox.app.util

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.nexbox.core.core.NativeCore
import com.nexbox.core.utils.Slog
import com.nexbox.core.xposed.RuntimeLog

/** Debug-only, same-UID bound service. Emits synthetic diagnostics, never mutates virtual users. */
class DiagnosticProbeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        val marker = message.data.getString("marker").orEmpty().take(80)
        val enabled = message.data.getBoolean("enabled")
        emitWhenConfigured(marker, enabled, message.replyTo, 0)
        true
    })
    override fun onBind(intent: Intent?): IBinder? =
        if (packageName.endsWith(".logtest")) messenger.binder else null

    private fun emitWhenConfigured(marker: String, enabled: Boolean, reply: Messenger?, attempt: Int) {
        if (RuntimeLog.isEnabled() != enabled && attempt < 30) {
            handler.postDelayed({ emitWhenConfigured(marker, enabled, reply, attempt + 1) }, 100)
            return
        }
        var success = RuntimeLog.isEnabled() == enabled
        try {
            NexLog.i("HostProbe", "marker=$marker token=private-$marker")
            Slog.w("CoreProbe", "marker=$marker core bridge", IllegalStateException("fixture-$marker"))
            RuntimeLog.i("ModuleProbe", "marker=$marker runtime bridge")
            RuntimeLog.crash(Thread.currentThread(), IllegalStateException("marker=$marker synthetic-crash"))
            NativeCore::class.java.getDeclaredMethod("diagnosticLogProbe", String::class.java).apply { isAccessible = true }
                .invoke(null, marker)
        } catch (error: Throwable) {
            success = false
            NexLog.e("Probe", "marker=$marker diagnostic bridge failure", error)
        }
        runCatching { reply?.send(Message.obtain(null, if (success) 1 else 0, Process.myPid(), 0)) }
    }
}
