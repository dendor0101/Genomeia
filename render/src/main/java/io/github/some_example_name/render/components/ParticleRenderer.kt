package io.github.some_example_name.render.components

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL30
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.BufferUtils
import io.github.some_example_name.render.pack.CellInstanceBuffer
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Рисует клетки инстансингом GLES 3.0.
 *
 * Данные инстанса едут обычными вершинными атрибутами с делителем 1 — по 20 байт на
 * клетку, раскладка в CellInstanceBuffer.
 *
 * ЧТО БЫЛО ДО ЭТОГО И ПОЧЕМУ ПОМЕНЯЛОСЬ
 * -------------------------------------
 * Раньше инстансные данные заливались в текстуру RGBA32UI и доставались в вершинном
 * шейдере через texelFetch — по два тексела на клетку, то есть по восемь выборок на
 * инстанс (четыре вершины квада × два). Комментарий объяснял это как «GLES 3.0, нет SSBO»,
 * но SSBO тут и не нужен: glVertexAttribDivisor есть в GLES 3.0 нативно, и выборка
 * атрибутов — это то, что железо делает бесплатно и с кэшем.
 *
 * Что ушло вместе с текстурой данных:
 *   - ручной рост текстуры и пересчёт высоты под число инстансов;
 *   - загрузка glTexSubImage2D двумя вызовами (полные строки + хвостовая);
 *   - распаковка unpackRGBA8 в шейдере — нормализованные байтовые атрибуты приезжают
 *     готовым vec4;
 *   - расхождение платформ: WebGL2 требует строгого совпадения типа TypedArray с
 *     параметром type у glTexSubImage2D, из-за чего целочисленная текстура там не
 *     заводилась. У атрибутов такой проблемы нет вовсе.
 *   - риск MetalANGLE с целочисленными текстурами в вершинном шейдере.
 *
 * ПОЧЕМУ СЫРОЙ GL, А НЕ Mesh ИЗ libGDX
 * ------------------------------------
 * Mesh.enableInstancedRendering принимает данные только как FloatBuffer и требует заранее
 * заданного максимума инстансов — при превышении меш пришлось бы пересоздавать. Здесь же
 * раскладка смешанная (два float плюс три упакованных четвёрки байт), а число клеток
 * меняется каждый кадр, поэтому VBO ведём сами: рост — это один glBufferData.
 */
class ParticleRenderer(private val texturePaths: List<String>) : RenderComponent {

    private lateinit var shader: ShaderProgram
    private var textureArray: Int = 0
    private var numLayers: Int = 0

    /** VAO со всей раскладкой атрибутов: настраивается один раз, дальше только биндится. */
    private var vao = 0

    /** Геометрия одной клетки, общая для всех инстансов. */
    private var baseVbo = 0
    private var baseVertexCount = 0

    /** Инстансные данные, перезаливаются каждый кадр. */
    private var instanceVbo = 0
    private var instanceCapacityBytes = 0

    override fun create() {
        createShader()
        createTextureArray()
        createGeometry()
    }

    private fun createShader() {
        val vertexShader = Gdx.files.internal("shaders/debug/circle_pc.vert").readString()
        val fragmentShader = Gdx.files.internal("shaders/debug/circle.frag").readString()
        shader = ShaderProgram(vertexShader, fragmentShader)
        if (!shader.isCompiled) {
            throw RuntimeException("Shader compilation failed: ${shader.log}")
        }
    }

    private fun createTextureArray() {
        numLayers = texturePaths.size
        if (numLayers == 0) throw IllegalStateException("Нет текстур для TextureArray!")

        val gl30 = Gdx.gl30
            ?: throw IllegalStateException("GL30 required for ParticleRenderer texture array")

        val pixmaps = texturePaths.map { path ->
            val file = Gdx.files.internal(path)
            if (!file.exists()) throw IllegalArgumentException("Текстура не найдена: $path")
            Pixmap(file)
        }

        val width = pixmaps[0].width
        val height = pixmaps[0].height

        for (p in pixmaps) {
            if (p.width != width || p.height != height) {
                throw IllegalStateException(
                    "Все текстуры в TextureArray должны быть одного размера! (${width}×${height})"
                )
            }
        }

        val buffer = BufferUtils.newIntBuffer(1)
        gl30.glGenTextures(1, buffer)
        textureArray = buffer.get(0)

        gl30.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, textureArray)

        gl30.glTexImage3D(
            GL30.GL_TEXTURE_2D_ARRAY, 0, GL30.GL_RGBA8,
            width, height, numLayers, 0,
            GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, null
        )

        for ((layer, pixmap) in pixmaps.withIndex()) {
            gl30.glTexSubImage3D(
                GL30.GL_TEXTURE_2D_ARRAY, 0,
                0, 0, layer,
                pixmap.width, pixmap.height, 1,
                GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE,
                pixmap.pixels
            )
            pixmap.dispose()
        }

        gl30.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY)

        gl30.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_LINEAR_MIPMAP_LINEAR)
        gl30.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_MAG_FILTER, GL30.GL_LINEAR)
        gl30.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_WRAP_S, GL30.GL_REPEAT)
        gl30.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_WRAP_T, GL30.GL_REPEAT)

        gl30.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0)

        println("✅ TextureArray создан: $numLayers слоёв, ${width}×${height} px")
    }

    /**
     * Геометрия одной клетки плюс раскладка инстансных атрибутов — всё в один VAO.
     *
     * VAO здесь не роскошь: в core-профиле десктопного GL нулевой VAO невалиден, то есть
     * без него рисовать нельзя вообще. В GLES 3.0 и WebGL2 нулевой существует, но держать
     * раскладку в объекте всё равно дешевле — она настраивается один раз, а не каждый кадр.
     */
    private fun createGeometry() {
        val gl = Gdx.gl
        val gl30 = Gdx.gl30
            ?: throw IllegalStateException("GL30 required for ParticleRenderer instancing")

        val ids = BufferUtils.newIntBuffer(2)
        gl.glGenBuffers(2, ids)
        baseVbo = ids.get(0)
        instanceVbo = ids.get(1)

        val vaoIds = BufferUtils.newIntBuffer(1)
        gl30.glGenVertexArrays(1, vaoIds)
        vao = vaoIds.get(0)

        val corners = buildFan(FAN_SEGMENTS)
        baseVertexCount = corners.size / FAN_VERTEX_FLOATS
        val cornerBuffer = BufferUtils.newFloatBuffer(corners.size).apply {
            put(corners)
            flip()
        }

        gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, baseVbo)
        gl.glBufferData(GL20.GL_ARRAY_BUFFER, corners.size * 4, cornerBuffer, GL20.GL_STATIC_DRAW)

        gl30.glBindVertexArray(vao)

        // Базовая геометрия — на вершину.
        gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, baseVbo)
        gl.glEnableVertexAttribArray(A_CORNER)
        gl.glVertexAttribPointer(
            A_CORNER, FAN_VERTEX_FLOATS, GL20.GL_FLOAT, false, FAN_VERTEX_FLOATS * 4, 0
        )

        // Инстансные — на клетку. Делитель 1 означает «шаг раз в инстанс, а не в вершину».
        gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, instanceVbo)
        val stride = CellInstanceBuffer.STRUCT_SIZE

        gl.glEnableVertexAttribArray(A_CENTER)
        gl.glVertexAttribPointer(
            A_CENTER, 2, GL20.GL_FLOAT, false, stride, CellInstanceBuffer.OFFSET_CENTER
        )
        gl30.glVertexAttribDivisor(A_CENTER, 1)

        // normalized = true: байт 0..255 приезжает в шейдер как float 0..1. Ровно это
        // раньше делал unpackRGBA8 вручную.
        gl.glEnableVertexAttribArray(A_COLOR)
        gl.glVertexAttribPointer(
            A_COLOR, 4, GL20.GL_UNSIGNED_BYTE, true, stride, CellInstanceBuffer.OFFSET_COLOR
        )
        gl30.glVertexAttribDivisor(A_COLOR, 1)

        gl.glEnableVertexAttribArray(A_SHAPE)
        gl.glVertexAttribPointer(
            A_SHAPE, 4, GL20.GL_UNSIGNED_BYTE, true, stride, CellInstanceBuffer.OFFSET_SHAPE
        )
        gl30.glVertexAttribDivisor(A_SHAPE, 1)

        gl.glEnableVertexAttribArray(A_TYPE)
        gl.glVertexAttribPointer(
            A_TYPE, 4, GL20.GL_UNSIGNED_BYTE, true, stride, CellInstanceBuffer.OFFSET_TYPE
        )
        gl30.glVertexAttribDivisor(A_TYPE, 1)

        gl30.glBindVertexArray(0)
        gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Веер треугольников: центр плюс обод, по три числа на вершину — x, y и d/R.
     *
     * Третья компонента и есть тот самый конус, ради которого веер и нужен: растеризатор
     * интерполирует её линейно, и она приезжает во фрагментный шейдер как расстояние до
     * центра в долях радиуса. Подробности — в шапке circle_pc.vert.
     *
     * Обод отодвинут на 1/cos(π/N): при вписанном многоугольнике середины граней лежали бы
     * ВНУТРИ круга, и клетка выглядела бы меньше положенного. С этим множителем середины
     * граней ложатся точно на круг, а наружу вылезают только вершины — на 0.48 % при N = 32.
     *
     * Метрика при этом остаётся одинаковой для всех инстансов (в вершинах d/R = 1 у всех),
     * поэтому argmin по клеткам не ломается и диаграмма Вороного не съезжает.
     */
    private fun buildFan(segments: Int): FloatArray {
        val vertices = FloatArray((segments + 2) * FAN_VERTEX_FLOATS)
        var i = 0

        // Центр веера.
        vertices[i++] = 0f
        vertices[i++] = 0f
        vertices[i++] = 0f

        val rim = (1.0 / cos(PI / segments)).toFloat()
        // segments + 1: последняя вершина повторяет первую и замыкает веер.
        for (s in 0..segments) {
            val angle = (2.0 * PI * s / segments).toFloat()
            vertices[i++] = cos(angle.toDouble()).toFloat() * rim
            vertices[i++] = sin(angle.toDouble()).toFloat() * rim
            vertices[i++] = 1f
        }
        return vertices
    }

    /**
     * Залить инстансы в VBO.
     *
     * Рост — это просто новый glBufferData большего размера: раскладку атрибутов он не
     * трогает, она живёт в VAO и ссылается на имя буфера, а не на его содержимое.
     * GL_STREAM_DRAW говорит драйверу, что данные пишутся раз и читаются раз, — это как
     * раз наш случай, кадр за кадром.
     */
    private fun uploadInstances(data: ByteBuffer, byteCount: Int) {
        val gl = Gdx.gl
        gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, instanceVbo)

        if (byteCount > instanceCapacityBytes) {
            var grown = if (instanceCapacityBytes == 0) {
                CellInstanceBuffer.INITIAL_CAPACITY * CellInstanceBuffer.STRUCT_SIZE
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

    override fun resize(width: Int, height: Int) {
        // ParticleRenderer doesn't have size-dependent resources
    }

    override fun render(context: RenderContext) {
        val gl = Gdx.gl
        val gl30 = Gdx.gl30 ?: return
        val particleData = context.particleData
        val numInstances = context.numInstances
        val byteCount = particleData?.remaining() ?: 0

        if (context.isNewFrame && particleData != null && byteCount > 0 && numInstances > 0) {
            uploadInstances(particleData, byteCount)
        }

        if (context.usePostProcess) {
            context.sceneFbo?.begin()
        }

        gl.glDisable(GL20.GL_BLEND)
        gl.glEnable(GL20.GL_DEPTH_TEST)
        gl.glDepthFunc(GL20.GL_LESS)
        gl.glDepthMask(true)
        gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        shader.bind()
        shader.setUniformMatrix("u_projTrans", context.cameraProjection)
        shader.setUniformf("u_colorScale", if (context.usePostProcess) 0.0f else 1.0f)
        shader.setUniformi("u_textureArray", 0)

        gl.glActiveTexture(GL20.GL_TEXTURE0)
        gl30.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, textureArray)

        gl30.glBindVertexArray(vao)
        gl30.glDrawArraysInstanced(GL20.GL_TRIANGLE_FAN, 0, baseVertexCount, numInstances)
        gl30.glBindVertexArray(0)

        gl30.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0)

        if (context.usePostProcess) {
            context.sceneFbo?.end()
            context.currentTexture = context.sceneFbo?.colorBufferTexture
        }
    }

    override fun dispose() {
        if (::shader.isInitialized) shader.dispose()

        val gl = Gdx.gl
        val gl30 = Gdx.gl30

        if (textureArray != 0) {
            val deleteBuf = BufferUtils.newIntBuffer(1).apply {
                put(textureArray)
                flip()
            }
            gl.glDeleteTextures(1, deleteBuf)
            textureArray = 0
        }

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

    companion object {
        /**
         * Номера атрибутов. Дублируются в circle_pc.vert через layout(location = ...),
         * поэтому имена атрибутов в шейдере роли не играют и glGetAttribLocation не нужен.
         */
        /**
         * Число сегментов веера. Компромисс: 32 даёт ошибку границы 0.48 %% и 34 вершины
         * на клетку. Меньше — заметнее огранка силуэта на сильном приближении, больше —
         * лишняя вершинная работа там, где клетка занимает несколько пикселей.
         */
        private const val FAN_SEGMENTS = 32

        /** x, y, d/R на вершину. */
        private const val FAN_VERTEX_FLOATS = 3

        private const val A_CORNER = 0
        private const val A_CENTER = 1
        private const val A_COLOR = 2
        private const val A_SHAPE = 3
        private const val A_TYPE = 4
    }
}
