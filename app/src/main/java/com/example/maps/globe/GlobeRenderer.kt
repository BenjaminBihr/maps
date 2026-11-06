package com.example.maps.globe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.example.maps.globe.tiles.TileLayer
import com.example.maps.globe.tiles.TileLoader
import com.example.maps.globe.tiles.TileMesh
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws the globe with an orthographic camera: a soft atmosphere halo, the textured
 * and lit sphere, then crisp coastlines and borders on top.
 *
 * Two looks, cross-faded: the political map lit by a studio light, or NASA satellite
 * imagery lit by the real sun, with city lights on the night side.
 */
internal class GlobeRenderer(
    private val context: Context,
    private val camera: GlobeCamera,
    private val density: Float,
    private val markers: List<GlobeCamera.GeoPoint>,
    private val requestRender: () -> Unit,
    /** Called on the GL thread after each frame with the state that was drawn. */
    private val onFrame: (GlobeCamera.View) -> Unit,
) : GLSurfaceView.Renderer {

    /** Coastlines or borders: one GL_LINES buffer, with a color for each look. */
    private class LineLayer(
        val buffer: Int,
        val vertexCount: Int,
        val mapColor: FloatArray,
        val satelliteColor: FloatArray,
        val widthDp: Float,
    )

    /** Set from any thread: whether the satellite look is wanted. */
    @Volatile
    var satelliteRequested = false

    /** Set from any thread: satellite lit by the real sun (day and night), or daylight everywhere. */
    @Volatile
    var sunlightRequested = true

    private var world: WorldData? = null

    /** Sharper satellite imagery streamed as Web Mercator tiles when zoomed in. */
    private val tileLayer = TileLayer(TileLoader(File(context.cacheDir, TILE_CACHE_DIR), requestRender))

    // Satellite look: textures loaded on first use (0 = not loaded), and the cross-fades.
    private var dayTexture = 0
    private var landTexture = 0
    private var nightTexture = 0
    private var cloudTexture = 0
    private var satelliteBlend = 0f // 0 = political map, 1 = satellite
    private var sunlightBlend = 1f // 0 = daylight everywhere, 1 = real sun
    private val satelliteLoading = AtomicBoolean(false)

    private class SatelliteImages(val day: Bitmap, val night: Bitmap, val clouds: Bitmap)

    /** Handed over by the decoding thread, uploaded by the next frame. */
    @Volatile
    private var decodedSatellite: SatelliteImages? = null
    private var maxTextureSize = TEXTURE_WIDTH

    // Per-frame scratch arrays, reused to avoid allocations while drawing.
    private val sunWorld = FloatArray(4)
    private val sunView = FloatArray(4)
    private val lineColor = FloatArray(4)

    // GL objects, rebuilt by onSurfaceCreated whenever the GL context is new.
    private var sphereProgram = 0
    private var lineProgram = 0
    private var haloProgram = 0
    private var markerProgram = 0
    private var markerVertices = 0
    private val pointSizeRange = FloatArray(2)
    private var clockSeconds = 0f // drives the marker pulse
    private var frameReveal = 1f

    private var sphereVertices = 0
    private var sphereIndices = 0
    private var sphereIndexCount = 0
    private var haloVertices = 0
    private var texture = 0 // political map
    private var lineLayers = emptyList<LineLayer>()
    private val lineWidthRange = FloatArray(2)

    private val projection = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private var lastFrameNanos = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // The world data is parsed once; GL objects are rebuilt for every new context.
        val data = world ?: context.assets.open(WORLD_ASSET).use { WorldData.read(it) }.also { world = it }

        GLES20.glClearColor(BACKGROUND[0], BACKGROUND[1], BACKGROUND[2], 1f)
        GLES20.glGetFloatv(GLES20.GL_ALIASED_LINE_WIDTH_RANGE, lineWidthRange, 0)

        sphereProgram = GlUtil.createProgram(Shaders.SPHERE_VERTEX, Shaders.SPHERE_FRAGMENT)
        lineProgram = GlUtil.createProgram(Shaders.LINE_VERTEX, Shaders.LINE_FRAGMENT)
        haloProgram = GlUtil.createProgram(Shaders.HALO_VERTEX, Shaders.HALO_FRAGMENT)
        markerProgram = GlUtil.createProgram(Shaders.MARKER_VERTEX, Shaders.MARKER_FRAGMENT)
        GLES20.glGetFloatv(GLES20.GL_ALIASED_POINT_SIZE_RANGE, pointSizeRange, 0)
        markerVertices = GlUtil.arrayBuffer(
            FloatArray(markers.size * 3).also { out ->
                markers.forEachIndexed { i, p -> GeoMath.toUnitVector(p.lat, p.lon, out, i * 3) }
            },
        )

        val mesh = SphereMesh.build(lonSegments = 192, latSegments = 96)
        sphereVertices = GlUtil.arrayBuffer(mesh.vertices)
        sphereIndices = GlUtil.elementBuffer(mesh.indices)
        sphereIndexCount = mesh.indices.size

        // A screen-facing square around the globe; the shader draws the glow inside it.
        haloVertices = GlUtil.arrayBuffer(
            floatArrayOf(-HALO_RADIUS, -HALO_RADIUS, HALO_RADIUS, -HALO_RADIUS, -HALO_RADIUS, HALO_RADIUS, HALO_RADIUS, HALO_RADIUS),
        )

        // On satellite imagery: faint white borders, no coastline (the photo shows it).
        lineLayers = listOf(
            lineLayer(
                data, WorldData.LineKind.BORDER, widthDp = 0.7f,
                mapColor = floatArrayOf(0.22f, 0.20f, 0.18f, 0.55f),
                satelliteColor = floatArrayOf(1f, 1f, 1f, 0.3f),
            ),
            lineLayer(
                data, WorldData.LineKind.COASTLINE, widthDp = 0.9f,
                mapColor = floatArrayOf(0.05f, 0.16f, 0.26f, 0.85f),
                satelliteColor = floatArrayOf(0.05f, 0.16f, 0.26f, 0f),
            ),
        )

        val maxSize = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0)
        maxTextureSize = maxSize[0]
        texture = GlUtil.uploadTexture(WorldTexture.render(data, width = min(TEXTURE_WIDTH, maxTextureSize)))
        landTexture = GlUtil.uploadTexture(WorldTexture.renderLandMask(data, width = min(LAND_MASK_WIDTH, maxTextureSize)))
        tileLayer.onSurfaceCreated()
        // A new GL context lost the satellite textures: they are decoded again when needed.
        dayTexture = 0
        nightTexture = 0
        cloudTexture = 0
        lastFrameNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        camera.setViewport(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else min((now - lastFrameNanos) / 1e9f, MAX_FRAME_SECONDS)
        lastFrameNanos = now
        clockSeconds = (clockSeconds + dt) % 3600f // wrapped to keep float precision
        val animating = camera.update(dt)
        val blending = updateSatellite(dt) or updateSunlight(dt)
        val view = camera.snapshot()
        frameReveal = view.reveal

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (view.radiusPx <= 0f) return

        // Units of globe radii: the globe is always the unit sphere, zoom only widens the frustum.
        val halfWidth = view.viewportWidth / 2f / view.radiusPx
        val halfHeight = view.viewportHeight / 2f / view.radiusPx
        Matrix.orthoM(projection, 0, -halfWidth, halfWidth, -halfHeight, halfHeight, -2f, 2f)
        // Turn the globe so the center point faces the viewer (see Projection).
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, view.centerLat.toFloat(), 1f, 0f, 0f)
        Matrix.rotateM(model, 0, -view.centerLon.toFloat(), 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, projection, 0, model, 0)

        // Direction of the sun in view space, for the day/night shading.
        val sun = Sun.subsolarPoint(System.currentTimeMillis())
        GeoMath.toUnitVector(sun.lat, sun.lon, sunWorld, 0)
        sunWorld[3] = 0f
        Matrix.multiplyMV(sunView, 0, model, 0, sunWorld, 0)

        drawHalo()
        drawSphere(view)
        drawLines()
        if (view.tourIndex in markers.indices) drawTourMarkers(view.tourIndex)

        onFrame(view)
        // Render-when-dirty: keep asking for frames while anything is still changing.
        if (animating || blending || tileLayer.hasPendingUploads) requestRender()
    }

    /** Any thread: stops tile downloads (the view is going away). */
    fun stopTileLoading() = tileLayer.stopLoading()

    /**
     * Loads the satellite textures the first time they are wanted (decoded off the GL
     * thread) and cross-fades toward the requested look. Returns true while fading.
     */
    private fun updateSatellite(dt: Float): Boolean {
        decodedSatellite?.let { images ->
            decodedSatellite = null
            dayTexture = GlUtil.uploadTexture(images.day)
            nightTexture = GlUtil.uploadTexture(images.night)
            cloudTexture = GlUtil.uploadTexture(images.clouds)
            satelliteLoading.set(false)
        }
        val ready = dayTexture != 0 && nightTexture != 0 && cloudTexture != 0
        if (satelliteRequested && !ready && satelliteLoading.compareAndSet(false, true)) {
            val sampleSize = if (maxTextureSize < TEXTURE_WIDTH) 2 else 1
            thread(name = "satellite-textures") {
                decodedSatellite = SatelliteImages(
                    day = decodeAsset(DAY_ASSET, sampleSize),
                    night = decodeAsset(NIGHT_ASSET, sampleSize),
                    clouds = decodeAsset(CLOUDS_ASSET, sampleSize),
                )
                requestRender()
            }
        }
        val target = if (satelliteRequested && ready) 1f else 0f
        if (satelliteBlend == target) return false
        val step = dt / SATELLITE_FADE_SECONDS
        satelliteBlend = if (target > satelliteBlend) min(target, satelliteBlend + step) else max(target, satelliteBlend - step)
        return true
    }

    /** Fades the real sun in or out. Returns true while fading. */
    private fun updateSunlight(dt: Float): Boolean {
        val target = if (sunlightRequested) 1f else 0f
        if (sunlightBlend == target) return false
        val step = dt / SATELLITE_FADE_SECONDS
        sunlightBlend = if (target > sunlightBlend) min(target, sunlightBlend + step) else max(target, sunlightBlend - step)
        return true
    }

    private fun decodeAsset(name: String, sampleSize: Int): Bitmap {
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return context.assets.open(name).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Cannot decode $name")
    }

    /** Every tour stop as a small dot, the current one in gold with a repeating pulse. */
    private fun drawTourMarkers(current: Int) {
        val position = beginMarkers(markerVertices)
        val pulse = (clockSeconds % PULSE_SECONDS) / PULSE_SECONDS
        drawPoints(0, markers.size, sizeDp = 7f, color = MARKER_WHITE, alpha = 0.9f)
        drawPoints(current, 1, sizeDp = 14f + 30f * pulse, color = MARKER_GOLD, alpha = 1f - pulse, ring = true)
        drawPoints(current, 1, sizeDp = 14f, color = MARKER_GOLD, alpha = 1f)
        endMarkers(position)
    }

    /** Sets up blending and the marker program; returns the position attribute to disable after. */
    private fun beginMarkers(buffer: Int): Int {
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDepthMask(false)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(markerProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(markerProgram, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(markerProgram, "uModel"), 1, false, model, 0)
        val position = GLES20.glGetAttribLocation(markerProgram, "aPosition")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 0, 0)
        return position
    }

    private fun endMarkers(position: Int) {
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDepthMask(true)
    }

    /** Draws markers [first] until [first] + [count] as dots, or as rings with [ring]. */
    private fun drawPoints(
        first: Int,
        count: Int,
        sizeDp: Float,
        color: FloatArray,
        alpha: Float,
        ring: Boolean = false,
    ) {
        val sizePx = (sizeDp * density).coerceIn(pointSizeRange[0], pointSizeRange[1])
        GLES20.glUniform1f(GLES20.glGetUniformLocation(markerProgram, "uPointSize"), sizePx)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(markerProgram, "uColor"), 1, color, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(markerProgram, "uAlpha"), alpha * frameReveal)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(markerProgram, "uRing"), if (ring) 1f else 0f)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(markerProgram, "uRingInner"),
            (1f - 4f * RING_WIDTH_DP * density / sizePx).coerceIn(0.05f, 0.95f),
        )
        GLES20.glDrawArrays(GLES20.GL_POINTS, first, count)
    }

    /** The atmosphere glow, drawn first: the opaque globe then covers its center. */
    private fun drawHalo() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(haloProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(haloProgram, "uProjection"), 1, false, projection, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(haloProgram, "uOuterRadius"), HALO_RADIUS)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(haloProgram, "uReveal"), frameReveal)
        val position = GLES20.glGetAttribLocation(haloProgram, "aPosition")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, haloVertices)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawSphere(view: GlobeCamera.View) {
        // Before binding this pass's textures: uploading new tiles binds textures too.
        val tiles = if (satelliteBlend > 0f && dayTexture != 0) tileLayer.tilesToDraw(view) else emptyList()

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LESS)
        GLES20.glDepthMask(true)
        GLES20.glUseProgram(sphereProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(sphereProgram, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(sphereProgram, "uModel"), 1, false, model, 0)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(sphereProgram, "uLightDir"), 1, LIGHT_DIRECTION, 0)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(sphereProgram, "uBackground"), 1, BACKGROUND, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(sphereProgram, "uReveal"), frameReveal)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(sphereProgram, "uSunDir"), 1, sunView, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(sphereProgram, "uSatellite"), smoothstep(satelliteBlend))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(sphereProgram, "uSunlight"), smoothstep(sunlightBlend))
        bindTexture(unit = 0, texture, "uTexture")
        bindTexture(unit = 1, dayTexture, "uDay")
        bindTexture(unit = 2, nightTexture, "uNight")
        bindTexture(unit = 3, landTexture, "uLand")
        bindTexture(unit = 4, cloudTexture, "uClouds")

        val position = GLES20.glGetAttribLocation(sphereProgram, "aPosition")
        val uv = GLES20.glGetAttribLocation(sphereProgram, "aUv")
        val dayUv = GLES20.glGetAttribLocation(sphereProgram, "aDayUv")
        val patch = GLES20.glGetUniformLocation(sphereProgram, "uPatch")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glEnableVertexAttribArray(dayUv)

        // The whole globe, with the bundled daylight image.
        val stride = SphereMesh.FLOATS_PER_VERTEX * Float.SIZE_BYTES
        GLES20.glUniform1f(patch, 0f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, sphereVertices)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, stride, 3 * Float.SIZE_BYTES)
        GLES20.glVertexAttribPointer(dayUv, 2, GLES20.GL_FLOAT, false, stride, 3 * Float.SIZE_BYTES)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, sphereIndices)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, sphereIndexCount, GLES20.GL_UNSIGNED_SHORT, 0)

        // Sharper satellite tiles painted over it, coarse to fine; the depth buffer keeps the
        // globe's own depth for the lines drawn next.
        if (tiles.isNotEmpty()) {
            val tileStride = TileMesh.FLOATS_PER_VERTEX * Float.SIZE_BYTES
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glUniform1f(patch, 1f)
            // Unit 1 is uDay: each tile's image stands in for the base daylight image.
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, tileLayer.indexBuffer)
            for (tile in tiles) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tile.texture)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, tile.vertices)
                GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, tileStride, 0)
                GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, tileStride, 3 * Float.SIZE_BYTES)
                GLES20.glVertexAttribPointer(dayUv, 2, GLES20.GL_FLOAT, false, tileStride, 5 * Float.SIZE_BYTES)
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, tileLayer.indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
            }
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        }

        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(uv)
        GLES20.glDisableVertexAttribArray(dayUv)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /** Binds texture [id] to [unit] and points the sphere shader's [sampler] at it. */
    private fun bindTexture(unit: Int, id: Int, sampler: String) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(sphereProgram, sampler), unit)
    }

    private fun smoothstep(t: Float) = t * t * (3f - 2f * t)

    private fun drawLines() {
        // Depth-tested so the far side stays hidden, but no depth writes so lines blend together.
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDepthMask(false)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(lineProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(lineProgram, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(lineProgram, "uModel"), 1, false, model, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lineProgram, "uLift"), LINE_LIFT)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lineProgram, "uReveal"), frameReveal)
        val colorLocation = GLES20.glGetUniformLocation(lineProgram, "uColor")
        val position = GLES20.glGetAttribLocation(lineProgram, "aPosition")
        GLES20.glEnableVertexAttribArray(position)
        val satellite = smoothstep(satelliteBlend)
        for (layer in lineLayers) {
            for (i in 0..3) lineColor[i] = layer.mapColor[i] + (layer.satelliteColor[i] - layer.mapColor[i]) * satellite
            if (lineColor[3] <= 0f) continue
            GLES20.glLineWidth((layer.widthDp * density).coerceIn(lineWidthRange[0], lineWidthRange[1]))
            GLES20.glUniform4fv(colorLocation, 1, lineColor, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, layer.buffer)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, layer.vertexCount)
        }
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDepthMask(true)
    }

    /** Uploads every line of one [kind] as a single GL_LINES buffer. */
    private fun lineLayer(
        data: WorldData,
        kind: WorldData.LineKind,
        widthDp: Float,
        mapColor: FloatArray,
        satelliteColor: FloatArray,
    ): LineLayer {
        val segments = GeoMath.polylinesToSegments(data.lines.filter { it.kind == kind }.map { it.points })
        return LineLayer(GlUtil.arrayBuffer(segments), segments.size / 3, mapColor, satelliteColor, widthDp)
    }

    private companion object {
        const val WORLD_ASSET = "world.bin"

        /** Public domain imagery (NASA, Tom Patterson), see tools/fetch_satellite_textures.sh. */
        const val DAY_ASSET = "earth_day.jpg"
        const val NIGHT_ASSET = "earth_night.jpg"
        const val CLOUDS_ASSET = "earth_clouds.jpg"
        const val TILE_CACHE_DIR = "tiles/bluemarble"
        const val SATELLITE_FADE_SECONDS = 0.8f

        /** Width of the political map texture (capped by the GPU's maximum texture size). */
        const val TEXTURE_WIDTH = 4096
        const val LAND_MASK_WIDTH = 2048

        /** Outer edge of the atmosphere glow, in globe radii. */
        const val HALO_RADIUS = 1.3f

        /** Lines float just above the surface so the depth test never hides them. */
        const val LINE_LIFT = 1.0015f

        /** Longest step an animation takes, so a stalled frame does not make it jump. */
        const val MAX_FRAME_SECONDS = 0.05f
        const val PULSE_SECONDS = 1.6f
        const val RING_WIDTH_DP = 3f
        val MARKER_WHITE = floatArrayOf(1f, 1f, 1f)
        val MARKER_GOLD = floatArrayOf(1f, 0.77f, 0.28f)

        /** Clear color; the sphere fades from it during the intro. */
        val BACKGROUND = floatArrayOf(0.02f, 0.03f, 0.06f)

        /** Light fixed to the viewer, from the upper left. */
        val LIGHT_DIRECTION = floatArrayOf(-0.35f, 0.45f, 0.82f).let { v ->
            val length = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            floatArrayOf(v[0] / length, v[1] / length, v[2] / length)
        }
    }
}
