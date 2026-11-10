package com.nexbox.app.view.fake

import com.nexbox.app.util.NexLog

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.nexbox.app.ui.icons.filled.ChevronRight
import com.nexbox.app.ui.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Star
import com.nexbox.app.ui.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import com.nexbox.app.ui.components.Icon
import androidx.compose.material3.MaterialTheme
import com.nexbox.app.ui.components.Scaffold
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.components.TextButton
import com.nexbox.app.ui.components.ListItem
import com.nexbox.app.ui.components.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import com.nexbox.app.R
import com.nexbox.app.data.FakeLocationRepository
import com.nexbox.app.data.HostLocationRepository
import com.nexbox.app.data.HostLocationRepository.Companion.DEFAULT_LATITUDE as DEFAULT_LAT
import com.nexbox.app.data.HostLocationRepository.Companion.DEFAULT_LONGITUDE as DEFAULT_LON
import com.nexbox.app.data.MapTilePreferences
import com.nexbox.app.data.MapViewportPreferences
import com.nexbox.app.data.isValidWgs84Point
import com.nexbox.app.data.UsersRepository
import com.nexbox.app.ui.components.NexBoxCard
import com.nexbox.app.ui.components.CircularProgressIndicator
import com.nexbox.app.ui.components.IconButton
import com.nexbox.app.ui.components.NexBoxTopAppBar
import com.nexbox.app.ui.navigation.rememberPageRefreshLifecycle
import com.nexbox.app.ui.navigation.NexBoxBackGuard
import com.nexbox.app.ui.components.ConfigurationStopFailureDialog
import com.nexbox.core.utils.ConfigurationStopException
import com.nexbox.app.ui.theme.NexBoxSpacing
import java.util.Locale

private enum class LocationPointOrigin { DEFAULT, CACHED, CURRENT, APPROXIMATE, SELECTED, SAVED }

/**
 * 虚拟定位。
 *
 * osmdroid 的 `MapView` 有明确的生命周期要求：`onResume` / `onPause` 必须被转发，
 * `onDetach()` 必须被调用（否则瓦片线程和 `cache.db` 连接不会释放，反复进出该
 * 页面会产生第二个 MapView 打开同一个数据库，进而锁死不再加载瓦片）。
 * 这里用 [AndroidView] 的 `onRelease` 保证 `onDetach()` 只会被调用一次。
 *
 * 注意：`onDetach()` 之后 MapView 永久不可用，因此绝不能把它放在比本屏幕
 * 存活更久的地方（例如 ViewModel）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FakeLocationScreen(
    userId: Int = 0,
    onBack: () -> Unit = {},
    onOpenScope: () -> Unit = {}
) {
    // 玻璃采样由 NavDestination.routeAllowsGlass 在外壳层统一关闭。此处不再重复提供：
    // 导航栏是内容的兄弟节点，页面内的 provider 覆盖不到它。
    FakeLocationContent(userId = userId, onBack = onBack, onOpenScope = onOpenScope)
}

/** A separate navigation destination, sharing the map editor but never the group's saved point. */
@Composable
fun FakeLocationAppScreen(
    userId: Int,
    packageName: String,
    appLabel: String? = null,
    expectedGeneration: Long,
    onBack: () -> Unit = {}
) {
    FakeLocationContent(userId = userId, packageName = packageName, appLabel = appLabel,
        expectedGeneration = expectedGeneration, onBack = onBack)
}

@Composable
private fun FakeLocationContent(
    userId: Int,
    packageName: String? = null,
    appLabel: String? = null,
    expectedGeneration: Long? = null,
    onBack: () -> Unit,
    onOpenScope: () -> Unit = {}
) {
    val context = LocalContext.current
    val repo = remember { FakeLocationRepository() }
    val hostRepository = remember(context.applicationContext) { HostLocationRepository(context) }
    val cachedHostLocation = remember(hostRepository, userId, packageName, expectedGeneration) {
        hostRepository.cachedLocation()
    }
    val scope = rememberCoroutineScope()
    val refreshLifecycle = rememberPageRefreshLifecycle()

    // Keep a draft selection when appearance, navigation or configuration changes.
    val latState = rememberSaveable(userId, packageName, expectedGeneration) {
        mutableStateOf(cachedHostLocation?.latitude ?: DEFAULT_LAT)
    }
    val lonState = rememberSaveable(userId, packageName, expectedGeneration) {
        mutableStateOf(cachedHostLocation?.longitude ?: DEFAULT_LON)
    }
    var savedLatitude by rememberSaveable(userId, packageName, expectedGeneration) { mutableStateOf(DEFAULT_LAT) }
    var savedLongitude by rememberSaveable(userId, packageName, expectedGeneration) { mutableStateOf(DEFAULT_LON) }
    var confirmLeave by rememberSaveable(userId, packageName, expectedGeneration) { mutableStateOf(false) }
    var restoredSelection by rememberSaveable(userId, packageName, expectedGeneration) { mutableStateOf(false) }
    var enabled by rememberSaveable(userId, packageName, expectedGeneration) { mutableStateOf(false) }
    var noClonedApps by rememberSaveable(userId, packageName, expectedGeneration) { mutableStateOf(false) }
    var selectedInScope by rememberSaveable(userId, packageName, expectedGeneration) { mutableStateOf(true) }
    var independent by rememberSaveable(userId, packageName, expectedGeneration) { mutableStateOf(false) }
    var restoring by remember(userId, packageName, expectedGeneration) { mutableStateOf(true) }
    var profileRefresh by remember(userId, packageName, expectedGeneration) { mutableIntStateOf(0) }
    var serviceUnavailable by rememberSaveable(userId, packageName, expectedGeneration) { mutableStateOf(false) }
    var restoredIdentity by rememberSaveable(userId, packageName, expectedGeneration) { mutableLongStateOf(0L) }
    var selectionEdited by rememberSaveable(userId, packageName, expectedGeneration) { mutableStateOf(false) }
    val selectionVersion = rememberSaveable(userId, packageName, expectedGeneration) { mutableIntStateOf(0) }
    var automaticSelectionHandled by rememberSaveable(userId, packageName, expectedGeneration) { mutableStateOf(false) }
    var pointOrigin by rememberSaveable(userId, packageName, expectedGeneration) {
        mutableStateOf(if (cachedHostLocation == null) LocationPointOrigin.DEFAULT else LocationPointOrigin.CACHED)
    }
    val busy = remember(userId, packageName, expectedGeneration) { mutableStateOf(false) }
    var stopFailure by remember(userId, packageName, expectedGeneration) { mutableStateOf<ConfigurationStopException?>(null) }
    val mapPreferences = remember(context.applicationContext) { MapTilePreferences(context) }
    var mapSettings by remember { mutableStateOf(mapPreferences.read()) }
    val mapSource = remember(mapSettings) { mapSettings.source() }
    val viewportPreferences = remember(context.applicationContext) { MapViewportPreferences(context) }
    var mapZoom by rememberSaveable { mutableDoubleStateOf(viewportPreferences.readZoom().coerceIn(0.0, mapSource.maxZoom.toDouble())) }
    var bottomCardHeight by remember { mutableIntStateOf(0) }
    var showSourceDialog by rememberSaveable { mutableStateOf(false) }
    var savingSource by remember { mutableStateOf(false) }
    var sourceSaveFailed by remember { mutableStateOf(false) }
    var mapUnavailable by remember { mutableStateOf(false) }
    var receivedTile by remember { mutableStateOf(false) }
    var reportedTileFailure by remember { mutableStateOf(false) }
    var retryToken by remember { mutableLongStateOf(0L) }

    val mapView = remember(context, userId, packageName, expectedGeneration) {
        MapConfiguration.ensureConfigured(context)
        MapView(context).apply {
            // AndroidView.onRelease owns final destruction; temporary detaches may be reused.
            setDestroyMode(false)
            setTileSource(mapSource.asTileSource())
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setMinZoomLevel(0.0)
            setMaxZoomLevel(mapSource.maxZoom.toDouble())
            controller.setZoom(mapZoom.coerceIn(minZoomLevel, maxZoomLevel))
            controller.setCenter(GeoPoint(latState.value, lonState.value))
        }
    }
    val marker = remember(mapView) {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setInfoWindow(null)
        }
    }

    fun selectPoint(
        latitude: Double,
        longitude: Double,
        edited: Boolean = true,
        origin: LocationPointOrigin = LocationPointOrigin.SELECTED
    ) {
        latState.value = latitude
        lonState.value = longitude
        selectionEdited = edited
        selectionVersion.intValue++
        automaticSelectionHandled = true
        pointOrigin = origin
        mapView.controller.animateTo(GeoPoint(latitude, longitude))
    }

    val hostLocation = rememberHostLocation(refreshLifecycle, selectionVersion, hostRepository) { fix, explicit ->
        if (!busy.value && (explicit || !automaticSelectionHandled)) {
            val display = fix ?: hostRepository.cachedLocation()
            selectPoint(display?.latitude ?: DEFAULT_LAT, display?.longitude ?: DEFAULT_LON, edited = explicit,
                origin = when {
                    display == null -> LocationPointOrigin.DEFAULT
                    fix == null -> LocationPointOrigin.CACHED
                    fix.approximate -> LocationPointOrigin.APPROXIMATE
                    else -> LocationPointOrigin.CURRENT
                })
        }
    }
    val favorites = rememberLocationFavorites(refreshLifecycle, latState.value, lonState.value) { place ->
        if (!busy.value && !restoring) selectPoint(place.latitude, place.longitude)
    }

    LaunchedEffect(mapPreferences, refreshLifecycle) {
        refreshLifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            MapTilePreferences.changes.collectLatest {
                mapSettings = withContext(Dispatchers.IO) { mapPreferences.read() }
            }
        }
    }

    // Restore only the saved scope. Newly cloned apps stay off after the first explicit save.
    LaunchedEffect(userId, packageName, expectedGeneration, profileRefresh) {
        restoring = true
        try {
            val restored = withContext(Dispatchers.IO) {
                if (packageName != null) {
                    val current = repo.loadAppLocation(userId, packageName, checkNotNull(expectedGeneration))
                    RestoredLocation(current.createTime, current.point, current.enabled, 1, false,
                        current.selected, current.independent)
                } else {
                    val user = UsersRepository.users().firstOrNull { it.id == userId }
                    if (user == null) RestoredLocation(null, null, false, 0, false) else {
                        val current = repo.loadScope(userId)
                        val restored = (!current.enabled && current.independentPackages.isEmpty()) ||
                            repo.reconcileLocations(userId, current.createTime)
                        RestoredLocation(current.createTime, current.point, current.enabled,
                            current.apps.size, !restored)
                    }
                }
            }
            val sameIdentity = (restored.identity ?: 0L) == restoredIdentity
            val hasDraft = restoredSelection && selectionEdited &&
                (latState.value != savedLatitude || lonState.value != savedLongitude)
            val nextLatitude = restored.point?.first ?: DEFAULT_LAT
            val nextLongitude = restored.point?.second ?: DEFAULT_LON
            val savedPointChanged = nextLatitude != savedLatitude || nextLongitude != savedLongitude
            savedLatitude = nextLatitude
            savedLongitude = nextLongitude
            // Initial host positioning is a preview. Only user-selected points become unsaved changes.
            if (restoredSelection && (!sameIdentity || (!hasDraft && savedPointChanged))) {
                latState.value = savedLatitude
                lonState.value = savedLongitude
                selectionEdited = false
                selectionVersion.intValue++
                automaticSelectionHandled = true
                pointOrigin = if (restored.point == null) LocationPointOrigin.DEFAULT else LocationPointOrigin.SAVED
                confirmLeave = false
                mapView.controller.setCenter(GeoPoint(latState.value, lonState.value))
            }
            restoredSelection = true
            restoredIdentity = restored.identity ?: 0L
            enabled = restored.enabled
            noClonedApps = restored.appCount == 0
            serviceUnavailable = restored.failed
            selectedInScope = restored.selected
            independent = restored.independent
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            serviceUnavailable = true
            NexLog.w("Location", "action=restoreFromScreen userId=$userId", error)
        } finally { restoring = false }
    }

    // A source switch keeps the camera/selected WGS-84 point and only changes the tile provider.
    LaunchedEffect(mapView, mapSource, retryToken) {
        NexLog.i("Map", "action=loadTiles provider=${mapSettings.provider} cache=${mapSource.cacheName} retry=$retryToken")
        receivedTile = false
        reportedTileFailure = false
        mapUnavailable = false
        mapView.setTileSource(mapSource.asTileSource())
        mapView.setMaxZoomLevel(mapSource.maxZoom.toDouble())
        mapView.controller.setZoom(mapView.zoomLevelDouble.coerceIn(mapView.minZoomLevel, mapView.maxZoomLevel))
        mapZoom = mapView.zoomLevelDouble
        mapView.invalidate()
        delay(10_000)
        if (!receivedTile) {
            mapUnavailable = true
            NexLog.w("Map", "action=loadTiles stage=timeout provider=${mapSettings.provider} waitMs=10000")
        }
    }

    // 地图交互与生命周期转发。点击回调直接写状态持有者——它由 rememberSaveable
    // 保证跨重组稳定，不存在捕获陈旧值的问题。
    DisposableEffect(mapView, refreshLifecycle) {
        val zoomHandler = Handler(Looper.getMainLooper())
        val saveZoom = Runnable { viewportPreferences.saveZoom(mapZoom) }
        fun persistZoom() {
            zoomHandler.removeCallbacks(saveZoom)
            viewportPreferences.saveZoom(mapZoom)
        }
        val zoomEvents = object : MapListener {
            override fun onScroll(event: ScrollEvent): Boolean = false
            override fun onZoom(event: ZoomEvent): Boolean {
                mapZoom = event.zoomLevel
                zoomHandler.removeCallbacks(saveZoom)
                zoomHandler.postDelayed(saveZoom, 250L)
                return false
            }
        }
        mapView.addMapListener(zoomEvents)
        val events = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(point: GeoPoint): Boolean {
                if (busy.value || restoring || !isValidWgs84Point(point.latitude, point.longitude)) return true
                selectPoint(point.latitude, point.longitude)
                return true
            }

            override fun longPressHelper(point: GeoPoint): Boolean = false
        })
        mapView.overlays.add(events)
        mapView.overlays.add(marker)
        val tileEvents = Handler(Looper.getMainLooper()) { message ->
            when (message.what) {
                MapTileProviderBase.MAPTILE_SUCCESS_ID -> {
                    if (!receivedTile) NexLog.i("Map", "action=loadTiles stage=firstTileReady")
                    receivedTile = true; mapUnavailable = false
                }
                MapTileProviderBase.MAPTILE_FAIL_ID -> {
                    // A failed tile is not a failed map: other requests or the disk cache
                    // may still succeed. Only the first-tile deadline shows the warning.
                    if (!receivedTile && !reportedTileFailure) {
                        reportedTileFailure = true
                        NexLog.w("Map", "action=loadTiles stage=providerFailure waitingForFirstTile=true")
                    }
                }
            }
            false
        }
        mapView.tileProvider.tileRequestCompleteHandlers.add(tileEvents)

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mapView.onResume()
                    if (!busy.value) profileRefresh++
                }
                Lifecycle.Event.ON_PAUSE -> {
                    mapView.controller.stopAnimation(false)
                    persistZoom()
                    mapView.onPause()
                }
                else -> Unit
            }
        }
        refreshLifecycle.addObserver(observer)

        onDispose {
            persistZoom()
            zoomHandler.removeCallbacksAndMessages(null)
            mapView.removeMapListener(zoomEvents)
            refreshLifecycle.removeObserver(observer)
            mapView.overlays.remove(events)
            mapView.overlays.remove(marker)
            mapView.tileProvider.tileRequestCompleteHandlers.remove(tileEvents)
            tileEvents.removeCallbacksAndMessages(null)
        }
    }

    key(refreshLifecycle) {
        LocationFavoriteMarkers(mapView, favorites.places, enabled = !busy.value && !restoring) { place ->
            selectPoint(place.latitude, place.longitude)
        }
    }

    // 选点变化时同步标记位置
    LaunchedEffect(latState.value, lonState.value) {
        marker.position = GeoPoint(latState.value, lonState.value)
        mapView.invalidate()
    }

    fun applyLocation(leaveAfterSave: Boolean = false) {
        if (busy.value || restoring) return
        automaticSelectionHandled = true
        selectionVersion.intValue++
        val latitude = latState.value
        val longitude = lonState.value
        busy.value = true
        scope.launch {
            try {
                if (packageName != null) {
                    val applied = withContext(Dispatchers.IO) {
                        if (leaveAfterSave) {
                            repo.saveAppPoint(userId, packageName, checkNotNull(expectedGeneration), latitude, longitude)
                        } else {
                            repo.applyAppLocation(userId, packageName, checkNotNull(expectedGeneration), latitude, longitude)
                        }
                    }
                    check(applied) { "Could not apply app location" }
                    if (!leaveAfterSave) enabled = true
                    independent = true
                    serviceUnavailable = false
                    savedLatitude = latitude
                    savedLongitude = longitude
                    selectionEdited = false
                    pointOrigin = LocationPointOrigin.SAVED
                    confirmLeave = false
                    profileRefresh++
                    if (leaveAfterSave) onBack()
                    return@launch
                }
                val current = withContext(Dispatchers.IO) {
                    if (UsersRepository.users().none { it.id == userId }) null else repo.loadScope(userId)
                }
                if (current == null || current.apps.isEmpty()) {
                    noClonedApps = true
                    serviceUnavailable = false
                    Toast.makeText(context, R.string.scope_apps_empty, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                noClonedApps = false
                if (!leaveAfterSave && current.selectedPackages.isEmpty()) {
                    Toast.makeText(context, R.string.location_scope_required_title, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val applied = withContext(Dispatchers.IO) {
                    val identity = restoredIdentity.takeIf { it > 0L } ?: current.createTime
                    if (leaveAfterSave) {
                        if (repo.saveGlobalPoint(userId, identity, latitude, longitude)) 0 else -1
                    } else repo.applyGlobalLocation(userId, latitude, longitude, identity)
                }
                if (applied < 0) {
                    serviceUnavailable = true
                    Toast.makeText(context, R.string.fake_location_operation_failed, Toast.LENGTH_SHORT).show()
                } else {
                    serviceUnavailable = false
                    if (!leaveAfterSave) enabled = true
                    savedLatitude = latitude
                    savedLongitude = longitude
                    selectionEdited = false
                    pointOrigin = LocationPointOrigin.SAVED
                    confirmLeave = false
                    if (leaveAfterSave) onBack()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (error: ConfigurationStopException) {
                stopFailure = error
                serviceUnavailable = false
                if (!leaveAfterSave) enabled = true
                if (packageName != null) independent = true
                savedLatitude = latitude
                savedLongitude = longitude
                selectionEdited = false
                pointOrigin = LocationPointOrigin.SAVED
                confirmLeave = false
                profileRefresh++
            } catch (error: Exception) {
                NexLog.e("Location", "action=applyFromScreen userId=$userId", error)
                serviceUnavailable = true
                Toast.makeText(context, R.string.fake_location_operation_failed, Toast.LENGTH_SHORT).show()
            } finally {
                if (currentCoroutineContext().isActive) busy.value = false
            }
        }
    }

    fun clearLocation() {
        if (packageName != null || busy.value || restoring) return
        automaticSelectionHandled = true
        selectionVersion.intValue++
        busy.value = true
        scope.launch {
            try {
                val cleared = withContext(Dispatchers.IO) {
                    repo.clearGlobalLocation(userId, restoredIdentity.takeIf { it > 0L })
                }
                if (cleared) {
                    enabled = false
                    serviceUnavailable = false
                } else {
                    serviceUnavailable = true
                    Toast.makeText(context, R.string.fake_location_operation_failed, Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (error: ConfigurationStopException) {
                stopFailure = error
                enabled = false
                serviceUnavailable = false
            } catch (error: Exception) {
                NexLog.e("Location", "action=clearFromScreen userId=$userId", error)
                serviceUnavailable = true
                Toast.makeText(context, R.string.fake_location_operation_failed, Toast.LENGTH_SHORT).show()
            } finally {
                if (currentCoroutineContext().isActive) busy.value = false
            }
        }
    }

    fun useGroupLocation() {
        if (packageName == null || busy.value || restoring) return
        automaticSelectionHandled = true
        selectionVersion.intValue++
        busy.value = true
        scope.launch {
            try {
                val restored = withContext(Dispatchers.IO) {
                    val identity = checkNotNull(expectedGeneration)
                    check(repo.resetAppLocation(userId, packageName, identity)) { "Could not restore group location" }
                    repo.loadAppLocation(userId, packageName, identity)
                }
                latState.value = restored.point?.first ?: DEFAULT_LAT
                lonState.value = restored.point?.second ?: DEFAULT_LON
                savedLatitude = latState.value
                savedLongitude = lonState.value
                selectionEdited = false
                pointOrigin = if (restored.point == null) LocationPointOrigin.DEFAULT else LocationPointOrigin.SAVED
                enabled = restored.enabled
                independent = restored.independent
                selectedInScope = restored.selected
                serviceUnavailable = false
                mapView.controller.animateTo(GeoPoint(latState.value, lonState.value))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ConfigurationStopException) {
                stopFailure = error
                serviceUnavailable = false
                independent = false
                latState.value = savedLatitude
                lonState.value = savedLongitude
                selectionEdited = false
                pointOrigin = LocationPointOrigin.SAVED
                confirmLeave = false
                profileRefresh++
            } catch (error: Exception) {
                NexLog.e("Location", "action=restoreGroupFromScreen userId=$userId package=$packageName", error)
                serviceUnavailable = true
                Toast.makeText(context, R.string.fake_location_operation_failed, Toast.LENGTH_SHORT).show()
            } finally {
                if (currentCoroutineContext().isActive) busy.value = false
            }
        }
    }

    val hasUnsavedPoint = restoredSelection && selectionEdited &&
        (latState.value != savedLatitude || lonState.value != savedLongitude)
    fun requestBack() {
        if (busy.value || restoring) return
        if (hasUnsavedPoint) confirmLeave = true else onBack()
    }
    NexBoxBackGuard(blocked = busy.value || restoring || hasUnsavedPoint,
        allowPreview = !busy.value && !restoring && !hasUnsavedPoint, onBlocked = ::requestBack)

    Scaffold(
        topBar = {
            NexBoxTopAppBar(
                title = if (packageName == null) stringResource(R.string.fake_location)
                    else appLabel?.takeIf { it.isNotBlank() } ?: packageName,
                onBack = ::requestBack,
                actions = {
                    val locationDescription = stringResource(
                        if (hostLocation.locating) R.string.location_locating else R.string.location_current
                    )
                    IconButton(onClick = hostLocation.request,
                        enabled = !busy.value && !restoring && !hostLocation.locating,
                        modifier = Modifier.size(NexBoxSpacing.touchTarget).semantics { contentDescription = locationDescription }) {
                        if (hostLocation.locating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.MyLocation, contentDescription = null)
                    }
                    FakeLocationMoreMenu(enabled = !busy.value && !restoring,
                        onMapSource = { sourceSaveFailed = false; showSourceDialog = true },
                        onFavorites = favorites.open)
                }
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                onRelease = {
                    it.controller.stopAnimation(false)
                    it.onDetach()
                }
            )

            if (mapUnavailable) {
                NexBoxCard(modifier = Modifier.align(Alignment.TopCenter).padding(NexBoxSpacing.screenPadding)) {
                    Column(Modifier.padding(NexBoxSpacing.md)) {
                        Text(stringResource(R.string.map_tiles_unavailable), style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { retryToken++ }) { Text(stringResource(R.string.map_tiles_retry)) }
                            TextButton(onClick = { showSourceDialog = true }) { Text(stringResource(R.string.map_tile_source)) }
                        }
                    }
                }
            }

            val cardHeight = with(LocalDensity.current) { bottomCardHeight.toDp() }
            MapZoomControls(
                canZoomIn = mapZoom < mapSource.maxZoom && !busy.value && !restoring,
                canZoomOut = mapZoom > 0.0 && !busy.value && !restoring,
                onZoomIn = { mapView.controller.zoomTo((mapView.zoomLevelDouble + 1.0).coerceIn(mapView.minZoomLevel, mapView.maxZoomLevel)) },
                onZoomOut = { mapView.controller.zoomTo((mapView.zoomLevelDouble - 1.0).coerceIn(mapView.minZoomLevel, mapView.maxZoomLevel)) },
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = NexBoxSpacing.screenPadding + NexBoxSpacing.cardPadding,
                        bottom = cardHeight + NexBoxSpacing.sm)
            )

            NexBoxCard(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .heightIn(max = maxHeight * 0.5f)
                    .onSizeChanged { bottomCardHeight = it.height }
                    .padding(NexBoxSpacing.screenPadding)
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(NexBoxSpacing.cardPadding)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = favorites.selected?.name ?: stringResource(when (pointOrigin) {
                                    LocationPointOrigin.DEFAULT -> R.string.location_default
                                    LocationPointOrigin.CACHED -> R.string.location_cached
                                    LocationPointOrigin.CURRENT -> R.string.location_current
                                    LocationPointOrigin.APPROXIMATE -> R.string.location_approximate
                                    LocationPointOrigin.SELECTED -> R.string.fake_location_pick
                                    LocationPointOrigin.SAVED -> R.string.location_saved
                                }),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            val coordinateDescription = stringResource(R.string.fake_location_coords,
                                String.format(Locale.US, "%.5f", lonState.value),
                                String.format(Locale.US, "%.5f", latState.value))
                            Text(locationCoordinates(latState.value, lonState.value),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clearAndSetSemantics { contentDescription = coordinateDescription })
                        }
                        IconButton(onClick = {
                            automaticSelectionHandled = true
                            selectionVersion.intValue++
                            favorites.editCurrent()
                        }, enabled = favorites.ready && !busy.value && !restoring) {
                            Icon(if (favorites.selected == null) Icons.Outlined.StarBorder else Icons.Default.Star,
                                stringResource(if (favorites.selected == null) R.string.location_favorite_add else R.string.location_favorite_edit),
                                tint = if (favorites.selected == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                        }
                    }

                    if (!restoring && noClonedApps) {
                        Text(stringResource(R.string.scope_apps_empty), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (packageName != null && !restoring) {
                        Text(stringResource(if (independent) R.string.location_app_independent
                            else R.string.location_app_inherited), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!selectedInScope) {
                            Text(stringResource(R.string.location_app_outside_scope),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    if (serviceUnavailable) {
                        Spacer(modifier = Modifier.height(NexBoxSpacing.xs))
                        Text(
                            text = stringResource(id = R.string.fake_location_operation_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    if (packageName == null) ListItem(
                        headlineContent = { Text(stringResource(R.string.location_scope_title)) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().padding(top = NexBoxSpacing.sm)
                            .clickable(enabled = !busy.value && !restoring) {
                                onOpenScope()
                            }
                    )
                    FakeLocationActions(
                        simulationEnabled = enabled,
                        enabled = !busy.value && !restoring && !noClonedApps && restoredIdentity > 0L,
                        onApply = { applyLocation() },
                        onSecondary = if (packageName == null) ::clearLocation else ::useGroupLocation,
                        secondaryLabel = stringResource(if (packageName == null) R.string.location_disable_simulation
                            else R.string.device_restore_global),
                        showSecondary = if (packageName == null) enabled else true,
                        secondaryEnabled = packageName == null || independent,
                        applyLabelOverride = if (packageName != null && !selectedInScope)
                            stringResource(R.string.location_save_position) else null,
                        modifier = Modifier.fillMaxWidth().padding(top = NexBoxSpacing.md)
                    )
                    Text(mapSource.attribution, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = NexBoxSpacing.sm))
                }
            }
        }
    }

    if (showSourceDialog) MapSourceDialog(mapSettings, savingSource, sourceSaveFailed, onSave = { candidate ->
        savingSource = true
        sourceSaveFailed = false
        scope.launch {
            try {
                if (withContext(Dispatchers.IO) { mapPreferences.save(candidate) }) {
                    showSourceDialog = false
                } else sourceSaveFailed = true
            } finally {
                if (currentCoroutineContext().isActive) savingSource = false
            }
        }
    }, onDismiss = { showSourceDialog = false })

    ConfigurationStopFailureDialog(stopFailure, onDismiss = { stopFailure = null })

    if (confirmLeave) AlertDialog(
        onDismissRequest = { if (!busy.value) confirmLeave = false },
        title = { Text(stringResource(R.string.device_unsaved_title)) },
        confirmButton = {
            TextButton(onClick = { applyLocation(leaveAfterSave = true) }, enabled = !busy.value && !restoring) {
                Text(stringResource(R.string.device_save_and_leave))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                latState.value = savedLatitude
                lonState.value = savedLongitude
                confirmLeave = false
                onBack()
            }, enabled = !busy.value && !restoring) { Text(stringResource(R.string.device_discard_changes)) }
        }
    )

}

private data class RestoredLocation(
    val identity: Long?,
    val point: Pair<Double, Double>?,
    val enabled: Boolean,
    val appCount: Int,
    val failed: Boolean,
    val selected: Boolean = true,
    val independent: Boolean = false
)
