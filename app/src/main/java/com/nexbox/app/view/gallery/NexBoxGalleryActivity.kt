package com.nexbox.app.view.gallery

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nexbox.app.ui.theme.NexBoxAppearanceHost

/** A host window for share/import flows; it shares the main navigation's gallery. */
class NexBoxGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NexBoxAppearanceHost { GalleryScreen(onBack = { finish() }) }
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, NexBoxGalleryActivity::class.java))
        }
    }
}
