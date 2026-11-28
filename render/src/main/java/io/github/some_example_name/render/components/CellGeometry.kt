package io.github.some_example_name.render.components

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.utils.BufferUtils
import io.github.some_example_name.render.pack.CellInstanceBuffer
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Геометрия клеток на GPU: веер одной клетки плюс инстансные данные всех клеток кадра.
 *
 * Данные инстанса едут обычными вершинными атрибутами с делителем 1 — по 40 байт на
 * клетку, раскладка в CellInstanceBuffer.
 *
 * ЗАЧЕМ ОТДЕЛЬНО ОТ ПРОХОДОВ
 * --------------------------
 * DebugRenderer и NostalgicRenderer используют один корпус и один поток инстансов.

 *
 * Атрибуты привязаны к номерам (layout(location = ...) в шейдерах), а не к именам, поэтому
 * один VAO годится любой программе с той же раскладкой входов.
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
class CellGeometry {



    /** VAO со всей раскладкой атрибутов: настраивается один раз, дальше только биндится. */
    private var vao = 0

    /**
     * Геометрия одной клетки, общая для всех инстансов: оба веера подряд в одном VBO.
     *
     * Один VBO и один VAO, а веер выбирается смещением first у glDrawArraysInstanced.
     * Инстансных атрибутов first не касается — их индекс считается от номера инстанса,
     * а не вершины, — поэтому оба веера читают одни и те же данные клеток.
     */
    private var baseVbo = 0
    private var hullVertexCount = 0

    /** Инстансные данные, перезаливаются каждый кадр. */
    internal var instanceVbo = 0
        private set
    private var instanceCapacityBytes = 0

    /**
     * Неподвижный слой (27.11): террейн и стенка у рамки в своём VBO и своём VAO с той же
     * раскладкой. Снимок мира их больше не несёт — они не двигаются, и перекладывать десятки
     * тысяч одинаковых инстансов каждый тик было бы впустую (замер на телефонах — TODO 11.3б).
     */
    private var staticVao = 0
    private var staticVbo = 0
    private var staticCapacityBytes = 0

    /**
     * Геометрия одной клетки плюс раскладка инстансных атрибутов — всё в один VAO.
     *
     * VAO здесь не роскошь: в core-профиле десктопного GL нулевой VAO невалиден, то есть
     * без него рисовать нельзя вообще. В GLES 3.0 и WebGL2 нулевой существует, но держать
     * раскладку в объекте всё равно дешевле — она настраивается один раз, а не каждый кадр.
     */
    fun create() {
        val gl = Gdx.gl
        val gl30 = Gdx.gl30
            ?: throw IllegalStateException("GL30 required for cell instancing")

        val ids = BufferUtils.newIntBuffer(3)
        gl.glGenBuffers(3, ids)
        baseVbo = ids.get(0)
        instanceVbo = ids.get(1)
        staticVbo = ids.get(2)

        val vaoIds = BufferUtils.newIntBuffer(2)
        gl30.glGenVertexArrays(2, vaoIds)
        vao = vaoIds.get(0)
        staticVao = vaoIds.get(1)

        val corners = buildFan(HULL_SEGMENTS)
        hullVertexCount = corners.size / FAN_VERTEX_FLOATS
        val cornerBuffer = BufferUtils.newFloatBuffer(corners.size).apply {
            put(corners)
            flip()
        }

        gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, baseVbo)
        gl.glBufferData(GL20.GL_ARRAY_BUFFER, corners.size * 4, cornerBuffer, GL20.GL_STATIC_DRAW)

        setupVao(vao, instanceVbo)
        setupVao(staticVao, staticVbo)
    }

    /** Раскладка атрибутов: веер [baseVbo] на вершину и инстансы [instances] на клетку — в [target]. */
    private fun setupVao(target: Int, instances: Int) {
        val gl = Gdx.gl
        val gl30 = Gdx.gl30 ?: return
        gl30.glBindVertexArray(target)

        // Базовая геометрия — на вершину.
        gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, baseVbo)
        gl.glEnableVertexAttribArray(A_CORNER)
        gl.glVertexAttribPointer(
            A_CORNER, FAN_VERTEX_FLOATS, GL20.GL_FLOAT, false, FAN_VERTEX_FLOATS * 4, 0
        )

        // Инстансные — на клетку. Делитель 1 означает «шаг раз в инстанс, а не в вершину».
        gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, instances)
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

        // Вид в отладочном режиме (CellLook). Остальные шейдеры клеток его не объявляют —
        // атрибут просто не читается.
        gl.glEnableVertexAttribArray(A_LOOK)
        gl.glVertexAttribPointer(
            A_LOOK, 4, GL20.GL_UNSIGNED_BYTE, true, stride, CellInstanceBuffer.OFFSET_LOOK
        )
        gl30.glVertexAttribDivisor(A_LOOK, 1)

        // Направление — нормализованные short: -32767..32767 приезжает в шейдер как -1..1.
        // Байта на угол не хватало, см. CellInstanceBuffer.
        gl.glEnableVertexAttribArray(A_DIR)
        gl.glVertexAttribPointer(
            A_DIR, 2, GL20.GL_SHORT, true, stride, CellInstanceBuffer.OFFSET_DIR
        )
        gl30.glVertexAttribDivisor(A_DIR, 1)

        // Поза в прошлом тике — для интерполяции между тиками (CellInstanceBuffer).
        gl.glEnableVertexAttribArray(A_PREV_CENTER)
        gl.glVertexAttribPointer(
            A_PREV_CENTER, 2, GL20.GL_FLOAT, false, stride, CellInstanceBuffer.OFFSET_PREV_CENTER
        )
        gl30.glVertexAttribDivisor(A_PREV_CENTER, 1)

        gl.glEnableVertexAttribArray(A_PREV_DIR)
        gl.glVertexAttribPointer(
            A_PREV_DIR, 2, GL20.GL_SHORT, true, stride, CellInstanceBuffer.OFFSET_PREV_DIR
        )
        gl30.glVertexAttribDivisor(A_PREV_DIR, 1)

        gl30.glBindVertexArray(0)
        gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Веер треугольников: центр плюс обод, по три числа на вершину — x, y и d/R.
     *
     * Третья компонента и есть тот самый конус, ради которого веер и нужен: растеризатор
     * интерполирует её линейно, и она приезжает во фрагментный шейдер как расстояние до
     * центра в долях радиуса.
     *
     * Обод отодвинут на 1/cos(π/N): при вписанном многоугольнике середины граней лежали бы
     * ВНУТРИ круга, и клетка выглядела бы меньше положенного. С этим множителем середины
     * граней ложатся точно на круг, а наружу вылезают только вершины — на 0.48 % при N = 32.
     *
     * Метрика при этом остаётся одинаковой для всех инстансов (в вершинах d/R = 1 у всех),
     * поэтому argmin по клеткам не ломается и диаграмма Вороного не съезжает.
     *
     * Тот же множитель делает восьмиугольный корпус описанным: середины граней восьмиугольника
     * касаются окружности, и круг лежит внутри целиком.
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
    fun upload(data: ByteBuffer) {
        instanceCapacityBytes = uploadInto(instanceVbo, instanceCapacityBytes, data, GL20.GL_STREAM_DRAW)
    }

    /**
     * Залить неподвижный слой (террейн, стенка у рамки — 27.11): тот же формат инстансов, свой
     * VBO. Заливается при новом мире или когда в кадр вошёл другой набор кусков террейна
     * (VisibleCells), а не каждый снимок: GL_STATIC_DRAW.
     */
    fun uploadStatic(data: ByteBuffer) {
        staticCapacityBytes = uploadInto(staticVbo, staticCapacityBytes, data, GL20.GL_STATIC_DRAW)
    }

    /** Байты [data] в буфер [vbo]; растёт новым glBufferData. Возвращает ёмкость буфера. */
    private fun uploadInto(vbo: Int, capacity: Int, data: ByteBuffer, usage: Int): Int {
        val byteCount = data.remaining()
        if (byteCount == 0) return capacity

        val gl = Gdx.gl
        gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo)

        var size = capacity
        if (byteCount > capacity) {
            var grown = if (capacity == 0) {
                CellInstanceBuffer.INITIAL_CAPACITY * CellInstanceBuffer.STRUCT_SIZE
            } else {
                capacity
            }
            while (grown < byteCount) grown = (grown * 1.5).toInt().coerceAtLeast(byteCount)

            gl.glBufferData(GL20.GL_ARRAY_BUFFER, grown, null, usage)
            size = grown
        }

        gl.glBufferSubData(GL20.GL_ARRAY_BUFFER, 0, byteCount, data)
        gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0)
        return size
    }

    /** Нарисовать [instances] клеток текущей привязанной программой. */
    fun draw(instances: Int) = drawVao(vao, instances)

    /** Нарисовать [instances] инстансов неподвижного слоя ([uploadStatic]) той же программой. */
    fun drawStatic(instances: Int) = drawVao(staticVao, instances)

    private fun drawVao(target: Int, instances: Int) {
        if (instances <= 0) return
        val gl30 = Gdx.gl30 ?: return
        gl30.glBindVertexArray(target)
        gl30.glDrawArraysInstanced(GL20.GL_TRIANGLE_FAN, 0, hullVertexCount, instances)
        gl30.glBindVertexArray(0)
    }

    /**
     * Контекст GL потерян и создан заново (GlContextWatch): прежние имена буферов и VAO в
     * новом контексте ничего не значат. Удалять их нельзя — те же номера уже могут
     * принадлежать чужим объектам, — поэтому только забыть и создать заново. Данные клеток
     * перезальёт следующий кадр: WorldRenderer сбрасывает свою пометку о залитом буфере.
     */
    fun recreateAfterContextLoss() {
        vao = 0
        staticVao = 0
        baseVbo = 0
        instanceVbo = 0
        staticVbo = 0
        instanceCapacityBytes = 0
        staticCapacityBytes = 0
        create()
    }

    fun dispose() {
        val gl = Gdx.gl
        val gl30 = Gdx.gl30

        if (baseVbo != 0 || instanceVbo != 0 || staticVbo != 0) {
            val deleteBuf = BufferUtils.newIntBuffer(3).apply {
                put(baseVbo)
                put(instanceVbo)
                put(staticVbo)
                flip()
            }
            gl.glDeleteBuffers(3, deleteBuf)
            baseVbo = 0
            instanceVbo = 0
            staticVbo = 0
            instanceCapacityBytes = 0
            staticCapacityBytes = 0
        }

        if ((vao != 0 || staticVao != 0) && gl30 != null) {
            val deleteBuf = BufferUtils.newIntBuffer(2).apply {
                put(vao)
                put(staticVao)
                flip()
            }
            gl30.glDeleteVertexArrays(2, deleteBuf)
            vao = 0
            staticVao = 0
        }
    }

    companion object {
        /**
         * Сегментов у корпуса клетки. Восемь — компромисс между вершинами и
         * фрагментами: площадь описанного восьмиугольника на 5.5 %% больше круга, и эти
         * фрагменты отсекаются discard'ом впустую; у квадрата было бы 27 %%, зато 4 вершины.
         */
        private const val HULL_SEGMENTS = 8

        /** x, y, d/R на вершину. */
        private const val FAN_VERTEX_FLOATS = 3

        /**
         * Номера атрибутов. Дублируются в шейдерах клеток (debug_view/cell.vert,
         * nostalgic.vert) через layout(location = ...), поэтому имена атрибутов в шейдере
         * роли не играют и glGetAttribLocation не нужен.
         */
        private const val A_CORNER = 0
        private const val A_CENTER = 1
        private const val A_COLOR = 2
        private const val A_SHAPE = 3
        private const val A_TYPE = 4
        private const val A_LOOK = 5
        private const val A_DIR = 6
        private const val A_PREV_CENTER = 7
        private const val A_PREV_DIR = 8
    }
}
