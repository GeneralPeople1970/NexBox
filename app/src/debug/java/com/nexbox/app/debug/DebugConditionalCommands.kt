package com.nexbox.app.debug

import android.os.Bundle
import com.nexbox.app.data.GmsRepository
import com.nexbox.app.data.UsersRepository
import com.nexbox.core.NexBoxCore
import com.nexbox.core.utils.compat.BundleCompat
import org.json.JSONArray
import org.json.JSONObject

internal class DebugConditionalCommands {
    private val gms = GmsRepository()

    fun rawInventory(payload: JSONObject): JSONObject = json(checkNotNull(
        NexBoxCore.getBPackageManager().getRawPackageInventory(payload.getInt("userId"), payload.getLong("createTime"))
    ) { "Raw inventory unavailable" })

    fun deleteUser(payload: JSONObject): JSONObject {
        require(payload.getBoolean("confirm")) { "confirm=true is required" }
        return json(UsersRepository.deleteIfInventory(payload.getInt("userId"), payload.getLong("createTime"),
            payload.getString("expectedInventoryToken"), payload.optBoolean("onlyIfEmpty"), payload.getString("requestId")))
    }

    fun installGms(payload: JSONObject): JSONObject = json(gms.installIfInventory(
        payload.getInt("userId"), payload.getLong("createTime"), payload.getString("expectedInventoryToken"),
        payload.getBoolean("requireTraceFree"), payload.getString("requestId")))

    fun removeGms(payload: JSONObject, reset: Boolean): JSONObject {
        require(payload.getBoolean("confirm")) { "confirm=true is required" }
        val values = payload.getJSONArray("expectedComponents")
        require(values.length() <= 1024) { "Too many components" }
        val components = List(values.length()) { index ->
            val item = values.getJSONObject(index)
            require(item.keys().asSequence().toSet() == setOf("packageName", "installationVersion")) { "Invalid component fields" }
            val name = DebugCommandJson.requiredPackage(item)
            val version = item.get("installationVersion")
            require(version is String && version.length in 1..128) { "Invalid installationVersion" }
            Bundle().apply { putString("packageName", name); putString("installationVersion", version) }
        }
        return json(gms.removeIfInventory(payload.getInt("userId"), payload.getLong("createTime"),
            payload.getString("expectedInventoryToken"), components, payload.getString("requestId"), reset))
    }

    companion object {
        fun json(bundle: Bundle): JSONObject = JSONObject().apply {
            bundle.keySet().forEach { key -> put(key, value(BundleCompat.getValue(bundle, key))) }
        }

        private fun value(item: Any?): Any = when (item) {
            null -> JSONObject.NULL
            is Bundle -> json(item)
            is List<*> -> JSONArray().apply { item.forEach { put(value(it)) } }
            is String, is Boolean, is Number -> item
            else -> error("Unsupported contract value")
        }
    }
}
