package com.camstream.app.encoder

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * Etapa OpenGL entre la cámara y el encoder. La cámara pinta en
 * [cameraSurface]; cada frame se redibuja sobre la superficie del encoder
 * aplicando giro (0/90/180/270), espejo y ajustes de imagen (brillo,
 * contraste, saturación) en el shader. Todo corre en un hilo propio con
 * su contexto EGL.
 */
class GlPipe(
    private val srcWidth: Int,
    private val srcHeight: Int,
    private val outputSurface: Surface,
    private val rotationDeg: Int,
    @Volatile var mirror: Boolean,
) {
    /** Brillo -0.5..0.5, contraste 0.5..1.5, saturación 0..2. */
    @Volatile var brightness = 0f
    @Volatile var contrast = 1f
    @Volatile var saturation = 1f

    lateinit var cameraSurface: Surface
        private set

    private val thread = HandlerThread("GlPipe").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private lateinit var surfaceTexture: SurfaceTexture
    private var textureId = 0
    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uMvp = 0
    private var uTexMatrix = 0
    private var uBrightness = 0
    private var uContrast = 0
    private var uSaturation = 0
    private var outWidth = 0
    private var outHeight = 0

    // Rótulo superpuesto (texto del usuario)
    private var overlayProgram = 0
    private var ovAPosition = 0
    private var ovATexCoord = 0
    private var overlayTexId = 0
    private var overlayVertexBuffer: FloatBuffer? = null
    private val overlayTexCoords: FloatBuffer = floatBufferOf(
        0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f,
    )

    private val texMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    @Volatile private var released = false

    private val vertexBuffer: FloatBuffer = floatBufferOf(
        -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f,
    )
    private val texBuffer: FloatBuffer = floatBufferOf(
        0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f,
    )

    init {
        val latch = CountDownLatch(1)
        var setupError: Exception? = null
        handler.post {
            try {
                setup()
            } catch (e: Exception) {
                setupError = e
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        setupError?.let {
            thread.quitSafely()
            throw it
        }
    }

    private fun setup() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Sin display EGL" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize falló" }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0
        ) { "Sin config EGL compatible" }
        val config = configs[0]!!

        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext falló" }

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, outputSurface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface falló" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent falló"
        }

        val size = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, size, 0)
        outWidth = size[0]
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, size, 0)
        outHeight = size[0]

        program = buildProgram(MAIN_VERTEX_SHADER, MAIN_FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uBrightness = GLES20.glGetUniformLocation(program, "uBrightness")
        uContrast = GLES20.glGetUniformLocation(program, "uContrast")
        uSaturation = GLES20.glGetUniformLocation(program, "uSaturation")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
        )

        overlayProgram = buildProgram(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER)
        ovAPosition = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
        ovATexCoord = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord")

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setDefaultBufferSize(srcWidth, srcHeight)
        cameraSurface = Surface(surfaceTexture)
        surfaceTexture.setOnFrameAvailableListener({ drawFrame() }, handler)
    }

    /**
     * Cambia (o quita, con null) el rótulo superpuesto. anchor: 0 izquierda,
     * 1 centro, 2 derecha; siempre en la franja inferior del video.
     */
    fun setOverlay(bitmap: Bitmap?, anchor: Int) {
        handler.post {
            if (overlayTexId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(overlayTexId), 0)
                overlayTexId = 0
            }
            overlayVertexBuffer = null
            if (bitmap == null || released) {
                bitmap?.recycle()
                return@post
            }
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            overlayTexId = tex[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
            )
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

            // Rectángulo en coordenadas NDC, margen del 4% de la altura
            val w = 2f * bitmap.width / outWidth
            val h = 2f * bitmap.height / outHeight
            val marginPx = 0.04f * outHeight
            val my = 2f * marginPx / outHeight
            val mx = 2f * marginPx / outWidth
            val y0 = -1f + my
            val y1 = y0 + h
            val (x0, x1) = when (anchor) {
                1 -> -w / 2f to w / 2f
                2 -> (1f - mx - w) to (1f - mx)
                else -> (-1f + mx) to (-1f + mx + w)
            }
            overlayVertexBuffer = floatBufferOf(x0, y0, x1, y0, x0, y1, x1, y1)
            bitmap.recycle()
        }
    }

    private fun drawFrame() {
        if (released) return
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)

        Matrix.setIdentityM(mvpMatrix, 0)
        if (mirror) Matrix.scaleM(mvpMatrix, 0, -1f, 1f, 1f)
        if (rotationDeg != 0) Matrix.rotateM(mvpMatrix, 0, rotationDeg.toFloat(), 0f, 0f, 1f)

        GLES20.glViewport(0, 0, outWidth, outHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform1f(uBrightness, brightness)
        GLES20.glUniform1f(uContrast, contrast)
        GLES20.glUniform1f(uSaturation, saturation)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)

        drawOverlay()

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, surfaceTexture.timestamp)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun drawOverlay() {
        val vertices = overlayVertexBuffer ?: return
        if (overlayTexId == 0) return
        GLES20.glUseProgram(overlayProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        // Los bitmaps de Android llevan alfa premultiplicado
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
        GLES20.glEnableVertexAttribArray(ovAPosition)
        GLES20.glVertexAttribPointer(ovAPosition, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(ovATexCoord)
        GLES20.glVertexAttribPointer(ovATexCoord, 2, GLES20.GL_FLOAT, false, 0, overlayTexCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(ovAPosition)
        GLES20.glDisableVertexAttribArray(ovATexCoord)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun release() {
        if (released) return
        released = true
        val latch = CountDownLatch(1)
        handler.post {
            try {
                surfaceTexture.setOnFrameAvailableListener(null)
                cameraSurface.release()
                surfaceTexture.release()
                if (overlayTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(overlayTexId), 0)
                if (overlayProgram != 0) GLES20.glDeleteProgram(overlayProgram)
                if (program != 0) GLES20.glDeleteProgram(program)
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                )
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
                EGL14.eglTerminate(eglDisplay)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        thread.quitSafely()
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "Link de shaders falló: " + GLES20.glGetProgramInfoLog(prog)
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "Shader no compila: " + GLES20.glGetShaderInfoLog(shader)
        }
        return shader
    }

    private fun floatBufferOf(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(values); position(0) }

    companion object {
        private const val MAIN_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uMvp;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """
        private const val MAIN_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform float uBrightness;
            uniform float uContrast;
            uniform float uSaturation;
            void main() {
                vec3 c = texture2D(sTexture, vTexCoord).rgb;
                c = (c - 0.5) * uContrast + 0.5 + uBrightness;
                float gray = dot(c, vec3(0.299, 0.587, 0.114));
                c = mix(vec3(gray), c, uSaturation);
                gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
            }
        """
        private const val OVERLAY_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord.xy;
            }
        """
        private const val OVERLAY_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
