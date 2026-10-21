package io.github.some_example_name.render.components

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.utils.BufferUtils
import io.github.some_example_name.render.pack.OverlayInstanceBuffer
import java.nio.ByteBuffer

/**
 * Геометрия оверлея на GPU: один квад на все фигуры плюс инстансные данные кадра.
 *
 * Устроена так же, как CellGeometry (см. там, почему сырой GL и VAO): базовый квад —
 * четыре вершины полосой, данные фигуры — атрибутами с делителем 1, раскладка в
 * OverlayInstanceBuffer.
 */
class OverlayGeometry {

    private var vao = 0
    private var baseVbo = 0
    private var instanceVbo = 0
    private var instanceCapacityBytes = 0

    fun create() {
        val gl = Gdx.gl
        val gl30 = Gdx.gl30
            ?: throw IllegalStateException("GL30 required for overlay instancing")

        val ids = BufferUtils.newIntBuffer(2)
        gl.glGenBuffers(2, ids)
        baseVbo = ids.get(0)
        instanceVbo = ids.get(1)

        val vaoIds = BufferUtils.newIntBuffer(1)
        gl30.glGenVertexArrays(1, vaoIds)
        vao = vaoIds.get(0)

        // x — доля длины отрезка (0..1), y — сторона поперёк (-1..1). Полоса из двух
        // треугольников; что именно из этого получается, решает overlay.vert по виду фигуры.
        val corners = floatArrayOf(
            0f, -1f,
            1f, -1f,
            0f, 1f,
            1f, 1f
        )
        val cornerBuffer = BufferUtils.newFloatBuffer(corners.size).apply {
            put(corners)
            flip()
        }

        gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, baseVbo)
        gl.glBufferData(GL20.GL_ARRAY_BUFFER, corners.size * 4, cornerBuffer, GL20.GL_STATIC_DRAW)

        gl30.glBindVertexArray(vao)

        gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, baseVbo)
        gl.glEnableVertexAttribArray(A_CORNER)
        gl.glVertexAttribPointer(A_CORNER, 2, GL20.GL_FLOAT, false, 2 * 4, 0)

        gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, instanceVbo)
        val stride = OverlayInstanceBuffer.STRUCT_SIZE

        gl.glEnableVertexAttribArray(A_FROM)
        gl.glVertexAttribPointer(A_FROM, 2, GL20.GL_FLOAT, false, stride, OverlayInstanceBuffer.OFFSET_FROM)
        gl30.glVertexAttribDivisor(A_FROM, 1)

        gl.glEnableVertexAttribArray(A_TO)
        gl.glVertexAttribPointer(A_TO, 2, GL20.GL_FLOAT, false, stride, OverlayInstanceBuffer.OFFSET_TO)
        gl30.glVertexAttribDivisor(A_TO, 1)

        gl.glEnableVertexAttribArray(A_COLOR)
        gl.glVertexAttribPointer(
            A_COLOR, 4, GL20.GL_UNSIGNED_BYTE, true, stride, OverlayInstanceBuffer.OFFSET_COLOR
        )
        gl30.glVertexAttribDivisor(A_COLOR, 1)

        gl.glEnableVertexAttribArray(A_PARAMS)
        gl.glVertexAttribPointer(
            A_PARAMS, 4, GL20.GL_UNSIGNED_BYTE, true, stride, OverlayInstanceBuffer.OFFSET_PARAMS
        )
        gl30.glVertexAttribDivisor(A_PARAMS, 1)

        // Концы в прошлом тике — для интерполяции между тиками.
        gl.glEnableVertexAttribArray(A_PREV_FROM)
        gl.glVertexAttribPointer(A_PREV_FROM, 2, GL20.GL_FLOAT, false, stride, OverlayInstanceBuffer.OFFSET_PREV_FROM)
        gl30.glVertexAttribDivisor(A_PREV_FROM, 1)

        gl.glEnableVertexAttribArray(A_PREV_TO)
        gl.glVertexAttribPointer(A_PREV_TO, 2, GL20.GL_FLOAT, false, stride, OverlayInstanceBuffer.OFFSET_PREV_TO)
        gl30.glVertexAttribDivisor(A_PREV_TO, 1)

        gl30.glBindVertexArray(0)
        gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0)
    }

    /** Залить фигуры кадра. Рост — новый glBufferData, как в CellGeometry.upload. */
    fun upload(data: ByteBuffer) {
        val byteCount = data.remaining()
        if (byteCount == 0) return

        val gl = Gdx.gl
        gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, instanceVbo)

        if (byteCount > instanceCapacityBytes) {
            var grown = if (instanceCapacityBytes == 0) {
                OverlayInstanceBuffer.INITIAL_CAPACITY * OverlayInstanceBuffer.STRUCT_SIZE
            } else {
                instanceCapacityBytes
            }
            while (grown < byteCount) grown = (grown * 1.5).toInt().coerceAtLeast(byteCount)

            gl.glBufferData(GL20.GL_ARRAY_BUFFER, grown, null, GL20.GL_STREAM_DRAW)
            instanceCapacityBytes = grown
        }

        gl.glBufferSubData(GL20.GL_ARRAY_BUFFER, 0, byteCount, data)
        gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0)
    }

    fun draw(instances: Int) {
        if (instances <= 0) return
        val gl30 = Gdx.gl30 ?: return
        gl30.glBindVertexArray(vao)
        gl30.glDrawArraysInstanced(GL20.GL_TRIANGLE_STRIP, 0, 4, instances)
        gl30.glBindVertexArray(0)
    }

    fun dispose() {
        val gl = Gdx.gl
        val gl30 = Gdx.gl30

        if (baseVbo != 0 || instanceVbo != 0) {
            val deleteBuf = BufferUtils.newIntBuffer(2).apply {
                put(baseVbo)
                put(instanceVbo)
                flip()
            }
            gl.glDeleteBuffers(2, deleteBuf)
            baseVbo = 0
            instanceVbo = 0
            instanceCapacityBytes = 0
        }

        if (vao != 0 && gl30 != null) {
            val deleteBuf = BufferUtils.newIntBuffer(1).apply {
                put(vao)
                flip()
            }
            gl30.glDeleteVertexArrays(1, deleteBuf)
            vao = 0
        }
    }

    private companion object {
        // Номера атрибутов — layout(location = ...) в overlay.vert.
        const val A_CORNER = 0
        const val A_FROM = 1
        const val A_TO = 2
        const val A_COLOR = 3
        const val A_PARAMS = 4
        const val A_PREV_FROM = 5
        const val A_PREV_TO = 6
    }
}
