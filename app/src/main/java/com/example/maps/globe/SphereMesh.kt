package com.example.maps.globe

/**
 * Latitude/longitude grid sphere. Vertex layout: x, y, z, u, v.
 * The seam column is duplicated so u runs cleanly from 0 (lon -180) to 1 (lon 180),
 * and v runs from 0 (north pole) to 1 (south pole), matching an equirectangular texture.
 */
internal class SphereMesh(val vertices: FloatArray, val indices: ShortArray) {

    companion object {
        const val FLOATS_PER_VERTEX = 5

        fun build(lonSegments: Int, latSegments: Int): SphereMesh {
            val columns = lonSegments + 1
            val rows = latSegments + 1
            require(columns * rows <= 0xFFFF) { "Too many vertices for 16-bit indices" }

            val vertices = FloatArray(columns * rows * FLOATS_PER_VERTEX)
            var cursor = 0
            for (row in 0 until rows) {
                val v = row.toFloat() / latSegments
                val lat = 90.0 - 180.0 * v
                for (col in 0 until columns) {
                    val u = col.toFloat() / lonSegments
                    GeoMath.toUnitVector(lat, -180.0 + 360.0 * u, vertices, cursor)
                    vertices[cursor + 3] = u
                    vertices[cursor + 4] = v
                    cursor += FLOATS_PER_VERTEX
                }
            }

            return SphereMesh(vertices, gridIndices(lonSegments, latSegments))
        }

        /** Two triangles per cell of a (columns + 1) x (rows + 1) vertex grid stored row by row. */
        fun gridIndices(columns: Int, rows: Int): ShortArray {
            val stride = columns + 1
            val indices = ShortArray(columns * rows * 6)
            var cursor = 0
            for (row in 0 until rows) {
                for (col in 0 until columns) {
                    val topLeft = row * stride + col
                    val bottomLeft = topLeft + stride
                    indices[cursor++] = topLeft.toShort()
                    indices[cursor++] = bottomLeft.toShort()
                    indices[cursor++] = (topLeft + 1).toShort()
                    indices[cursor++] = (topLeft + 1).toShort()
                    indices[cursor++] = bottomLeft.toShort()
                    indices[cursor++] = (bottomLeft + 1).toShort()
                }
            }
            return indices
        }
    }
}
