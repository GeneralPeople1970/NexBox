package com.nexbox.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object ContextUtil {

    fun Context.openAppSystemSettings() = NexLog.trace("Permissions", "openAppSettings") {
        startActivity(Intent().apply {
            action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data = Uri.fromParts("package", packageName, null)
        })
    }
}
