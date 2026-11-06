package com.example.maps.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GlobeCameraTest {

    private lateinit var camera: GlobeCamera

    @Before
    fun setUp() {
        camera = GlobeCamera(initialLat = 0.0, initialLon = 0.0, autoRotate = false)
        camera.setViewport(1000, 2000)
    }

    @Test
    fun `globe radius fits the smallest viewport side and scales with zoom`() {
        assertEquals(1000 * GlobeCamera.BASE_RADIUS_FRACTION, camera.snapshot().radiusPx, 1e-3f)
        camera.zoomBy(2f)
        assertEquals(2000 * GlobeCamera.BASE_RADIUS_FRACTION, camera.snapshot().radiusPx, 1e-3f)
    }

    @Test
    fun `dragging right moves the surface right so the center goes west`() {
        val radius = camera.snapshot().radiusPx
        camera.drag(dxPx = radius * (Math.PI / 4).toFloat(), dyPx = 0f)
        assertEquals(-45.0, camera.snapshot().centerLon, 1e-3)
    }

    @Test
    fun `dragging down shows the north`() {
        val radius = camera.snapshot().radiusPx
        camera.drag(dxPx = 0f, dyPx = radius * (Math.PI / 6).toFloat())
        assertEquals(30.0, camera.snapshot().centerLat, 1e-3)
    }

    @Test
    fun `latitude is clamped so the poles never flip`() {
        camera.drag(dxPx = 0f, dyPx = 1_000_000f)
        assertEquals(GlobeCamera.MAX_LAT, camera.snapshot().centerLat, 1e-9)
        camera.drag(dxPx = 0f, dyPx = -1_000_000f)
        assertEquals(-GlobeCamera.MAX_LAT, camera.snapshot().centerLat, 1e-9)
    }

    @Test
    fun `zoom is clamped`() {
        camera.zoomBy(1000f)
        assertEquals(GlobeCamera.MAX_ZOOM, camera.snapshot().zoom, 1e-6f)
        camera.zoomBy(0.0001f)
        assertEquals(GlobeCamera.MIN_ZOOM, camera.snapshot().zoom, 1e-6f)
    }

    @Test
    fun `satellite view zooms much deeper, and leaving it glides back out`() {
        camera.setMaxZoom(GlobeCamera.SATELLITE_MAX_ZOOM)
        camera.zoomBy(1000f)
        assertEquals(GlobeCamera.SATELLITE_MAX_ZOOM, camera.snapshot().zoom, 1e-6f)

        camera.setMaxZoom(GlobeCamera.MAX_ZOOM)
        assertTrue(camera.update(0.016f))
        repeat(500) { camera.update(0.016f) }
        assertEquals(GlobeCamera.MAX_ZOOM, camera.snapshot().zoom, 1e-2f)
    }

    @Test
    fun `fling keeps spinning then comes to rest`() {
        camera.fling(velocityXPx = 2000f, velocityYPx = 0f)
        assertTrue(camera.update(0.016f))
        assertTrue(camera.snapshot().centerLon < 0.0)
        repeat(1000) { camera.update(0.016f) }
        assertFalse(camera.update(0.016f))
    }

    @Test
    fun `touching the globe stops every motion`() {
        val spinning = GlobeCamera(initialLat = 0.0, initialLon = 0.0, autoRotate = true)
        spinning.setViewport(1000, 1000)
        spinning.fling(5000f, 5000f)
        spinning.stopMotion()
        assertFalse(spinning.update(0.016f))
    }

    @Test
    fun `auto rotation turns the globe eastward like the real Earth`() {
        val spinning = GlobeCamera(initialLat = 0.0, initialLon = 0.0, autoRotate = true)
        spinning.setViewport(1000, 1000)
        assertTrue(spinning.update(1f))
        assertEquals(-GlobeCamera.AUTO_ROTATE_DEG_PER_S, spinning.snapshot().centerLon, 1e-6)
    }

    @Test
    fun `screen center maps to the globe center`() {
        val camera = GlobeCamera(initialLat = 48.85, initialLon = 2.35, autoRotate = false)
        camera.setViewport(1000, 2000)
        val geo = camera.screenToGeo(500f, 1000f)
        assertNotNull(geo)
        assertEquals(48.85, geo!!.lat, 1e-6)
        assertEquals(2.35, geo.lon, 1e-6)
    }

    @Test
    fun `right edge of the disc is 90 degrees east`() {
        val radius = camera.snapshot().radiusPx
        val geo = camera.screenToGeo(500f + radius * 0.999999f, 1000f)
        assertNotNull(geo)
        assertEquals(0.0, geo!!.lat, 1e-3)
        assertEquals(90.0, geo.lon, 0.2)
    }

    @Test
    fun `top of the disc is north`() {
        val radius = camera.snapshot().radiusPx
        val geo = camera.screenToGeo(500f, 1000f - radius * 0.5f)
        assertEquals(30.0, geo!!.lat, 1e-3)
    }

    @Test
    fun `points outside the disc hit nothing`() {
        assertNull(camera.screenToGeo(0f, 0f))
    }

    @Test
    fun `double tap flies to the tapped point and zooms in`() {
        val radius = camera.snapshot().radiusPx
        camera.focusOn(500f + radius * 0.5f, 1000f)
        repeat(500) { camera.update(0.016f) }
        val view = camera.snapshot()
        assertEquals(30.0, view.centerLon, 1e-2)
        assertEquals(0.0, view.centerLat, 1e-2)
        assertEquals(2f, view.zoom, 1e-2f)
        assertFalse(camera.update(0.016f))
    }

    @Test
    fun `double tap at max zoom zooms back out`() {
        camera.zoomBy(GlobeCamera.MAX_ZOOM)
        camera.focusOn(500f, 1000f)
        repeat(500) { camera.update(0.016f) }
        assertEquals(1f, camera.snapshot().zoom, 1e-2f)
    }

    private val stops = listOf(
        GlobeCamera.GeoPoint(0.0, 100.0),
        GlobeCamera.GeoPoint(10.0, 20.0),
        GlobeCamera.GeoPoint(-30.0, -60.0),
    )

    private fun GlobeCamera.run(seconds: Double) {
        var elapsed = 0.0
        while (elapsed < seconds) {
            update(0.016f)
            elapsed += 0.016
        }
    }

    @Test
    fun `tour starts with the nearest stop and settles on it zoomed in`() {
        camera.startTour(stops)
        assertEquals(1, camera.snapshot().tourIndex)

        camera.run(Flight.BASE_SECONDS + Flight.SECONDS_PER_HALF_TURN * 0.2)
        val view = camera.snapshot()
        assertEquals(10.0, view.centerLat, 1e-6)
        assertEquals(20.0, view.centerLon, 1e-6)
        assertEquals(GlobeCamera.TOUR_ZOOM, view.zoom, 1e-5f)
        assertEquals(1, view.tourIndex)
    }

    @Test
    fun `tour moves on after the pause and loops forever`() {
        camera.startTour(stops)
        camera.run(2.0 + GlobeCamera.TOUR_PAUSE_SECONDS)
        assertEquals(2, camera.snapshot().tourIndex)

        camera.run(4.0 + GlobeCamera.TOUR_PAUSE_SECONDS)
        assertEquals(0, camera.snapshot().tourIndex)
        assertTrue(camera.update(0.016f))
    }

    @Test
    fun `flying to the user lands on them zoomed in`() {
        camera.flyToUser(GlobeCamera.GeoPoint(48.85, 2.35))
        assertTrue(camera.update(0.016f))

        camera.run(Flight.DIVE_BASE_SECONDS + Flight.DIVE_SECONDS_PER_HALF_TURN + 1.0)
        val view = camera.snapshot()
        assertEquals(48.85, view.centerLat, 1e-9)
        assertEquals(2.35, view.centerLon, 1e-9)
        assertEquals(GlobeCamera.USER_ZOOM, view.zoom, 1e-6f)
        assertTrue(view.showingUser)
        assertFalse(camera.update(0.016f))
    }

    @Test
    fun `touching during the dive stops it where it is`() {
        camera.flyToUser(GlobeCamera.GeoPoint(48.85, 2.35))
        camera.run(1.0)
        camera.stopMotion()
        assertFalse(camera.snapshot().showingUser)
        assertFalse(camera.update(0.016f))
    }

    @Test
    fun `flying to the user ends a running tour`() {
        camera.startTour(stops)
        camera.flyToUser(GlobeCamera.GeoPoint(48.85, 2.35))
        assertEquals(-1, camera.snapshot().tourIndex)
    }

    private fun hiddenCamera() =
        GlobeCamera(initialLat = 20.0, initialLon = 10.0, autoRotate = false, startHidden = true)
            .apply { setViewport(1000, 2000) }

    @Test
    fun `a hidden globe shows nothing and stays still until the intro`() {
        val hidden = hiddenCamera()
        assertEquals(0f, hidden.snapshot().reveal, 0f)
        assertFalse(hidden.update(0.016f))
    }

    @Test
    fun `intro fades the globe in, spins it and settles on the starting view`() {
        val intro = hiddenCamera()
        intro.startIntro()
        intro.run(0.2)
        val early = intro.snapshot()
        assertTrue(early.reveal > 0f && early.reveal < 0.9f)
        assertTrue(early.zoom < 1f)

        intro.run(GlobeCamera.INTRO_SECONDS)
        val end = intro.snapshot()
        assertEquals(1f, end.reveal, 0f)
        assertEquals(1f, end.zoom, 0f)
        assertEquals(20.0, end.centerLat, 1e-9)
        assertEquals(10.0, end.centerLon, 1e-9)
        assertFalse(intro.update(0.016f))
    }

    @Test
    fun `intro zoom overshoots a little before settling`() {
        val intro = hiddenCamera()
        intro.startIntro()
        intro.run(GlobeCamera.INTRO_SECONDS * 0.64)
        assertTrue(intro.snapshot().zoom > 1.02f)
    }

    @Test
    fun `a position found during the intro is dived onto right after it`() {
        val intro = hiddenCamera()
        intro.startIntro()
        intro.flyToUser(GlobeCamera.GeoPoint(48.85, 2.35))
        intro.run(0.5)
        assertTrue(intro.snapshot().reveal < 1f)
        assertNull(intro.snapshot().userLocation)

        intro.run(GlobeCamera.INTRO_SECONDS + Flight.DIVE_BASE_SECONDS + Flight.DIVE_SECONDS_PER_HALF_TURN + 0.5)
        val view = intro.snapshot()
        assertEquals(48.85, view.centerLat, 1e-9)
        assertEquals(GlobeCamera.USER_ZOOM, view.zoom, 1e-6f)
        assertTrue(view.showingUser)
    }

    @Test
    fun `touching during the intro shows the globe at once and cancels the dive`() {
        val intro = hiddenCamera()
        intro.startIntro()
        intro.flyToUser(GlobeCamera.GeoPoint(48.85, 2.35))
        intro.run(0.3)
        intro.stopMotion()
        val view = intro.snapshot()
        assertEquals(1f, view.reveal, 0f)
        assertTrue(view.zoom >= GlobeCamera.MIN_ZOOM)
        assertFalse(intro.update(0.016f))
        assertNull(intro.snapshot().userLocation)
    }

    @Test
    fun `touching the globe ends the tour where it is`() {
        camera.startTour(stops)
        camera.run(0.5)
        camera.stopMotion()
        assertEquals(-1, camera.snapshot().tourIndex)
        assertFalse(camera.update(0.016f))
    }
}
