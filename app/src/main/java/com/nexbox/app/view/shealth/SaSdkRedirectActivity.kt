package com.nexbox.app.view.shealth

import com.nexbox.app.util.NexLog

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.nexbox.core.NexBoxCore
import com.nexbox.core.core.env.SamsungHealthCompat

class SaSdkRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            if (!isSamsungHealthRedirect(intent)) {
                NexLog.w("SaSdkRedirect", "action=forward stage=rejected reason=invalid_redirect")
                return
            }
            val uri = requireNotNull(intent.data)
            val target = SamsungHealthCompat.getLastSaSdkRedirectTarget()
            val forward = Intent(Intent.ACTION_VIEW, uri)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage("com.sec.android.app.shealth")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            NexLog.d("SaSdkRedirect", "action=forward stage=dispatch userId=${target.id} generation=${target.createTime}")
            NexBoxCore.get().startActivity(forward, target.id, target.createTime)
        } catch (failure: Exception) {
            // Redirect payloads can contain authentication data, including in exception messages.
            NexLog.w("SaSdkRedirect", "action=forward stage=failed error=${failure.javaClass.simpleName}")
        } finally {
            finish()
        }
    }
}

internal fun isSamsungHealthRedirect(intent: Intent?): Boolean {
    val uri = intent?.data ?: return false
    return intent.action == Intent.ACTION_VIEW && uri.scheme == "sasdk" &&
        uri.authority == "samsunghealthhybridweb"
}
