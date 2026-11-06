package com.example.maps.globe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WorldDataTest {

    @Test
    fun `reads polygons with holes and typed lines`() {
        val buffer = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("GLB1".toByteArray())
        buffer.putInt(1) // polygons
        buffer.putInt(3).putInt(2) // color 3, two rings
        buffer.putInt(2).putFloat(1f).putFloat(2f).putFloat(3f).putFloat(4f)
        buffer.putInt(1).putFloat(5f).putFloat(6f)
        buffer.putInt(1) // lines
        buffer.putInt(1).putInt(2).putFloat(7f).putFloat(8f).putFloat(9f).putFloat(10f)

        val world = WorldData.read(ByteArrayInputStream(buffer.array(), 0, buffer.position()))

        assertEquals(1, world.polygons.size)
        assertEquals(3, world.polygons[0].colorIndex)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), world.polygons[0].rings[0], 0f)
        assertArrayEquals(floatArrayOf(5f, 6f), world.polygons[0].rings[1], 0f)
        assertEquals(WorldData.LineKind.BORDER, world.lines[0].kind)
        assertArrayEquals(floatArrayOf(7f, 8f, 9f, 10f), world.lines[0].points, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a file that is not a world asset`() {
        WorldData.read(ByteArrayInputStream("NOPE....".toByteArray()))
    }

    @Test
    fun `bundled asset parses and covers the whole world`() {
        val asset = File("src/main/assets/world.bin")
        val world = asset.inputStream().use { WorldData.read(it) }

        assertEquals(1620, world.polygons.size)
        val lons = world.polygons.flatMap { p -> p.rings.flatMap { r -> r.filterIndexed { i, _ -> i % 2 == 0 } } }
        assertEquals(-180f, lons.min(), 0.01f)
        assertEquals(180f, lons.max(), 0.01f)
        assertEquals(true, world.lines.any { it.kind == WorldData.LineKind.COASTLINE })
        assertEquals(true, world.lines.any { it.kind == WorldData.LineKind.BORDER })
    }
}
