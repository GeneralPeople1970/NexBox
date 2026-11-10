package com.nexbox.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.nexbox.app.util.NexLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.util.concurrent.Executor

internal data class HostLocationFix(
    val latitude: Double,
    val longitude: Double,
    val approximate: Boolean,
    val timestampMillis: Long
)

internal class HostLocationRepository(context: Context) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("host_location", Context.MODE_PRIVATE)

    fun hasPermission(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
        granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun wasPermissionRequested(): Boolean = preferences.getBoolean("permission_requested", false)

    fun markPermissionRequested() {
        preferences.edit().putBoolean("permission_requested", true).apply()
    }

    /** A cached fix is only a preview; it never enables or changes simulated locations. */
    fun cachedLocation(): HostLocationFix? = synchronized(cacheLock) {
        if (!hasPermission()) return@synchronized null
        val saved = preferences.all
        val latitude = Double.fromBits(saved["latitude_bits"] as? Long ?: return@synchronized null)
        val longitude = Double.fromBits(saved["longitude_bits"] as? Long ?: return@synchronized null)
        val approximate = saved["approximate"] as? Boolean ?: return@synchronized null
        val timestamp = saved["timestamp_millis"] as? Long ?: return@synchronized null
        // A previous precise fix must not be exposed after the user switches to coarse access.
        if ((!approximate && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) ||
            timestamp <= 0L || !isValidWgs84Point(latitude, longitude)) return@synchronized null
        HostLocationFix(latitude, longitude, approximate, timestamp)
    }

    private fun cache(fix: HostLocationFix) = synchronized(cacheLock) {
        val previous = cachedLocation()
        if (hasPermission() && (fix.approximate || granted(Manifest.permission.ACCESS_FINE_LOCATION)) &&
            (previous == null || fix.timestampMillis >= previous.timestampMillis)) {
            preferences.edit()
                .putLong("latitude_bits", fix.latitude.toBits())
                .putLong("longitude_bits", fix.longitude.toBits())
                .putBoolean("approximate", fix.approximate)
                .putLong("timestamp_millis", fix.timestampMillis)
                .apply()
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    suspend fun locate(): HostLocationFix? = withContext(Dispatchers.IO) {
        val operation = NexLog.operation("Location", "locateHost")
        try {
            if (!hasPermission()) {
                operation.finish(false, "reason=permission_required")
                return@withContext null
            }
            val manager = context.getSystemService(LocationManager::class.java)
            if (manager == null) {
                operation.finish(false, "reason=service_unavailable")
                return@withContext null
            }
            val precise = granted(Manifest.permission.ACCESS_FINE_LOCATION)
            val providers = listOfNotNull(
                LocationManager.FUSED_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER.takeIf { precise }
            ).filter { manager.hasProvider(it) && manager.isProviderEnabled(it) }
            if (providers.isEmpty()) {
                operation.finish(false, "reason=no_enabled_provider")
                return@withContext null
            }
            operation.step("requestFix", "providerCount=${providers.size} precise=$precise")
            val location = withTimeoutOrNull(10_000L) {
                coroutineScope {
                    val results = Channel<Location?>(Channel.UNLIMITED)
                    val requests = providers.map { provider ->
                        launch { results.send(awaitFix(manager, provider)) }
                    }
                    try {
                        repeat(providers.size) {
                            val candidate = results.receive()
                            if (candidate != null) return@coroutineScope candidate
                        }
                        null
                    } finally {
                        requests.forEach { it.cancel() }
                        results.close()
                    }
                }
            }
            if (!hasPermission() || (precise && !granted(Manifest.permission.ACCESS_FINE_LOCATION))) {
                operation.finish(false, "reason=permission_changed")
                return@withContext null
            }
            val fix = location?.let {
                HostLocationFix(it.latitude, it.longitude, !precise, it.time).also(::cache)
            }
            operation.finish(fix != null, if (fix == null) "reason=location_unavailable" else "fresh=true approximate=${fix.approximate}")
            fix
        } catch (cancelled: CancellationException) {
            operation.fail(cancelled)
            throw cancelled
        } catch (error: Exception) {
            operation.fail(error, "reason=location_unavailable")
            null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitFix(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                manager.getCurrentLocation(provider, signal, callbackExecutor) { location ->
                    val recent = location?.takeIf {
                        val age = SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos
                        age in 0L..120_000_000_000L && isValidWgs84Point(it.latitude, it.longitude)
                    }
                    if (continuation.isActive) continuation.resume(recent)
                }
            } catch (error: Exception) {
                if (continuation.isActive) {
                    NexLog.w("Location", "action=locateHost provider=$provider unavailable", error)
                    continuation.resume(null)
                }
            }
        }

    companion object {
        const val DEFAULT_LATITUDE = 39.9042
        const val DEFAULT_LONGITUDE = 116.4074
        private val cacheLock = Any()
        private val callbackExecutor = Executor { it.run() }
    }
}
