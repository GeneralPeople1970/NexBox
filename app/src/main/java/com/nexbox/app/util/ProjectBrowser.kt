package com.nexbox.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.nexbox.app.R

object ProjectBrowser {
    fun open(context: Context, url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host != "github.com") {
            NexLog.w("Updates", "action=openBrowser result=invalid_url")
            Toast.makeText(context, R.string.update_browser_failed, Toast.LENGTH_SHORT).show()
            return
        }
        // Select browser activities so verified GitHub app links do not take over this action.
        val selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            setSelector(selector)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            NexLog.i("Updates", "action=openBrowser result=dispatched path=${uri.path}")
        } catch (failure: RuntimeException) {
            NexLog.w("Updates", "action=openBrowser result=failed reason=${failure.javaClass.simpleName}")
            Toast.makeText(context, R.string.update_browser_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
