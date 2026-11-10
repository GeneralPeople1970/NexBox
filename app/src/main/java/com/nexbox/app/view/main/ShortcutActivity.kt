package com.nexbox.app.view.main

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nexbox.app.R
import com.nexbox.app.data.CloneAppActions
import com.nexbox.app.data.CloneTarget
import com.nexbox.app.data.UserIdentityResolver
import com.nexbox.app.util.NexLog
import com.nexbox.app.util.requireNexBoxServices
import com.nexbox.app.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShortcutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            try {
                val accepted = withContext(Dispatchers.IO) {
                    val packageName = requireNotNull(intent.getStringExtra("pkg"))
                    require(packageName.length in 1..255 && PACKAGE_NAME.matches(packageName)) {
                        "A valid shortcut package is required"
                    }
                    val userId = intent.getIntExtra("userId", 0)
                    require(userId >= 0) { "A valid shortcut user is required" }
                    requireNexBoxServices()
                    val hasGeneration = intent.hasExtra("createTime")
                    // Legacy pinned shortcuts lack their creation generation. Keep their existing
                    // current-user behavior, but bind this dispatch to one captured lifetime.
                    val target = if (hasGeneration) CloneTarget(userId, intent.getLongExtra("createTime", -1L))
                        else UserIdentityResolver.captureTarget(userId)
                    require(target.createTime > 0L) { "The shortcut user is unavailable" }
                    NexLog.i("Shortcut", "action=launch package=$packageName userId=$userId" +
                        " generation=${target.createTime} generationSource=${if (hasGeneration) "shortcut" else "current_user"}")
                    currentCoroutineContext().ensureActive()
                    CloneAppActions.start(packageName, target)
                }
                if (!accepted) toast(R.string.start_fail)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                NexLog.w("Shortcut", "action=launch stage=failed error=${failure.javaClass.simpleName}")
                toast(R.string.start_fail)
            } finally {
                finish()
            }
        }
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")
    }
}
