package com.pokedaisy.app

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt

/** What the device's GPU can do, for settings that depend on it (ui-preview has a stand-in). */
object GlCaps {
    /** OpenGL ES 3.0 or newer: [EmulatorView] then asks for an ES 3 context, which SHADERS > SMOOTHING needs. */
    fun gles3(context: Context): Boolean = glEsVersion(context) >= 0x30000 && es3Config

    /** OpenGL ES 3.2 or newer: what melonDS's GPU renderer needs (SETTINGS > DS RESOLUTION, nds/pk_nds_gl.h). */
    fun gles32(context: Context): Boolean = glEsVersion(context) >= 0x30002 && es3Config

    private fun glEsVersion(context: Context): Int =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).deviceConfigurationInfo.reqGlEsVersion

    /**
     * EGL has a config that renders ES 3 in the game view's format (GLSurfaceView's default: RGB 888, a 16-bit depth). The
     * version the system reports is a property a ROM sets: LineageOS on a Huawei P8 Lite says 3.1 on a Mali-450, an ES 2 GPU,
     * and asked for ES 3 there GLSurfaceView's eglChooseConfig failed and the game crashed as it opened.
     */
    private val es3Config: Boolean by lazy {
        runCatching {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) return@runCatching false
            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_NONE,
            )
            val count = IntArray(1)
            EGL14.eglChooseConfig(display, attrs, 0, arrayOfNulls<EGLConfig>(1), 0, 1, count, 0) && count[0] > 0
        }.getOrDefault(false)
    }
}
