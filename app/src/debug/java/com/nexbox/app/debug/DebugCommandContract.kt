package com.nexbox.app.debug

import com.nexbox.app.debug.DebugCommandJson.jsonArray
import org.json.JSONArray
import org.json.JSONObject

/** The same contract drives dispatch validation and help; removed parameters cannot silently win. */
internal data class DebugCommandContract(
    val parameters: Set<String>,
    val required: Set<String> = emptySet(),
    val anyOf: Set<String> = emptySet(),
    val requiresServices: Boolean = true,
    val mutation: Boolean = false,
    val description: String = "",
    val aliasFor: String? = null,
    val safetyContract: String? = null
) {
    init {
        require(parameters.containsAll(required) && parameters.containsAll(anyOf)) {
            "Command requirements must be declared parameters"
        }
    }

    fun validate(request: JSONObject) {
        val supplied = request.keys().asSequence().toSet()
        require(supplied.all { it in parameters }) { "Unsupported request parameter; use help for this command" }
        require(required.all { request.has(it) && !request.isNull(it) }) {
            "Required parameters: ${required.joinToString()}"
        }
        require(anyOf.isEmpty() || anyOf.any { request.has(it) && !request.isNull(it) }) {
            "Supply at least one of: ${anyOf.joinToString()}"
        }
        supplied.forEach { key ->
            val value = request.get(key)
            if (value == JSONObject.NULL && key == "packageName" && key !in required) return@forEach
            require(when (key) {
                in BOOLEAN_PARAMETERS -> value is Boolean
                in INTEGER_PARAMETERS -> value is Int || value is Long
                in NUMBER_PARAMETERS -> value is Number && value.toDouble().isFinite()
                "profile", "simProfile" -> value is JSONObject
                "cards", "mediaIds", "expectedComponents" -> value is JSONArray
                "packages", "targets", "paths" -> value is JSONArray || value is String
                else -> value is String
            }) { "Invalid type for parameter: $key" }
            if (key in required && value is String) require(value.isNotBlank()) { "$key must not be blank" }
            if (key in INTEGER_PARAMETERS && key !in LONG_PARAMETERS) {
                require((value as Number).toLong() in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$key is outside the integer range" }
            }
        }
        for (key in listOf("userId", "offset", "sinceMillis", "untilMillis", "afterId")) {
            if (request.has(key)) require(request.getLong(key) >= 0L) { "$key must be nonnegative" }
        }
        if (request.has("userId")) require(request.getLong("userId") <= Int.MAX_VALUE) { "Invalid userId" }
        if (request.has("createTime")) require(request.getLong("createTime") > 0L) { "createTime must be positive" }
        if (request.has("limit")) require(request.getLong("limit") in 1..10000) { "limit must be between 1 and 10000" }
        if (request.has("pid")) require(request.getLong("pid") in 1..Int.MAX_VALUE.toLong()) { "Invalid pid" }
        if (safetyContract != null) {
            if (mutation) com.nexbox.core.utils.MutationReceipt.requireRequestId(request.getString("requestId"))
            if (request.has("expectedComponents")) {
                val components = request.getJSONArray("expectedComponents")
                require(components.length() <= 1024) { "Too many components" }
                val identities = hashSetOf<String>()
                for (index in 0 until components.length()) {
                    val entry = components.opt(index) as? JSONObject
                    require(entry != null && entry.keys().asSequence().toSet() == setOf("packageName", "installationVersion")) {
                        "Components require packageName and installationVersion only"
                    }
                    val name = DebugCommandJson.requiredPackage(entry)
                    val version = entry.opt("installationVersion")
                    require(identities.add(name) && version is String && version.length in 1..128) { "Invalid component identity or version" }
                }
            }
            if (safetyContract.startsWith("favorite-") && request.has("name")) {
                require(request.getString("name").trim().length in 1..60) { "Invalid favorite name" }
            }
            for (key in listOf("expectedInventoryToken", "expectedVersion", "expectedOwnerToken", "favoriteId")) {
                if (request.has(key)) require(request.getString(key).length in 1..128) { "Invalid $key" }
            }
            if (request.has("expectedSha256")) require(request.getString("expectedSha256").matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid expectedSha256" }
            if (request.has("mediaId")) require(request.getLong("mediaId") > 0) { "Invalid mediaId" }
            for (key in listOf("confirm", "confirmExternalWrite")) {
                if (request.has(key)) require(request.getBoolean(key)) { "$key=true is required" }
            }
        }
    }

    fun json(): JSONObject = JSONObject()
        .put("parameters", jsonArray(parameters)).put("required", jsonArray(required))
        .put("atLeastOne", jsonArray(anyOf)).put("requiresServices", requiresServices)
        .put("mutatesState", mutation).put("description", description)
        .put("aliasFor", aliasFor ?: JSONObject.NULL)
        .apply {
            if (safetyContract != null) {
                put("safetyContract", safetyContract).put("safetyVersion", 1)
                put("safetyConstraints", JSONObject().apply {
                    put("requestIdIsIdempotencyKey", false)
                    if (safetyContract.startsWith("media-")) {
                        put("requiresNoGuestProcesses", true)
                        put("maxBytes", 65536)
                        put("mimeTypes", jsonArray(listOf("image/png")))
                        put("versionLifetime", "core-process")
                        if (safetyContract != "media-import-verified-bytes") {
                            put("requiresExclusiveKernelLease", true)
                            put("requiresVerifiedImportReceipt", true)
                            put("writableDescriptorExposureRevokesVersion", true)
                        } else put("maxDimension", 1024)
                    }
                    if (safetyContract.startsWith("favorite-")) put("versionLifetime", "host-process")
                    if (safetyContract == "raw-package-inventory") {
                        put("snapshotLifetime", "core-process")
                        put("expiresAfterMillis", 600000)
                        put("maxPackages", 1024)
                    }
                    if (safetyContract.startsWith("conditional-gms-")) put("automaticRollback", false)
                })
            }
        }

    companion object {
        const val USER = "userId createTime"
        const val APP = "$USER packageName"
        const val PROFILE = "manufacturer brand model device product fingerprint serial androidId imei macAddress oaid catalogId buildMode simProfile"
        const val SIM = "version enabled cards voice data sms"
        const val LOG_FILTERS = "minLevel level query tag process thread session context userId createTime packageName sinceMillis untilMillis pid requestId"
        const val LOG_SELECTION = "$LOG_FILTERS limit newestFirst"
        private val BOOLEAN_PARAMETERS = words("enabled confirm fromHost showSystem showSystemApps showPackageName reverse launch newUser hideRoot autoFreeze instantFreezeButton freezeExcluded recordingEnabled adbEnabled detailedEnabled debugEnabled cliEnabled newestFirst stopAtLimit diagnostics compatibleOnly liquidGlassEnabled predictiveBackEnabled autoCheckEnabled onlyIfEmpty requireTraceFree confirmExternalWrite")
        private val INTEGER_PARAMETERS = words("userId createTime mediaId limit offset sinceMillis untilMillis afterId pid count slot version voice data sms expectedLength")
        private val LONG_PARAMETERS = words("createTime mediaId sinceMillis untilMillis afterId expectedLength")
        private val NUMBER_PARAMETERS = words("latitude longitude angle distance")
        fun words(value: String): Set<String> = value.split(' ').filter { it.isNotBlank() }.toSet()
    }
}

internal data class DebugCommandError(val code: String, val message: String)

/** A successful Binder call is not proof that the requested business action succeeded. */
internal fun debugResultError(result: JSONObject): DebugCommandError? = when {
    result.has("errorCode") -> DebugCommandError(result.getString("errorCode"), "Conditional operation failed; see data.stage, mutationState and effects")
    result.optBoolean("nameSaved") && !result.optBoolean("taskRefreshConfirmed") ->
        DebugCommandError("user_renamed_refresh_pending", "Name saved, but task refresh is not fully confirmed; see data.taskRefresh")
    result.optInt("failureCount", 0) > 0 -> DebugCommandError("partial_failure", "Some items failed; see data.failures")
    result.has("success") && !result.getBoolean("success") -> DebugCommandError("operation_not_confirmed", "Operation was not confirmed; see data")
    result.has("requestAccepted") && !result.getBoolean("requestAccepted") -> DebugCommandError("request_rejected", "The launch request was not accepted")
    result.optBoolean("launchRequested") && !result.optBoolean("launchRequestAccepted") ->
        DebugCommandError("launch_rejected_after_install", "Installation succeeded, but the launch request was not accepted")
    else -> null
}
