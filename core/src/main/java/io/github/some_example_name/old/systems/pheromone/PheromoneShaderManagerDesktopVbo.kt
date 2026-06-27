package io.github.some_example_name.old.systems.pheromone

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL30
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.BufferUtils
import io.github.some_example_name.old.systems.pheromone.PheromonesManager.Companion.K
import io.github.some_example_name.old.systems.pheromone.PheromonesManager.Companion.P
import io.github.some_example_name.old.systems.render.DesktopShaderSource
import io.github.some_example_name.old.systems.render.RenderSystem.Companion.INITIAL_PHEROMONE_CAPACITY
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

/**
 * macOS desktop pheromone renderer — port of [io.github.some_example_name.android.PheromoneShaderManagerAndroid].
 * VBO + instanced attributes (no SSBOs), issuing GL through libGDX `Gdx.gl30` and loading the same
 * `_android`/shared shaders via [DesktopShaderSource] (ES GLSL -> desktop 4.1). Windows/Linux keep
 * the SSBO pheromone renderer ([PheromoneShaderManagerLibgdx]).
 *
 * GL buffers handed to LWJGL (gen/delete/iv-queries) MUST be direct (`BufferUtils.newIntBuffer`);
 * heap buffers crash the JVM natively on macOS. VAO gen/delete use the `int[]+offset` overload
 * (LWJGL copies the array, so a heap array is fine there).
 */
class PheromoneShaderManagerDesktopVbo : PheromoneShaderManager {

    private val gl get() = Gdx.gl30!!

    private fun oneInt(): IntBuffer = BufferUtils.newIntBuffer(1)
    private fun oneInt(id: Int): IntBuffer = BufferUtils.newIntBuffer(1).also { it.put(id); it.flip() }

    private var program = 0
    private var vao = 0
    private var quadVbo = 0
    private var instanceVbo = 0

    private var uProjTransLoc = -1
    private var uKLoc = -1
    private var uPLoc = -1

    private var instanceCapacity = 0

    // Размер одной структуры в байтах: vec2 (8) + float (4) + uint (4) = 16
    private val STRUCT_SIZE = 16

    private val quadVertices = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f
    )

    override fun create() {
        dispose()

        val vertSource = DesktopShaderSource.load("shaders/pheromone/pheromone_android.vert")
        val fragSource = DesktopShaderSource.load("shaders/pheromone/pheromone.frag")
        program = createProgram(vertSource, fragSource)

        uProjTransLoc = gl.glGetUniformLocation(program, "u_projTrans")
        uKLoc = gl.glGetUniformLocation(program, "u_K")
        uPLoc = gl.glGetUniformLocation(program, "u_P")

        setupVAO()
    }

    private fun setupVAO() {
        val tmp = IntArray(1)

        // VAO
        gl.glGenVertexArrays(1, tmp, 0)
        vao = tmp[0]
        gl.glBindVertexArray(vao)

        // Quad VBO (статический, 4 вершины)
        val q = oneInt()
        gl.glGenBuffers(1, q)
        quadVbo = q.get(0)
        gl.glBindBuffer(GL30.GL_ARRAY_BUFFER, quadVbo)

        val fb = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        fb.put(quadVertices).position(0)

        gl.glBufferData(
            GL30.GL_ARRAY_BUFFER,
            quadVertices.size * 4,
            fb,
            GL30.GL_STATIC_DRAW
        )

        gl.glVertexAttribPointer(0, 2, GL30.GL_FLOAT, false, 0, 0)
        gl.glEnableVertexAttribArray(0)

        // Instance VBO (динамический)
        val iv = oneInt()
        gl.glGenBuffers(1, iv)
        instanceVbo = iv.get(0)
        gl.glBindBuffer(GL30.GL_ARRAY_BUFFER, instanceVbo)

        // a_pos (location 1) — vec2
        gl.glVertexAttribPointer(1, 2, GL30.GL_FLOAT, false, STRUCT_SIZE, 0)
        gl.glEnableVertexAttribArray(1)
        gl.glVertexAttribDivisor(1, 1)

        // a_A (location 2) — float
        gl.glVertexAttribPointer(2, 1, GL30.GL_FLOAT, false, STRUCT_SIZE, 8)
        gl.glEnableVertexAttribArray(2)
        gl.glVertexAttribDivisor(2, 1)

        // a_color (location 3) — uint
        gl.glVertexAttribIPointer(3, 1, GL30.GL_UNSIGNED_INT, STRUCT_SIZE, 12)
        gl.glEnableVertexAttribArray(3)
        gl.glVertexAttribDivisor(3, 1)

        gl.glBindVertexArray(0)
    }

    override fun renderPheromones(cameraProjection: Matrix4, currentRead: ByteBuffer) {
        val dataSize = currentRead.remaining()
        val numInstances = dataSize / STRUCT_SIZE
        if (numInstances == 0) return

        resizeInstanceBuffer(dataSize)

        gl.glBindVertexArray(vao)

        // Загружаем данные инстансов
        gl.glBindBuffer(GL30.GL_ARRAY_BUFFER, instanceVbo)
        currentRead.mark()
        gl.glBufferSubData(GL30.GL_ARRAY_BUFFER, 0, dataSize, currentRead)
        currentRead.reset()

        gl.glUseProgram(program)

        gl.glUniformMatrix4fv(uProjTransLoc, 1, false, cameraProjection.`val`, 0)
        gl.glUniform1f(uKLoc, K)
        gl.glUniform1f(uPLoc, P)

        gl.glEnable(GL30.GL_BLEND)
        gl.glBlendFunc(GL30.GL_SRC_ALPHA, GL30.GL_ONE_MINUS_SRC_ALPHA)

        gl.glDrawArraysInstanced(GL30.GL_TRIANGLE_STRIP, 0, 4, numInstances)

        gl.glDisable(GL30.GL_BLEND)
        gl.glBindVertexArray(0)
    }

    private fun resizeInstanceBuffer(newSize: Int) {
        if (newSize <= instanceCapacity) return

        var cap = if (instanceCapacity == 0) {
            INITIAL_PHEROMONE_CAPACITY * STRUCT_SIZE
        } else {
            instanceCapacity
        }
        while (cap < newSize) cap = (cap * 1.5).toInt()

        gl.glBindBuffer(GL30.GL_ARRAY_BUFFER, instanceVbo)
        gl.glBufferData(GL30.GL_ARRAY_BUFFER, cap, null, GL30.GL_DYNAMIC_DRAW)
        instanceCapacity = cap
    }

    private fun createProgram(vertSource: String, fragSource: String): Int {
        val vertexShader = compileShader(GL30.GL_VERTEX_SHADER, vertSource)
        val fragmentShader = compileShader(GL30.GL_FRAGMENT_SHADER, fragSource)

        val prog = gl.glCreateProgram()
        gl.glAttachShader(prog, vertexShader)
        gl.glAttachShader(prog, fragmentShader)
        gl.glLinkProgram(prog)

        val status = oneInt()
        gl.glGetProgramiv(prog, GL30.GL_LINK_STATUS, status)
        if (status.get(0) == 0) {
            throw RuntimeException("Program link error: ${gl.glGetProgramInfoLog(prog)}")
        }

        gl.glDeleteShader(vertexShader)
        gl.glDeleteShader(fragmentShader)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = gl.glCreateShader(type)
        gl.glShaderSource(shader, source)
        gl.glCompileShader(shader)

        val status = oneInt()
        gl.glGetShaderiv(shader, GL30.GL_COMPILE_STATUS, status)
        if (status.get(0) == 0) {
            throw RuntimeException("Shader compile error: ${gl.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    override fun dispose() {
        if (program != 0) {
            gl.glDeleteProgram(program)
            program = 0
        }
        if (vao != 0) {
            gl.glDeleteVertexArrays(1, intArrayOf(vao), 0)
            vao = 0
        }
        if (quadVbo != 0) {
            gl.glDeleteBuffers(1, oneInt(quadVbo))
            quadVbo = 0
        }
        if (instanceVbo != 0) {
            gl.glDeleteBuffers(1, oneInt(instanceVbo))
            instanceVbo = 0
        }

        uProjTransLoc = -1
        uKLoc = -1
        uPLoc = -1
        instanceCapacity = 0
    }
}
