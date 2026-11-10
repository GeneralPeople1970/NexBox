package com.nexbox.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.nexbox.app.R

/**
 * The home tab uses the shared monochrome brand geometry, without launcher safe-zone padding.
 *
 * The lynx silhouette spans (29, 28) to (79, 81). This centered crop gives the
 * 24 dp icon an 18.87 x 20 dp silhouette, preserving its even-odd eye cutouts. Keep the
 * source in branding/icon.svg; NexBoxHomeIconTest guards the geometry used to calibrate this crop.
 */
@Composable
internal fun rememberNexBoxHomeIcon(): ImageVector {
    val source = ImageVector.vectorResource(R.drawable.ic_launcher_monochrome)
    return remember(source) {
        ImageVector.Builder(
            name = "NexBox.Home",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = HOME_ICON_VIEWPORT,
            viewportHeight = HOME_ICON_VIEWPORT
        ).apply {
            addGroup(
                name = "NavigationOpticalCrop",
                translationX = -HOME_ICON_ORIGIN_X,
                translationY = -HOME_ICON_ORIGIN_Y
            )
            source.root.forEach { path ->
                require(path is VectorPath) { "The shared NexBox brand must contain ungrouped paths." }
                addPath(
                    pathData = path.pathData,
                    pathFillType = path.pathFillType,
                    name = path.name,
                    fill = path.fill,
                    fillAlpha = path.fillAlpha,
                    stroke = path.stroke,
                    strokeAlpha = path.strokeAlpha,
                    strokeLineWidth = path.strokeLineWidth,
                    strokeLineCap = path.strokeLineCap,
                    strokeLineJoin = path.strokeLineJoin,
                    strokeLineMiter = path.strokeLineMiter,
                    trimPathStart = path.trimPathStart,
                    trimPathEnd = path.trimPathEnd,
                    trimPathOffset = path.trimPathOffset
                )
            }
            clearGroup()
        }.build()
    }
}

private const val HOME_ICON_VIEWPORT = 63.6f
private const val HOME_ICON_ORIGIN_X = 22.2f
private const val HOME_ICON_ORIGIN_Y = 22.7f
