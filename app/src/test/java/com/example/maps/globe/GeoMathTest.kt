package com.example.maps.globe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GeoMathTest {

    private fun vector(lat: Double, lon: Double): FloatArray =
        FloatArray(3).also { GeoMath.toUnitVector(lat, lon, it, 0) }

    @Test
    fun `lat 0 lon 0 faces the viewer on +z`() {
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), vector(0.0, 0.0), 1e-6f)
    }

    @Test
    fun `east is +x and north is +y`() {
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), vector(0.0, 90.0), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), vector(90.0, 42.0), 1e-6f)
    }

    @Test
    fun `normalizeLon wraps into -180 until 180`() {
        assertEquals(-170.0, GeoMath.normalizeLon(190.0), 1e-9)
        assertEquals(170.0, GeoMath.normalizeLon(-190.0), 1e-9)
        assertEquals(-180.0, GeoMath.normalizeLon(180.0), 1e-9)
        assertEquals(10.0, GeoMath.normalizeLon(730.0), 1e-9)
    }

    @Test
    fun `shortestLonDelta goes across the antimeridian when shorter`() {
        assertEquals(20.0, GeoMath.shortestLonDelta(170.0, -170.0), 1e-9)
        assertEquals(-20.0, GeoMath.shortestLonDelta(-170.0, 170.0), 1e-9)
        assertEquals(30.0, GeoMath.shortestLonDelta(10.0, 40.0), 1e-9)
    }

    @Test
    fun `polylines become independent segment pairs on the unit sphere`() {
        val segments = GeoMath.polylinesToSegments(
            listOf(floatArrayOf(0f, 0f, 90f, 0f, 90f, 90f)),
        )
        // 3 points -> 2 segments -> 4 vertices -> 12 floats
        assertEquals(12, segments.size)
        assertArrayEquals(
            floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
            segments,
            1e-6f,
        )
    }
}
