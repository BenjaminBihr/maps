package com.example.maps.globe

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/**
 * Timed camera move between two places along the great circle (the shortest path on the
 * globe), easing in and out.
 *
 * - [Style.CRUISE]: hop between places, pulling the zoom back mid-way on long hops.
 * - [Style.DIVE]: cinematic arrival: climb until the whole globe shows, turn to face the
 *   target, then plunge onto it.
 */
internal class Flight(
    fromLat: Double,
    fromLon: Double,
    private val fromZoom: Float,
    toLat: Double,
    toLon: Double,
    private val toZoom: Float,
    private val style: Style = Style.CRUISE,
) {
    enum class Style { CRUISE, DIVE }

    data class Sample(val lat: Double, val lon: Double, val zoom: Float)

    private val from = unitVector(fromLat, fromLon)
    private val to = unitVector(toLat, toLon)

    /** Angular distance between the two places, in radians. */
    private val angle = acos((from[0] * to[0] + from[1] * to[1] + from[2] * to[2]).coerceIn(-1.0, 1.0))

    /** Longer for farther places: a base time plus a share per half turn of the globe. */
    val durationSeconds = when (style) {
        Style.CRUISE -> BASE_SECONDS + SECONDS_PER_HALF_TURN * angle / PI
        Style.DIVE -> DIVE_BASE_SECONDS + DIVE_SECONDS_PER_HALF_TURN * angle / PI
    }

    private var elapsedSeconds = 0.0

    val finished get() = elapsedSeconds >= durationSeconds

    fun advance(dtSeconds: Double) {
        elapsedSeconds = min(elapsedSeconds + dtSeconds, durationSeconds)
    }

    /** Camera position and zoom at the current point of the flight. */
    fun sample(): Sample {
        val t = elapsedSeconds / durationSeconds
        return when (style) {
            Style.CRUISE -> cruise(t)
            Style.DIVE -> dive(t)
        }
    }

    private fun cruise(t: Double): Sample {
        val eased = smoothstep(t)
        val pullBack = 1.0 - ZOOM_PULL_BACK * (angle / PI) * sin(PI * t)
        return sampleAt(eased, logLerp(fromZoom.toDouble(), toZoom.toDouble(), eased) * pullBack)
    }

    /** Zoom climbs until [DIVE_CLIMB_END] then plunges; the turn toward the target ends at [DIVE_TURN_END]. */
    private fun dive(t: Double): Sample {
        val turn = smoothstep(min(t / DIVE_TURN_END, 1.0))
        val altitudeZoom = min(fromZoom, DIVE_ALTITUDE_ZOOM).toDouble()
        val zoom = if (t < DIVE_CLIMB_END) {
            logLerp(fromZoom.toDouble(), altitudeZoom, smoothstep(t / DIVE_CLIMB_END))
        } else {
            logLerp(altitudeZoom, toZoom.toDouble(), smootherstep((t - DIVE_CLIMB_END) / (1.0 - DIVE_CLIMB_END)))
        }
        return sampleAt(turn, zoom)
    }

    private fun sampleAt(progress: Double, zoom: Double): Sample {
        val point = slerp(progress)
        return Sample(
            lat = Math.toDegrees(asin(point[1].coerceIn(-1.0, 1.0))),
            lon = Math.toDegrees(atan2(point[0], point[2])),
            zoom = zoom.toFloat(),
        )
    }

    /** Spherical interpolation: the point [progress] of the way along the great-circle arc. */
    private fun slerp(progress: Double): DoubleArray {
        val sinAngle = sin(angle)
        if (sinAngle < 1e-9) return if (progress < 0.5) from else to
        val wFrom = sin((1 - progress) * angle) / sinAngle
        val wTo = sin(progress * angle) / sinAngle
        return DoubleArray(3) { wFrom * from[it] + wTo * to[it] }
    }

    companion object {
        const val BASE_SECONDS = 1.6
        const val SECONDS_PER_HALF_TURN = 1.6

        /** Share of the zoom given up mid-way on a cruise to the antipode (less for shorter hops). */
        private const val ZOOM_PULL_BACK = 0.65

        const val DIVE_BASE_SECONDS = 3.0
        const val DIVE_SECONDS_PER_HALF_TURN = 1.0

        /** Share of a dive spent climbing; the plunge takes the rest. */
        const val DIVE_CLIMB_END = 0.4

        /** Share of a dive after which the target faces the viewer. */
        const val DIVE_TURN_END = 0.7

        /** Zoom at the top of the climb (never zooms in while climbing). */
        const val DIVE_ALTITUDE_ZOOM = 0.9f

        private fun smoothstep(t: Double) = t * t * (3 - 2 * t)

        private fun smootherstep(t: Double) = t * t * t * (t * (6 * t - 15) + 10)

        /** Interpolates zoom geometrically, so every doubling of the zoom takes the same time. */
        private fun logLerp(a: Double, b: Double, t: Double) = exp(ln(a) + (ln(b) - ln(a)) * t)

        private fun unitVector(latDeg: Double, lonDeg: Double): DoubleArray {
            val lat = Math.toRadians(latDeg)
            val lon = Math.toRadians(lonDeg)
            return doubleArrayOf(cos(lat) * sin(lon), sin(lat), cos(lat) * cos(lon))
        }
    }
}
