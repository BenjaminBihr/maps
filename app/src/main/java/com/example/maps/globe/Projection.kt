package com.example.maps.globe

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Orthographic projection of the globe for one camera view: the point (centerLat, centerLon)
 * faces the viewer at the middle of a [width] x [height] viewport, the globe is [radiusPx] wide.
 * Matches the renderer's model rotation Rx(centerLat) * Ry(-centerLon).
 */
internal class Projection(
    centerLat: Double,
    centerLon: Double,
    private val radiusPx: Double,
    private val width: Int,
    private val height: Int,
) {
    private val sinLat0 = sin(Math.toRadians(centerLat))
    private val cosLat0 = cos(Math.toRadians(centerLat))
    private val sinLon0 = sin(Math.toRadians(centerLon))
    private val cosLon0 = cos(Math.toRadians(centerLon))

    /** View-space unit vector of a geo point into [out]: x right, y up, z toward the viewer. */
    fun toView(latDeg: Double, lonDeg: Double, out: DoubleArray) {
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)
        val x = cos(lat) * sin(lon)
        val y = sin(lat)
        val z = cos(lat) * cos(lon)
        val x1 = x * cosLon0 - z * sinLon0
        val z1 = x * sinLon0 + z * cosLon0
        out[0] = x1
        out[1] = y * cosLat0 - z1 * sinLat0
        out[2] = y * sinLat0 + z1 * cosLat0
    }

    /** Screen pixel of a [toView] result (y grows downward, as on Android). */
    fun screenX(view: DoubleArray) = width / 2.0 + view[0] * radiusPx

    fun screenY(view: DoubleArray) = height / 2.0 - view[1] * radiusPx

    /** The geo point under a screen pixel, or null off the globe. */
    fun unproject(xPx: Double, yPx: Double): GlobeCamera.GeoPoint? {
        if (radiusPx <= 0.0) return null
        val x = (xPx - width / 2.0) / radiusPx
        val y = (height / 2.0 - yPx) / radiusPx
        val r2 = x * x + y * y
        if (r2 > 1.0) return null
        val z = sqrt(1.0 - r2)
        // Undo Rx(lat) * Ry(-lon): apply Rx(-lat) then Ry(lon).
        val y1 = y * cosLat0 + z * sinLat0
        val z1 = -y * sinLat0 + z * cosLat0
        val x2 = x * cosLon0 + z1 * sinLon0
        val z2 = -x * sinLon0 + z1 * cosLon0
        return GlobeCamera.GeoPoint(
            lat = Math.toDegrees(asin(y1.coerceIn(-1.0, 1.0))),
            lon = GeoMath.normalizeLon(Math.toDegrees(atan2(x2, z2))),
        )
    }
}
