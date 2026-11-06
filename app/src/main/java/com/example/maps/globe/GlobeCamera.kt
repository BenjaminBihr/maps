package com.example.maps.globe

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Where the globe is looking: the (lat, lon) under the screen center and the zoom.
 *
 * Gestures write from the UI thread and the renderer reads from the GL thread, so every
 * access is synchronized. Pure Kotlin so it can be unit tested on the JVM.
 */
class GlobeCamera(
    initialLat: Double = 30.0,
    initialLon: Double = 2.35,
    autoRotate: Boolean = true,
    /** Keeps the globe invisible and still until [startIntro]. */
    startHidden: Boolean = false,
) {

    data class View(
        val centerLat: Double,
        val centerLon: Double,
        val zoom: Float,
        val radiusPx: Float,
        val viewportWidth: Int,
        val viewportHeight: Int,
        /** Index of the tour stop being flown to or shown, -1 when no tour runs. */
        val tourIndex: Int,
        /** Where the user was located, null until then. */
        val userLocation: GeoPoint?,
        /** True from the landing on the user until the next touch, tour or flight. */
        val showingUser: Boolean,
        /** 0 = globe invisible, 1 = fully shown; fades in during the intro. */
        val reveal: Float,
    )

    data class GeoPoint(val lat: Double, val lon: Double)

    /** Where a double tap (or a lowered zoom limit) glides the camera to. */
    private class Target(val lat: Double, val lon: Double, val zoom: Float)

    private var centerLat = initialLat
    private var centerLon = GeoMath.normalizeLon(initialLon)
    private var zoom = if (startHidden) INTRO_START_ZOOM else 1f
    private var maxZoom = MAX_ZOOM
    private var viewportWidth = 0
    private var viewportHeight = 0

    // Intro animation.
    private var hidden = startHidden
    private var reveal = if (startHidden) 0f else 1f
    private var introElapsed = -1.0 // -1 = no intro running
    private var introEndLat = 0.0
    private var introEndLon = 0.0
    private var queuedUser: GeoPoint? = null // dive asked for during the intro, started after it

    // Free motion: idle spin, fling inertia, double-tap glide.
    private var autoRotating = autoRotate
    private var velocityLat = 0.0 // degrees per second
    private var velocityLon = 0.0
    private var target: Target? = null

    // Capitals tour: a flight to each stop, then a pause.
    private var tourStops = emptyList<GeoPoint>()
    private var tourIndex = -1
    private var flight: Flight? = null
    private var pauseLeft = 0.0

    // Dive onto the user's position.
    private var userLocation: GeoPoint? = null
    private var userFlight: Flight? = null
    private var showingUser = false

    @Synchronized
    fun setViewport(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
    }

    /** A consistent copy of the state, read once per frame by the renderer. */
    @Synchronized
    fun snapshot() = View(
        centerLat, centerLon, zoom, radiusPx(), viewportWidth, viewportHeight,
        tourIndex, userLocation, showingUser, reveal,
    )

    /**
     * Appearance: the globe fades in from a dot, grows with a slight overshoot and arrives
     * spinning, slowing down until it settles on the starting view.
     */
    @Synchronized
    fun startIntro() {
        hidden = false
        introEndLat = centerLat
        introEndLon = centerLon
        introElapsed = 0.0
        applyIntro(0.0)
    }

    /** Deepest zoom allowed; when lowered below the current zoom, the globe glides back out. */
    @Synchronized
    fun setMaxZoom(limit: Float) {
        maxZoom = limit
        if (zoom > limit) target = Target(centerLat, centerLon, limit)
    }

    /** Called on finger down: the globe stops under the finger. */
    @Synchronized
    fun stopMotion() {
        zoom = min(zoom, maxZoom)
        if (introElapsed >= 0.0) {
            introElapsed = -1.0
            reveal = 1f
            zoom = max(zoom, MIN_ZOOM)
        }
        queuedUser = null
        autoRotating = false
        velocityLat = 0.0
        velocityLon = 0.0
        target = null
        stopTour()
        userFlight = null
        showingUser = false
    }

    /**
     * Cinematic dive onto the user's position: climb, turn to face it, then plunge.
     * Requested while the globe is hidden or appearing, it starts right after the intro.
     */
    @Synchronized
    fun flyToUser(location: GeoPoint) {
        if (hidden || introElapsed >= 0.0) {
            queuedUser = location
            return
        }
        stopMotion()
        startUserFlight(location)
    }

    private fun startUserFlight(location: GeoPoint) {
        userLocation = location
        userFlight = Flight(centerLat, centerLon, zoom, location.lat, location.lon, USER_ZOOM, Flight.Style.DIVE)
    }

    /** Flies from stop to stop (nearest one first), pausing on each, looping until stopped. */
    @Synchronized
    fun startTour(stops: List<GeoPoint>) {
        require(stops.isNotEmpty()) { "A tour needs at least one stop" }
        stopMotion()
        tourStops = stops
        tourIndex = nearestStop(stops)
        flight = flightTo(stops[tourIndex])
    }

    @Synchronized
    fun stopTour() {
        tourStops = emptyList()
        tourIndex = -1
        flight = null
    }

    /** Moves the surface along with the finger ([dxPx], [dyPx] in screen pixels). */
    @Synchronized
    fun drag(dxPx: Float, dyPx: Float) {
        val radius = radiusPx()
        if (radius <= 0f) return
        centerLon = GeoMath.normalizeLon(centerLon - Math.toDegrees(dxPx / radius.toDouble()))
        centerLat = clampLat(centerLat + Math.toDegrees(dyPx / radius.toDouble()))
    }

    /** Lets the globe coast after a swipe ([velocityXPx], [velocityYPx] in pixels per second). */
    @Synchronized
    fun fling(velocityXPx: Float, velocityYPx: Float) {
        val radius = radiusPx()
        if (radius <= 0f) return
        velocityLon = -Math.toDegrees(velocityXPx / radius.toDouble())
        velocityLat = Math.toDegrees(velocityYPx / radius.toDouble())
    }

    /** Pinch: multiplies the zoom, within its limits. */
    @Synchronized
    fun zoomBy(factor: Float) {
        zoom = (zoom * factor).coerceIn(MIN_ZOOM, maxZoom)
        target = null
    }

    /** Double tap: fly to the tapped place and zoom in, or back out when already fully zoomed. */
    @Synchronized
    fun focusOn(xPx: Float, yPx: Float) {
        val geo = screenToGeoLocked(xPx, yPx) ?: return
        val nextZoom = if (zoom >= maxZoom * 0.99f) 1f else min(zoom * 2f, maxZoom)
        autoRotating = false
        velocityLat = 0.0
        velocityLon = 0.0
        target = Target(clampLat(geo.lat), geo.lon, nextZoom)
    }

    @Synchronized
    fun screenToGeo(xPx: Float, yPx: Float): GeoPoint? = screenToGeoLocked(xPx, yPx)

    /** Advances animations by [dtSeconds]; returns true while something is still moving. */
    @Synchronized
    fun update(dtSeconds: Float): Boolean {
        if (hidden) return false
        // The intro, then a tour, take the camera over entirely while they run.
        if (advanceIntro(dtSeconds.toDouble())) return true
        if (tourStops.isNotEmpty()) {
            advanceTour(dtSeconds.toDouble())
            return true
        }

        var moving = advanceUserFlight(dtSeconds.toDouble())

        if (autoRotating) {
            centerLon = GeoMath.normalizeLon(centerLon - AUTO_ROTATE_DEG_PER_S * dtSeconds)
            moving = true
        }

        // Fling inertia, slowed by friction; hitting the latitude limit stops the vertical part.
        if (velocityLat != 0.0 || velocityLon != 0.0) {
            centerLon = GeoMath.normalizeLon(centerLon + velocityLon * dtSeconds)
            val nextLat = centerLat + velocityLat * dtSeconds
            centerLat = clampLat(nextLat)
            if (centerLat != nextLat) velocityLat = 0.0
            val decay = exp(-FLING_FRICTION * dtSeconds)
            velocityLat *= decay
            velocityLon *= decay
            if (hypot(velocityLat, velocityLon) < MIN_FLING_DEG_PER_S) {
                velocityLat = 0.0
                velocityLon = 0.0
            } else {
                moving = true
            }
        }

        // Glide toward the target: each frame covers a share of the remaining way (frame rate
        // independent), zoom in log space; snaps once close enough.
        target?.let { t ->
            val k = 1.0 - exp(-FOCUS_SPEED * dtSeconds)
            val dLon = GeoMath.shortestLonDelta(centerLon, t.lon)
            val dLat = t.lat - centerLat
            val dLogZoom = ln(t.zoom.toDouble()) - ln(zoom.toDouble())
            if (abs(dLon) < 0.01 && abs(dLat) < 0.01 && abs(dLogZoom) < 0.001) {
                centerLon = GeoMath.normalizeLon(t.lon)
                centerLat = t.lat
                zoom = t.zoom
                target = null
            } else {
                centerLon = GeoMath.normalizeLon(centerLon + dLon * k)
                centerLat += dLat * k
                zoom = exp(ln(zoom.toDouble()) + dLogZoom * k).toFloat()
                moving = true
            }
        }

        return moving
    }

    /** Flies to the current stop, lands exactly on it, waits, then heads for the next one. */
    private fun advanceTour(dtSeconds: Double) {
        val current = flight
        if (current == null) {
            pauseLeft -= dtSeconds
            if (pauseLeft <= 0) {
                tourIndex = (tourIndex + 1) % tourStops.size
                flight = flightTo(tourStops[tourIndex])
            }
            return
        }
        current.advance(dtSeconds)
        if (current.finished) {
            val stop = tourStops[tourIndex]
            centerLat = clampLat(stop.lat)
            centerLon = GeoMath.normalizeLon(stop.lon)
            zoom = TOUR_ZOOM
            flight = null
            pauseLeft = TOUR_PAUSE_SECONDS
        } else {
            applySample(current.sample())
        }
    }

    private fun advanceIntro(dtSeconds: Double): Boolean {
        if (introElapsed < 0.0) return false
        introElapsed += dtSeconds
        val t = min(introElapsed / INTRO_SECONDS, 1.0)
        applyIntro(t)
        if (t >= 1.0) {
            introElapsed = -1.0
            val user = queuedUser
            if (user != null) {
                stopMotion()
                startUserFlight(user)
            }
        }
        return true
    }

    /** Intro pose at progress [t] (0..1): fades in first, grows, and the spin and tilt die out. */
    private fun applyIntro(t: Double) {
        val rest = 1.0 - t
        reveal = if (t >= 1.0) 1f else smoothstep(min(t / INTRO_REVEAL_END, 1.0)).toFloat()
        zoom = if (t >= 1.0) 1f else (INTRO_START_ZOOM + (1f - INTRO_START_ZOOM) * easeOutBack(t)).toFloat()
        centerLon = GeoMath.normalizeLon(introEndLon + INTRO_SPIN_DEG * rest.pow(4))
        centerLat = clampLat(introEndLat + INTRO_TILT_DEG * rest.pow(3))
    }

    private fun smoothstep(t: Double) = t * t * (3 - 2 * t)

    /** Ease-out that goes past 1 before settling back: the globe briefly grows too big. */
    private fun easeOutBack(t: Double): Double {
        val x = t - 1.0
        return 1.0 + (INTRO_OVERSHOOT + 1.0) * x * x * x + INTRO_OVERSHOOT * x * x
    }

    private fun advanceUserFlight(dtSeconds: Double): Boolean {
        val flight = userFlight ?: return false
        val location = userLocation ?: return false
        flight.advance(dtSeconds)
        if (flight.finished) {
            centerLat = clampLat(location.lat)
            centerLon = GeoMath.normalizeLon(location.lon)
            zoom = USER_ZOOM
            userFlight = null
            showingUser = true
        } else {
            applySample(flight.sample())
        }
        return true
    }

    private fun applySample(sample: Flight.Sample) {
        centerLat = clampLat(sample.lat)
        centerLon = GeoMath.normalizeLon(sample.lon)
        zoom = sample.zoom.coerceIn(MIN_ZOOM, maxZoom)
    }

    private fun flightTo(stop: GeoPoint) = Flight(centerLat, centerLon, zoom, stop.lat, stop.lon, TOUR_ZOOM)

    /** Index of the stop closest to the screen center (largest cosine = smallest angle). */
    private fun nearestStop(stops: List<GeoPoint>): Int {
        val lat0 = Math.toRadians(centerLat)
        val lon0 = Math.toRadians(centerLon)
        return stops.indices.maxBy { i ->
            val lat = Math.toRadians(stops[i].lat)
            val lon = Math.toRadians(stops[i].lon)
            sin(lat0) * sin(lat) + cos(lat0) * cos(lat) * cos(lon - lon0)
        }
    }

    private fun radiusPx(): Float = min(viewportWidth, viewportHeight) * BASE_RADIUS_FRACTION * zoom

    private fun screenToGeoLocked(xPx: Float, yPx: Float): GeoPoint? =
        Projection(centerLat, centerLon, radiusPx().toDouble(), viewportWidth, viewportHeight)
            .unproject(xPx.toDouble(), yPx.toDouble())

    private fun clampLat(lat: Double) = lat.coerceIn(-MAX_LAT, MAX_LAT)

    companion object {
        /** Globe radius at zoom 1, as a fraction of the smallest viewport side. */
        const val BASE_RADIUS_FRACTION = 0.42f
        const val MIN_ZOOM = 0.7f
        const val MAX_ZOOM = 6f

        /** Satellite tiles stay sharp much deeper than the political map texture. */
        const val SATELLITE_MAX_ZOOM = 32f

        /** The screen center never goes past this latitude, so the view never tips over a pole. */
        const val MAX_LAT = 85.0
        const val AUTO_ROTATE_DEG_PER_S = 6.0
        const val TOUR_ZOOM = 2.2f
        const val TOUR_PAUSE_SECONDS = 2.5
        const val USER_ZOOM = 4.5f

        const val INTRO_SECONDS = 2.4
        const val INTRO_START_ZOOM = 0.12f

        /** Share of the intro during which the globe fades in. */
        private const val INTRO_REVEAL_END = 0.4

        /** Extra turn and tilt at the start of the intro, both easing out to zero. */
        private const val INTRO_SPIN_DEG = 220.0
        private const val INTRO_TILT_DEG = -25.0
        private const val INTRO_OVERSHOOT = 1.2

        /** Fling speed decays as exp(-FLING_FRICTION * t), and stops below the minimum. */
        private const val FLING_FRICTION = 2.5
        private const val MIN_FLING_DEG_PER_S = 0.5

        /** Glide rate toward a target, per second: higher is snappier. */
        private const val FOCUS_SPEED = 6.0
    }
}
