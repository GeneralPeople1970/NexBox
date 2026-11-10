package com.nexbox.app.view.main

import com.nexbox.app.util.NexLog

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nexbox.app.data.UpdateRepository
import com.nexbox.core.NexBoxCore
import com.nexbox.app.ui.NexBoxApp
import com.nexbox.app.ui.theme.NexBoxAppearanceHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"

        fun start(context: Context) {
            val intent = Intent(context, MainActivity::class.java)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        try {
            NexBoxCore.get().onBeforeMainActivityOnCreate(this)
        } catch (e: Exception) {
            NexLog.e(TAG, "Error in onBeforeMainActivityOnCreate: ${e.message}", e)
        }

        setContent {
            NexBoxAppearanceHost {
                NexBoxApp()
            }
        }

        try {
            NexBoxCore.get().onAfterMainActivityOnCreate(this)
        } catch (e: Exception) {
            NexLog.e(TAG, "Error in onAfterMainActivityOnCreate: ${e.message}", e)
        }

        lifecycleScope.launch {
            val updates = withContext(Dispatchers.IO) { UpdateRepository.get(applicationContext) }
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                updates.state.map { it.autoCheckEnabled }.distinctUntilChanged().collectLatest { enabled ->
                    if (enabled) updates.checkAutomatically()
                }
            }
        }
    }
}
