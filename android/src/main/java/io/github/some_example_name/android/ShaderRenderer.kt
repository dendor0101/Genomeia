package io.github.some_example_name.android

import android.opengl.GLES32
import com.badlogic.gdx.Gdx
import io.github.some_example_name.old.good_one.ShaderRendererContract
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ShaderRenderer : ShaderRendererContract {

    private var bgProgram = 0
    private var particleProgram = 0
    private var timeLoc = 0
    private var resLoc = 0
    private var projTransLoc = 0

    private val bgVao = IntArray(1)
    private val bgVbo = IntArray(1)
    private val particleVao = IntArray(1)
    private val particleBaseVbo = IntArray(1)
    private val particleInstanceVbo = IntArray(1)
    private var projectionMatrix = FloatArray(16)
    private var numParticles = 0 // Dynamic number of instances

    init {
        createBgMesh()
        createParticleMeshes()
        bgProgram = createProgram(BG_VERTEX, BG_FRAGMENT)
        particleProgram = createProgram(PARTICLE_VERTEX, PARTICLE_FRAGMENT)

        timeLoc = GLES32.glGetUniformLocation(bgProgram, "uTime")
        resLoc = GLES32.glGetUniformLocation(bgProgram, "uRes")
        projTransLoc = GLES32.glGetUniformLocation(particleProgram, "u_projTrans")
    }

    private fun createBgMesh() {
        val verts = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        val buf = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.put(verts).position(0)

        GLES32.glGenVertexArrays(1, bgVao, 0)
        GLES32.glBindVertexArray(bgVao[0])

        GLES32.glGenBuffers(1, bgVbo, 0)
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, bgVbo[0])
        GLES32.glBufferData(GLES32.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES32.GL_STATIC_DRAW)

        GLES32.glEnableVertexAttribArray(0)
        GLES32.glVertexAttribPointer(0, 2, GLES32.GL_FLOAT, false, 0, 0)
        GLES32.glBindVertexArray(0)
    }

    private fun createParticleMeshes() {
        val baseVerts = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        val buf = ByteBuffer.allocateDirect(baseVerts.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.put(baseVerts).position(0)

        GLES32.glGenVertexArrays(1, particleVao, 0)
        GLES32.glBindVertexArray(particleVao[0])

        // Base VBO
        GLES32.glGenBuffers(1, particleBaseVbo, 0)
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, particleBaseVbo[0])
        GLES32.glBufferData(GLES32.GL_ARRAY_BUFFER, baseVerts.size * 4, buf, GLES32.GL_STATIC_DRAW)
        GLES32.glEnableVertexAttribArray(0)
        GLES32.glVertexAttribPointer(0, 2, GLES32.GL_FLOAT, false, 0, 0)

        // Instance VBO (allocate for max size)
        GLES32.glGenBuffers(1, particleInstanceVbo, 0)
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, particleInstanceVbo[0])
        GLES32.glBufferData(GLES32.GL_ARRAY_BUFFER, MAX_PARTICLES * 7 * 4, null, GLES32.GL_DYNAMIC_DRAW)

        val stride = 7 * 4 // bytes
        // instancePos vec2 location 1
        GLES32.glEnableVertexAttribArray(1)
        GLES32.glVertexAttribPointer(1, 2, GLES32.GL_FLOAT, false, stride, 0)
        GLES32.glVertexAttribDivisor(1, 1)

        // instanceRadius float location 2
        GLES32.glEnableVertexAttribArray(2)
        GLES32.glVertexAttribPointer(2, 1, GLES32.GL_FLOAT, false, stride, 8) // after vec2
        GLES32.glVertexAttribDivisor(2, 1)

        // instanceColor vec4 location 3
        GLES32.glEnableVertexAttribArray(3)
        GLES32.glVertexAttribPointer(3, 4, GLES32.GL_FLOAT, false, stride, 12) // after vec2 + float
        GLES32.glVertexAttribDivisor(3, 1)

        GLES32.glBindVertexArray(0)
    }

    override fun render(time: Float) {
        // Draw background
        GLES32.glUseProgram(bgProgram)
        GLES32.glBindVertexArray(bgVao[0])
        GLES32.glDisable(GLES32.GL_DEPTH_TEST)
        GLES32.glDisable(GLES32.GL_CULL_FACE)
        GLES32.glViewport(0, 0, Gdx.graphics.width, Gdx.graphics.height)
        GLES32.glUniform1f(timeLoc, time)
        GLES32.glUniform2f(resLoc, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0, 4)

        // Draw particles (only if numParticles > 0)
        if (numParticles > 0) {
            GLES32.glUseProgram(particleProgram)
            GLES32.glBindVertexArray(particleVao[0])
            GLES32.glEnable(GLES32.GL_BLEND)
            GLES32.glBlendFunc(GLES32.GL_SRC_ALPHA, GLES32.GL_ONE_MINUS_SRC_ALPHA)
            GLES32.glUniformMatrix4fv(projTransLoc, 1, false, projectionMatrix, 0)
            GLES32.glDrawArraysInstanced(GLES32.GL_TRIANGLE_STRIP, 0, 4, numParticles)
            GLES32.glDisable(GLES32.GL_BLEND)
        }

        GLES32.glBindVertexArray(0)
        GLES32.glUseProgram(0)
    }

    override fun updateParticles(data: FloatArray) {
        if (data.size != numParticles * 7) {
            throw IllegalArgumentException("Data size must be numParticles * 7")
        }
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, particleInstanceVbo[0])
        val buf = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(data)
        buf.position(0)
        GLES32.glBufferSubData(GLES32.GL_ARRAY_BUFFER, 0, data.size * 4, buf)
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, 0)
    }

    override fun setProjectionMatrix(matrix: FloatArray) {
        matrix.copyInto(projectionMatrix)
    }

    override fun setNumParticles(num: Int) {
        if (num > MAX_PARTICLES) {
            throw IllegalArgumentException("Num particles exceeds MAX_PARTICLES: $MAX_PARTICLES")
        }
        numParticles = num
    }

    override fun dispose() {
        GLES32.glDeleteProgram(bgProgram)
        GLES32.glDeleteProgram(particleProgram)
        GLES32.glDeleteVertexArrays(1, bgVao, 0)
        GLES32.glDeleteBuffers(1, bgVbo, 0)
        GLES32.glDeleteVertexArrays(1, particleVao, 0)
        GLES32.glDeleteBuffers(1, particleBaseVbo, 0)
        GLES32.glDeleteBuffers(1, particleInstanceVbo, 0)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES32.glCreateShader(type)
        GLES32.glShaderSource(s, src)
        GLES32.glCompileShader(s)

        val ok = IntArray(1)
        GLES32.glGetShaderiv(s, GLES32.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES32.glGetShaderInfoLog(s)
            GLES32.glDeleteShader(s)
            error("Shader compile error: $log")
        }

        return s
    }

    private fun createProgram(v: String, f: String): Int {
        val vs = compile(GLES32.GL_VERTEX_SHADER, v)
        val fs = compile(GLES32.GL_FRAGMENT_SHADER, f)

        val p = GLES32.glCreateProgram()
        GLES32.glAttachShader(p, vs)
        GLES32.glAttachShader(p, fs)
        GLES32.glLinkProgram(p)

        val ok = IntArray(1)
        GLES32.glGetProgramiv(p, GLES32.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES32.glGetProgramInfoLog(p)
            GLES32.glDeleteProgram(p)
            error("Program link error: $log")
        }

        GLES32.glDeleteShader(vs)
        GLES32.glDeleteShader(fs)

        return p
    }

    private fun error(msg: String): Nothing {
        throw RuntimeException(msg)
    }

    companion object {
        private const val MAX_PARTICLES = 10000 // Максимум, можно увеличить (проверь память GPU)

        private const val BG_VERTEX = """
            #version 320 es
            layout(location=0) in vec2 aPos;
            void main(){
                gl_Position = vec4(aPos,0,1);
            }
        """

        private const val BG_FRAGMENT = """
            #version 320 es
            precision highp float;

            uniform float uTime;
            uniform vec2 uRes;
            out vec4 fragColor;

            void main(){
                vec2 uv = gl_FragCoord.xy / uRes;
                float c = 0.5 + 0.5 * sin(uTime + uv.x * 10.0);
                fragColor = vec4(uv, c, 1.0);
            }
        """

        private const val PARTICLE_VERTEX = """
            #version 320 es
            layout(location=0) in vec2 aPos;
            layout(location=1) in vec2 instancePos;
            layout(location=2) in float instanceRadius;
            layout(location=3) in vec4 instanceColor;

            uniform mat4 u_projTrans;

            out vec2 vPos;
            out vec4 vColor;

            void main(){
                vPos = aPos;
                vColor = instanceColor;
                vec2 worldPos = aPos * instanceRadius + instancePos;
                gl_Position = u_projTrans * vec4(worldPos, 0.0, 1.0);
            }
        """

        private const val PARTICLE_FRAGMENT = """
            #version 320 es
            precision highp float;

            in vec2 vPos;
            in vec4 vColor;
            out vec4 fragColor;

            void main(){
                if (length(vPos) > 1.0) discard;
                fragColor = vColor;
            }
        """
    }
}
