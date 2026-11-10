package com.nexbox.app.view.fake

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.nexbox.app.R
import com.nexbox.app.data.LocationFavorite
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.math.roundToInt

@Composable
internal fun LocationFavoriteMarkers(
    mapView: MapView,
    places: List<LocationFavorite>,
    enabled: Boolean,
    onSelect: (LocationFavorite) -> Unit
) {
    val context = LocalContext.current
    val background = MaterialTheme.colorScheme.primaryContainer.toArgb()
    val foreground = MaterialTheme.colorScheme.onPrimaryContainer.toArgb()
    val currentEnabled by rememberUpdatedState(enabled)
    val select by rememberUpdatedState(onSelect)
    val icon = remember(context, background, foreground) {
        val density = context.resources.displayMetrics.density
        val size = (36 * density).roundToInt()
        val inset = (7 * density).roundToInt()
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(background)
            setStroke(density.roundToInt().coerceAtLeast(1), foreground)
            setSize(size, size)
        }
        val star = checkNotNull(AppCompatResources.getDrawable(context, R.drawable.ic_location_favorite))
            .mutate().apply { setTint(foreground) }
        LayerDrawable(arrayOf(circle, star)).apply {
            setLayerSize(1, size - inset * 2, size - inset * 2)
            setLayerInset(1, inset, inset, inset, inset)
        }
    }
    DisposableEffect(mapView, places, icon) {
        val markers = places.map { place ->
            Marker(mapView).apply {
                position = GeoPoint(place.latitude, place.longitude)
                title = place.name
                this.icon = icon
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
                setOnMarkerClickListener { _, _ ->
                    if (currentEnabled) select(place)
                    true
                }
            }
        }
        mapView.overlays.addAll(markers)
        mapView.invalidate()
        onDispose {
            mapView.overlays.removeAll(markers.toSet())
            markers.forEach { it.onDetach(mapView) }
            mapView.invalidate()
        }
    }
}
