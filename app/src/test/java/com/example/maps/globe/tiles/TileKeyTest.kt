package com.example.maps.globe.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileKeyTest {

    @Test
    fun `the root tile covers the Web Mercator world`() {
        val root = TileKey(0, 0, 0)
        assertEquals(-180.0, root.west, 1e-9)
        assertEquals(180.0, root.east, 1e-9)
        assertEquals(Mercator.MAX_LAT, root.north, 1e-9)
        assertEquals(-Mercator.MAX_LAT, root.south, 1e-9)
    }

    @Test
    fun `Paris is in the same zoom 8 tile as on Google Maps`() {
        assertEquals(TileKey(8, 129, 88), TileKey.at(48.8566, 2.3522, 8))
        assertTrue(TileKey(8, 129, 88).contains(48.8566, 2.3522))
        assertFalse(TileKey(8, 129, 88).contains(40.7, -74.0))
    }

    @Test
    fun `parents and children nest`() {
        val tile = TileKey(5, 17, 11)
        assertEquals(TileKey(4, 8, 5), tile.parent)
        assertNull(TileKey(0, 0, 0).parent)
        val children = tile.children()
        assertEquals(4, children.size)
        assertTrue(children.all { it.parent == tile })
        assertEquals(tile.west, children.minOf { it.west }, 1e-9)
        assertEquals(tile.north, children.maxOf { it.north }, 1e-9)
    }

    @Test
    fun `mercator rows map back to latitudes`() {
        for (lat in listOf(-80.0, -45.0, 0.0, 30.0, 85.0)) {
            assertEquals(lat, Mercator.latitude(Mercator.y(lat)), 1e-9)
        }
    }
}
