package com.nexbox.app.util

import android.content.Context
import android.content.Intent
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import com.nexbox.app.R
import com.nexbox.app.app.App
import com.nexbox.app.app.AppManager
import com.nexbox.app.bean.AppInfo
import com.nexbox.app.util.ContextUtil.openAppSystemSettings
import com.nexbox.app.view.main.ShortcutActivity

object ShortcutUtil {

    fun createShortcut(context: Context, info: AppInfo, userID: Int) {
        NexLog.i("Shortcut", "action=prepare package=${info.packageName} userId=$userID")
        if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            val labelName = if (userID > 0) "${info.name} ($userID)" else info.name
            val intent = Intent(context, ShortcutActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .putExtra("pkg", info.packageName)
                .putExtra("userId", userID)

            val input = EditText(context).apply {
                setText(labelName)
            }

            AlertDialog.Builder(context)
                .setTitle(R.string.app_shortcut)
                .setView(input)
                .setPositiveButton(R.string.done) { _, _ ->
                    val shortcutName = input.text.toString().ifBlank { labelName }
                    val iconBitmap = try {
                        info.icon?.toBitmap()
                    } catch (e: Exception) {
                        NexLog.w("Shortcut", "action=loadIcon package=${info.packageName} userId=$userID", e)
                        null
                    }

                    val builder = ShortcutInfoCompat.Builder(context, info.packageName + userID)
                        .setIntent(intent)
                        .setShortLabel(shortcutName)
                        .setLongLabel(shortcutName)

                    if (iconBitmap != null) {
                        builder.setIcon(IconCompat.createWithBitmap(iconBitmap))
                    }

                    NexLog.trace("Shortcut", "requestPin", "package=${info.packageName} userId=$userID") { operation ->
                        val accepted = ShortcutManagerCompat.requestPinShortcut(context, builder.build(), null)
                        operation.finish(accepted, "launcherRequestAccepted=$accepted userConfirmationNotObserved=true")
                    }
                    showAllowPermissionDialog(context)
                }
                .setNegativeButton(R.string.cancel) { _, _ -> NexLog.i("Shortcut", "action=cancel package=${info.packageName} userId=$userID") }
                .show()
        } else {
            NexLog.w("Shortcut", "action=requestPin unsupported=true package=${info.packageName} userId=$userID")
            toast(R.string.cannot_create_shortcut)
        }
    }

    private fun showAllowPermissionDialog(context: Context) {
        if (!AppManager.mNexBoxLoader.showShortcutPermissionDialog()) {
            return
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.try_add_shortcut)
            .setMessage(R.string.add_shortcut_fail_msg)
            .setPositiveButton(R.string.done, null)
            .setNegativeButton(R.string.permission_setting) { _, _ ->
                App.getContext().openAppSystemSettings()
            }
            .setNeutralButton(R.string.no_reminders) { _, _ ->
                AppManager.mNexBoxLoader.invalidShortcutPermissionDialog(false)
            }
            .show()
    }
}
