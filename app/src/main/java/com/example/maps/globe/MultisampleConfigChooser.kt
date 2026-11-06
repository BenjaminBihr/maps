package com.example.maps.globe

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/** Asks for 4x MSAA (smooth globe edge and lines), falling back to no multisampling. */
internal class MultisampleConfigChooser : GLSurfaceView.EGLConfigChooser {

    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig =
        choose(egl, display, samples = 4)
            ?: choose(egl, display, samples = 0)
            ?: error("No suitable EGL config")

    private fun choose(egl: EGL10, display: EGLDisplay, samples: Int): EGLConfig? {
        val attributes = buildList {
            addAll(listOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8))
            addAll(listOf(EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT))
            if (samples > 0) addAll(listOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples))
            add(EGL10.EGL_NONE)
        }.toIntArray()
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val found = egl.eglChooseConfig(display, attributes, configs, 1, count)
        return if (found && count[0] > 0) configs[0] else null
    }

    private companion object {
        /** Not exposed by EGL10 (it lives in EGL14). */
        const val EGL_OPENGL_ES2_BIT = 4
    }
}
