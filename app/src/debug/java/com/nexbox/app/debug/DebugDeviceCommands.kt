package com.nexbox.app.debug

import android.content.Context
import com.nexbox.app.data.DeviceRepository
import com.nexbox.app.data.ScopeAppRepository
import com.nexbox.app.data.UserIdentityResolver
import com.nexbox.app.debug.DebugCommandJson.jsonArray
import com.nexbox.app.debug.DebugCommandJson.scopeAppJson
import com.nexbox.app.debug.DebugCommandJson.optionalPackage
import com.nexbox.app.debug.DebugCommandJson.stringSet
import com.nexbox.app.debug.DebugCommandJson.stringSetJson
import com.nexbox.app.debug.DebugCommandTargets.target
import com.nexbox.core.fake.device.DeviceSpoofProfile
import com.nexbox.core.fake.device.DeviceCatalog
import com.nexbox.core.fake.device.DeviceIdentifierGenerator
import com.nexbox.core.fake.device.SimCarrier
import com.nexbox.core.fake.device.SimProfile
import org.json.JSONArray
import org.json.JSONObject

internal class DebugDeviceCommands(private val context: Context) {
    fun deviceGet(payload: JSONObject): JSONObject {
        val target = target(payload, destructive = false)
        val packageName = optionalPackage(payload)
        DeviceRepository.requireEditableTarget(target, packageName)
        val profile = if (packageName == null) DeviceRepository.getProfile(target.userId, target.createTime)
        else DeviceRepository.getAppProfile(packageName, target.userId, target.createTime)
        val scope = DeviceRepository.getScopePackages(target.userId, target.createTime)
        val independent = packageName?.let { DeviceRepository.hasAppProfile(it, target.userId, target.createTime) }
        UserIdentityResolver.validateTarget(target)
        return profileJson(profile).put("userId", target.userId).put("createTime", target.createTime)
            .put("packageName", packageName ?: JSONObject.NULL)
            .put("scopePackages", stringSetJson(scope)).put("profile", profileJson(profile))
            .put("independent", independent ?: JSONObject.NULL)
            .put("selected", packageName?.let { scope == null || it in scope } ?: JSONObject.NULL)
    }

    fun deviceSave(payload: JSONObject): JSONObject {
        val target = target(payload, destructive = false)
        val packageName = optionalPackage(payload)
        val profileKeys = DebugCommandContract.words(DebugCommandContract.PROFILE)
        val profileObject = if (payload.has("profile")) payload.getJSONObject("profile") else JSONObject().apply {
            profileKeys.filter(payload::has).forEach { put(it, payload.get(it)) }
        }
        if (payload.has("profile")) {
            require(profileKeys.none(payload::has)) { "Do not mix profile with top-level device fields" }
        }
        require(profileObject.length() > 0) { "profile must contain a device field" }
        validateProfileFields(profileObject)
        val base = if (packageName == null) DeviceRepository.getProfile(target.userId, target.createTime)
        else DeviceRepository.getAppProfile(packageName, target.userId, target.createTime)
        val profile = profileFromJson(base, profileObject, target.userId)
        if (packageName == null) DeviceRepository.saveUserProfile(target.userId, target.createTime, profile)
        else DeviceRepository.saveAppProfile(packageName, target.userId, target.createTime, profile)
        return deviceGet(JSONObject().put("userId", target.userId).put("createTime", target.createTime)
            .put("packageName", packageName ?: JSONObject.NULL))
    }

    fun deviceRandomize(payload: JSONObject): JSONObject {
        val target = target(payload, destructive = false)
        val packageName = optionalPackage(payload)
        val part = payload.optString("part", "all")
        val profile = if (packageName != null && part == "all") {
            DeviceRepository.randomizeAppProfile(packageName, target.userId, target.createTime)
        } else {
            val base = if (packageName == null) DeviceRepository.getProfile(target.userId, target.createTime)
            else DeviceRepository.getAppProfile(packageName, target.userId, target.createTime)
            val next = when (part) {
                "ids" -> DeviceRepository.randomizeIdentifiers(base)
                "model" -> DeviceRepository.randomizeModel(base)
                "sim" -> {
                    require(base.simProfile.enabled && base.simProfile.cards.isNotEmpty()) {
                        "Enable at least one virtual SIM before randomizing SIM fields"
                    }
                    base.withSimProfile(base.simProfile.randomizedCards())
                }
                "all" -> DeviceRepository.randomizeAll(base)
                else -> throw IllegalArgumentException("part must be all, ids, model, or sim")
            }
            if (packageName == null) DeviceRepository.saveUserProfile(target.userId, target.createTime, next)
            else DeviceRepository.saveAppProfile(packageName, target.userId, target.createTime, next)
            next
        }
        return profileJson(profile).put("userId", target.userId).put("createTime", target.createTime)
            .put("packageName", packageName ?: JSONObject.NULL).put("part", part)
    }

    fun deviceReset(payload: JSONObject): JSONObject {
        val target = target(payload, destructive = false)
        val packageName = optionalPackage(payload)
        if (packageName == null) DeviceRepository.resetToDefaults(target.userId, target.createTime)
        else DeviceRepository.resetAppProfile(packageName, target.userId, target.createTime)
        return deviceGet(JSONObject().put("userId", target.userId).put("createTime", target.createTime)
            .put("packageName", packageName ?: JSONObject.NULL))
    }

    fun deviceScopeGet(payload: JSONObject): JSONObject {
        val target = target(payload, destructive = false)
        val snapshot = DeviceRepository.loadScope(target.userId, target.createTime, ScopeAppRepository(context))
        val saved = DeviceRepository.getScopePackages(target.userId, target.createTime)
        UserIdentityResolver.validateTarget(target)
        return JSONObject().put("userId", target.userId).put("createTime", target.createTime)
            .put("packages", stringSetJson(saved)).put("selectionMode", if (saved == null) "all" else "explicit")
            .put("selectedPackages", jsonArray(snapshot.selected)).put("independentPackages", jsonArray(snapshot.independent))
            .put("apps", JSONArray().apply { snapshot.apps.forEach { put(scopeAppJson(it.packageName, it.label, it.versionName)) } })
    }

    fun deviceScopeSet(payload: JSONObject): JSONObject {
        val target = target(payload, destructive = false)
        val requested = stringSet(payload, "packages")
        val available = DeviceRepository.loadScope(target.userId, target.createTime, ScopeAppRepository(context)).apps.map { it.packageName }.toSet()
        require(available.containsAll(requested)) { "Device scope contains an unavailable app or Xposed module" }
        DeviceRepository.saveScopePackages(target.userId, target.createTime, requested)
        return deviceScopeGet(JSONObject().put("userId", target.userId).put("createTime", target.createTime))
    }

    fun deviceEsimGet(): JSONObject = JSONObject().put("enabled", DeviceRepository.initializeGlobalEsimSpoofSetting())

    fun deviceEsimSet(payload: JSONObject): JSONObject {
        require(payload.has("enabled")) { "enabled is required" }
        DeviceRepository.setGlobalEsimSpoofEnabled(payload.getBoolean("enabled"))
        return deviceEsimGet()
    }

    fun catalogList(payload: JSONObject): JSONObject {
        val matches = DeviceCatalog.get(context).search(payload.optString("query"), payload.optBoolean("compatibleOnly", true))
        val offset = payload.optInt("offset", 0)
        val limit = payload.optInt("limit", 100)
        require(offset >= 0 && limit in 1..500) { "offset must be nonnegative and limit between 1 and 500" }
        val selected = matches.drop(offset).take(limit)
        return JSONObject().put("totalCount", matches.size).put("returnedCount", selected.size)
            .put("offset", offset).put("nextOffset", if (offset.toLong() + selected.size < matches.size) offset + selected.size else JSONObject.NULL)
            .put("entries", JSONArray().apply { selected.forEach { entry ->
                put(JSONObject().put("catalogId", entry.id).put("manufacturer", entry.manufacturer)
                    .put("brand", entry.brand).put("model", entry.modelName).put("device", entry.device)
                    .put("formFactor", entry.formFactor).put("processor", entry.processorName).put("ram", entry.ram)
                    .put("gpu", entry.gpu).put("abis", jsonArray(entry.abis)).put("sdkVersions", jsonArray(entry.sdkVersions))
                    .put("hostAbiCompatible", if (entry.abis.isEmpty()) JSONObject.NULL else entry.matchesHostAbi())
                    .put("listsCurrentSdk", entry.listsCurrentSdk()).put("buildMode", DeviceSpoofProfile.MODE_BASIC))
            } })
    }

    fun catalogSelect(payload: JSONObject): JSONObject {
        val owner = target(payload, destructive = true)
        val packageName = optionalPackage(payload)
        val id = payload.getString("catalogId")
        val entry = requireNotNull(DeviceCatalog.get(context).entries.firstOrNull { it.id == id && it.formFactor == "Phone" }) {
            "Unknown phone catalogue entry"
        }
        val base = if (packageName == null) DeviceRepository.getProfile(owner.userId, owner.createTime)
            else DeviceRepository.getAppProfile(packageName, owner.userId, owner.createTime)
        val profile = DeviceRepository.selectCatalogEntry(base, entry)
        if (packageName == null) DeviceRepository.saveUserProfile(owner.userId, owner.createTime, profile)
        else DeviceRepository.saveAppProfile(packageName, owner.userId, owner.createTime, profile)
        return deviceGet(payload)
    }

    fun simGet(payload: JSONObject): JSONObject {
        val target = target(payload, destructive = false)
        val packageName = optionalPackage(payload)
        DeviceRepository.requireEditableTarget(target, packageName)
        val profile = if (packageName == null) DeviceRepository.getProfile(target.userId, target.createTime)
        else DeviceRepository.getAppProfile(packageName, target.userId, target.createTime)
        UserIdentityResolver.validateTarget(target)
        return JSONObject().put("userId", target.userId).put("createTime", target.createTime)
            .put("packageName", packageName ?: JSONObject.NULL)
            .put("simProfile", JSONObject(profile.simProfile.toJson()))
            .put("roamingSupported", false)
    }

    fun simSet(payload: JSONObject): JSONObject {
        val target = target(payload, destructive = false)
        val packageName = optionalPackage(payload)
        val profileObject = when {
            payload.has("simProfile") -> payload.getJSONObject("simProfile")
            payload.has("profile") -> payload.getJSONObject("profile")
            else -> payload
        }
        require(!(payload.has("simProfile") && payload.has("profile"))) { "Supply simProfile or profile, not both" }
        if (payload.has("simProfile") || payload.has("profile")) {
            require(DebugCommandContract.words(DebugCommandContract.SIM).none(payload::has)) { "Do not mix a SIM object with top-level SIM fields" }
        }
        validateSimFields(profileObject, allowTarget = profileObject === payload)
        val base = if (packageName == null) DeviceRepository.getProfile(target.userId, target.createTime)
        else DeviceRepository.getAppProfile(packageName, target.userId, target.createTime)
        val next = base.withSimProfile(SimProfile.fromDraftJson(profileObject.toString()))
        if (packageName == null) DeviceRepository.saveUserProfile(target.userId, target.createTime, next)
        else DeviceRepository.saveAppProfile(packageName, target.userId, target.createTime, next)
        return simGet(JSONObject().put("userId", target.userId).put("createTime", target.createTime)
            .put("packageName", packageName ?: JSONObject.NULL))
    }

    fun simRandomize(payload: JSONObject): JSONObject = changeSim(payload) {
        require(it.enabled && it.cards.isNotEmpty()) { "Enable at least one virtual SIM before randomizing" }
        it.randomizedCards()
    }

    fun simSetCount(payload: JSONObject): JSONObject {
        val count = payload.getInt("count")
        require(count in 0..2) { "count must be 0, 1 or 2" }
        return changeSim(payload) { it.withCount(count) }
    }

    fun simRandomizeField(payload: JSONObject): JSONObject {
        val slot = payload.getInt("slot")
        require(slot in 0..1) { "slot must be 0 or 1" }
        val field = payload.getString("field")
        require(field in setOf("phone", "imsi", "iccid")) { "field must be phone, imsi or iccid" }
        return changeSim(payload) { sim ->
            require(sim.enabled) { "Enable virtual SIM before randomizing fields" }
            val card = requireNotNull(sim.cardForSlot(slot)) { "The selected SIM slot has no card" }
            sim.withCard(when (field) {
                "phone" -> card.withRandomPhoneNumber()
                "imsi" -> card.withRandomImsi()
                else -> card.withRandomIccid()
            })
        }
    }

    fun simCarriers(): JSONObject = JSONObject().put("carriers", JSONArray().apply {
        SimCarrier.PRESETS.forEach { carrier ->
            put(JSONObject().put("country", carrier.countryIso).put("dialCode", carrier.dialCode)
                .put("mcc", carrier.mcc).put("mnc", carrier.mnc).put("name", carrier.name))
        }
    }).put("roamingSupported", false)

    private fun changeSim(payload: JSONObject, change: (SimProfile) -> SimProfile): JSONObject {
        val owner = target(payload, destructive = true)
        val packageName = optionalPackage(payload)
        val base = if (packageName == null) DeviceRepository.getProfile(owner.userId, owner.createTime)
            else DeviceRepository.getAppProfile(packageName, owner.userId, owner.createTime)
        val next = base.withSimProfile(change(base.simProfile))
        if (packageName == null) DeviceRepository.saveUserProfile(owner.userId, owner.createTime, next)
        else DeviceRepository.saveAppProfile(packageName, owner.userId, owner.createTime, next)
        return simGet(payload)
    }

    fun simReset(payload: JSONObject): JSONObject {
        val target = target(payload, destructive = false)
        val packageName = optionalPackage(payload)
        val base = if (packageName == null) DeviceRepository.getProfile(target.userId, target.createTime)
        else DeviceRepository.getAppProfile(packageName, target.userId, target.createTime)
        val next = base.withSimProfile(SimProfile.disabled())
        if (packageName == null) DeviceRepository.saveUserProfile(target.userId, target.createTime, next)
        else DeviceRepository.saveAppProfile(packageName, target.userId, target.createTime, next)
        return simGet(JSONObject().put("userId", target.userId).put("createTime", target.createTime)
            .put("packageName", packageName ?: JSONObject.NULL))
    }

    fun simToggle(payload: JSONObject, enabled: Boolean): JSONObject {
        val target = target(payload, destructive = false)
        val packageName = optionalPackage(payload)
        val base = if (packageName == null) DeviceRepository.getProfile(target.userId, target.createTime)
        else DeviceRepository.getAppProfile(packageName, target.userId, target.createTime)
        val next = base.withSimProfile(base.simProfile.withEnabled(enabled))
        if (packageName == null) DeviceRepository.saveUserProfile(target.userId, target.createTime, next)
        else DeviceRepository.saveAppProfile(packageName, target.userId, target.createTime, next)
        return simGet(JSONObject().put("userId", target.userId).put("createTime", target.createTime)
            .put("packageName", packageName ?: JSONObject.NULL))
    }

    private fun profileJson(profile: DeviceSpoofProfile): JSONObject = JSONObject()
        .put("manufacturer", profile.manufacturer).put("brand", profile.brand).put("model", profile.model)
        .put("device", profile.device).put("product", profile.product).put("fingerprint", profile.fingerprint)
        .put("serial", profile.serial).put("androidId", profile.androidId).put("imei", profile.imei)
        .put("macAddress", profile.macAddress).put("oaid", profile.oaid).put("catalogId", profile.catalogId)
        .put("buildMode", profile.buildMode).put("simProfile", JSONObject(profile.simProfile.toJson()))

    private fun profileFromJson(base: DeviceSpoofProfile, json: JSONObject, userId: Int): DeviceSpoofProfile {
        val manufacturer = json.optStringOrDefault("manufacturer", base.manufacturer)
        val brand = json.optStringOrDefault("brand", base.brand)
        val model = json.optStringOrDefault("model", base.model)
        val device = json.optStringOrDefault("device", base.device)
        val product = json.optStringOrDefault("product", base.product)
        val fingerprint = json.optStringOrDefault("fingerprint", base.fingerprint)
        val androidId = json.optStringOrDefault("androidId", base.androidId)
        val build = if (listOf("brand", "device", "product", "fingerprint", "catalogId", "buildMode").any(json::has)) {
            DeviceSpoofProfile(manufacturer, brand, model, device, product, fingerprint,
                base.serial, androidId, base.imei, base.macAddress, base.oaid,
                json.optStringOrDefault("catalogId", base.catalogId),
                json.optStringOrDefault("buildMode", base.buildMode), base.simProfile)
        } else DeviceRepository.editProfile(userId, base, manufacturer, model, androidId)
        if (json.has("simProfile")) validateSimFields(json.getJSONObject("simProfile"))
        val sim = if (json.has("simProfile")) SimProfile.fromDraftJson(json.getJSONObject("simProfile").toString()) else base.simProfile
        return DeviceSpoofProfile(
            build.manufacturer, build.brand, build.model, build.device, build.product, build.fingerprint,
            json.optStringOrDefault("serial", base.serial), androidId,
            json.optStringOrDefault("imei", base.imei), json.optStringOrDefault("macAddress", base.macAddress),
            json.optStringOrDefault("oaid", base.oaid), build.catalogId, build.buildMode, sim
        )
    }

    private fun validateProfileFields(json: JSONObject) {
        DebugCommandContract(DebugCommandContract.words(DebugCommandContract.PROFILE)).validate(json)
        for (key in listOf("manufacturer", "model")) {
            if (json.has(key)) require(json.getString(key).isNotBlank()) { "$key must not be blank" }
        }
        val validators: Map<String, (String) -> Boolean> = mapOf(
            "androidId" to DeviceIdentifierGenerator::isValidAndroidId,
            "serial" to DeviceIdentifierGenerator::isValidSerial,
            "imei" to DeviceIdentifierGenerator::isValidImei,
            "macAddress" to DeviceIdentifierGenerator::isValidMac,
            "oaid" to DeviceIdentifierGenerator::isValidOaid
        )
        validators.forEach { (key, validate) ->
            if (json.has(key)) require(validate(json.getString(key))) { "Invalid device field: $key" }
        }
        if (json.has("buildMode")) require(json.getString("buildMode") in setOf(
            DeviceSpoofProfile.MODE_HOST, DeviceSpoofProfile.MODE_BASIC, DeviceSpoofProfile.MODE_LEGACY, DeviceSpoofProfile.MODE_TEMPLATE
        )) { "Invalid buildMode" }
    }

    private fun validateSimFields(json: JSONObject, allowTarget: Boolean = false) {
        val keys = DebugCommandContract.words(DebugCommandContract.SIM)
        DebugCommandContract(keys + if (allowTarget) DebugCommandContract.words(DebugCommandContract.APP) else emptySet(), keys).validate(json)
        val cards = json.getJSONArray("cards")
        require(cards.length() <= 2) { "At most two SIM cards are supported" }
        for (index in 0 until cards.length()) {
            val card = cards.getJSONObject(index)
            val cardKeys = setOf("slot", "subId", "home", "network", "roaming", "phone", "seed", "imsi", "iccid")
            require(card.keys().asSequence().all { it in cardKeys }) { "Unsupported SIM card field" }
            for (key in listOf("slot", "subId")) require(card.opt(key) is Int || card.opt(key) is Long) { "Invalid SIM card integer field" }
            for (key in listOf("slot", "subId")) require(card.getLong(key) in 0..Int.MAX_VALUE.toLong()) { "SIM card integer is out of range" }
            require(card.opt("seed") is String) { "SIM seed is required" }
            for (key in listOf("phone", "imsi", "iccid")) {
                if (card.has(key)) require(card.opt(key) is String) { "Invalid SIM card string field" }
            }
            require(!card.has("roaming") || card.opt("roaming") == false) { "SIM roaming was removed" }
            val home = card.getJSONObject("home")
            val carrierKeys = setOf("country", "dialCode", "mcc", "mnc", "name")
            require(home.keys().asSequence().toSet() == carrierKeys && carrierKeys.all { home.opt(it) is String }) {
                "Invalid SIM carrier object"
            }
            if (card.has("network")) {
                val network = card.getJSONObject("network")
                require(network.keys().asSequence().toSet() == carrierKeys && carrierKeys.all { network.opt(it) == home.opt(it) }) {
                    "Visited-network overrides were removed; network must equal home"
                }
            }
        }
    }
}

private fun JSONObject.optStringOrDefault(key: String, fallback: String): String =
    if (has(key) && !isNull(key)) optString(key) else fallback
