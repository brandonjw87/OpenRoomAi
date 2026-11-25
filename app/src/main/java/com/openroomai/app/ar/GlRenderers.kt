package com.openroomai.app.ar

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal object Gl {
    fun program(vs: String, fs: String): Int {
        val v = shader(GLES20.GL_VERTEX_SHADER, vs)
        val f = shader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("OpenRoomGL", "link: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("OpenRoomGL", "compile: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    fun floats(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
}

/** Draws the live camera image as the scene background. */
class BackgroundRenderer {
    var textureId = -1
        private set
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private val quad = Gl.floats(8).apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private val tex = Gl.floats(8)

    fun create() {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        textureId = t[0]
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        program = Gl.program(
            """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }
            """.trimIndent(),
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, v_TexCoord); }
            """.trimIndent(),
        )
        aPos = GLES20.glGetAttribLocation(program, "a_Position")
        aTex = GLES20.glGetAttribLocation(program, "a_TexCoord")
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            quad.position(0); tex.position(0)
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad, Coordinates2d.TEXTURE_NORMALIZED, tex)
        }
        if (frame.timestamp == 0L) return
        quad.position(0); tex.position(0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUseProgram(program)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, tex)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }
}

/** Immediate-mode colored lines, points and translucent triangles in world space. */
class PrimitiveRenderer {
    private var program = 0
    private var aPos = 0
    private var uMvp = 0
    private var uColor = 0
    private var uPointSize = 0
    private var buf = Gl.floats(4096)
    private var maxLineWidth = 1f

    fun create() {
        program = Gl.program(
            """
            uniform mat4 u_Mvp;
            uniform float u_PointSize;
            attribute vec3 a_Pos;
            void main() { gl_Position = u_Mvp * vec4(a_Pos, 1.0); gl_PointSize = u_PointSize; }
            """.trimIndent(),
            """
            precision mediump float;
            uniform vec4 u_Color;
            void main() { gl_FragColor = u_Color; }
            """.trimIndent(),
        )
        aPos = GLES20.glGetAttribLocation(program, "a_Pos")
        uMvp = GLES20.glGetUniformLocation(program, "u_Mvp")
        uColor = GLES20.glGetUniformLocation(program, "u_Color")
        uPointSize = GLES20.glGetUniformLocation(program, "u_PointSize")
        val range = FloatArray(2)
        GLES20.glGetFloatv(GLES20.GL_ALIASED_LINE_WIDTH_RANGE, range, 0)
        maxLineWidth = range[1].coerceAtLeast(1f)
        createOcclusion()
    }

    // Occlusion-aware variant: fragments behind real-world surfaces fade out (soft edges).
    private var occProgram = 0
    private var oPos = 0
    private var oMvp = 0
    private var oView = 0
    private var oColor = 0
    private var oDepth = 0
    private var oUvO = 0
    private var oUvX = 0
    private var oUvY = 0
    private var oViewport = 0
    private var oUse = 0

    private fun createOcclusion() {
        occProgram = Gl.program(
            """
            uniform mat4 u_Mvp;
            uniform mat4 u_View;
            attribute vec3 a_Pos;
            varying float v_Depth;
            void main() {
                gl_Position = u_Mvp * vec4(a_Pos, 1.0);
                v_Depth = -(u_View * vec4(a_Pos, 1.0)).z;
            }
            """.trimIndent(),
            """
            // Depth arrives in millimetres; mediump is only guaranteed to ±16384, so ask for highp.
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform vec4 u_Color;
            uniform sampler2D u_Depth;
            uniform vec2 u_UvO;
            uniform vec2 u_UvX;
            uniform vec2 u_UvY;
            uniform vec2 u_Viewport;
            uniform float u_Use;
            varying float v_Depth;
            void main() {
                float vis = 1.0;
                if (u_Use > 0.5) {
                    vec2 s = gl_FragCoord.xy / u_Viewport;
                    vec2 uv = u_UvO + s.x * u_UvX + s.y * u_UvY;
                    vec4 d = texture2D(u_Depth, uv);
                    float mm = d.r * 255.0 + d.a * 255.0 * 256.0;
                    if (mm > 0.0) {
                        // Visible when the real surface is behind the virtual one; 12 cm soft band.
                        vis = clamp((mm * 0.001 - v_Depth) / 0.12 + 0.5, 0.0, 1.0);
                    }
                }
                gl_FragColor = vec4(u_Color.rgb, u_Color.a * vis);
            }
            """.trimIndent(),
        )
        oPos = GLES20.glGetAttribLocation(occProgram, "a_Pos")
        oMvp = GLES20.glGetUniformLocation(occProgram, "u_Mvp")
        oView = GLES20.glGetUniformLocation(occProgram, "u_View")
        oColor = GLES20.glGetUniformLocation(occProgram, "u_Color")
        oDepth = GLES20.glGetUniformLocation(occProgram, "u_Depth")
        oUvO = GLES20.glGetUniformLocation(occProgram, "u_UvO")
        oUvX = GLES20.glGetUniformLocation(occProgram, "u_UvX")
        oUvY = GLES20.glGetUniformLocation(occProgram, "u_UvY")
        oViewport = GLES20.glGetUniformLocation(occProgram, "u_Viewport")
        oUse = GLES20.glGetUniformLocation(occProgram, "u_Use")
    }

    /** Lines or triangles that hide behind real objects when [depth] is ready. */
    fun occluded(mode: Int, mvp: FloatArray, view: FloatArray, xyz: FloatArray, count: Int, argb: Long, depth: DepthTexture?, viewW: Int, viewH: Int, lineWidth: Float = 6f) {
        if (count <= 0) return
        val n = count * 3
        if (buf.capacity() < n) buf = Gl.floats(n * 2)
        buf.clear(); buf.put(xyz, 0, n); buf.position(0)
        GLES20.glUseProgram(occProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUniformMatrix4fv(oMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(oView, 1, false, view, 0)
        GLES20.glUniform4f(oColor, ((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f, ((argb shr 24) and 0xFF) / 255f)
        val use = depth != null && depth.ready
        GLES20.glUniform1f(oUse, if (use) 1f else 0f)
        if (use) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depth!!.textureId)
            GLES20.glUniform1i(oDepth, 1)
            GLES20.glUniform2f(oUvO, depth.uvOrigin[0], depth.uvOrigin[1])
            GLES20.glUniform2f(oUvX, depth.uvX[0], depth.uvX[1])
            GLES20.glUniform2f(oUvY, depth.uvY[0], depth.uvY[1])
            GLES20.glUniform2f(oViewport, viewW.toFloat(), viewH.toFloat())
        }
        if (mode == GLES20.GL_LINES) GLES20.glLineWidth(lineWidth.coerceAtMost(maxLineWidth))
        GLES20.glVertexAttribPointer(oPos, 3, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glEnableVertexAttribArray(oPos)
        GLES20.glDrawArrays(mode, 0, count)
        GLES20.glDisableVertexAttribArray(oPos)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    fun lines(mvp: FloatArray, xyz: FloatArray, count: Int, argb: Long, width: Float = 6f) =
        draw(GLES20.GL_LINES, mvp, xyz, count, argb) { GLES20.glLineWidth(width.coerceAtMost(maxLineWidth)) }

    fun points(mvp: FloatArray, xyz: FloatArray, count: Int, argb: Long, size: Float = 8f) =
        draw(GLES20.GL_POINTS, mvp, xyz, count, argb) { GLES20.glUniform1f(uPointSize, size) }

    fun triangles(mvp: FloatArray, xyz: FloatArray, count: Int, argb: Long) =
        draw(GLES20.GL_TRIANGLES, mvp, xyz, count, argb) {}

    private inline fun draw(mode: Int, mvp: FloatArray, xyz: FloatArray, count: Int, argb: Long, setup: () -> Unit) {
        if (count <= 0) return
        val n = count * 3
        if (buf.capacity() < n) buf = Gl.floats(n * 2)
        buf.clear(); buf.put(xyz, 0, n); buf.position(0)
        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        val a = ((argb shr 24) and 0xFF) / 255f
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        GLES20.glUniform4f(uColor, r, g, b, a)
        GLES20.glUniform1f(uPointSize, 8f)
        setup()
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glDrawArrays(mode, 0, count)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
}

/**
 * Real-world depth for occlusion: ARCore's 16-bit depth (millimetres) uploaded as a GLES2
 * LUMINANCE_ALPHA texture (low byte → L, high byte → A), plus the screen → texture mapping.
 */
class DepthTexture {
    var textureId = -1
        private set
    var ready = false
        private set
    /** Texture coords of the screen's bottom-left corner and its +x / +y spans. */
    val uvOrigin = FloatArray(2)
    val uvX = FloatArray(2)
    val uvY = FloatArray(2)
    private var tight: java.nio.ByteBuffer? = null

    fun create() {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        textureId = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // Packed integers must not be interpolated.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        ready = false
    }

    fun update(frame: Frame) {
        // Screen (NDC) → camera texture coordinates; the depth image shares the texture's space.
        val ndc = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f)
        val uv = FloatArray(6)
        frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndc, Coordinates2d.TEXTURE_NORMALIZED, uv)
        uvOrigin[0] = uv[0]; uvOrigin[1] = uv[1]
        uvX[0] = uv[2] - uv[0]; uvX[1] = uv[3] - uv[1]
        uvY[0] = uv[4] - uv[0]; uvY[1] = uv[5] - uv[1]
        try {
            frame.acquireDepthImage16Bits().use { img ->
                val plane = img.planes[0]
                val w = img.width; val h = img.height
                var buf = plane.buffer
                if (plane.rowStride != w * 2 || plane.pixelStride != 2) {
                    // Repack into a tight buffer so GL can read it row by row.
                    val t = tight?.takeIf { it.capacity() >= w * h * 2 } ?: java.nio.ByteBuffer.allocateDirect(w * h * 2).also { tight = it }
                    t.clear()
                    for (y in 0 until h) for (x in 0 until w) {
                        val i = y * plane.rowStride + x * plane.pixelStride
                        t.put(buf.get(i)); t.put(buf.get(i + 1))
                    }
                    t.flip(); buf = t
                }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA, w, h, 0, GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, buf)
                ready = true
            }
        } catch (_: Exception) {
            // NotYetAvailable or no depth on this device: keep the last frame / draw unoccluded.
        }
    }
}

/** Growable float list to build vertex data without per-frame allocations. */
class FloatList(initial: Int = 1024) {
    var data = FloatArray(initial)
        private set
    var size = 0
        private set
    val vertices get() = size / 3
    fun clear() { size = 0 }
    fun add(x: Float, y: Float, z: Float) {
        if (size + 3 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = x; data[size++] = y; data[size++] = z
    }
}
