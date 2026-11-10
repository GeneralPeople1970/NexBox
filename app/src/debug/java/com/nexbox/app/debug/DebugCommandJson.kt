package com.nexbox.app.debug

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object DebugCommandJson {
    fun hasJsonCause(failure: Throwable?): Boolean =
        generateSequence(failure) { it.cause }.take(12).any { it is JSONException }

    // JSONException messages may embed the complete request, not just the invalid field.
    fun loggableFailure(failure: Throwable): Throwable = if (hasJsonCause(failure))
        IllegalArgumentException("Malformed JSON or a field has the wrong type") else failure

    fun optionalPackage(payload: JSONObject): String? =
        if (payload.isNull("packageName")) null else packageValue(payload, "packageName")

    fun requiredPackage(payload: JSONObject): String = packageValue(payload, "packageName")

    fun packageValue(payload: JSONObject, key: String): String =
        (payload.opt(key) as? String)?.trim().orEmpty().also {
            require(it.length in 1..255 && PACKAGE.matches(it)) { "$key must be a valid package name" }
        }

    fun scopeAppJson(packageName: String, label: String, versionName: String?): JSONObject = JSONObject()
        .put("packageName", packageName).put("name", label).put("versionName", versionName ?: JSONObject.NULL)

    fun stringSet(payload: JSONObject, key: String): Set<String> {
        require(payload.has(key) && !payload.isNull(key)) { "$key is required; use [] for an empty selection" }
        val value = payload.get(key)
        val values = when (value) {
            is JSONArray -> List(value.length()) { index ->
                require(value.get(index) is String) { "$key must contain package names" }
                value.getString(index).trim()
            }
            is String -> value.split(',').map(String::trim)
            else -> throw IllegalArgumentException("$key must be a package list")
        }
        require(values.size <= 5000 && values.all { it.length in 1..255 && PACKAGE.matches(it) }) {
            "$key must contain valid package names; use [] for an empty selection"
        }
        return values.toSet()
    }

    fun stringSetJson(values: Set<String>?): Any = values?.let(::jsonArray) ?: JSONObject.NULL

    fun jsonArray(values: Iterable<*>): JSONArray = JSONArray().apply { values.forEach { put(it) } }

    private val PACKAGE = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")
}
