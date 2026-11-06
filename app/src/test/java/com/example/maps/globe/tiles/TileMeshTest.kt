package com.example.maps.globe.tiles

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.asin
import kotlin.math.atan2

class TileMeshTest {

    private val tile = TileKey(6, 32, 22)
    private val vertices = TileMesh.vertices(tile, segments = 4)
    private val stride = TileMesh.FLOATS_PER_VERTEX

    private fun latLon(i: Int): Pair<Double, Double> {
        val o = i * stride
        val lat = Math.toDegrees(asin(vertices[o + 1].toDouble()))
        val lon = Math.toDegrees(atan2(vertices[o].toDouble(), vertices[o + 2].toDouble()))
        return lat to lon
    }

    @Test
    fun `corners sit on the tile bounds with tile uv 0 to 1`() {
        assertEquals(5 * 5 * stride, vertices.size)
        val (lat0, lon0) = latLon(0)
        assertEquals(tile.north, lat0, 1e-4)
        assertEquals(tile.west, lon0, 1e-4)
        assertEquals(0f, vertices[5], 0f)
        assertEquals(0f, vertices[6], 0f)

        val last = 24
        val (lat1, lon1) = latLon(last)
        assertEquals(tile.south, lat1, 1e-4)
        assertEquals(tile.east, lon1, 1e-4)
        assertEquals(1f, vertices[last * stride + 5], 0f)
        assertEquals(1f, vertices[last * stride + 6], 0f)
    }

    @Test
    fun `map uv matches the equirectangular textures`() {
        val (lat, lon) = latLon(12)
        assertEquals(((lon + 180) / 360).toFloat(), vertices[12 * stride + 3], 1e-5f)
        assertEquals(((90 - lat) / 180).toFloat(), vertices[12 * stride + 4], 1e-5f)
    }

    @Test
    fun `rows are evenly spaced in Mercator so the tile image is not distorted`() {
        val (latMid, _) = latLon(2 * 5)
        val expected = Mercator.latitude((tile.y + 0.5) / (1 shl tile.z))
        assertEquals(expected, latMid, 1e-4)
    }

    @Test
    fun `tile urls follow the z y x order of NASA GIBS`() {
        assertEquals(true, TileSource.url(TileKey(8, 129, 88)).endsWith("/GoogleMapsCompatible_Level8/8/88/129.jpeg"))
    }
}
