package com.example.maps.globe

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Low-precision solar ephemeris (Astronomical Almanac), accurate to about 0.01 degree. */
internal object Sun {

    private const val MILLIS_PER_DAY = 86_400_000.0
    private const val J2000_EPOCH_DAYS = 10_957.5 // 2000-01-01T12:00Z in days since 1970

    /** The point on Earth where the sun is at the zenith at [epochMillis] (UTC). */
    fun subsolarPoint(epochMillis: Long): GlobeCamera.GeoPoint {
        val n = epochMillis / MILLIS_PER_DAY - J2000_EPOCH_DAYS
        val meanLongitude = 280.460 + 0.9856474 * n
        val meanAnomaly = Math.toRadians(357.528 + 0.9856003 * n)
        val eclipticLongitude = Math.toRadians(
            meanLongitude + 1.915 * sin(meanAnomaly) + 0.020 * sin(2 * meanAnomaly),
        )
        val obliquity = Math.toRadians(23.439 - 0.0000004 * n)

        val declination = asin(sin(obliquity) * sin(eclipticLongitude))
        val rightAscension = atan2(cos(obliquity) * sin(eclipticLongitude), cos(eclipticLongitude))
        val greenwichSiderealTime = 280.46061837 + 360.98564736629 * n

        return GlobeCamera.GeoPoint(
            lat = Math.toDegrees(declination),
            lon = GeoMath.normalizeLon(Math.toDegrees(rightAscension) - greenwichSiderealTime),
        )
    }
}
