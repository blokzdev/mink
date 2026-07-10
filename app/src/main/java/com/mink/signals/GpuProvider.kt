package com.mink.signals

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.PermissionKind
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Reads the OpenGL surface the way a web page reads WebGL: by standing up a tiny
 * offscreen GL context and asking the driver who it is. GL_RENDERER, GL_VENDOR,
 * GL_VERSION, the shader language version, the extension list, and the maximum
 * texture size together form one of the strongest passive fingerprints on the
 * device. The whole context is built on a dedicated background thread, read once,
 * and torn down; every step is wrapped so a driver quirk can never crash Mink.
 */
class GpuProvider(ctx: ProviderContext) : SignalProvider {

    @Suppress("unused")
    private val providerContext = ctx

    override val category: SignalCategory = SignalCategory.GPU
    override val permission: PermissionKind? = null

    override suspend fun collect(): List<FingerprintSignal> {
        val info = readGpuInfo()
        val signals = mutableListOf<FingerprintSignal>()

        if (info == null) {
            signals += FingerprintSignal.make(
                key = "status",
                category = category,
                name = "Graphics",
                value = "unavailable",
                rationale = "Mink could not open an offscreen graphics context on this device, so " +
                    "the renderer details are not readable here.",
            )
            return signals
        }

        info.renderer?.let {
            signals += FingerprintSignal.make(
                key = "renderer",
                category = category,
                name = "Renderer",
                value = it,
                rationale = "The GPU model your driver reports. This is the same string web pages " +
                    "read through WebGL, and it points straight at your chip. Read from " +
                    "GL_RENDERER.",
            )
        }

        info.vendor?.let {
            signals += FingerprintSignal.make(
                key = "vendor",
                category = category,
                name = "Vendor",
                value = it,
                rationale = "Who makes your GPU. Read from GL_VENDOR.",
            )
        }

        info.version?.let {
            signals += FingerprintSignal.make(
                key = "version",
                category = category,
                name = "OpenGL version",
                value = it,
                rationale = "The OpenGL ES version string, including the driver build. The build " +
                    "suffix is often unique to your firmware. Read from GL_VERSION.",
            )
        }

        info.shadingLanguage?.let {
            signals += FingerprintSignal.make(
                key = "glsl",
                category = category,
                name = "Shader language",
                value = it,
                rationale = "The shading language version the driver supports. Read from " +
                    "GL_SHADING_LANGUAGE_VERSION.",
            )
        }

        if (info.maxTextureSize > 0) {
            signals += FingerprintSignal.make(
                key = "maxTexture",
                category = category,
                name = "Max texture size",
                value = "${info.maxTextureSize} px",
                rationale = "The largest texture the GPU accepts. It varies by chip and adds to " +
                    "the renderer fingerprint.",
            )
        }

        if (info.extensions.isNotEmpty()) {
            signals += FingerprintSignal.make(
                key = "extensions",
                category = category,
                name = "GL extensions",
                value = "${info.extensions.size} supported",
                rationale = "The extensions your driver advertises. The exact list is a strong, " +
                    "stable fingerprint, much like WebGL extensions in a browser.",
                displayHint = DisplayHint.TAGS,
                entries = info.extensions.take(MAX_EXTENSIONS).map { SignalEntry(it, "") },
            )
        }

        return signals
    }

    /**
     * Everything read from a single offscreen GL context. Nullable string fields
     * degrade gracefully when the driver does not answer.
     */
    data class GpuInfo(
        val renderer: String?,
        val vendor: String?,
        val version: String?,
        val shadingLanguage: String?,
        val maxTextureSize: Int,
        val extensions: List<String>,
    )

    private suspend fun readGpuInfo(): GpuInfo? {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        return try {
            withContext(dispatcher) {
                runCatching { renderOffscreenAndRead() }.getOrNull()
            }
        } finally {
            runCatching { executor.shutdownNow() }
        }
    }

    /**
     * Builds a 1x1 pbuffer EGL context, makes it current, reads the GL strings,
     * and tears the whole thing down. Runs on the dedicated dispatcher thread.
     */
    private fun renderOffscreenAndRead(): GpuInfo? {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null

            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val chosen = EGL14.eglChooseConfig(
                display, configAttribs, 0, configs, 0, 1, numConfigs, 0,
            )
            if (!chosen || numConfigs[0] <= 0 || configs[0] == null) return null
            val config = configs[0] ?: return null

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE,
            )
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) return null

            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE,
            )
            surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) return null

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null

            val renderer = glString(GLES20.GL_RENDERER)
            val vendor = glString(GLES20.GL_VENDOR)
            val glVersion = glString(GLES20.GL_VERSION)
            val glsl = glString(GLES20.GL_SHADING_LANGUAGE_VERSION)
            val extensions = parseExtensions(glString(GLES20.GL_EXTENSIONS))

            val maxTexture = IntArray(1)
            runCatching { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexture, 0) }

            return GpuInfo(
                renderer = renderer,
                vendor = vendor,
                version = glVersion,
                shadingLanguage = glsl,
                maxTextureSize = maxTexture[0],
                extensions = extensions,
            )
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            }
        }
    }

    private fun glString(name: Int): String? = runCatching {
        GLES20.glGetString(name)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    companion object {
        private const val MAX_EXTENSIONS = 80

        /**
         * Splits a raw GL_EXTENSIONS string into a sorted, deduped list. Pure and
         * testable.
         */
        fun parseExtensions(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        }
    }
}
