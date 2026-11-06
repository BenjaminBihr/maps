package com.example.maps.globe

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** Small OpenGL ES 2 helpers. Like every GL call, they must run on the GL thread. */
internal object GlUtil {

    /** Compiles and links a shader program; throws with the driver's log on failure. */
    fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        check(status[0] == GLES20.GL_TRUE) {
            "Program link failed: ${GLES20.glGetProgramInfoLog(program)}".also { GLES20.glDeleteProgram(program) }
        }
        return program
    }

    /** A new vertex buffer holding [data]; returns its id. */
    fun arrayBuffer(data: FloatArray): Int {
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        replaceArrayBuffer(ids[0], data)
        return ids[0]
    }

    fun replaceArrayBuffer(id: Int, data: FloatArray) {
        val buffer = ByteBuffer.allocateDirect(data.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(data)
            .position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * Float.SIZE_BYTES, buffer, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /** A new index buffer holding [data]; returns its id. */
    fun elementBuffer(data: ShortArray): Int {
        val buffer = ByteBuffer.allocateDirect(data.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .put(data)
            .position(0)
        return upload(GLES20.GL_ELEMENT_ARRAY_BUFFER, data.size * Short.SIZE_BYTES, buffer)
    }

    /** Uploads a map image with mipmaps (and anisotropic filtering when available), then recycles it. */
    fun uploadTexture(bitmap: Bitmap): Int {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0) // never disturb the other units' bindings
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Keeps the map sharp near the globe's edge, where it is seen at a grazing angle.
        if (GLES20.glGetString(GLES20.GL_EXTENSIONS)?.contains("GL_EXT_texture_filter_anisotropic") == true) {
            val maxAnisotropy = FloatArray(1)
            GLES20.glGetFloatv(GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, maxAnisotropy, 0)
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY_EXT, min(8f, maxAnisotropy[0]))
        }
        return ids[0]
    }

    private const val GL_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE
    private const val GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FF

    private fun upload(target: Int, sizeBytes: Int, data: java.nio.Buffer): Int {
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        GLES20.glBindBuffer(target, ids[0])
        GLES20.glBufferData(target, sizeBytes, data, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(target, 0)
        return ids[0]
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}".also { GLES20.glDeleteShader(shader) }
        }
        return shader
    }
}
