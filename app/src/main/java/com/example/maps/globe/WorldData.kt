package com.example.maps.globe

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Country shapes and outlines decoded from `assets/world.bin`
 * (built by `tools/build_world_asset.py` from Natural Earth, public domain).
 * All coordinate arrays are interleaved (lon, lat) in degrees.
 */
class WorldData(val polygons: List<Polygon>, val lines: List<Line>) {

    /** One filled shape; the first ring is the outline, the others are holes. */
    class Polygon(val colorIndex: Int, val rings: List<FloatArray>)

    /** An open polyline: a stretch of coast, or a land border shared by two countries. */
    class Line(val kind: LineKind, val points: FloatArray)

    enum class LineKind { COASTLINE, BORDER }

    companion object {
        private val MAGIC = "GLB1".toByteArray()

        /** Parses the binary format documented in `tools/build_world_asset.py`. */
        fun read(input: InputStream): WorldData {
            val buffer = ByteBuffer.wrap(input.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(MAGIC.size)
            require(buffer.remaining() >= MAGIC.size) { "Not a world asset" }
            buffer.get(magic)
            require(magic.contentEquals(MAGIC)) { "Not a world asset" }

            val polygons = List(buffer.int) {
                val color = buffer.int
                Polygon(color, List(buffer.int) { readPoints(buffer) })
            }
            val lines = List(buffer.int) {
                val kind = if (buffer.int == 1) LineKind.BORDER else LineKind.COASTLINE
                Line(kind, readPoints(buffer))
            }
            return WorldData(polygons, lines)
        }

        private fun readPoints(buffer: ByteBuffer): FloatArray {
            val points = FloatArray(buffer.int * 2)
            buffer.asFloatBuffer().get(points)
            buffer.position(buffer.position() + points.size * Float.SIZE_BYTES)
            return points
        }
    }
}
