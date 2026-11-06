package com.example.maps.location

import android.Manifest
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Finds the device position with the platform [LocationManager] (no Play Services
 * library) and names the place with the platform [Geocoder] when one is available.
 */
class UserLocator(private val context: Context) {

    private val manager = context.getSystemService(LocationManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pending: CancellationSignal? = null

    val isLocationEnabled: Boolean get() = manager.isLocationEnabled

    /** Calls [onResult] on the main thread with a position, or null when none could be found. */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION])
    fun locate(onResult: (Location?) -> Unit) {
        cancel()
        val lastKnown = bestLastKnown()
        if (lastKnown != null && ageMillis(lastKnown) < FRESH_ENOUGH_MS) {
            onResult(lastKnown)
            return
        }
        val provider = PROVIDERS.firstOrNull { manager.hasProvider(it) && manager.isProviderEnabled(it) }
        if (provider == null) {
            onResult(lastKnown)
            return
        }
        // High accuracy so the GPS is used: the default balanced request relies on Wi-Fi and
        // cell towers only, and may never answer indoors or on an emulator.
        val request = LocationRequest.Builder(0L)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .setDurationMillis(LOCATE_TIMEOUT_MS)
            .build()
        val signal = CancellationSignal().also { pending = it }
        manager.getCurrentLocation(provider, request, signal, context.mainExecutor) { location ->
            pending = null
            onResult(location ?: lastKnown)
        }
    }

    /** Cancels a pending [locate] request, if any. */
    fun cancel() {
        pending?.cancel()
        pending = null
    }

    /** Calls [onName] on the main thread with something like "Paris, France", if it can be found. */
    fun describe(location: Location, onName: (String) -> Unit) {
        if (!Geocoder.isPresent()) return
        val geocoder = Geocoder(context, Locale.getDefault())
        val deliver = { addresses: List<Address>? ->
            addresses?.firstOrNull()?.let(::placeName)?.let { name -> mainHandler.post { onName(name) } }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses -> deliver(addresses) }
        } else {
            thread(name = "geocoder") {
                @Suppress("DEPRECATION")
                deliver(runCatching { geocoder.getFromLocation(location.latitude, location.longitude, 1) }.getOrNull())
            }
        }
    }

    /** The most recent position any provider still remembers. */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION])
    private fun bestLastKnown(): Location? = PROVIDERS
        .filter { manager.hasProvider(it) }
        .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.elapsedRealtimeNanos }

    private fun ageMillis(location: Location) =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

    private fun placeName(address: Address): String? {
        val place = address.locality ?: address.subAdminArea ?: address.adminArea
        return listOfNotNull(place, address.countryName).joinToString(", ").ifEmpty { null }
    }

    private companion object {
        /** A last known position younger than this is used right away, without a new fix. */
        const val FRESH_ENOUGH_MS = 2 * 60 * 1000L
        const val LOCATE_TIMEOUT_MS = 10_000L

        /** In order of preference. */
        val PROVIDERS = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }
}
