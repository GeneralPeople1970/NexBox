package com.nexbox.app.view.apps

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.nexbox.app.data.AppIconKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only a composed row owns its image, and a new resource identity starts empty. */
@Composable
internal fun rememberAppBitmap(
    key: AppIconKey?,
    fallback: Drawable?,
    enabled: Boolean,
    loadIcon: (suspend () -> Bitmap?)?
): Bitmap? {
    var bitmap by remember(key, fallback) { mutableStateOf<Bitmap?>(null) }
    val latestLoader by rememberUpdatedState(loadIcon)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(key, fallback, enabled, lifecycle) {
        if (!enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // A background trim may invalidate an unfinished load. Retry empty visible rows
            // on return, without polling or decoding while the screen is stopped.
            if (bitmap == null) {
                bitmap = if (fallback != null) withContext(Dispatchers.IO) {
                    runCatching { fallback.toBitmap(width = 96, height = 96) }.getOrNull()
                } else latestLoader?.invoke()
            }
        }
    }
    return bitmap
}
