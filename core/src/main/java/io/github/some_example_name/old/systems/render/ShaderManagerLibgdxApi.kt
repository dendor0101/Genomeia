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
import java.nio.ByteBuffer
import java.nio.IntBuffer

interface ShaderManager {
    fun create()
    fun render(currentRead: ByteBuffer, cameraProjection: Matrix4, isNewFrame: Boolean)
    fun dispose()
}

class ShaderManagerLibgdxApi : ShaderManager {
    // Три SSBO с динамическим размером (растут ×1.2, как на CPU)
    private val ssboIds = IntArray(3)
    private val ssboCapacities = IntArray(3)
    private var currentSsboIndex = 0
    private var activeSsboId = 0

    // Начальный размер берём из TripleBufferManager (теперь он динамический)
    private val INITIAL_BUFFER_BYTES = TripleBufferManager.INITIAL_CAPACITY * 16

    private lateinit var shader: ShaderProgram
    private lateinit var mesh: Mesh

    override fun create() {
        // Создаём три SSBO с маленьким начальным размером
        val ssboBuffer: IntBuffer = BufferUtils.newIntBuffer(3)
        Gdx.gl31.glGenBuffers(3, ssboBuffer)

        for (i in 0 until 3) {
            ssboIds[i] = ssboBuffer.get(i)
            ssboCapacities[i] = INITIAL_BUFFER_BYTES

            Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, ssboIds[i])
            Gdx.gl31.glBufferData(
                GL31.GL_SHADER_STORAGE_BUFFER,
                INITIAL_BUFFER_BYTES,
                null,
                GL20.GL_DYNAMIC_DRAW
            )
        }
        Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, 0)

        activeSsboId = ssboIds[0]
        currentSsboIndex = 0

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
    }

    override fun render(
        currentRead: ByteBuffer,
        cameraProjection: Matrix4,
        isNewFrame: Boolean
    ) {
        val dataSize = currentRead.remaining()
        val numInstances = dataSize / 16
        if (numInstances == 0) return

        // ←←← ТОЛЬКО ПРИ НОВОМ КАДРЕ: ротация + resize (если нужно) + загрузка
        if (isNewFrame) {
            currentSsboIndex = (currentSsboIndex + 1) % 3
            val targetIndex = currentSsboIndex
            val ssboId = ssboIds[targetIndex]
            activeSsboId = ssboId

            // Динамическое увеличение SSBO (точно как в TripleBufferManager)
            if (dataSize > ssboCapacities[targetIndex]) {
                var newCapacity = ssboCapacities[targetIndex].toDouble()
                do {
                    newCapacity *= 1.2
                } while (newCapacity < dataSize)

                val finalCapacity = newCapacity.toInt().coerceAtLeast(dataSize)

                Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, ssboId)
                Gdx.gl31.glBufferData(
                    GL31.GL_SHADER_STORAGE_BUFFER,
                    finalCapacity,
                    null,
                    GL20.GL_DYNAMIC_DRAW
                )
                Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, 0)

                ssboCapacities[targetIndex] = finalCapacity
            }

            // Загружаем данные
            Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, ssboId)
            Gdx.gl31.glBufferSubData(GL31.GL_SHADER_STORAGE_BUFFER, 0, dataSize, currentRead)
            Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, 0)
        }

        // Всегда рисуем текущий активный SSBO
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        shader.bind()
        shader.setUniformMatrix("u_projTrans", cameraProjection)
        mesh.bind(shader)

        Gdx.gl31.glBindBufferBase(GL31.GL_SHADER_STORAGE_BUFFER, 0, activeSsboId)
        Gdx.gl31.glDrawArraysInstanced(GL20.GL_TRIANGLE_STRIP, 0, 4, numInstances)

        mesh.unbind(shader)
        Gdx.gl31.glBindBufferBase(GL31.GL_SHADER_STORAGE_BUFFER, 0, 0)
    }

    override fun dispose() {
        shader.dispose()
        val ssboBuffer: IntBuffer = BufferUtils.newIntBuffer(3)
        for (id in ssboIds) {
            ssboBuffer.put(id)
        }
        ssboBuffer.flip()
        Gdx.gl31.glDeleteBuffers(3, ssboBuffer)
    }
}
