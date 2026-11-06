package com.example.maps.globe.tiles

import android.opengl.GLES20
import com.example.maps.globe.GlUtil
import com.example.maps.globe.GlobeCamera

/**
 * GL-thread side of the satellite tiles: uploads what the loader decoded, keeps a bounded
 * set of tile textures (least recently drawn evicted first) and picks what to draw this
 * frame. A tile still loading is stood in for by its closest loaded ancestor, blurrier.
 */
internal class TileLayer(private val loader: TileLoader) {

    /** A tile on the GPU: its image texture and the vertex buffer of its sphere patch. */
    class Tile(val key: TileKey, val texture: Int, val vertices: Int)

    private val tiles = LinkedHashMap<TileKey, Tile>(64, 0.75f, true) // access order = LRU

    /** Every patch has the same grid, so all tiles share one index buffer. */
    var indexBuffer = 0
        private set
    val indexCount = TileMesh.indices().size

    /** A new GL context: previous textures and buffers are gone. */
    fun onSurfaceCreated() {
        tiles.clear()
        indexBuffer = GlUtil.elementBuffer(TileMesh.indices())
    }

    /** Tiles to draw for [view], coarsest first so sharper ones paint over them. */
    fun tilesToDraw(view: GlobeCamera.View): List<Tile> {
        uploadReady()
        val draw = LinkedHashSet<TileKey>()
        for (key in TileSelector.select(view)) {
            if (key in tiles) {
                draw += key
            } else {
                loader.request(key)
                closestLoadedAncestor(key)?.let(draw::add)
            }
        }
        evictBeyondBudget(keep = draw)
        return draw.sortedBy { it.z }.map { tiles.getValue(it) }
    }

    /** More decoded tiles are waiting to be uploaded on a next frame. */
    val hasPendingUploads get() = loader.ready.isNotEmpty()

    /** Any thread: stops downloads; GL objects go with the GL context. */
    fun stopLoading() = loader.release()

    /** Stops at [TileSelector.MIN_LEVEL]: above it, the base image already shows. */
    private fun closestLoadedAncestor(key: TileKey): TileKey? {
        var ancestor = key.parent
        while (ancestor != null && ancestor.z >= TileSelector.MIN_LEVEL) {
            if (ancestor in tiles) return ancestor
            ancestor = ancestor.parent
        }
        return null
    }

    /** A few uploads per frame so a burst of arrivals never stalls the animation. */
    private fun uploadReady() {
        repeat(MAX_UPLOADS_PER_FRAME) {
            val decoded = loader.ready.poll() ?: return
            tiles[decoded.tile]?.let(::delete)
            tiles[decoded.tile] = Tile(
                key = decoded.tile,
                texture = GlUtil.uploadTexture(decoded.bitmap),
                vertices = GlUtil.arrayBuffer(TileMesh.vertices(decoded.tile)),
            )
        }
    }

    /** Frees the least recently drawn tiles beyond [MAX_TILES], never one drawn this frame. */
    private fun evictBeyondBudget(keep: Set<TileKey>) {
        if (tiles.size <= MAX_TILES) return
        val iterator = tiles.entries.iterator()
        while (tiles.size > MAX_TILES && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in keep) continue
            delete(entry.value)
            iterator.remove()
        }
    }

    private fun delete(tile: Tile) {
        GLES20.glDeleteTextures(1, intArrayOf(tile.texture), 0)
        GLES20.glDeleteBuffers(1, intArrayOf(tile.vertices), 0)
    }

    private companion object {
        /** About 50 MB of GPU memory with mipmaps. */
        const val MAX_TILES = 160
        const val MAX_UPLOADS_PER_FRAME = 4
    }
}
