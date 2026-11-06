package com.example.maps.globe.tiles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Fetches tile images off the GL thread, from three levels of cache: compressed tiles kept
 * in memory (instant, survives the GPU evicting a tile), then the disk cache (kept across
 * launches, works offline), then the network.
 * Requests are refreshed every frame; the most recent ones are served first and those no
 * longer asked for are dropped, so the visible area always loads before what was just left.
 */
internal class TileLoader(
    private val cacheDir: File,
    private val onTileReady: () -> Unit,
) {
    class Decoded(val tile: TileKey, val bitmap: Bitmap)

    /** Decoded tiles waiting to be uploaded by the GL thread. */
    val ready = ConcurrentLinkedQueue<Decoded>()

    /** Compressed JPEG bytes of recent tiles (about 20 KB each), shared by every request. */
    private val memory = object : LruCache<TileKey, ByteArray>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: TileKey, value: ByteArray) = value.size
    }

    // Request bookkeeping, shared by the GL thread and the workers: guarded by [lock].
    private val lock = Any()
    private val wanted = LinkedHashMap<TileKey, Long>() // tile -> last request time, newest last
    private val inFlight = HashSet<TileKey>()
    private val failedAt = HashMap<TileKey, Long>() // not retried before RETRY_AFTER_MS
    private var executor: ExecutorService? = null

    /** Bumped by [release]: workers started before it see the change and quit. */
    private var generation = 0

    /** Workers running or queued; a new one starts on demand, up to [WORKERS]. */
    private var workers = 0

    init {
        thread(name = "tile-cache-trim", isDaemon = true) { trimDiskCache() }
    }

    /** Asks for a tile; call again on every frame it is still needed. */
    fun request(tile: TileKey) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (tile in inFlight) return
            val failed = failedAt[tile]
            if (failed != null && now - failed < RETRY_AFTER_MS) return
            wanted.remove(tile)
            wanted[tile] = now
            if (workers < WORKERS) {
                workers++
                val pool = executor ?: Executors.newFixedThreadPool(WORKERS) { runnable ->
                    Thread(runnable, "tile-loader").apply { isDaemon = true }
                }.also { executor = it }
                val myGeneration = generation
                pool.execute { work(myGeneration) }
            }
        }
    }

    /** Stops the workers and forgets pending work; loading resumes on the next request. */
    fun release() {
        synchronized(lock) {
            executor?.shutdownNow()
            executor = null
            generation++
            workers = 0
            wanted.clear()
            inFlight.clear()
        }
        while (true) ready.poll()?.bitmap?.recycle() ?: break
    }

    /** Worker loop: takes the next wanted tile until none is left, then the worker ends. */
    private fun work(myGeneration: Int) {
        while (true) {
            val tile = synchronized(lock) {
                if (myGeneration != generation) return
                nextTile() ?: run {
                    workers--
                    return
                }
            }
            val bitmap = runCatching { load(tile) }.getOrNull()
            synchronized(lock) {
                if (myGeneration != generation) {
                    bitmap?.recycle()
                    return
                }
                inFlight -= tile
                if (bitmap == null) failedAt[tile] = System.currentTimeMillis() else failedAt -= tile
            }
            if (bitmap != null) {
                ready += Decoded(tile, bitmap)
                onTileReady()
            }
        }
    }

    /** The most recently requested tile that is still wanted; stale requests are dropped. */
    private fun nextTile(): TileKey? {
        val now = System.currentTimeMillis()
        wanted.entries.removeAll { now - it.value > STALE_AFTER_MS }
        val tile = wanted.keys.lastOrNull() ?: return null
        wanted.remove(tile)
        inFlight += tile
        return tile
    }

    /** Memory cache, then disk, then network; null when the tile could not be had. */
    private fun load(tile: TileKey): Bitmap? {
        val bytes = memory.get(tile) ?: run {
            val file = File(cacheDir, "${tile.z}/${tile.x}/${tile.y}.jpg")
            val stored = if (file.exists()) file.readBytes() else download(tile)?.also { save(file, it) }
            stored?.also { memory.put(tile, it) }
        }
        return bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    private fun download(tile: TileKey): ByteArray? {
        val connection = URL(TileSource.url(tile)).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "MapsGlobe/1.0 (Android)")
            if (connection.responseCode == HttpURLConnection.HTTP_OK) connection.inputStream.use { it.readBytes() } else null
        } finally {
            connection.disconnect()
        }
    }

    /** Written aside then renamed, so an interrupted write never leaves a broken tile. */
    private fun save(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val partial = File(file.parentFile, file.name + ".part")
        partial.writeBytes(bytes)
        partial.renameTo(file)
    }

    /** Keeps the disk cache bounded: oldest tiles go first. */
    private fun trimDiskCache() {
        val files = cacheDir.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_DISK_BYTES * 3 / 4) break
            total -= file.length()
            file.delete()
        }
    }

    private companion object {
        const val WORKERS = 4
        const val TIMEOUT_MS = 10_000

        /** A request not renewed for this long is dropped: the tile has left the screen. */
        const val STALE_AFTER_MS = 1_500L

        /** A tile that failed to load is not asked for again before this. */
        const val RETRY_AFTER_MS = 30_000L
        const val MEMORY_CACHE_BYTES = 32 * 1024 * 1024
        const val MAX_DISK_BYTES = 300L * 1024 * 1024
    }
}
