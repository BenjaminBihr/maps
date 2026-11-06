package com.example.maps.globe

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.example.maps.R

/** What the globe is currently presenting, for the caption above it. */
sealed interface GlobeLabel {
    data class CapitalStop(val capital: Capital) : GlobeLabel
    data class UserHere(val location: GlobeCamera.GeoPoint) : GlobeLabel
}

/**
 * Interactive globe: drag to turn it, fling to let it spin, pinch to zoom,
 * double tap to fly to a place (double tap again at full zoom to zoom back out),
 * a capitals tour and a dive onto the user's position; touching the globe interrupts both.
 */
class GlobeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    /** Main thread: what the caption should present, null for nothing. */
    var onLabelChanged: ((GlobeLabel?) -> Unit)? = null

    val isTouring: Boolean get() = camera.snapshot().tourIndex >= 0

    private val camera = GlobeCamera(startHidden = true)
    private var pinchedDuringGesture = false // a pinch ending in a fling must not spin the globe
    private var touchedSinceLaunch = false
    private var reportedLabel: GlobeLabel? = null // GL thread only

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinchedDuringGesture = true
                camera.zoomBy(detector.scaleFactor)
                requestRender()
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                camera.drag(-distanceX, -distanceY)
                requestRender()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (pinchedDuringGesture) return false
                camera.fling(velocityX, velocityY)
                requestRender()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                camera.focusOn(e.x, e.y)
                requestRender()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent) = performClick()
        },
    )

    private val renderer = GlobeRenderer(
        context = context.applicationContext,
        camera = camera,
        density = resources.displayMetrics.density,
        markers = Capitals.TOUR.map { it.point },
        requestRender = ::requestRender,
        onFrame = ::onFrameDrawn,
    )

    /** Redraws now and then so the day/night line follows the sun even when nothing moves. */
    private val sunTicker = object : Runnable {
        override fun run() {
            requestRender()
            postDelayed(this, SUN_REFRESH_MS)
        }
    }

    init {
        contentDescription = context.getString(R.string.globe_description)
        setEGLContextClientVersion(2)
        setEGLConfigChooser(MultisampleConfigChooser())
        preserveEGLContextOnPause = true // keeps textures across pause/resume when the device allows
        setRenderer(renderer)
        // Frames only when asked: the renderer keeps requesting them while something animates.
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Satellite imagery lit by the real sun (city lights at night), or the political map. */
    fun setSatellite(enabled: Boolean) {
        renderer.satelliteRequested = enabled
        camera.setMaxZoom(if (enabled) GlobeCamera.SATELLITE_MAX_ZOOM else GlobeCamera.MAX_ZOOM)
        updateSunTicker()
        requestRender()
    }

    /** Satellite view lit by the real sun (night side, glint), or in daylight everywhere. */
    fun setSunlight(enabled: Boolean) {
        renderer.sunlightRequested = enabled
        updateSunTicker()
        requestRender()
    }

    private fun updateSunTicker() {
        removeCallbacks(sunTicker)
        if (renderer.satelliteRequested && renderer.sunlightRequested) postDelayed(sunTicker, SUN_REFRESH_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(sunTicker)
        renderer.stopTileLoading()
        super.onDetachedFromWindow()
    }

    fun startTour() {
        camera.startTour(Capitals.TOUR.map { it.point })
        requestRender()
    }

    fun stopTour() {
        camera.stopTour()
        requestRender()
    }

    /** The globe stays invisible until this plays its appearance animation. */
    fun startIntro() {
        camera.startIntro()
        requestRender()
    }

    fun flyToUser(lat: Double, lon: Double) {
        camera.flyToUser(GlobeCamera.GeoPoint(lat, lon))
        requestRender()
    }

    /** Automatic arrival on the user at launch: dives, unless they already took the globe in hand. */
    fun greetUser(lat: Double, lon: Double) {
        if (touchedSinceLaunch) return
        camera.flyToUser(GlobeCamera.GeoPoint(lat, lon))
        requestRender()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            pinchedDuringGesture = false
            touchedSinceLaunch = true
            camera.stopMotion()
            requestRender() // lets the frame callback report that the caption must go
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    // Overridden because onTouchEvent is: single taps are reported as clicks for accessibility.
    override fun performClick(): Boolean = super.performClick()

    /** GL thread: derives the caption from the drawn state and posts it only when it changes. */
    private fun onFrameDrawn(view: GlobeCamera.View) {
        val stop = Capitals.TOUR.getOrNull(view.tourIndex)
        val user = view.userLocation
        val label = when {
            stop != null -> GlobeLabel.CapitalStop(stop)
            view.showingUser && user != null -> GlobeLabel.UserHere(user)
            else -> null
        }
        if (label == reportedLabel) return
        reportedLabel = label
        post { onLabelChanged?.invoke(label) }
    }

    private companion object {
        const val SUN_REFRESH_MS = 30_000L
    }
}
