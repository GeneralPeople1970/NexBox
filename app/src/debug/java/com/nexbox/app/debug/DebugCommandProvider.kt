package com.nexbox.app.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import com.nexbox.app.debug.DebugCommandJson.hasJsonCause
import com.nexbox.app.data.RuntimeSettingsSaveException
import com.nexbox.app.data.ModulePostInstallException
import com.nexbox.app.util.NexLog
import com.nexbox.app.util.LogPrivacy
import com.nexbox.core.utils.ConfigurationStopException
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Debug-only adb control plane. Release builds do not merge this source set. */
class DebugCommandProvider : ContentProvider() {
    private lateinit var router: DebugCommandRouter
    private val nextCallerRejectionLog = AtomicLong()

    override fun onCreate(): Boolean {
        val appContext = requireNotNull(context)
        // ContentProviders are created before Application.onCreate on a cold start. Make the
        // diagnostic surface self-contained so `logs enable/clear` is usable during boot failures.
        NexLog.install(appContext)
        router = DebugCommandRouter(appContext)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        var command = ""
        var requestId = UUID.randomUUID().toString()
        if (method != METHOD_EXECUTE) return response(command, requestId, false, null,
            IllegalArgumentException("Unsupported debug provider method"))
        if (!isAllowedCaller()) return response(command, requestId, false, null,
            SecurityException("Debug commands require adb shell, root, or the host main process"))
        val callingIdentity = Binder.clearCallingIdentity()
        return try {
            val wire = readPayload(arg, extras)
            require(wire.keys().asSequence().all { it == "command" || it == "request" }) { "Unsupported envelope field" }
            val requestedCommand = wire.opt("command")
            require(requestedCommand is String && requestedCommand.matches(Regex("[a-z][a-z0-9.-]{0,79}"))) {
                "A valid command is required"
            }
            command = requestedCommand
            val payload = if (wire.has("request")) wire.getJSONObject("request") else JSONObject()
            if (router.isConditionalMutation(command)) {
                val suppliedId = payload.opt("requestId") as? String
                com.nexbox.core.utils.MutationReceipt.requireRequestId(suppliedId)
                requestId = requireNotNull(suppliedId)
            }
            val result = router.execute(command, payload, requestId)
            val failure = debugResultError(result)
            response(command, requestId, failure == null, result, null, failure)
        } catch (failure: Throwable) {
            // JSON exception text can contain the complete input, including credentials or coordinates.
            val jsonFailure = hasJsonCause(failure)
            NexLog.e("DebugCLI", "command=$command requestId=$requestId stage=failed error=${errorCode(failure)}",
                if (jsonFailure) null else failure)
            val saved = if (failure is ConfigurationStopException) {
                val targets = JSONArray().apply {
                    failure.failedTargets.forEach { target ->
                        put(JSONObject().put("packageName", target.packageName).put("userId", target.userId)
                            .put("createTime", target.generation))
                    }
                }
                JSONObject().put("configurationSaved", true).put("retryCommand", "config.retry-stops")
                    .put("failedTargets", targets).put("retryRequest", JSONObject().put("targets", targets))
            } else if (failure is ModulePostInstallException) {
                JSONObject().put("installationConfirmed", true).put("packageName", failure.packageName)
                    .put("userId", failure.owner.userId).put("createTime", failure.owner.createTime)
                    .put("validationConfirmed", false)
            } else if (failure is RuntimeSettingsSaveException) {
                if (failure.unconfirmedKeys.isEmpty()) {
                    JSONObject().put("configurationSaved", false).put("previousConfigurationRestored", true)
                } else {
                    JSONObject().put("configurationSaved", JSONObject.NULL).put("partialSavePossible", true)
                        .put("unconfirmedKeys", JSONArray(failure.unconfirmedKeys.toList()))
                }
            } else if (failure is UpdateCheckException) {
                failure.data
            } else null
            response(command, requestId, false, saved, failure)
        } finally {
            Binder.restoreCallingIdentity(callingIdentity)
        }
    }

    private fun isAllowedCaller(): Boolean {
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        val allowed = isAllowedCaller(uid, pid, Process.myUid(), Process.myPid())
        if (!allowed && NexLog.isEnabled(NexLog.Level.WARN)) {
            val now = SystemClock.elapsedRealtime()
            val next = nextCallerRejectionLog.get()
            if (now >= next && nextCallerRejectionLog.compareAndSet(next, now + 10_000L)) {
                NexLog.w("DebugCLI", "stage=callerRejected reason=caller_not_allowed callerUid=$uid callerPid=$pid")
            }
        }
        return allowed
    }

    private fun readPayload(arg: String?, extras: Bundle?): JSONObject {
        val encodedArg = arg?.trim().orEmpty()
        val path = if (encodedArg.startsWith("file:")) encodedArg.removePrefix("file:").trim()
            else extras?.getString(EXTRA_PAYLOAD_PATH)?.trim().orEmpty()
        if (path.isNotEmpty()) {
            val file = File(path).canonicalFile
            val root = requireNotNull(context).getExternalFilesDir(DEBUG_DIRECTORY)?.canonicalFile
                ?: File(requireNotNull(context).filesDir, DEBUG_DIRECTORY).canonicalFile
            require(file.path.startsWith(root.path + File.separator)) { "Payload path is outside the debug directory" }
            require(file.isFile && file.length() <= MAX_PAYLOAD_BYTES) { "Invalid or oversized payload file" }
            return try {
                JSONObject(file.readText(Charsets.UTF_8))
            } finally {
                file.delete()
            }
        }
        val encoded = if (encodedArg.startsWith("b64:")) encodedArg.removePrefix("b64:").trim()
            else extras?.getString(EXTRA_PAYLOAD_B64)?.trim().orEmpty()
        if (encoded.isEmpty()) return JSONObject().put("command", encodedArg)
        require(encoded.length <= (MAX_PAYLOAD_BYTES * 4 / 3 + 4)) { "Payload is too large" }
        val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "Payload is too large" }
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    private fun response(command: String, requestId: String, ok: Boolean, data: JSONObject?, failure: Throwable?,
                         resultError: DebugCommandError? = null): Bundle {
        val envelope = JSONObject()
            .put("schema", 1)
            .put("ok", ok)
            .put("command", command)
            .put("requestId", requestId)
        if (data != null || ok) envelope.put("data", data ?: JSONObject())
        if (!ok) envelope.put("error", JSONObject()
            .put("code", resultError?.code ?: errorCode(failure))
            .put("message", LogPrivacy.clean(resultError?.message ?: if (hasJsonCause(failure))
                "Malformed JSON or a field has the wrong type; use help for this command"
                else failure?.message ?: "Debug command failed").take(512)))
        val json = envelope.toString()
        val bytes = json.toByteArray(Charsets.UTF_8)
        return Bundle().apply {
            putBoolean("ok", ok)
            if (bytes.size <= MAX_INLINE_RESPONSE_BYTES) {
                putString("json_b64", Base64.encodeToString(bytes,
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            } else {
                val directory = requireNotNull(context).getExternalFilesDir(DEBUG_DIRECTORY)
                    ?: error("External app storage is unavailable for a large response")
                check(directory.isDirectory || directory.mkdirs()) { "Could not create response directory" }
                directory.listFiles().orEmpty().filter {
                    it.isFile && it.name.startsWith("response-") &&
                        System.currentTimeMillis() - it.lastModified() > RESPONSE_RETENTION_MS
                }.forEach { it.delete() }
                val output = File.createTempFile("response-", ".json", directory)
                FileOutputStream(output).use { it.write(bytes) }
                putString("json_path", output.absolutePath)
            }
        }
    }

    private fun errorCode(failure: Throwable?): String = when (failure) {
        is UpdateCheckException -> failure.code
        is com.nexbox.core.core.system.pm.PackageCodeAvailability.SourceUnavailableException -> "source_unavailable"
        is ConfigurationStopException -> "config_saved_stop_failed"
        is RuntimeSettingsSaveException -> "runtime_settings_save_failed"
        is ModulePostInstallException -> "module_installed_validation_failed"
        is SecurityException -> "permission_denied"
        is IllegalArgumentException -> "invalid_request"
        is JSONException -> "invalid_request"
        is IllegalStateException -> "state_unavailable"
        else -> "command_failed"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object {
        const val METHOD_EXECUTE = "execute"
        const val EXTRA_PAYLOAD_B64 = "payload_b64"
        const val EXTRA_PAYLOAD_PATH = "payload_path"
        const val DEBUG_DIRECTORY = "debug-cli"
        private const val MAX_PAYLOAD_BYTES = 512 * 1024L
        private const val MAX_INLINE_RESPONSE_BYTES = 48 * 1024
        private const val RESPONSE_RETENTION_MS = 10 * 60 * 1000L
        private const val SHELL_UID = 2000
        private const val ROOT_UID = 0

        internal fun isAllowedCaller(uid: Int, pid: Int, hostUid: Int, hostPid: Int): Boolean =
            uid == SHELL_UID || uid == ROOT_UID ||
                // Guests and the core share the app UID. Only local host calls may become
                // a manager after clearCallingIdentity; no core readiness check is required.
                (uid == hostUid && hostPid > 0 && pid == hostPid)
    }
}
