package com.example.maps.globe.tiles

import com.example.maps.globe.GeoMath
import com.example.maps.globe.SphereMesh

/**
 * A curved patch of the sphere covering one tile. Vertex layout: x, y, z, then the
 * equirectangular uv of the whole-world textures (clouds, night, land mask), then the
 * uv inside the tile image. Rows are evenly spaced in Mercator so the tile image maps
 * linearly.
 */
internal object TileMesh {

    /** Grid cells per side: enough for a large tile to follow the curve of the sphere. */
    const val SEGMENTS = 16
    const val FLOATS_PER_VERTEX = 7

    /** Vertex data of [tile]'s patch: a ([segments] + 1)² grid, row by row from the north. */
    fun vertices(tile: TileKey, segments: Int = SEGMENTS): FloatArray {
        val count = 1 shl tile.z
        val out = FloatArray((segments + 1) * (segments + 1) * FLOATS_PER_VERTEX)
        var cursor = 0
        for (row in 0..segments) {
            val tileV = row.toDouble() / segments
            val lat = Mercator.latitude((tile.y + tileV) / count)
            for (col in 0..segments) {
                val tileU = col.toDouble() / segments
                val lon = tile.west + (tile.east - tile.west) * tileU
                GeoMath.toUnitVector(lat, lon, out, cursor)
                out[cursor + 3] = ((lon + 180.0) / 360.0).toFloat()
                out[cursor + 4] = ((90.0 - lat) / 180.0).toFloat()
                out[cursor + 5] = tileU.toFloat()
                out[cursor + 6] = tileV.toFloat()
                cursor += FLOATS_PER_VERTEX
            }
        }
        return out
    }

    fun indices(segments: Int = SEGMENTS): ShortArray = SphereMesh.gridIndices(segments, segments)
}
