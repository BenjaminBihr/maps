package com.example.maps.globe.tiles

import com.example.maps.globe.GlobeCamera
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TileSelectorTest {

    private fun view(lat: Double, lon: Double, zoom: Float): GlobeCamera.View {
        val width = 1344
        val height = 2992
        return GlobeCamera.View(
            centerLat = lat,
            centerLon = lon,
            zoom = zoom,
            radiusPx = width * GlobeCamera.BASE_RADIUS_FRACTION * zoom,
            viewportWidth = width,
            viewportHeight = height,
            tourIndex = -1,
            userLocation = null,
            showingUser = false,
            reveal = 1f,
        )
    }

    @Test
    fun `whole globe in view needs no tile, the base image is sharp enough`() {
        assertTrue(TileSelector.select(view(30.0, 0.0, 1f)).isEmpty())
    }

    @Test
    fun `zoomed in on Paris it picks the finest tiles around Paris only`() {
        val tiles = TileSelector.select(view(48.8566, 2.3522, 32f))
        assertTrue(TileKey(8, 129, 88) in tiles)
        assertTrue(tiles.all { it.z >= TileSource.MAX_LEVEL - 1 })
        assertTrue(tiles.size in 4..120)
        assertTrue(tiles.all { abs(it.west - 2.35) < 10 && abs(it.north - 48.85) < 10 })
    }

    @Test
    fun `half zoomed it uses intermediate levels on the visible side only`() {
        val tiles = TileSelector.select(view(0.0, 0.0, 4f))
        assertTrue(tiles.isNotEmpty())
        assertTrue(tiles.all { it.z in TileSelector.MIN_LEVEL..TileSource.MAX_LEVEL })
        // Nothing from the far side of the globe (around the antimeridian).
        assertTrue(tiles.none { it.west > 100 || it.east < -100 })
    }

    @Test
    fun `chosen tiles never overlap`() {
        val tiles = TileSelector.select(view(20.0, 10.0, 6f))
        for (tile in tiles) {
            var ancestor = tile.parent
            while (ancestor != null) {
                assertTrue(ancestor !in tiles)
                ancestor = ancestor.parent
            }
        }
    }
}
