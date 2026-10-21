package io.github.some_example_name.render.pack

import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверка раскладки инстанса клетки против ТОГО ЖЕ разбора, что делает GPU.
 *
 * Смысл этих тестов не в том, чтобы «покрыть класс», а в том, что раскладка живёт в трёх
 * местах сразу — здесь, в объявлении атрибутов CellGeometry и в шейдерах клеток — и
 * разъехаться они могут молча. Поэтому распаковка ниже написана не «как удобно», а
 * буквально повторяет то, что делает железо выборки атрибутов и вершинный шейдер:
 *
 *   GL_UNSIGNED_BYTE + normalized  →  float = байт / 255.0
 *   GL_SHORT + normalized          →  float = max(short / 32767.0, -1.0)
 *   cosA, sinA = a_dir.x, a_dir.y;  прошлые — a_prevDir.x, a_prevDir.y
 *   center = mix(a_center, a_prevCenter, u_lag)
 *   radius = 0.05 + a_shape.z * 0.7
 *   seed  = byteValue(a_type.y) + byteValue(a_type.z) * 256.0
 *   isCell = младший бит byteValue(a_type.w) равен нулю     (nostalgic.vert, debug_view/cell.vert)
 *   pattern = byteValue(a_look.x); action = знаковый byteValue(a_look.y) / 127;
 *   opacity = 1 - byteValue(a_look.z) / 255                   (debug_view/cell.vert)
 *
 * ВНИМАНИЕ: здесь намеренно ЛИТЕРАЛЫ, а не константы из CellInstanceBuffer. Тест сверяет
 * Kotlin с ШЕЙДЕРОМ, а шейдер про константы Kotlin ничего не знает — в нём голые числа.
 * Если бы декодер брал RADIUS_SPAN из кодера, правка этой константы прошла бы тест
 * насквозь: обе стороны поехали бы синхронно, а circle_pc.vert остался бы со старым 0.7.
 */
class CellInstanceBufferTest {

    // ==================== Разбор, повторяющий GPU ====================

    /** Нормализованный байт: ровно то, что отдаёт GL для GL_UNSIGNED_BYTE + normalized. */
    private fun normalized(packed: Int, index: Int): Float =
        ((packed ushr (index * 8)) and 0xFF) / 255f

    /** `byteValue()` из circle_pc.vert: round(normalized * 255). */
    private fun byteValue(packed: Int, index: Int): Int =
        (normalized(packed, index) * 255f).roundToInt()

    /** GL_SHORT + normalized: младший (index 0) или старший (index 1) short слова. */
    private fun shortNormalized(packed: Int, index: Int): Float {
        val raw = ((packed ushr (index * 16)) and 0xFFFF).toShort().toInt()
        return maxOf(raw / 32767f, -1f)
    }

    /** `a_dir.x` / `a_dir.y` — cos и sin направления. */
    private fun shaderDir(dir: Int, index: Int): Float = shortNormalized(dir, index)


    /** `radius = 0.05 + a_shape.z * 0.7` */
    private fun shaderRadius(shape: Int): Float = 0.05f + normalized(shape, 2) * 0.7f

    /** Доля энергии: ядро = `a_shape.w * u_nucleusAtFullEnergy` (nostalgic.vert, debug_view/cell.vert). */
    private fun shaderEnergyFraction(shape: Int): Float = normalized(shape, 3)

    /** `ex_cellType = int(byteValue(a_type.x))` */
    private fun shaderCellType(type: Int): Int = byteValue(type, 0)

    /** `seed = byteValue(a_type.y) + byteValue(a_type.z) * 256.0` */
    private fun shaderSeed(type: Int): Int = byteValue(type, 1) + byteValue(type, 2) * 256

    /** nostalgic.vert: `isCell = mod(floor(a_type.w * 255.0 + 0.5), 2.0) < 1.0` */
    private fun shaderIsCell(type: Int): Boolean = byteValue(type, 3) % 2 < 1

    /** debug_view/cell.vert: `v_pattern = int(byteValue(a_look.x))` */
    private fun shaderPattern(look: Int): Int = byteValue(look, 0)

    /** debug_view/cell.vert: знаковый байт, `if (action > 127) action -= 256; v_action = action / 127` */
    private fun shaderAction(look: Int): Float {
        var action = byteValue(look, 1)
        if (action > 127) action -= 256
        return action / 127f
    }

    /** debug_view/cell.vert: `v_opacity = 1.0 - byteValue(a_look.z) / 255.0` */
    private fun shaderOpacity(look: Int): Float = 1f - byteValue(look, 2) / 255f

    private fun readInstance(data: ByteBuffer, index: Int): Instance {
        val base = index * CellInstanceBuffer.STRUCT_SIZE
        return Instance(
            x = data.getFloat(base + CellInstanceBuffer.OFFSET_CENTER),
            y = data.getFloat(base + CellInstanceBuffer.OFFSET_CENTER + 4),
            color = data.getInt(base + CellInstanceBuffer.OFFSET_COLOR),
            shape = data.getInt(base + CellInstanceBuffer.OFFSET_SHAPE),
            type = data.getInt(base + CellInstanceBuffer.OFFSET_TYPE),
            look = data.getInt(base + CellInstanceBuffer.OFFSET_LOOK),
            dir = data.getInt(base + CellInstanceBuffer.OFFSET_DIR),
            prevX = data.getFloat(base + CellInstanceBuffer.OFFSET_PREV_CENTER),
            prevY = data.getFloat(base + CellInstanceBuffer.OFFSET_PREV_CENTER + 4),
            prevDir = data.getInt(base + CellInstanceBuffer.OFFSET_PREV_DIR)
        )
    }

    private data class Instance(
        val x: Float,
        val y: Float,
        val color: Int,
        val shape: Int,
        val type: Int,
        val look: Int,
        val dir: Int,
        val prevX: Float,
        val prevY: Float,
        val prevDir: Int
    )

    // ==================== Тесты ====================

    @Test
    fun `раскладка инстанса ровно 40 байт и смещения выровнены`() {
        assertEquals(40, CellInstanceBuffer.STRUCT_SIZE, "менять только вместе с CellGeometry и RenderSceneDump")

        // GL и особенно WebGL2 требуют, чтобы смещение атрибута было кратно размеру его
        // типа, а шаг — размеру самого крупного. У нас крупнейший тип float (4 байта).
        assertEquals(0, CellInstanceBuffer.STRUCT_SIZE % 4, "шаг обязан быть кратен 4")
        assertEquals(0, CellInstanceBuffer.OFFSET_CENTER % 4)
        assertEquals(0, CellInstanceBuffer.OFFSET_COLOR % 4)
        assertEquals(0, CellInstanceBuffer.OFFSET_SHAPE % 4)
        assertEquals(0, CellInstanceBuffer.OFFSET_TYPE % 4)
        assertEquals(0, CellInstanceBuffer.OFFSET_LOOK % 4)
        assertEquals(0, CellInstanceBuffer.OFFSET_DIR % 4)
        assertEquals(0, CellInstanceBuffer.OFFSET_PREV_CENTER % 4)
        assertEquals(0, CellInstanceBuffer.OFFSET_PREV_DIR % 4)

        // Поля не должны перекрываться и обязаны покрывать инстанс целиком.
        assertEquals(8, CellInstanceBuffer.OFFSET_COLOR, "после двух float")
        assertEquals(12, CellInstanceBuffer.OFFSET_SHAPE)
        assertEquals(16, CellInstanceBuffer.OFFSET_TYPE)
        assertEquals(20, CellInstanceBuffer.OFFSET_LOOK)
        assertEquals(24, CellInstanceBuffer.OFFSET_DIR)
        assertEquals(28, CellInstanceBuffer.OFFSET_PREV_CENTER)
        assertEquals(36, CellInstanceBuffer.OFFSET_PREV_DIR, "после двух float прошлого центра")
        assertEquals(CellInstanceBuffer.STRUCT_SIZE, CellInstanceBuffer.OFFSET_PREV_DIR + 4, "prevDir — последнее поле")
        assertEquals(CellInstanceBuffer.STRUCT_SIZE, CellInstanceBuffer.STRUCT_INTS * 4)
    }

    @Test
    fun `константы диапазонов совпадают с литералами в шейдере`() {
        // Прямая сверка с `radius = 0.05 + a_shape.z * 0.7` из circle_pc.vert.
        // Падение этого теста означает: либо константу поменяли, не тронув шейдер,
        // либо шейдер поменяли, не тронув константу. Оба случая — молча битая картинка.
        assertEquals(0.05f, CellInstanceBuffer.RADIUS_MIN, "circle_pc.vert: radius = 0.05 + ...")
        assertEquals(0.7f, CellInstanceBuffer.RADIUS_SPAN, "circle_pc.vert: ... + a_shape.z * 0.7")
    }

    @Test
    fun `записанная клетка читается обратно теми же формулами, что на GPU`() {
        val buffer = CellInstanceBuffer(initialCapacity = 4)
        buffer.begin(1)
        buffer.putCell(
            x = 12.5f,
            y = -3.25f,
            color = 0x11223344,
            angleCos = 0.6f,
            angleSin = -0.8f,
            radius = 0.4f,
            energy = 5f,
            cellType = 7,
            noiseSeed = 12345
        )
        val data = buffer.end()

        assertEquals(1, buffer.count)
        assertEquals(CellInstanceBuffer.STRUCT_SIZE, data.remaining())

        val cell = readInstance(data, 0)

        // Координаты и цвет идут как есть, без потерь.
        assertEquals(12.5f, cell.x)
        assertEquals(-3.25f, cell.y)
        assertEquals(0x11223344, cell.color)

        // Направление — по 16 бит, допуск в одну ступень 1/32767.
        assertNear(0.6f, shaderDir(cell.dir, 0), 1f / 32767f, "cos")
        assertNear(-0.8f, shaderDir(cell.dir, 1), 1f / 32767f, "sin")

        // Радиус — через байт, с допуском на квантование в 1/255.
        val quantum = 1f / 255f
        assertNear(0.4f, shaderRadius(cell.shape), 2 * quantum * 0.7f, "radius")

        // Без прошлой позы клетка стоит: прошлая поза ровно нынешняя, бит в бит.
        assertEquals(cell.x, cell.prevX)
        assertEquals(cell.y, cell.prevY)
        assertEquals(cell.dir, cell.prevDir)

        // Энергия: байт = (e/10)*255, в шейдере обратно — доля a_shape.w.
        assertNear(5f / 10f, shaderEnergyFraction(cell.shape), 2 * quantum, "energy")

        // Тип и ключ шума обязаны восстанавливаться ТОЧНО, без допуска.
        assertEquals(7, shaderCellType(cell.type))
        assertEquals(12345, shaderSeed(cell.type))
    }

    @Test
    fun `тип клетки и ключ шума точны на всём диапазоне`() {
        // Байт нормализуется как b/255, а это не всегда точно представимо во float.
        // Без округления в шейдере тип клетки съезжал бы на единицу — то есть клетка
        // брала бы ЧУЖОЙ слой текстуры. Проверяем все 256 значений и границы ключа.
        for (cellType in 0..255) {
            val packed = CellInstanceBuffer.type(cellType = cellType, noiseSeed = 0)
            assertEquals(cellType, shaderCellType(packed), "тип $cellType не восстановился")
        }
        for (seed in listOf(0, 1, 255, 256, 257, 4242, 32768, 65534, 65535)) {
            val packed = CellInstanceBuffer.type(cellType = 3, noiseSeed = seed)
            assertEquals(seed, shaderSeed(packed), "ключ $seed не восстановился")
            assertEquals(3, shaderCellType(packed), "ключ $seed повредил тип клетки")
        }
    }

    @Test
    fun `ключ шума закреплён за клеткой, а не за позицией в буфере`() {
        // Ради этого свойства ключ и завели: он берётся из инстансных данных, а не из
        // gl_InstanceID. Иначе рождение клетки в раннем слоте сдвигало бы все последующие,
        // и текстуры визуально крутились бы у всего тела, пока организм растёт.
        val buffer = CellInstanceBuffer(initialCapacity = 4)

        buffer.begin(2)
        buffer.putCell(0f, 0f, 0, 1f, 0f, 0.3f, 0f, 1, noiseSeed = 777)
        buffer.putCell(1f, 1f, 0, 1f, 0f, 0.3f, 0f, 1, noiseSeed = 999)
        val first = buffer.end()
        val seedAtSlot1 = shaderSeed(readInstance(first, 1).type)

        // Та же клетка, но теперь она первая в буфере.
        buffer.begin(1)
        buffer.putCell(1f, 1f, 0, 1f, 0f, 0.3f, 0f, 1, noiseSeed = 999)
        val second = buffer.end()
        val seedAtSlot0 = shaderSeed(readInstance(second, 0).type)

        assertEquals(999, seedAtSlot1)
        assertEquals(seedAtSlot1, seedAtSlot0, "ключ не должен зависеть от слота в буфере")
    }

    @Test
    fun `признак не-клетки едет своим байтом и не трогает тип и ключ`() {
        // По нему ностальгический шейдер решает, рисовать ли ядро. Клетка — значение по
        // умолчанию: ноль в байте флагов у всех, кто про флаги не знает (редактор, старые
        // дампы), обязан означать клетку.
        val cell = CellInstanceBuffer.type(cellType = 7, noiseSeed = 0xBEEF)
        val notCell = CellInstanceBuffer.type(cellType = 7, noiseSeed = 0xBEEF, isCell = false)

        assertTrue(shaderIsCell(cell), "по умолчанию — клетка")
        assertTrue(!shaderIsCell(notCell), "не-клетка не распозналась")
        assertEquals(0, byteValue(cell, 3), "у клетки байт флагов пустой")

        for (packed in listOf(cell, notCell)) {
            assertEquals(7, shaderCellType(packed), "флаг повредил тип")
            assertEquals(0xBEEF, shaderSeed(packed), "флаг повредил ключ шума")
        }

        // И через putCell — тем путём, которым пишет стенд и редактор.
        val data = CellInstanceBuffer(initialCapacity = 2).apply {
            begin(2)
            putCell(0f, 0f, 0, 1f, 0f, 0.3f, 0f, 3, 11)
            putCell(0f, 0f, 0, 1f, 0f, 0.3f, 0f, 3, 11, isCell = false)
        }.end()
        assertTrue(shaderIsCell(readInstance(data, 0).type))
        assertTrue(!shaderIsCell(readInstance(data, 1).type))
    }

    @Test
    fun `вид в отладке едет своим полем и читается так же, как в cell vert`() {
        // Ноль во всех байтах — прежняя плоская непрозрачная клетка: его пишут все, кто
        // про поле не знает (реалистичный режим, превью геномов, старые дампы).
        val data = CellInstanceBuffer(initialCapacity = 4).apply {
            begin(4)
            putCell(0f, 0f, 0, 1f, 0f, 0.3f, 0f, 13, 0x1234)
            putCell(0f, 0f, 0, 1f, 0f, 0.3f, 0f, 13, 0x1234, look = CellLook.look(CellLook.MUSCLE, action = -1f))
            putCell(0f, 0f, 0, 1f, 0f, 0.3f, 0f, 13, 0x1234, look = CellLook.look(CellLook.CHIP, action = 0.5f, transparency = 0.55f))
            putCell(0f, 0f, 0, 1f, 0f, 0.3f, 0f, 13, 0x1234, look = CellLook.look(CellLook.PLAIN, action = 1f))
        }.end()

        val plain = readInstance(data, 0).look
        assertEquals(0, plain)
        assertEquals(CellLook.PLAIN, shaderPattern(plain))
        assertEquals(0f, shaderAction(plain), "ноль сигнала обязан быть ровно нулём")
        assertEquals(1f, shaderOpacity(plain))

        val muscle = readInstance(data, 1).look
        assertEquals(CellLook.MUSCLE, shaderPattern(muscle))
        assertEquals(-1f, shaderAction(muscle))
        assertEquals(1f, shaderOpacity(muscle))

        val ghost = readInstance(data, 2).look
        assertEquals(CellLook.CHIP, shaderPattern(ghost))
        assertNear(0.5f, shaderAction(ghost), 1f / 127f, "сигнал")
        assertNear(0.45f, shaderOpacity(ghost), 1f / 255f, "непрозрачность призрака")

        assertEquals(1f, shaderAction(readInstance(data, 3).look))

        // Вид не трогает остальные поля.
        repeat(4) { i ->
            val cell = readInstance(data, i)
            assertEquals(13, shaderCellType(cell.type))
            assertEquals(0x1234, shaderSeed(cell.type))
            assertTrue(shaderIsCell(cell.type))
        }
    }

    @Test
    fun `сигнал за пределами и NaN зажимаются, а не переполняют соседний байт`() {
        assertEquals(1f, shaderAction(CellLook.look(CellLook.PLAIN, action = 50f)))
        assertEquals(-1f, shaderAction(CellLook.look(CellLook.PLAIN, action = -50f)))
        assertEquals(0f, shaderAction(CellLook.look(CellLook.PLAIN, action = Float.NaN)))
        // Отрицательный сигнал не должен залезть в байт прозрачности.
        assertEquals(1f, shaderOpacity(CellLook.look(CellLook.EYE, action = -0.3f)))
        assertEquals(CellLook.EYE, shaderPattern(CellLook.look(CellLook.EYE, action = -0.3f, transparency = 1f)))
    }

    @Test
    fun `значения за границами диапазона зажимаются, а не переполняют соседнее поле`() {
        val huge = CellInstanceBuffer.radiusByte(1000f)
        val negative = CellInstanceBuffer.radiusByte(-1000f)
        assertEquals(255, huge)
        assertEquals(0, negative)

        assertEquals(1f, shaderDir(CellInstanceBuffer.dir(10f, -10f), 0))
        assertEquals(-1f, shaderDir(CellInstanceBuffer.dir(10f, -10f), 1))
        assertEquals(0f, shaderDir(CellInstanceBuffer.dir(Float.NaN, 0f), 0), "NaN — ноль, а не мусор")
        assertEquals(255, CellInstanceBuffer.energyByte(1e9f))

        // Тип клетки за пределом байта не должен залезть в ключ шума.
        val packed = CellInstanceBuffer.type(cellType = 9999, noiseSeed = 0)
        assertEquals(0, shaderSeed(packed), "переполнение типа не должно попасть в seed")
        assertEquals(255, shaderCellType(packed))

        // И наоборот: ключ шире 16 бит не должен затирать тип.
        val wide = CellInstanceBuffer.type(cellType = 13, noiseSeed = 0x1_2345)
        assertEquals(0x2345, shaderSeed(wide))
        assertEquals(13, shaderCellType(wide))
    }

    @Test
    fun `поля shape не перетекают друг в друга`() {
        val packed = CellInstanceBuffer.shape(
            radiusByte = 0x56,
            energyByte = 0x78
        )
        assertEquals(0, packed and 0xFFFF, "два младших байта свободны")
        assertEquals(0x56, (packed ushr 16) and 0xFF)
        assertEquals(0x78, (packed ushr 24) and 0xFF)

        // Отрицательное направление не должно затереть соседний short.
        val dir = CellInstanceBuffer.dir(-1f, 0.5f)
        assertEquals(-1f, shaderDir(dir, 0))
        assertNear(0.5f, shaderDir(dir, 1), 1f / 32767f, "sin рядом с отрицательным cos")
    }

    @Test
    fun `угол восстанавливается точнее пикселя на любом приближении`() {
        // Ради этого направление и переехало с байта на 16 бит: при байте угол прыгал
        // ступеньками до 0.45°, и текстура вращающейся клетки дёргалась. Обходим полный
        // круг с шагом меньше прежней ступени и сверяем угол, который увидит шейдер.
        var worst = 0.0
        var previous = Double.NaN
        var steps = 0
        val count = 20_000
        for (i in 0 until count) {
            val angle = 2.0 * Math.PI * i / count
            val dir = CellInstanceBuffer.dir(cos(angle).toFloat(), sin(angle).toFloat())
            val seen = atan2(shaderDir(dir, 1).toDouble(), shaderDir(dir, 0).toDouble())
            var error = abs(seen - angle)
            if (error > Math.PI) error = 2 * Math.PI - error
            worst = maxOf(worst, error)
            if (!previous.isNaN() && seen != previous) steps++
            previous = seen
        }
        // 3e-5 рад — ступень 1/32767; клетка радиусом 1000 пикселей сдвигает край на 0.03 px.
        assertTrue(worst < 6e-5, "угол врёт на $worst рад")
        // Каждый шаг в 0.018° виден шейдеру: при байте подряд шли десятки одинаковых значений.
        assertTrue(steps > count * 0.99, "угол менялся только на $steps шагах из $count")
    }

    @Test
    fun `прошлая поза доезжает до шейдера как есть`() {
        val data = CellInstanceBuffer(initialCapacity = 1).apply {
            begin(1)
            putCell(
                10f, 20f, 0, 1f, 0f, 0.3f, 0f, 1, 7,
                prevX = 9.25f, prevY = 21.5f, prevCos = 0.6f, prevSin = -0.8f
            )
        }.end()
        val cell = readInstance(data, 0)

        // Позиция — float как есть: интерполяция не теряет ни бита и ничего не округляет.
        assertEquals(9.25f, cell.prevX)
        assertEquals(21.5f, cell.prevY)
        assertNear(0.6f, shaderDir(cell.prevDir, 0), 1f / 32767f, "прошлый cos")
        assertNear(-0.8f, shaderDir(cell.prevDir, 1), 1f / 32767f, "прошлый sin")
        assertEquals(1f, shaderDir(cell.dir, 0), "нынешнее направление не задето")
    }

    @Test
    fun `перенос дальше MOTION_RANGE — не движение`() {
        // Клетку, перенесённую мышью через полмира, нельзя тянуть полосой через экран:
        // снимок ставит ей прошлую позу равной нынешней (RenderBufferManager.remember).
        assertTrue(CellInstanceBuffer.isMotion(16f, -16f), "граница ещё движение")
        // Предел скорости физики — 4 единицы за тик (HALF_CHUNK_HEIGHT): частица на пределе
        // обязана интерполироваться с любым округлением, иначе тело дёргается.
        assertTrue(CellInstanceBuffer.isMotion(4.001f, -4.001f), "частица на пределе скорости")
        assertTrue(!CellInstanceBuffer.isMotion(50f, 0f))
        assertTrue(!CellInstanceBuffer.isMotion(0f, -16.01f))
        assertTrue(!CellInstanceBuffer.isMotion(Float.NaN, 0f), "NaN — тоже не движение")
    }

    @Test
    fun `write и putAll дают те же байты, что put`() {
        // write + putAll — путь снимка мира (int-массив в потоке симуляции, копия на
        // GL-потоке), put — путь редактора. Разойтись они не имеют права.
        val dir = CellInstanceBuffer.dir(-0.3f, 0.95f)
        val prevDir = CellInstanceBuffer.dir(0.1f, 0.99f)
        val shape = CellInstanceBuffer.shape(77, 12)
        val type = CellInstanceBuffer.type(21, 4242, isCell = false)

        val viaPut = CellInstanceBuffer(initialCapacity = 2).apply {
            begin(2)
            put(5f, -6f, 0x11223344, shape, type, look = 9, dir = dir, prevX = 4.5f, prevY = -6.25f, prevDir = prevDir)
            put(1f, 2f, 3, shape, type, look = 0, dir = dir)
        }.end()

        val ints = IntArray(2 * CellInstanceBuffer.STRUCT_INTS)
        CellInstanceBuffer.write(ints, 0, 5f, -6f, 0x11223344, shape, type, 9, dir, 4.5f, -6.25f, prevDir)
        CellInstanceBuffer.write(ints, 1, 1f, 2f, 3, shape, type, 0, dir, 1f, 2f, dir)
        // Дописывается после уже лежащего: проверяем и смещение позиции.
        val viaWrite = CellInstanceBuffer(initialCapacity = 1).apply {
            begin()
            putAll(ints, 2)
        }.end()

        assertEquals(viaPut.remaining(), viaWrite.remaining())
        for (i in 0 until viaPut.remaining()) {
            assertEquals(viaPut.get(i), viaWrite.get(i), "байт $i разошёлся")
        }
    }

    @Test
    fun `упакованное направление читается обратно так же, как в шейдере`() {
        for (angle in listOf(0.0, 0.7, 2.0, -2.5, Math.PI)) {
            val dir = CellInstanceBuffer.dir(cos(angle).toFloat(), sin(angle).toFloat())
            assertEquals(shaderDir(dir, 0), CellInstanceBuffer.dirCos(dir))
            assertEquals(shaderDir(dir, 1), CellInstanceBuffer.dirSin(dir))
        }
    }

    @Test
    fun `буфер растёт при переполнении и не теряет уже записанное`() {
        // Редактор генома раньше вообще не проверял ёмкость перед записью: метод роста
        // был объявлен, но не вызывался ни разу. Здесь рост проверяется явно.
        val buffer = CellInstanceBuffer(initialCapacity = 2)
        buffer.begin()

        val total = 50
        repeat(total) { i ->
            buffer.putCell(
                x = i.toFloat(),
                y = 0f,
                color = i,
                angleCos = 1f,
                angleSin = 0f,
                radius = 0.3f,
                energy = 0f,
                cellType = 1,
                noiseSeed = i
            )
        }
        val data = buffer.end()

        assertEquals(total, buffer.count)
        assertTrue(buffer.capacity >= total)
        assertEquals(total * CellInstanceBuffer.STRUCT_SIZE, data.remaining())

        // Каждая клетка, записанная ДО пересоздания буфера, обязана уцелеть.
        repeat(total) { i ->
            val cell = readInstance(data, i)
            assertEquals(i.toFloat(), cell.x, "клетка $i потерялась при росте буфера")
            assertEquals(i, cell.color)
            assertEquals(i, shaderSeed(cell.type))
        }
    }

    @Test
    fun `повторный end отдаёт те же данные, а не пустой буфер`() {
        // Редактор генома между тиками буфер не перезаписывает, но кадр рисует каждый раз
        // и буфер запрашивает заново. Голый flip() во второй раз обнулил бы limit,
        // и клетки исчезли бы с экрана.
        val buffer = CellInstanceBuffer(initialCapacity = 4)
        buffer.begin(1)
        buffer.putCell(1f, 2f, 3, 1f, 0f, 0.3f, 0f, 1, noiseSeed = 42)

        val first = buffer.end()
        val bytesFirst = first.remaining()
        val second = buffer.end()

        assertEquals(bytesFirst, second.remaining())
        assertEquals(CellInstanceBuffer.STRUCT_SIZE, second.remaining())
        assertEquals(1f, readInstance(second, 0).x)
    }

    @Test
    fun `put и putCell дают одинаковые байты`() {
        // put — путь GL-потока (shape/type уже посчитаны потоком симуляции),
        // putCell — путь редактора. Разойтись они не имеют права.
        val viaPutCell = CellInstanceBuffer(initialCapacity = 1).apply {
            begin(1)
            putCell(5f, 6f, 0x778899AA.toInt(), -0.3f, 0.95f, 0.62f, 3.5f, 21, 4242)
        }.end()

        val shape = CellInstanceBuffer.shape(
            radiusByte = CellInstanceBuffer.radiusByte(0.62f),
            energyByte = CellInstanceBuffer.energyByte(3.5f)
        )
        val type = CellInstanceBuffer.type(cellType = 21, noiseSeed = 4242)
        val viaPut = CellInstanceBuffer(initialCapacity = 1).apply {
            begin(1)
            put(5f, 6f, 0x778899AA.toInt(), shape, type, look = 0, dir = CellInstanceBuffer.dir(-0.3f, 0.95f))
        }.end()

        assertEquals(viaPutCell.remaining(), viaPut.remaining())
        for (i in 0 until viaPutCell.remaining()) {
            assertEquals(viaPutCell.get(i), viaPut.get(i), "байт $i разошёлся")
        }
    }

    @Test
    fun `цвет доезжает байт в байт и в порядке r g b a`() {
        // GL читает компоненты атрибута в порядке адресов, а putInt пишет нативным
        // порядком (little-endian на всех целевых платформах). То есть младший байт
        // цвета обязан оказаться компонентой .x — красной. Ровно это даёт
        // libGDX Color.toIntBits(), которым игра и пользуется.
        val color = 0x44332211 // a=0x44, b=0x33, g=0x22, r=0x11
        val data = CellInstanceBuffer(initialCapacity = 1).apply {
            begin(1)
            putCell(0f, 0f, color, 1f, 0f, 0.3f, 0f, 0, 0)
        }.end()

        assertEquals(ByteOrder.LITTLE_ENDIAN, data.order(), "иначе допущение ниже неверно")
        val base = CellInstanceBuffer.OFFSET_COLOR
        assertEquals(0x11, data.get(base).toInt() and 0xFF, "r")
        assertEquals(0x22, data.get(base + 1).toInt() and 0xFF, "g")
        assertEquals(0x33, data.get(base + 2).toInt() and 0xFF, "b")
        assertEquals(0x44, data.get(base + 3).toInt() and 0xFF, "a")
    }

    private fun assertNear(expected: Float, actual: Float, tolerance: Float, what: String) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "$what: ожидалось ~$expected, получено $actual (допуск $tolerance)"
        )
    }
}
