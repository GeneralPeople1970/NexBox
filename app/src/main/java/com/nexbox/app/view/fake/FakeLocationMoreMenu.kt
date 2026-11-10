package com.nexbox.app.view.fake

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import com.nexbox.app.ui.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nexbox.app.R
import com.nexbox.app.ui.components.Icon
import com.nexbox.app.ui.components.IconButton
import com.nexbox.app.ui.components.NexBoxMenuEntry
import com.nexbox.app.ui.components.NexBoxMenuPopup
import com.nexbox.app.ui.theme.NexBoxSpacing

/** The map's two optional controls share one overflow anchor in either UI style. */
@Composable
internal fun FakeLocationMoreMenu(enabled: Boolean, onMapSource: () -> Unit, onFavorites: () -> Unit) {
    val expanded = remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded.value = false }
    val menu: @Composable (() -> Unit) -> Unit = { dismiss ->
        fun select(action: () -> Unit) {
            if (!enabled || !expanded.value) return
            expanded.value = false
            dismiss()
            action()
        }
        NexBoxMenuEntry(stringResource(R.string.map_tile_source), index = 0, count = 2,
            icon = Icons.Default.Map,
            enabled = enabled, onClick = { select(onMapSource) })
        NexBoxMenuEntry(stringResource(R.string.location_favorites), index = 1, count = 2,
            icon = Icons.Default.Star,
            enabled = enabled, onClick = { select(onFavorites) })
    }
    Box {
        IconButton(onClick = { expanded.value = !expanded.value }, enabled = enabled,
            modifier = Modifier.size(NexBoxSpacing.touchTarget)) {
            Icon(Icons.Default.MoreVert, stringResource(R.string.location_more))
        }
        NexBoxMenuPopup(show = expanded) { dismiss -> menu(dismiss) }
    }
}
