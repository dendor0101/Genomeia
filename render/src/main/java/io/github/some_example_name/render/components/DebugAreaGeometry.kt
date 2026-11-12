package io.github.some_example_name.render.components

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.utils.BufferUtils
import io.github.some_example_name.render.pack.CellInstanceBuffer
import io.github.some_example_name.render.pack.DebugAreaIndices

/** Вершины уже в VBO клеток. В этом VAO делитель нулевой, и индекс выбирает саму клетку. */
internal class DebugAreaGeometry(cellVbo: Int) {
    private val gl = Gdx.gl30
    private val names = BufferUtils.newIntBuffer(1)
    private val vao: Int
    private val elements = gl.glGenBuffer()
    private var uploadedSource: Any? = null
    private var uploadedVersion = 0L

    init {
        gl.glGenVertexArrays(1, names)
        vao = names.get(0)
        gl.glBindVertexArray(vao)
        gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, cellVbo)
        attribute(1, 2, GL20.GL_FLOAT, false, CellInstanceBuffer.OFFSET_CENTER)
        attribute(2, 4, GL20.GL_UNSIGNED_BYTE, true, CellInstanceBuffer.OFFSET_COLOR)
        attribute(5, 4, GL20.GL_UNSIGNED_BYTE, true, CellInstanceBuffer.OFFSET_LOOK)
        attribute(7, 2, GL20.GL_FLOAT, false, CellInstanceBuffer.OFFSET_PREV_CENTER)
        gl.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, elements)
        gl.glBindVertexArray(0)
        gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0)
    }

    private fun attribute(location: Int, size: Int, type: Int, normalized: Boolean, offset: Int) {
        gl.glEnableVertexAttribArray(location)
        gl.glVertexAttribPointer(location, size, type, normalized, CellInstanceBuffer.STRUCT_SIZE, offset)
    }

    fun draw(indices: DebugAreaIndices) {
        gl.glBindVertexArray(vao)
        val count = indices.data.remaining()
        if (indices.source !== uploadedSource || indices.version != uploadedVersion) {
            gl.glBufferData(GL20.GL_ELEMENT_ARRAY_BUFFER, count * 4, indices.data, GL20.GL_DYNAMIC_DRAW)
            uploadedSource = indices.source
            uploadedVersion = indices.version
        }
        gl.glDrawElements(GL20.GL_TRIANGLES, count, GL20.GL_UNSIGNED_INT, 0)
        gl.glBindVertexArray(0)
    }

    fun dispose() {
        gl.glDeleteVertexArrays(1, names)
        gl.glDeleteBuffer(elements)
    }
}
