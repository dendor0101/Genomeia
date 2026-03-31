package io.github.some_example_name.old.systems.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL31
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.BufferUtils
import io.github.some_example_name.old.systems.render.TripleBufferManager.Companion.INITIAL_CAPACITY
import java.nio.ByteBuffer

class ShaderManagerLibgdxApi : ShaderManager {
    private val ssbos = IntArray(2)
    private var currentReadIndex = 0
    private val ssboCapacities = IntArray(2)

    private lateinit var shader: ShaderProgram
    private lateinit var mesh: Mesh

    override fun create() {
        val vertexShader = Gdx.files.internal("shaders/debug/circle.vert").readString()
        val fragmentShader = Gdx.files.internal("shaders/debug/circle.frag").readString()
        shader = ShaderProgram(vertexShader, fragmentShader)
        if (!shader.isCompiled) {
            throw RuntimeException("Shader compilation failed: ${shader.log}")
        }

        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val attributes = VertexAttributes(
            VertexAttribute(
                VertexAttributes.Usage.Position,
                2,
                ShaderProgram.POSITION_ATTRIBUTE
            )
        )
        mesh = Mesh(false, 4, 0, attributes).apply { setVertices(vertices) }

        create2SSBO()
    }

    private fun create2SSBO() {
        val ssboBuffer = BufferUtils.newIntBuffer(2)
        Gdx.gl31.glGenBuffers(2, ssboBuffer)
        ssbos[0] = ssboBuffer.get(0)
        ssbos[1] = ssboBuffer.get(1)

        for (i in 0..1) {
            ssboCapacities[i] = INITIAL_CAPACITY * 16
            Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, ssbos[i])
            Gdx.gl31.glBufferData(GL31.GL_SHADER_STORAGE_BUFFER, INITIAL_CAPACITY * 16, null, GL20.GL_DYNAMIC_DRAW)
            Gdx.gl31.glBindBufferBase(GL31.GL_SHADER_STORAGE_BUFFER, i, ssbos[i]) // binding 0 и 1
        }

        Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, 0)
    }

    private fun resize(dataSize: Int, targetIndex: Int, ssboId: Int) {
        if (dataSize > ssboCapacities[targetIndex]) {
            var newCapacity = ssboCapacities[targetIndex].toDouble()
            do {
                newCapacity *= 2
            } while (newCapacity < dataSize)

            val finalCapacity = newCapacity.toInt().coerceAtLeast(dataSize)

            Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, ssboId)
            Gdx.gl31.glBufferData(GL31.GL_SHADER_STORAGE_BUFFER, finalCapacity, null, GL20.GL_DYNAMIC_DRAW)
            Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, 0)

            ssboCapacities[targetIndex] = finalCapacity
        }
    }

    override fun render(
        currentRead: ByteBuffer,
        cameraProjection: Matrix4,
        isNewFrame: Boolean,
        isClear: Boolean
    ) {

        val dataSize = currentRead.remaining()
        val numInstances = dataSize / 16

        if (isNewFrame) {
            val writeIndex = 1 - currentReadIndex

            if (dataSize > 0) {
                // ← только здесь работаем с SSBO
                resize(dataSize, writeIndex, ssbos[writeIndex])

                Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, ssbos[writeIndex])
                Gdx.gl31.glBufferSubData(GL31.GL_SHADER_STORAGE_BUFFER, 0, dataSize, currentRead)
                Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, 0)
            }

            currentReadIndex = writeIndex
        }

        // Обычный рендер
        Gdx.gl.glDisable(GL20.GL_BLEND)
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
        Gdx.gl.glDepthFunc(GL20.GL_LESS)
        Gdx.gl.glDepthMask(true)
        Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT)

        shader.bind()
        shader.setUniformMatrix("u_projTrans", cameraProjection)
        shader.setUniformi("u_currentBuffer", currentReadIndex)
        mesh.bind(shader)

        Gdx.gl31.glDrawArraysInstanced(GL20.GL_TRIANGLE_STRIP, 0, 4, numInstances)

        mesh.unbind(shader)
    }

    override fun dispose() {
        shader.dispose()
        mesh.dispose()

        val deleteBuffer = BufferUtils.newIntBuffer(2).apply {
            put(ssbos[0])
            put(ssbos[1])
            flip()
        }
        Gdx.gl31.glDeleteBuffers(2, deleteBuffer)
    }
}
