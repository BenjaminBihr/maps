package com.example.maps.globe

import kotlin.math.cos
import kotlin.math.sin

/**
 * Geographic helpers shared by the mesh, the vector lines and the camera.
 *
 * Globe space: unit sphere, +y = north pole, +z = (lat 0, lon 0), +x = (lat 0, lon 90°E).
 */
internal object GeoMath {

    /** Writes the globe-space position of (lat, lon) into `out[offset..offset + 2]`. */
    fun toUnitVector(latDeg: Double, lonDeg: Double, out: FloatArray, offset: Int) {
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)
        val cosLat = cos(lat)
        out[offset] = (cosLat * sin(lon)).toFloat()
        out[offset + 1] = sin(lat).toFloat()
        out[offset + 2] = (cosLat * cos(lon)).toFloat()
    }

    /** Wraps a longitude into [-180, 180). */
    fun normalizeLon(lonDeg: Double): Double {
        val wrapped = (lonDeg + 180.0) % 360.0
        return (if (wrapped < 0) wrapped + 360.0 else wrapped) - 180.0
    }

    /** Signed longitude difference from [fromDeg] to [toDeg] going the short way round. */
    fun shortestLonDelta(fromDeg: Double, toDeg: Double): Double = normalizeLon(toDeg - fromDeg)

    /**
     * Turns interleaved (lon, lat) polylines into GL_LINES vertex data: every segment
     * becomes two xyz vertices on the unit sphere.
     */
    fun polylinesToSegments(polylines: List<FloatArray>): FloatArray {
        val segmentCount = polylines.sumOf { (it.size / 2 - 1).coerceAtLeast(0) }
        val out = FloatArray(segmentCount * 2 * 3)
        var cursor = 0
        for (points in polylines) {
            for (i in 0 until points.size / 2 - 1) {
                toUnitVector(points[i * 2 + 1].toDouble(), points[i * 2].toDouble(), out, cursor)
                toUnitVector(points[i * 2 + 3].toDouble(), points[i * 2 + 2].toDouble(), out, cursor + 3)
                cursor += 6
            }
        }
        return out
    }
}
