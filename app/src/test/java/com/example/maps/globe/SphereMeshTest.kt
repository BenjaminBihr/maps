package com.example.maps.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class SphereMeshTest {

    private val mesh = SphereMesh.build(lonSegments = 8, latSegments = 4)

    @Test
    fun `has one vertex per grid node including the texture seam`() {
        assertEquals(9 * 5 * SphereMesh.FLOATS_PER_VERTEX, mesh.vertices.size)
        assertEquals(8 * 4 * 6, mesh.indices.size)
    }

    @Test
    fun `every vertex lies on the unit sphere`() {
        for (i in mesh.vertices.indices step SphereMesh.FLOATS_PER_VERTEX) {
            val x = mesh.vertices[i]
            val y = mesh.vertices[i + 1]
            val z = mesh.vertices[i + 2]
            assertEquals(1f, sqrt(x * x + y * y + z * z), 1e-5f)
        }
    }

    @Test
    fun `texture coordinates follow the equirectangular layout`() {
        // first vertex: north pole, lon -180 -> u 0, v 0
        assertEquals(1f, mesh.vertices[1], 1e-6f)
        assertEquals(0f, mesh.vertices[3], 0f)
        assertEquals(0f, mesh.vertices[4], 0f)
        // last vertex: south pole, lon 180 -> u 1, v 1
        val last = mesh.vertices.size - SphereMesh.FLOATS_PER_VERTEX
        assertEquals(-1f, mesh.vertices[last + 1], 1e-6f)
        assertEquals(1f, mesh.vertices[last + 3], 0f)
        assertEquals(1f, mesh.vertices[last + 4], 0f)
    }

    @Test
    fun `indices stay inside the vertex buffer`() {
        val vertexCount = mesh.vertices.size / SphereMesh.FLOATS_PER_VERTEX
        assertTrue(mesh.indices.all { it.toInt() and 0xFFFF < vertexCount })
    }
}
