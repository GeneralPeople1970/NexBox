package com.nexbox.app.view.fake

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.nexbox.app.R
import com.nexbox.app.data.LocationFavorite
import com.nexbox.app.data.LocationFavoritesRepository
import com.nexbox.app.ui.components.AlertDialog
import com.nexbox.app.ui.components.CircularProgressIndicator
import com.nexbox.app.ui.components.Icon
import com.nexbox.app.ui.components.IconButton
import com.nexbox.app.ui.components.ListItem
import com.nexbox.app.ui.components.ModalBottomSheet
import com.nexbox.app.ui.components.OutlinedTextField
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.components.TextButton
import com.nexbox.app.ui.components.DestructiveButton
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.app.util.NexLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

internal data class LocationFavoritesUi(
    val places: List<LocationFavorite>,
    val selected: LocationFavorite?,
    val ready: Boolean,
    val open: () -> Unit,
    val editCurrent: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberLocationFavorites(
    lifecycle: Lifecycle,
    latitude: Double,
    longitude: Double,
    onSelect: (LocationFavorite) -> Unit
): LocationFavoritesUi {
    val context = LocalContext.current
    val repository = remember(context.applicationContext) { LocationFavoritesRepository(context) }
    val scope = rememberCoroutineScope()
    var places by remember { mutableStateOf(emptyList<LocationFavorite>()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var showList by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var editLatitude by rememberSaveable { mutableStateOf(0.0) }
    var editLongitude by rememberSaveable { mutableStateOf(0.0) }
    var editName by rememberSaveable { mutableStateOf("") }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val selected = places.firstOrNull { it.matches(latitude, longitude) }
    val ready = !loading && !loadFailed && !saving

    LaunchedEffect(lifecycle, reload, saving) {
        if (saving) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            LocationFavoritesRepository.changes.collectLatest {
                loading = true
                try {
                    places = repository.load()
                    loadFailed = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    loadFailed = true
                    NexLog.w("Location", "action=loadFavorites failed type=${error.javaClass.simpleName}")
                } finally {
                    loading = false
                }
            }
        }
    }

    fun edit(place: LocationFavorite?) {
        if (loading || loadFailed || saving) return
        editId = place?.id
        editLatitude = place?.latitude ?: latitude
        editLongitude = place?.longitude ?: longitude
        editName = place?.name ?: context.getString(R.string.location_favorite_default_name, places.size + 1)
        saveFailed = false
        confirmDelete = false
        editing = true
    }

    fun save() {
        if (loading || loadFailed || saving || editName.isBlank()) return
        saving = true
        saveFailed = false
        val name = editName
        val id = editId
        val lat = editLatitude
        val lon = editLongitude
        scope.launch {
            try {
                places = repository.save(id, name, lat, lon)
                editing = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                saveFailed = true
                NexLog.w("Location", "action=saveFavorite failed type=${error.javaClass.simpleName}")
            } finally {
                saving = false
            }
        }
    }

    if (showList && !editing && !confirmDelete) {
        val maxListHeight = LocalConfiguration.current.screenHeightDp.dp * 0.6f
        ModalBottomSheet(onDismissRequest = { showList = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Text(stringResource(R.string.location_favorites), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth().padding(horizontal = NexBoxSpacing.xl, vertical = NexBoxSpacing.md))
            when {
                loading && places.isEmpty() -> Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                loadFailed -> Column(Modifier.fillMaxWidth().padding(NexBoxSpacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.location_favorites_load_failed))
                    TextButton(onClick = { reload++ }) { Text(stringResource(R.string.location_retry)) }
                }
                places.isEmpty() -> Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.location_favorites_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = maxListHeight).padding(bottom = NexBoxSpacing.lg)) {
                    items(places, key = { it.id }) { place ->
                        ListItem(
                            headlineContent = { Text(place.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(locationCoordinates(place.latitude, place.longitude)) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { edit(place) }, enabled = ready) {
                                        Icon(Icons.Default.Edit, stringResource(R.string.location_favorite_edit_named, place.name))
                                    }
                                    IconButton(onClick = {
                                        editId = place.id
                                        editName = place.name
                                        saveFailed = false
                                        confirmDelete = true
                                    }, enabled = ready) {
                                        Icon(Icons.Default.Delete, stringResource(R.string.location_favorite_delete_named, place.name))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().clickable(enabled = ready) {
                                showList = false
                                onSelect(place)
                            }
                        )
                    }
                }
            }
        }
    }

    if (editing) {
        val focusRequester = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { if (!saving) editing = false },
            title = { Text(stringResource(if (editId == null) R.string.location_favorite_add else R.string.location_favorite_edit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.sm)) {
                    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it.take(LocationFavoritesRepository.MAX_NAME_LENGTH); saveFailed = false },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        enabled = !saving,
                        label = { Text(stringResource(R.string.location_favorite_name)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() })
                    )
                    if (saveFailed || loadFailed) Text(
                        stringResource(if (loadFailed) R.string.location_favorites_load_failed else R.string.location_favorite_save_failed),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = { TextButton(onClick = ::save, enabled = ready && editName.isNotBlank()) {
                Text(stringResource(R.string.location_save))
            } },
            dismissButton = { TextButton(onClick = { editing = false }, enabled = !saving) {
                Text(stringResource(R.string.cancel))
            } }
        )
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { if (!saving) confirmDelete = false },
        title = { Text(stringResource(R.string.location_favorite_delete)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.sm)) {
                Text(stringResource(R.string.location_favorite_delete_confirm, editName))
                if (saveFailed) Text(stringResource(R.string.location_favorite_save_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { DestructiveButton(enabled = ready, onClick = {
            if (loading || loadFailed || saving) return@DestructiveButton
            val id = editId ?: return@DestructiveButton
            saving = true
            saveFailed = false
            scope.launch {
                try {
                    places = repository.delete(id)
                    confirmDelete = false
                    editing = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    saveFailed = true
                    NexLog.w("Location", "action=deleteFavorite failed type=${error.javaClass.simpleName}")
                } finally {
                    saving = false
                }
            }
        }) { Text(stringResource(R.string.location_favorite_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }, enabled = !saving) {
            Text(stringResource(R.string.cancel))
        } }
    )

    return LocationFavoritesUi(places, selected, ready, open = { showList = true }, editCurrent = { edit(selected) })
}

internal fun locationCoordinates(latitude: Double, longitude: Double): String =
    String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
