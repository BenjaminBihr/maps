package com.example.maps.globe.tiles

import com.example.maps.globe.GlobeCamera
import com.example.maps.globe.Projection
import kotlin.math.PI
import kotlin.math.cos

/**
 * Level-of-detail choice, like a web map: walks the tile quadtree and keeps, for every part
 * of the globe on screen, the coarsest tile that is still sharp (shown at most
 * [TARGET_TILE_PX] wide for its 256 px). Tiles off screen or on the far side are skipped.
 */
internal object TileSelector {

    /** Below this level the bundled 2048 px base image is as sharp, so no tile is needed. */
    const val MIN_LEVEL = 4

    /** The walk starts from this level's 4 x 4 tiles; coarser ones would never be kept anyway. */
    private const val START_LEVEL = 2

    /** A tile is split once it would show wider than this, i.e. magnified past 1.75x. */
    private const val TARGET_TILE_PX = 448.0

    /** Tiles just off screen count as visible, so a small pan reveals no gap. */
    private const val MARGIN_PX = 64.0

    /** The screen is probed on a (PROBES + 1) x (PROBES + 1) grid of points. */
    private const val PROBES = 6

    /** Tiles that cover the visible part of the globe in [view], each at the level its on-screen size needs. */
    fun select(view: GlobeCamera.View): List<TileKey> {
        if (view.radiusPx <= 0f) return emptyList()
        val projection = Projection(
            view.centerLat, view.centerLon, view.radiusPx.toDouble(), view.viewportWidth, view.viewportHeight,
        )
        val probes = screenProbes(projection, view)
        val selected = ArrayList<TileKey>()
        val count = 1 shl START_LEVEL
        for (x in 0 until count) {
            for (y in 0 until count) visit(TileKey(START_LEVEL, x, y), view, projection, probes, selected)
        }
        return selected
    }

    /** Keeps [tile] if it is sharp enough, otherwise recurses into its children. */
    private fun visit(
        tile: TileKey,
        view: GlobeCamera.View,
        projection: Projection,
        probes: List<GlobeCamera.GeoPoint>,
        selected: MutableList<TileKey>,
    ) {
        if (!isOnScreen(tile, view, projection, probes)) return
        if (tile.z < TileSource.MAX_LEVEL && projectedWidthPx(tile, view) > TARGET_TILE_PX) {
            tile.children().forEach { visit(it, view, projection, probes, selected) }
        } else if (tile.z >= MIN_LEVEL) {
            selected += tile
        }
    }

    /** Widest the tile can appear: Mercator tiles are square on the ground, narrower towards the poles. */
    private fun projectedWidthPx(tile: TileKey, view: GlobeCamera.View): Double {
        val latClosestToEquator = when {
            tile.south > 0 -> tile.south
            tile.north < 0 -> tile.north
            else -> 0.0
        }
        return view.radiusPx * 2 * PI / (1 shl tile.z) * cos(Math.toRadians(latClosestToEquator))
    }

    /**
     * One of 3 x 3 sample points of the tile shows on the near side, within the screen;
     * or the tile holds one of the screen probes (a tile larger than the screen).
     */
    private fun isOnScreen(
        tile: TileKey,
        view: GlobeCamera.View,
        projection: Projection,
        probes: List<GlobeCamera.GeoPoint>,
    ): Boolean {
        val point = DoubleArray(3)
        for (i in 0..2) {
            val lat = tile.north + (tile.south - tile.north) * i / 2
            for (j in 0..2) {
                projection.toView(lat, tile.west + (tile.east - tile.west) * j / 2, point)
                if (point[2] <= 0) continue
                val x = projection.screenX(point)
                val y = projection.screenY(point)
                if (x > -MARGIN_PX && x < view.viewportWidth + MARGIN_PX && y > -MARGIN_PX && y < view.viewportHeight + MARGIN_PX) {
                    return true
                }
            }
        }
        return probes.any { tile.contains(it.lat, it.lon) }
    }

    /** Geo points under a grid of screen positions, to catch tiles bigger than the screen. */
    private fun screenProbes(projection: Projection, view: GlobeCamera.View): List<GlobeCamera.GeoPoint> {
        val probes = ArrayList<GlobeCamera.GeoPoint>()
        for (i in 0..PROBES) {
            for (j in 0..PROBES) {
                projection.unproject(
                    view.viewportWidth * j.toDouble() / PROBES,
                    view.viewportHeight * i.toDouble() / PROBES,
                )?.let(probes::add)
            }
        }
        return probes
    }
}
