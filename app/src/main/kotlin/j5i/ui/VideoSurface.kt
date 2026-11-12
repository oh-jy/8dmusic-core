package j5i.spatial8d.ui

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class VideoSurface(surface: Surface, width: Int, height: Int) : AutoCloseable {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context = EGL14.EGL_NO_CONTEXT
    private var target = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var textureReady = false
    private var positionLocation = -1
    private var texLocation = -1
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)); position(0)
    }

    init {
        try {
            check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142, 1, EGL14.EGL_NONE)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            target = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT && target != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, target, target, context))
            val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 pos; attribute vec2 tex; varying vec2 uv; void main(){gl_Position=vec4(pos,0.0,1.0);uv=tex;}")
            val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "precision mediump float; varying vec2 uv; uniform sampler2D image; void main(){gl_FragColor=texture2D(image,uv);}")
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment); GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
            val linked = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0); check(linked[0] != 0)
            positionLocation = GLES20.glGetAttribLocation(program, "pos")
            texLocation = GLES20.glGetAttribLocation(program, "tex")
            check(positionLocation >= 0 && texLocation >= 0)
            val textures = IntArray(1); GLES20.glGenTextures(1, textures, 0); texture = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glViewport(0, 0, width, height)
        } catch (error: Exception) { close(); throw error }
    }

    private fun shader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
        val compiled = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    fun draw(bitmap: Bitmap, timeUs: Long) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        if (textureReady) GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        else { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0); textureReady = true }
        vertices.position(0); GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2); GLES20.glVertexAttribPointer(texLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(positionLocation); GLES20.glEnableVertexAttribArray(texLocation)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
        check(EGLExt.eglPresentationTimeANDROID(display, target, timeUs * 1000L))
        check(EGL14.eglSwapBuffers(display, target))
    }

    override fun close() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        if (program != 0) GLES20.glDeleteProgram(program)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (target != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, target)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
    }
}
