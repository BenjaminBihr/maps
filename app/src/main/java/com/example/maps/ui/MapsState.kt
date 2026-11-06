package com.example.maps.ui

import android.annotation.SuppressLint
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.maps.R
import com.example.maps.globe.GlobeLabel
import com.example.maps.globe.GlobeView
import com.example.maps.location.UserLocator
import java.util.Locale
import kotlin.math.abs

/** Owns the OpenGL globe and the locator, and exposes what the Compose overlay shows. */
@Stable
class MapsState(private val context: Context) {

    val globe = GlobeView(context)
    private val locator = UserLocator(context)

    /** What the caption presents, null for nothing. */
    var caption by mutableStateOf<GlobeLabel?>(null)
        private set

    /** True while waiting for a position fix. */
    var searching by mutableStateOf(false)
        private set

    /** Satellite imagery lit by the real sun instead of the political map. */
    var satellite by mutableStateOf(false)
        private set

    /** In satellite view: lit by the real sun (day and night), or daylight everywhere. */
    var sunlight by mutableStateOf(true)
        private set

    /** "Paris, France" once geocoded, the coordinates until then. */
    var userPlace by mutableStateOf("")
        private set

    init {
        globe.onLabelChanged = { caption = it }
    }

    /** Plays the globe's appearance animation. */
    fun showGlobe() = globe.startIntro()

    fun toggleSatellite() {
        satellite = !satellite
        globe.setSatellite(satellite)
    }

    fun toggleSunlight() {
        sunlight = !sunlight
        globe.setSunlight(sunlight)
    }

    fun toggleTour() {
        if (globe.isTouring) globe.stopTour() else globe.startTour()
    }

    /**
     * [automatic]: the arrival dive at launch, silent when no position is available.
     * Otherwise the location button, which explains what went wrong.
     */
    @SuppressLint("MissingPermission") // callers check the location permission first
    fun locate(automatic: Boolean) {
        if (!locator.isLocationEnabled) {
            if (!automatic) toast(R.string.location_disabled)
            return
        }
        searching = true
        locator.locate { location ->
            searching = false
            if (location == null) {
                if (!automatic) toast(R.string.location_not_found)
                return@locate
            }
            userPlace = formatCoordinates(location.latitude, location.longitude)
            locator.describe(location) { name -> userPlace = name }
            if (automatic) {
                globe.greetUser(location.latitude, location.longitude)
            } else {
                globe.flyToUser(location.latitude, location.longitude)
            }
        }
    }

    fun toast(@StringRes message: Int) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    /** Stops pending work and callbacks once the screen leaves the composition. */
    fun dispose() {
        locator.cancel()
        globe.onLabelChanged = null
    }

    /** "48.8566° N  ·  2.3522° E", with French cardinal letters (O = ouest, west). */
    private fun formatCoordinates(lat: Double, lon: Double) = String.format(
        Locale.getDefault(),
        "%.4f° %s  ·  %.4f° %s",
        abs(lat), if (lat >= 0) "N" else "S",
        abs(lon), if (lon >= 0) "E" else "O",
    )
}

/** A [MapsState] that lives as long as the composition, disposed with it. */
@Composable
fun rememberMapsState(): MapsState {
    val context = LocalContext.current
    val state = remember(context) { MapsState(context) }
    DisposableEffect(state) {
        onDispose { state.dispose() }
    }
    return state
}
