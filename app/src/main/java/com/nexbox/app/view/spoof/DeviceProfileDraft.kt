package com.nexbox.app.view.spoof

import androidx.compose.runtime.saveable.listSaver
import com.nexbox.app.R
import com.nexbox.core.fake.device.DeviceIdentifierGenerator
import com.nexbox.app.data.DeviceRepository
import com.nexbox.core.fake.device.DeviceSpoofProfile
import com.nexbox.core.fake.device.SimProfile

/** Keep raw edits: the profile constructor must not repair half-typed identifiers. */
internal data class DeviceProfileDraft(
    val base: DeviceSpoofProfile,
    val manufacturer: String = base.manufacturer,
    val model: String = base.model,
    val androidId: String = base.androidId,
    val serial: String = base.serial,
    val imei: String = base.imei,
    val macAddress: String = base.macAddress,
    val oaid: String = base.oaid,
    val inheritGlobal: Boolean = false
) {
    fun validationError(): Int? = when {
        manufacturer.isBlank() || model.isBlank() -> R.string.device_spoofing_fill_required
        !DeviceIdentifierGenerator.isValidAndroidId(androidId.trim()) -> R.string.device_spoofing_invalid_android_id
        !DeviceIdentifierGenerator.isValidSerial(serial.trim()) -> R.string.device_invalid_serial
        !DeviceIdentifierGenerator.isValidImei(imei.trim()) -> R.string.device_invalid_imei
        !DeviceIdentifierGenerator.isValidMac(macAddress.trim()) -> R.string.device_invalid_mac
        !DeviceIdentifierGenerator.isValidOaid(oaid.trim()) -> R.string.device_invalid_oaid
        base.simProfile.validationError() != null -> R.string.sim_profile_invalid
        else -> null
    }

    fun toProfile(userId: Int): DeviceSpoofProfile {
        val build = DeviceRepository.editProfile(userId, base, manufacturer, model, androidId.trim())
        return DeviceSpoofProfile(build.manufacturer, build.brand, build.model, build.device,
            build.product, build.fingerprint, serial.trim(), androidId.trim(), imei.trim(),
            macAddress.trim(), oaid.trim(), build.catalogId, build.buildMode, base.simProfile.withFormattedPhoneNumbers())
    }

    fun withSim(sim: SimProfile) = copy(base = base.withSimProfile(sim))

    fun sameValues(other: DeviceProfileDraft?): Boolean = other != null && values() == other.values()

    fun values(): List<String> = listOf(base.manufacturer, base.brand, base.model, base.device,
        base.product, base.fingerprint, base.serial, base.androidId, base.imei, base.macAddress,
        base.oaid, base.catalogId, base.buildMode, base.simProfile.toJson(),
        manufacturer, model, androidId, serial, imei, macAddress, oaid,
        "inheritGlobal=$inheritGlobal")

    companion object {
        fun restore(values: List<String>): DeviceProfileDraft {
            val profile = DeviceSpoofProfile(values[0], values[1], values[2], values[3], values[4],
                values[5], values[6], values[7], values[8], values[9], values[10], values[11],
                values[12], SimProfile.fromDraftJson(values[13]))
            // The former eSIM field is ignored when restoring an older editor draft.
            val hasInheritance = values.lastOrNull()?.startsWith("inheritGlobal=") == true
            val legacySize = values.size - if (hasInheritance) 1 else 0
            val fields = if (legacySize == 22) 15 else 14
            return DeviceProfileDraft(profile, values[fields], values[fields + 1],
                values[fields + 2], values[fields + 3], values[fields + 4], values[fields + 5], values[fields + 6],
                inheritGlobal = hasInheritance && values.last() == "inheritGlobal=true")
        }
    }
}

internal val DeviceProfileDraftSaver = listSaver<DeviceProfileDraft?, String>(
    save = { it?.values().orEmpty() },
    restore = {
        if (it.size !in 21..23) null else try {
            DeviceProfileDraft.restore(it)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
)
