package com.example.maps.globe.tiles

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * A Web Mercator tile, the same grid as Google Maps: zoom level [z], column [x] counted
 * eastward from the antimeridian, row [y] counted southward from the top (85.05°N).
 */
data class TileKey(val z: Int, val x: Int, val y: Int) {

    /** Tiles per side at this level. */
    private val count get() = 1 shl z

    // Bounds in degrees. Equal steps in longitude, Mercator-spaced in latitude.
    val west get() = x * 360.0 / count - 180.0
    val east get() = (x + 1) * 360.0 / count - 180.0
    val north get() = Mercator.latitude(y.toDouble() / count)
    val south get() = Mercator.latitude((y + 1).toDouble() / count)

    val parent: TileKey? get() = if (z == 0) null else TileKey(z - 1, x / 2, y / 2)

    /** The four tiles of the next level that cover this one. */
    fun children() = listOf(
        TileKey(z + 1, 2 * x, 2 * y),
        TileKey(z + 1, 2 * x + 1, 2 * y),
        TileKey(z + 1, 2 * x, 2 * y + 1),
        TileKey(z + 1, 2 * x + 1, 2 * y + 1),
    )

    fun contains(lat: Double, lon: Double) = lon >= west && lon < east && lat <= north && lat >= south

    companion object {
        /** The level-[z] tile holding (lat, lon); points beyond the Mercator limit go to the edge row. */
        fun at(lat: Double, lon: Double, z: Int): TileKey {
            val count = 1 shl z
            val x = floor((lon + 180.0) / 360.0 * count).toInt().coerceIn(0, count - 1)
            val y = floor(Mercator.y(lat) * count).toInt().coerceIn(0, count - 1)
            return TileKey(z, x, y)
        }
    }
}

/** Web Mercator rows: y = 0 at the top edge (85.05°N), 1 at the bottom edge. */
internal object Mercator {
    const val MAX_LAT = 85.05112877980659

    fun latitude(y: Double): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * y))))

    /** Inverse of [latitude]; latitudes beyond [MAX_LAT] are clamped. */
    fun y(latDeg: Double): Double {
        val lat = Math.toRadians(latDeg.coerceIn(-MAX_LAT, MAX_LAT))
        return (1 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2
    }
}
