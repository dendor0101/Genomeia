package io.github.some_example_name.render.components

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL30
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.BufferUtils
import io.github.some_example_name.render.pack.PheromoneInstanceBuffer
import java.nio.Buffer
import java.nio.ByteBuffer

/**
 * Пятна феромонов отладочного режима: мягкий круг цвета феромона, непрозрачность — его
 * концентрация A / (1 + K r^2), край — порог P (shaders/debug_view/pheromone.*).
 *
 * Это реалистичный режим до 06.11 (PheromoneRenderer, удалён в ae52774 вместе с режимом),
 * перенесённый в отладку: те же шейдеры, та же раскладка 16 байт на феромон
 * ([PheromoneInstanceBuffer]), инстансы — тексель RGBA32UI на штуку и glDrawArraysInstanced
 * по одному кваду. Там пятна шли в FBO размытия под виньетку, здесь — прямо в вывод
 * отладочного режима, между клетками и оверлеем (см. DebugRenderer).
 *
 * Текстура данных растёт ×1.5 и не сжимается: в устоявшемся кадре — только заливка
 * glTexSubImage2D, без перевыделений. Создана сырым GL, поэтому при потере контекста её
 * надо забыть ([contextLost]): libGDX восстанавливает свои шейдеры и Mesh, но не её.
 */
internal class PheromoneLayer {

    private lateinit var shader: ShaderProgram
    private lateinit var mesh: Mesh

    /** Текстура данных (RGBA32UI, NEAREST); 0 — ещё не создана. */
    private var dataTexture = 0

    /** Высота текстуры в текселях; ширина постоянная — [TEX_WIDTH]. */
    private var texHeight = 0

    /** Сколько феромонов вмещает текстура: [TEX_WIDTH] × [texHeight]. */
    private var texCapacity = 0

    fun create(shader: ShaderProgram) {
        this.shader = shader
        val attributes = VertexAttributes(VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"))
        mesh = Mesh(false, 4, 0, attributes).apply { setVertices(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)) }
    }

    /** Нарисовать [count] феромонов из [data] в текущий вывод. Смешивание ставит сам. */
    fun draw(context: RenderContext, data: ByteBuffer, count: Int) {
        val gl30 = Gdx.gl30 ?: return
        upload(data, count)

        val gl = Gdx.gl
        gl.glEnable(GL20.GL_BLEND)
        gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        shader.bind()
        shader.setUniformMatrix("u_projTrans", context.cameraProjection)
        shader.setUniformf("u_K", context.pheromoneK)
        shader.setUniformf("u_P", context.pheromoneP)
        shader.setUniformi("u_data", 0)
        shader.setUniformi("u_texWidth", TEX_WIDTH)

        gl.glActiveTexture(GL20.GL_TEXTURE0)
        gl.glBindTexture(GL20.GL_TEXTURE_2D, dataTexture)

        mesh.bind(shader)
        gl30.glDrawArraysInstanced(GL20.GL_TRIANGLE_STRIP, 0, 4, count)
        mesh.unbind(shader)

        gl.glBindTexture(GL20.GL_TEXTURE_2D, 0)
    }

    /**
     * Залить [count] феромонов в текстуру: полные строки одним glTexSubImage2D, хвост —
     * вторым. Шаг строки кратен 16 байтам, так что UNPACK_ALIGNMENT = 4 по умолчанию годится.
     */
    private fun upload(data: ByteBuffer, count: Int) {
        ensureCapacity(count)

        val gl = Gdx.gl
        gl.glBindTexture(GL20.GL_TEXTURE_2D, dataTexture)

        val fullRows = count / TEX_WIDTH
        val remainder = count % TEX_WIDTH
        val oldPosition = data.position()
        val oldLimit = data.limit()
        val buffer = data as Buffer

        if (fullRows > 0) {
            buffer.position(0)
            buffer.limit(fullRows * TEX_WIDTH * STRUCT)
            gl.glTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 0, 0, TEX_WIDTH, fullRows, GL30.GL_RGBA_INTEGER, GL20.GL_UNSIGNED_INT, data)
        }
        if (remainder > 0) {
            val offset = fullRows * TEX_WIDTH * STRUCT
            buffer.position(offset)
            buffer.limit(offset + remainder * STRUCT)
            gl.glTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 0, fullRows, remainder, 1, GL30.GL_RGBA_INTEGER, GL20.GL_UNSIGNED_INT, data)
        }

        // Буфер — снимок симуляции, его читают и другие: положение возвращается как было.
        buffer.limit(oldLimit)
        buffer.position(oldPosition)
        gl.glBindTexture(GL20.GL_TEXTURE_2D, 0)
    }

    private fun ensureCapacity(needed: Int) {
        if (dataTexture != 0 && needed <= texCapacity) return

        if (dataTexture == 0) {
            val ids = BufferUtils.newIntBuffer(1)
            Gdx.gl.glGenTextures(1, ids)
            dataTexture = ids.get(0)
            texCapacity = 0
        }

        var capacity = if (texCapacity == 0) INITIAL_CAPACITY else texCapacity
        while (capacity < needed) capacity = (capacity * 3 / 2).coerceAtLeast(needed)
        // Целыми строками: каждый слот адресуем.
        val height = (capacity + TEX_WIDTH - 1) / TEX_WIDTH

        val gl = Gdx.gl
        gl.glBindTexture(GL20.GL_TEXTURE_2D, dataTexture)
        gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL30.GL_RGBA32UI, TEX_WIDTH, height, 0, GL30.GL_RGBA_INTEGER, GL20.GL_UNSIGNED_INT, null)
        // NEAREST и без мипов: целочисленная текстура с фильтрацией или неполной цепочкой
        // мипов в GLES 3.0 неполна и читается нулями.
        gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MIN_FILTER, GL20.GL_NEAREST)
        gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MAG_FILTER, GL20.GL_NEAREST)
        gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_S, GL20.GL_CLAMP_TO_EDGE)
        gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_T, GL20.GL_CLAMP_TO_EDGE)
        Gdx.gl30?.let {
            it.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_BASE_LEVEL, 0)
            it.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 0)
        }
        gl.glBindTexture(GL20.GL_TEXTURE_2D, 0)

        texHeight = height
        texCapacity = height * TEX_WIDTH
    }

    /** Контекст пересоздан: старой текстуры больше нет, следующий кадр создаст новую. */
    fun contextLost() {
        dataTexture = 0
        texHeight = 0
        texCapacity = 0
    }

    fun dispose() {
        if (::mesh.isInitialized) mesh.dispose()
        if (dataTexture != 0) {
            val ids = BufferUtils.newIntBuffer(1).apply { put(dataTexture); flip() }
            Gdx.gl.glDeleteTextures(1, ids)
        }
        contextLost()
    }

    private companion object {
        /** Ширина текстуры: с запасом под минимальный предел GLES 3.0 (2048). */
        const val TEX_WIDTH = 1024
        const val INITIAL_CAPACITY = PheromoneInstanceBuffer.INITIAL_CAPACITY
        const val STRUCT = PheromoneInstanceBuffer.STRUCT_SIZE
    }
}
