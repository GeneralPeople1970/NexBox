package com.nexbox.app.util

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** Private same-UID relay. Remote processes cannot change settings, clear, read or export logs. */
class DiagnosticLogProvider : ContentProvider() {
    override fun onCreate(): Boolean { context?.let(NexLog::install); return true }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == Process.myUid()) { "Diagnostic relay is private" }
        require(method == "config" || method == "append" || method == "crash") { "Unsupported diagnostic operation" }
        if (method == "crash") {
            val event = extras?.let(::decode)
            val accepted = event != null && NexLog.acceptRemoteCrash(event, Binder.getCallingPid())
            return encodeConfiguration(NexLog.configuration()).apply { putBoolean("accepted", accepted) }
        }
        if (method == "append") {
            @Suppress("DEPRECATION")
            val events = extras?.getParcelableArrayList<Bundle>("events").orEmpty()
            require(events.size <= MAX_BATCH) { "Diagnostic batch too large" }
            val pid = Binder.getCallingPid()
            events.forEach { bundle -> decode(bundle)?.let { NexLog.acceptRemote(it, pid) } }
        }
        return encodeConfiguration(NexLog.configuration())
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object {
        internal const val MAX_BATCH = 16
        internal fun uri(context: Context): Uri = Uri.parse("content://${context.packageName}.diagnostics")
        internal fun encodeConfiguration(configuration: LogConfiguration) = Bundle().apply {
            putBoolean("recording", configuration.settings.recordingEnabled)
            putBoolean("adb", configuration.settings.adbEnabled)
            putBoolean("detailed", configuration.settings.detailedEnabled)
            putLong("epoch", configuration.epoch)
        }
        internal fun decodeConfiguration(bundle: Bundle) = LogConfiguration(
            LogSettings(bundle.getBoolean("recording"), bundle.getBoolean("adb"), bundle.getBoolean("detailed")),
            bundle.getLong("epoch")
        )
        internal fun encode(event: PendingLog) = Bundle().apply {
            putInt("level", event.level.priority); putString("tag", event.tag)
            putString("message", event.message); putLong("epoch", event.epoch)
            putLong("time", event.origin.timeMillis); putString("process", event.origin.process)
            putInt("pid", event.origin.pid); putString("thread", event.origin.thread); putString("session", event.origin.session)
            putString("context", event.origin.context)
        }
        private fun decode(bundle: Bundle): PendingLog? {
            val message = bundle.getString("message") ?: return null
            if (message.length > LogPrivacy.MAX_MESSAGE_CHARS) return null
            return PendingLog(NexLog.Level.fromPriority(bundle.getInt("level")), bundle.getString("tag").orEmpty(), message,
                LogOrigin(bundle.getLong("time"), bundle.getString("process").orEmpty(), bundle.getInt("pid"),
                    bundle.getString("thread").orEmpty(), bundle.getString("session").orEmpty(), bundle.getString("context").orEmpty()), bundle.getLong("epoch"))
        }
    }
}
