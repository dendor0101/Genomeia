package io.github.some_example_name.old.systems.render

import io.github.some_example_name.old.cells.eyeData
import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.core.DEBUG_CHECKS
import io.github.some_example_name.old.core.PROFILE_INTERPOLATION
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralLinkEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.features.settings.GlobalSettings
import io.github.some_example_name.old.systems.simulation.SimulationData
import io.github.some_example_name.render.DebugFilter
import io.github.some_example_name.render.RenderMode
import io.github.some_example_name.render.pack.CellInstanceBuffer
import io.github.some_example_name.render.pack.CellLook
import io.github.some_example_name.render.pack.OverlayInstanceBuffer
import io.github.some_example_name.render.pack.PheromoneInstanceBuffer
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Снимок мира для рендера: поток симуляции раз в тик собирает его в [TripleBuffer], поток
 * рендера берёт самый свежий.
 *
 * Снимок — сразу раскладка GPU: клетки int-массивом в раскладке CellInstanceBuffer,
 * оверлей и феромоны — готовыми буферами инстансов. Раньше снимок лежал полями в шести
 * массивах, и GL-поток КАЖДЫЙ КАДР перекладывал их в буфер инстансов (0.64 мс на 100k
 * частиц). Теперь он копирует массив одним memcpy (0.21 мс на 100k) и только когда
 * пришёл новый снимок. Отдавать рендеру свои массивы без копии стало можно благодаря
 * тройному буферу: слот, который читает рендер, симуляция не трогает.
 *
 * Кроме поз снимок несёт позу каждой частицы в прошлом снимке — между ними шейдеры
 * рисуют движение плавно (CellInstanceBuffer: «Интерполяция между тиками»). Прошлые позы
 * живут в [history] и [nonCellHistory].
 *
 * TODO(XPBD): когда в игру придёт новая физика (цель — 30 UPS, тик из нескольких
 * подшагов), на очень сильном замедлении линейной интерполяции между тиками станет мало:
 * клетка поедет по хорде, а не по дуге, и столкновение «проскочит» между двумя снимками.
 * Тогда снимок надо снимать и с подшагов решателя — прошлой позой каждого снимка будет
 * поза прошлого подшага, а рендер пройдёт их по очереди. См. TODO.md, раздел 11.
 */
class RenderBufferManager(
    val simulationData: SimulationData,
    val cellEntity: CellEntity,
    val particleEntity: ParticleEntity,
    val pheromoneEntity: PheromoneEntity,
    val linkEntity: LinkEntity,
    val neuralLinkEntity: NeuralLinkEntity,
    val cellList: List<Cell>,
    val specialEntity: SpecialEntity,
    initialCellCapacity: Int = 50_000,
    initialOverlayCapacity: Int = 4_096,
    initialPheromoneCapacity: Int = 1_000
) {

    /**
     * Режим рендера этого мира. Свой у каждого мира: у симуляции его выбирают списком,
     * а мир в фоне меню всегда рисуется реалистично и чужой выбор не видит.
     *
     * Живёт здесь, а не в RenderSystem, потому что читает его поток симуляции: по нему
     * [updateBuffer] решает, красить ли клетки отладочными цветами и собирать ли оверлей
     * (нейросвязи, стрелки, лучи), — в остальных режимах это работа впустую. Пишет же его
     * GL-поток (список в экране
     * симуляции), отсюда @Volatile: без него поток симуляции имел полное право не увидеть
     * переключение никогда. Переключают руками раз в минуты, цена volatile-чтения ничто.
     */
    @Volatile
    var mode: RenderMode = RenderMode.entries[GlobalSettings.RENDER_MODE]

    /**
     * Фильтр отладочного режима (энергия, нейросигнал). Как и [mode]: пишет GL-поток (список
     * на экране симуляции), читает поток симуляции раз за [updateBuffer] — под него
     * пакуется байт фильтра каждой клетки, и вместе с клетками он уходит в снимок
     * (RenderSnapshot.filter), чтобы кадр не нарисовал байты одного фильтра шкалой
     * другого. Видимость обеспечивает ; переключают руками, цена ничто.
     */
    @Volatile
    var debugFilter: DebugFilter = DebugFilter.ORIGINAL

    /**
     * Клетки, оверлей, феромоны и строка состояния — одним снимком. Раньше у них были
     * отдельные двойные буферы со своими индексами, и правило «клетки и оверлей
     * переключаются вместе» держалось на дисциплине. Теперь согласованность бесплатна:
     * читатель получает весь снимок целиком или не получает ничего.
     */
    private val snapshots = TripleBuffer(
        RenderSnapshot(initialCellCapacity, initialOverlayCapacity, initialPheromoneCapacity),
        RenderSnapshot(initialCellCapacity, initialOverlayCapacity, initialPheromoneCapacity),
        RenderSnapshot(initialCellCapacity, initialOverlayCapacity, initialPheromoneCapacity)
    )

    /**
     * Поток рендера: взять самый свежий снимок. true — он новый с прошлого вызова (буфер
     * клеток надо перезалить), false — рисовать прежний. Звать один раз за кадр, до
     * [currentSnapshot].
     */
    fun acquireSnapshot(): Boolean = snapshots.acquire()

    /** Поток рендера: снимок, взятый последним [acquireSnapshot]. Симуляция его не трогает. */
    fun currentSnapshot(): RenderSnapshot = snapshots.front()

    // ==================== История для интерполяции ====================

    /**
     * Поза каждой клетки в последнем снимке — по индексу её частицы, [HISTORY_STRIDE] int:
     *   [номер снимка, ключ жильца, x, y, dir, prevX, prevY, —]
     * float лежат битами (floatToRawIntBits — на JVM и ART это бесплатно), dir — уже
     * упакованное направление (CellInstanceBuffer.dir): в следующем снимке оно станет
     * прошлым как есть, без второй упаковки. prevX, prevY — прошлая позиция, с которой
     * клетка ушла в этом снимке: по ней едут концы нейросвязей и подсветка. Только для
     * картинки: в состояние мира не входит, в хэш и сохранение не попадает, на симуляцию
     * не влияет.
     *
     * Поза годится в прошлую, только если записана ровно в прошлом снимке и тем же
     * жильцом — поколением клетки: слот умершей клетки занимает новая, и без ключа она
     * «приехала» бы с места погибшей.
     *
     * РАСКЛАДКА — ПО ЗАМЕРУ
     * ---------------------
     * Восемь полей в одном массиве = 32 байта = половина кэш-линии на частицу. Первый
     * вариант держал позу и метку в двух массивах и для не-клеток шёл по индексу частицы:
     * по профилю JFR треть времени снимка стояла на чтении метки — два случайных промаха
     * на каждую субстанцию. Теперь клетки читают историю потоком в порядке арен, а у
     * не-клеток своя история [nonCellHistory].
     *
     * Объём: 32 байта × 100k частиц = 3.2 МБ — больше L2 на любом железе, но проход
     * последовательный, и подтягивает его префетчер.
     */
    private var history = IntArray(0)

    /**
     * Поза не-клеток (субстанций) — [NON_CELL_STRIDE] int: [номер снимка, ключ, x, y], по
     * позиции в particleEntity.nonCellList, а не по индексу частицы. Угла у не-клетки нет,
     * и 16 байт вместо 32 — четверть линии. Список обходится по порядку
     * позиций, и история читается потоком; по индексу частицы это был бы случайный адрес
     * на каждую субстанцию.
     *
     * Ключ жильца — индекс частицы. Удаление переставляет в освободившуюся позицию
     * последнюю частицу списка: у неё ключ не совпадёт, и один снимок она простоит без
     * интерполяции — один кадр без плавности у одной субстанции, а не промах на каждую.
     * Переиспользованный индекс частицы ключ не отличит: новая субстанция на месте старой
     * один тик проскользит, если они ближе CellInstanceBuffer.MOTION_RANGE.
     */
    private var nonCellHistory = IntArray(0)

    /**
     * Инстансы клеток собираемого снимка — массив его слота (RenderSnapshot.instances),
     * на время [updateBuffer].
     */
    private var instances = IntArray(0)

    /**
     * Номер текущего снимка. С единицы: метка свежевыделенного слота — ноль, и она не
     * должна совпасть с «прошлым снимком» ни при каком значении.
     */
    private var serial = 1

    /** Тик прошлого снимка: разрыв в нумерации — рестарт или загрузка мира, см. [startSnapshot]. */
    private var lastTick = Int.MIN_VALUE

    /**
     * Прошлая поза, найденная последним [remember] или [rememberPosition]: её пишет
     * инстанс, а у клетки — ещё и оверлей. Полями, а не возвратом: три значения разом
     * без объекта на частицу.
     */
    private var lastPrevX = 0f
    private var lastPrevY = 0f
    private var lastPrevDir = 0

    /**
     * Сглаженный промежуток между снимками, нс. По нему рендер решает, какую долю тика
     * уже прожил снимок (RenderSystem.interpolationLag).
     *
     * Измеряется, а не берётся из targetUPS: симуляция, которая не успевает в цель (40 UPS
     * при цели 60), публикует реже, и по номиналу каждый снимок доигрывался бы раньше
     * времени, а потом кадры стояли бы до следующего. Каждое измерение зажато в
     * [половину, удвоение] текущего значения: пауза или подвисание дают один огромный
     * промежуток, и без зажима после каждой паузы движение на несколько тиков замедлялось
     * бы в разы. Смена UPS руками (60 → 10) догоняется за ~10 снимков.
     */
    private var tickNanos = 0L
    private var lastPublishNanos = 0L

    // ==================== Отладочный режим ====================

    /**
     * Собирается ли в текущем [updateBuffer] отладочный кадр. Режим читается ОДИН раз за
     * вызов: переключи его GL-поток посреди сборки — и половина клеток получила бы
     * отладочные цвета, а половина обычные.
     */
    private var debugFrame = false

    /** Фильтр текущего [updateBuffer]; читается один раз, как и [debugFrame]. */
    private var frameFilter = DebugFilter.ORIGINAL

    /** Оверлей текущего снимка вместе с концами фигур в прошлом тике. */
    private val overlaySink = OverlayMotionSink()

    /** Цвета, стрелки и лучи отладочного режима — общие с редактором генома. */
    private val palette = DebugCellPalette(cellList)

    /**
     * Клетки организмов — по аренам, то есть подряд.
     *
     * ЗАЧЕМ ИМЕННО ТАК
     * ----------------
     * Прежний проход шёл по particleEntity.aliveList (порядок создания, перемешанный
     * swap-with-last при каждой смерти) и на каждой клетке лез в CellEntity по
     * holderEntityIndex. То есть на частицу приходилось по два случайных прыжка: один
     * за её собственными полями, другой за полями её клетки.
     *
     * Обход по арене снимает оба. Клетки организма лежат подряд, частицы — подряд и
     * параллельно им, так что particleIndex получается арифметикой из слота, без чтения
     * particleIndexes. Поля CellEntity (angleCos/Sin, energy, cellType, а в отладке ещё
     * neuronImpulseOutput) читаются в том же слотовом порядке, то есть потоком. Так же,
     * по индексу частицы, — и [history].
     *
     * Ветки isCell внутри цикла тоже больше нет: здесь заведомо только клетки.
     */
    private fun writeOrganismCells(startIndex: Int): Int {
        var writeIndex = startIndex

        val organEntity = cellEntity.organEntity
        val organs = organEntity.aliveList

        val px = particleEntity.x
        val py = particleEntity.y
        val pColor = particleEntity.color
        val pRadius = particleEntity.radius
        val cellAlive = cellEntity.isAlive

        for (organSlot in 0 until organs.size) {
            val organIndex = organs.getInt(organSlot)
            if (!organEntity.hasArena(organIndex)) continue

            val cellFrom = organEntity.cellArenaBase[organIndex]
            val cellTo = organEntity.cellArenaEnd(organIndex)
            val particleBase = organEntity.particleArenaBase[organIndex]

            for (cellIndex in cellFrom until cellTo) {
                if (!cellAlive[cellIndex]) continue
                val particleIndex = particleBase + (cellIndex - cellFrom)

                // Частица берётся АРИФМЕТИКОЙ из слота, а не через particleIndexes.
                // Если параллельность арен где-то нарушится, углы и радиусы поедут от
                // ЧУЖОЙ клетки — картинка при этом останется правдоподобной, поэтому
                // расхождение надо ловить явно, а не глазами.
                if (DEBUG_CHECKS) checkArenaParallel(cellIndex, organIndex, particleIndex, cellFrom, particleBase)

                writeCell(writeIndex, cellIndex, particleIndex, px, py, pColor, pRadius)
                writeIndex++
            }
        }
        return writeIndex
    }

    private fun checkArenaParallel(cellIndex: Int, organIndex: Int, particleIndex: Int, cellFrom: Int, particleBase: Int) {
        val expected = cellEntity.particleIndexes[cellIndex]
        if (expected != particleIndex) {
            throw IllegalStateException(
                "рендер: клетка $cellIndex организма $organIndex ссылается на " +
                    "частицу $expected, а по параллельности арен это " +
                    "$particleIndex (cellBase=$cellFrom particleBase=$particleBase)"
            )
        }
        if (!particleEntity.isAlive[particleIndex]) {
            throw IllegalStateException(
                "рендер: живая клетка $cellIndex с мёртвой частицей $particleIndex"
            )
        }
    }

    /**
     * Клетки вне арен (organIndex == -1 либо у организма арены нет).
     *
     * Проход стоит O(живых клеток), поэтому включается только когда такие клетки реально
     * есть: на стенде их нет, и холостой обход всего мира каждый кадр был бы заметен.
     */
    private fun writeOrphanCells(startIndex: Int): Int {
        if (cellEntity.orphanCellCount == 0) return startIndex

        var writeIndex = startIndex
        val organEntity = cellEntity.organEntity
        val alive = cellEntity.aliveList

        val px = particleEntity.x
        val py = particleEntity.y
        val pColor = particleEntity.color
        val pRadius = particleEntity.radius

        for (i in 0 until alive.size) {
            val cellIndex = alive.getInt(i)
            if (organEntity.hasArena(cellEntity.organIndex[cellIndex])) continue
            val particleIndex = cellEntity.particleIndexes[cellIndex]
            if (particleIndex == -1) continue

            writeCell(writeIndex, cellIndex, particleIndex, px, py, pColor, pRadius)
            writeIndex++
        }
        return writeIndex
    }

    /** Субстанции и прочие не-клетки — своим списком, без единого обращения в CellEntity. */
    private fun writeNonCellParticles(startIndex: Int): Int {
        val target = instances
        val nonCells = particleEntity.nonCellList
        val px = particleEntity.x
        val py = particleEntity.y
        val pColor = particleEntity.color
        val pRadius = particleEntity.radius

        val inGrid = particleEntity.isInGrid

        // Тип «не клетка» один на всех — считается один раз, а не на каждой частице.
        val nonCellType = (cellList.size + 1)
        val collisionFilter = frameFilter == DebugFilter.COLLISION

        for (i in 0 until nonCells.size) {
            val particleIndex = nonCells.getInt(i)
            val x = px[particleIndex]
            val y = py[particleIndex]
            // Направления у не-клетки нет: ни поворота, ни угла в истории.
            rememberPosition(i, particleIndex, x, y)

            CellInstanceBuffer.write(
                target,
                startIndex + i,
                x = x,
                y = y,
                color = pColor[particleIndex],
                shape = CellInstanceBuffer.shape(
                    radiusByte = CellInstanceBuffer.radiusByte(pRadius[particleIndex]),
                    energyByte = 0
                ),
                type = CellInstanceBuffer.type(
                    cellType = nonCellType,
                    noiseSeed = particleIndex,
                    // Без ядра в ностальгическом режиме: ядро — признак клетки.
                    isCell = false
                ),
                // В отладке — плоская и непрозрачная, как и была. Из фильтров её касается
                // только «коллизии»: в сетке столкновений она, как и граничные клетки.
                look = if (collisionFilter) CellLook.look(0, filter = CellLook.collisionFilterByte(inGrid[particleIndex])) else 0,
                dir = CellInstanceBuffer.DIR_NONE,
                prevX = lastPrevX,
                prevY = lastPrevY,
                prevDir = CellInstanceBuffer.DIR_NONE
            )
        }
        return startIndex + nonCells.size
    }

    private fun writeCell(
        writeIndex: Int,
        cellIndex: Int,
        particleIndex: Int,
        px: FloatArray,
        py: FloatArray,
        pColor: IntArray,
        pRadius: FloatArray
    ) {
        val x = px[particleIndex]
        val y = py[particleIndex]
        val angleCos = cellEntity.angleCos[cellIndex]
        val angleSin = cellEntity.angleSin[cellIndex]

        val dir = CellInstanceBuffer.dir(angleCos, angleSin)
        remember(particleIndex, cellEntity.getGeneration(cellIndex), x, y, dir)

        CellInstanceBuffer.write(
            instances,
            writeIndex,
            x = x,
            y = y,
            color = pColor[particleIndex],
            // Радиус — физический, без degreeOfShortening. Укорочение мышцы меняет длину
            // её связей, а не размер клетки: сжимавшийся вместе с ним круг мигал на каждом
            // сигнале. Сжатие видно по самому телу и, в отладке, по яркости мышцы.
            shape = CellInstanceBuffer.shape(
                CellInstanceBuffer.radiusByte(pRadius[particleIndex]),
                CellInstanceBuffer.energyByte(cellEntity.energy[cellIndex])
            ),
            // Ключ шума — индекс частицы, а не позиция в буфере.
            //
            // Шейдер доворачивает текстуру на случайный угол, и раньше брал его из
            // gl_InstanceID, то есть из позиции в буфере. Пока буфер собирался в порядке
            // aliveList, позиция живой клетки не менялась, и доворот был постоянным.
            // Теперь порядок задаёт арена: рождение клетки в более раннем слоте сдвигает
            // все последующие, и во время роста доворот пересчитывался каждый тик —
            // текстуры визуально крутились у всего тела, пока организм не дорастал.
            //
            // Индекс частицы это слот арены: он закреплён за клеткой на всю жизнь и от
            // порядка сборки буфера не зависит вообще. По нему же отладка раскладывает рисунок.
            type = CellInstanceBuffer.type(cellEntity.cellType[cellIndex].toInt(), particleIndex, true),
            look = 0,
            dir = dir,
            prevX = lastPrevX,
            prevY = lastPrevY,
            prevDir = lastPrevDir
        )

        if (debugFrame) decorateDebugCell(writeIndex, cellIndex, particleIndex, x, y, angleCos, angleSin, pRadius[particleIndex])
    }

    /**
     * Отладочный режим поверх уже записанной клетки: цвет палитры, рисунок и сигнал в look,
     * стрелка, луч или жгутик в оверлей.
     *
     * Отдельным методом, а не веткой внутри [writeCell]: тот вызывается на каждую клетку
     * каждый тик и обязан оставаться маленьким, чтобы JIT встроил его целиком (см. CLAUDE.md,
     * «JIT»).
     */
    private fun decorateDebugCell(
        writeIndex: Int,
        cellIndex: Int,
        particleIndex: Int,
        x: Float,
        y: Float,
        angleCos: Float,
        angleSin: Float,
        radius: Float
    ) {
        val cellType = cellEntity.cellType[cellIndex].toInt()
        // Сигнал действия — выход клетки: мышца сжимается, хвост тянет, присоска
        // присасывается ровно по нему. От него анимация рисунка, см. DebugCellPalette.
        val signal = cellEntity.neuronImpulseOutput[cellIndex]
        val o = writeIndex * CellInstanceBuffer.STRUCT_INTS
        instances[o + CellInstanceBuffer.OFFSET_COLOR / 4] = palette.color(cellType, particleEntity.color[particleIndex])
        instances[o + CellInstanceBuffer.OFFSET_LOOK / 4] =
            palette.look(cellType, signal, filter = filterByte(cellIndex, particleIndex, signal))

        overlaySink.rigid(
            x, y, angleCos, angleSin,
            lastPrevX, lastPrevY, CellInstanceBuffer.dirCos(lastPrevDir), CellInstanceBuffer.dirSin(lastPrevDir)
        )
        writeDirection(cellIndex, cellType, particleIndex, x, y, angleCos, angleSin, radius, signal)
    }

    /**
     * Прошлая поза клетки с частицей [p] — в [lastPrevX], [lastPrevY], [lastPrevDir], — и
     * запись нынешней в [history]. Только копирование чисел: ни одного округления (см.
     * CellInstanceBuffer: «Почему прошлая поза, а не смещение»).
     *
     * [key] — жилец слота, см. [history]. Клетка без годной прошлой позы (новая) или
     * перенесённая дальше MOTION_RANGE получает прошлую позу, равную нынешней: она просто
     * стоит в снимке, без полёта из чужого места.
     */
    private fun remember(p: Int, key: Int, x: Float, y: Float, dir: Int) {
        val hist = history
        val h = p * HISTORY_STRIDE

        var prevX = x
        var prevY = y
        var prevDir = dir
        if (hist[h] == serial - 1 && hist[h + 1] == key) {
            val hx = Float.fromBits(hist[h + 2])
            val hy = Float.fromBits(hist[h + 3])
            if (PROFILE_INTERPOLATION) countStep(x - hx, y - hy)
            if (CellInstanceBuffer.isMotion(x - hx, y - hy)) {
                prevX = hx
                prevY = hy
                prevDir = hist[h + 4]
            }
        }

        hist[h] = serial
        hist[h + 1] = key
        hist[h + 2] = x.toRawBits()
        hist[h + 3] = y.toRawBits()
        hist[h + 4] = dir
        hist[h + 5] = prevX.toRawBits()
        hist[h + 6] = prevY.toRawBits()

        lastPrevX = prevX
        lastPrevY = prevY
        lastPrevDir = prevDir
    }

    /**
     * То же для не-клетки на позиции [slot] списка: только позиция, без угла, — в
     * [nonCellHistory]. [key] — индекс частицы.
     */
    private fun rememberPosition(slot: Int, key: Int, x: Float, y: Float) {
        val hist = nonCellHistory
        val h = slot * NON_CELL_STRIDE
        var prevX = x
        var prevY = y
        if (hist[h] == serial - 1 && hist[h + 1] == key) {
            val hx = Float.fromBits(hist[h + 2])
            val hy = Float.fromBits(hist[h + 3])
            if (PROFILE_INTERPOLATION) countStep(x - hx, y - hy)
            if (CellInstanceBuffer.isMotion(x - hx, y - hy)) {
                prevX = hx
                prevY = hy
            }
        }
        hist[h] = serial
        hist[h + 1] = key
        hist[h + 2] = x.toRawBits()
        hist[h + 3] = y.toRawBits()
        lastPrevX = prevX
        lastPrevY = prevY
    }

    /**
     * Прошлая x клетки с частицей [p] в ТЕКУЩЕМ снимке (после прохода по клеткам). Нет её в
     * снимке — нынешняя [x]: тогда и интерполировать нечего.
     */
    private fun prevXOf(p: Int, x: Float): Float =
        if (p >= 0 && history[p * HISTORY_STRIDE] == serial) Float.fromBits(history[p * HISTORY_STRIDE + 5]) else x

    private fun prevYOf(p: Int, y: Float): Float =
        if (p >= 0 && history[p * HISTORY_STRIDE] == serial) Float.fromBits(history[p * HISTORY_STRIDE + 6]) else y

    /**
     * Байт фильтра отладки для клетки (CellLook): заполненность энергией, нормализованный
     * выход или участие в столкновениях. Без фильтра ноль — лишних чтений нет.
     */
    private fun filterByte(cellIndex: Int, particleIndex: Int, signal: Float): Int = when (frameFilter) {
        DebugFilter.ORIGINAL -> 0
        DebugFilter.ENERGY -> CellLook.energyFilterByte(cellEntity.energy[cellIndex], cellEntity.maxEnergy[cellIndex])
        DebugFilter.SIGNAL -> CellLook.signalFilterByte(signal)
        DebugFilter.COLLISION -> CellLook.collisionFilterByte(particleEntity.isInGrid[particleIndex])
    }

    /** Стрелка направления, луч глаза или жгутик хвоста — в оверлей, см. DebugCellPalette. */
    private fun writeDirection(
        cellIndex: Int,
        cellType: Int,
        particleIndex: Int,
        x: Float,
        y: Float,
        angleCos: Float,
        angleSin: Float,
        radius: Float,
        signal: Float
    ) {
        if (palette.isEye(cellType)) {
            palette.writeEyeRay(
                overlaySink, cellType, x, y, angleCos, angleSin, radius,
                range = specialEntity.eyeData(cellIndex).visibilityRange,
                signal = signal
            )
        } else if (palette.hasFlagellum(cellType)) {
            palette.writeFlagellum(overlaySink, cellType, x, y, angleCos, angleSin, radius, signal, seed = particleIndex)
        } else if (palette.hasArrow(cellType)) {
            palette.writeArrow(overlaySink, x, y, angleCos, angleSin, radius, signal)
        }
    }

    /** Радиусы действия феромонов — кольцами, под всем остальным оверлеем. Пятна стоят на месте. */
    private fun writePheromoneRings() = with(pheromoneEntity) {
        overlaySink.still()
        val params = OverlayInstanceBuffer.params(OverlayInstanceBuffer.KIND_RING, 0f, 0)
        for (bufIndex in 0..<aliveList.size) {
            val i = aliveList.getInt(bufIndex)
            overlaySink.add(x[i], y[i], sqrt(radiusSquared[i]), 0f, color[i], params)
        }
    }

    /**
     * Нейросвязи — от источника к приёмнику, с сигналом, который источник отдаёт в связь.
     *
     * Направление то же, что у NeuralLinkManager.transportNeuralSignal: при
     * isLink1NeuralDirected сигнал идёт из второй клетки в первую, иначе из первой во
     * вторую. Физические связи в отладке не рисуются вовсе.
     *
     * Концы связи едут каждый со своей частицей: их прошлые позиции уже записаны проходом по
     * клеткам этого же снимка (поэтому связи пишутся после него).
     */
    private fun writeNeuralLinks() = with(neuralLinkEntity) {
        val px = particleEntity.x
        val py = particleEntity.y
        val output = cellEntity.neuronImpulseOutput

        for (bufIndex in 0..<aliveList.size) {
            val i = aliveList.getInt(bufIndex)
            val linkCellA = links1[i]
            val linkCellB = links2[i]
            val linkCellAIsDead = !cellEntity.isAlive[linkCellA] || cellEntity.getGeneration(linkCellA) != linksGeneration1[i]
            val linkCellBIsDead = !cellEntity.isAlive[linkCellB] || cellEntity.getGeneration(linkCellB) != linksGeneration2[i]
            if (linkCellAIsDead || linkCellBIsDead) continue

            val particleA = cellEntity.getParticleIndex(linkCellA)
            val particleB = cellEntity.getParticleIndex(linkCellB)
            // Клетка без частицы — уже испорченное состояние, но обращаться по индексу -1
            // нельзя: это выход за границу массива, а не битая картинка.
            if (particleA == -1 || particleB == -1) continue

            val fromA = !isLink1NeuralDirected[i]
            val source = if (fromA) linkCellA else linkCellB
            val from = if (fromA) particleA else particleB
            val to = if (fromA) particleB else particleA

            overlaySink.points(prevXOf(from, px[from]), prevYOf(from, py[from]), prevXOf(to, px[to]), prevYOf(to, py[to]))
            // Индекс связи — ключ фазы импульсов: закреплён за связью на всю её жизнь.
            palette.writeNeuralLink(
                overlaySink, px[from], py[from], px[to], py[to], color[i], output[source], seed = i
            )
        }
    }

    private fun writePheromones(snapshot: RenderSnapshot) = with(pheromoneEntity) {
        val pheromones = snapshot.pheromones
        pheromones.begin(aliveList.size)
        for (bufIndex in 0..<aliveList.size) {
            val i = aliveList.getInt(bufIndex)
            pheromones.put(x = x[i], y = y[i], a = time[i], color = color[i])
        }
        pheromones.end()
    }

    /**
     * Начало снимка: номер, проверка разрыва и место в [history] под все частицы.
     *
     * Разрыв в номерах тиков — рестарт, загрузка мира или повтор бага с другого места: позы
     * в истории от другого мира, и сравнивать с ними нельзя. Номер снимка перескакивает
     * через один, и все метки разом становятся «не из прошлого снимка» — без прохода по
     * массиву.
     */
    private fun startSnapshot() {
        val tick = simulationData.tickCounter
        serial += if (tick == lastTick + 1) 1 else 2
        lastTick = tick

        val particles = particleEntity.x.size
        if (history.size < particles * HISTORY_STRIDE) {
            history = history.copyOf(particles * HISTORY_STRIDE)
        }
        val nonCells = particleEntity.nonCellList.size
        if (nonCellHistory.size < nonCells * NON_CELL_STRIDE) {
            nonCellHistory = nonCellHistory.copyOf(maxOf(nonCells, nonCellHistory.size / NON_CELL_STRIDE * 2) * NON_CELL_STRIDE)
        }
    }

    fun updateBuffer(performanceInfo: String = "") {
        startSnapshot()
        val snapshot = snapshots.back()
        // Сколько инстансов вообще может быть записано: клетки (в аренах и вне их) и
        // не-клетки. Не aliveList частиц: если их сумма с ним разойдётся (это ловит
        // DEBUG_CHECKS), снимок не должен выйти за массив.
        instances = snapshot.reserveInstances(cellEntity.aliveList.size + particleEntity.nonCellList.size)

        debugFrame = mode.drawsDebugOverlay
        frameFilter = if (debugFrame) debugFilter else DebugFilter.ORIGINAL

        val overlay = snapshot.overlay
        overlay.begin()
        overlaySink.target = overlay

        // Порядок записи оверлея — порядок отрисовки: кольца феромонов снизу, стрелки и
        // лучи (их пишет writeCell) над ними, нейросвязи сверху.
        if (debugFrame) writePheromoneRings()

        // ==================== CELL ====================
        var written = writeOrganismCells(0)
        written = writeOrphanCells(written)
        written = writeNonCellParticles(written)

        // Буфер обязан содержать РОВНО все живые частицы: клетки организмов, клетки вне
        // арен и не-клетки в сумме дают aliveList. Расхождение означает, что какие-то
        // частицы либо не попали в буфер, либо попали дважды — а обе беды выглядят как
        // «часть картинки ведёт себя странно», а не как явная ошибка.
        if (DEBUG_CHECKS && written != particleEntity.aliveList.size) {
            throw IllegalStateException(
                "рендер: в буфер записано $written частиц, а живых " +
                    "${particleEntity.aliveList.size} (клеток ${cellEntity.aliveList.size}, " +
                    "не-клеток ${particleEntity.nonCellList.size}, " +
                    "вне арен ${cellEntity.orphanCellCount})"
            )
        }
        snapshot.instanceCount = written
        snapshot.filter = frameFilter

        // ==================== NEURAL LINKS ====================
        if (debugFrame) writeNeuralLinks()
        overlay.end()
        snapshot.hasOverlay = debugFrame

        writePheromones(snapshot)
        writeSpecific(snapshot.spec, performanceInfo)
        publish(snapshot)
    }

    private fun writeSpecific(spec: RenderSpecificBufferData, performanceInfo: String) = with(spec) {
        ups = simulationData.ups
        updateTime = round(1e5f / simulationData.ups) / 100f
        cellsAmount = cellEntity.lastId - cellEntity.deadStack.size + 1
        particleAmount = particleEntity.lastId - particleEntity.deadStack.size + 1
        linksAmount = linkEntity.lastId - linkEntity.deadStack.size + 1

        detailedPerformance = performanceInfo

        val cellIndex = simulationData.selectedCellIndex
        if (cellIndex != -1 && cellEntity.isAlive[cellIndex]) {
            selectedCellIndex = cellEntity.cellGenomeId[cellIndex]
            neuronImpulseInput = cellEntity.neuronImpulseInput[cellIndex]
            neuronImpulseOutput = cellEntity.neuronImpulseOutput[cellIndex]
            isCellSelected = true
            grabbedCellX = cellEntity.getX(cellIndex)
            grabbedCellY = cellEntity.getY(cellIndex)
            val particleIndex = cellEntity.getParticleIndex(cellIndex)
            grabbedPrevX = prevXOf(particleIndex, grabbedCellX!!)
            grabbedPrevY = prevYOf(particleIndex, grabbedCellY!!)
            val cellType = cellEntity.cellType[cellIndex].toInt()
            cellName = cellList[cellType].name +
                if (cellEntity.isNeural[cellIndex])
                    " ${cellEntity.formulas[cellEntity.getActivationFuncType(cellIndex)].label} " +
                        "${cellEntity.getA(cellIndex)} ${cellEntity.getB(cellIndex)} ${cellEntity.getC(cellIndex)}"
                else ""
        } else {
            neuronImpulseInput = null
            neuronImpulseOutput = null
            isCellSelected = false
            grabbedCellX = null
            grabbedCellY = null
            grabbedPrevX = 0f
            grabbedPrevY = 0f
            cellName = null
        }
    }

    /**
     * Отдать снимок рендеру. Время публикации и сглаженный промежуток между снимками
     * едут вместе со снимком — по ним рендер считает долю тика (см. [tickNanos]). Время
     * здесь только для картинки: на симуляцию оно не влияет.
     */
    /** Диагностика [PROFILE_INTERPOLATION]: шаги частиц текущего снимка. */
    private var stepMax = 0f
    private var stepTeleports = 0

    private fun countStep(dx: Float, dy: Float) {
        val step = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
        if (step > stepMax) stepMax = step
        if (!CellInstanceBuffer.isMotion(dx, dy)) stepTeleports++
    }

    private fun publish(snapshot: RenderSnapshot) {
        if (PROFILE_INTERPOLATION) {
            snapshot.serial = serial
            snapshot.stepMax = stepMax
            snapshot.stepTeleports = stepTeleports
            stepMax = 0f
            stepTeleports = 0
        }
        val now = System.nanoTime()
        if (tickNanos == 0L || lastPublishNanos == 0L) {
            tickNanos = NANOS_PER_SECOND / simulationData.targetUPS
        } else {
            val gap = (now - lastPublishNanos).coerceIn(tickNanos / 2, tickNanos * 2)
            tickNanos += (gap - tickNanos) / TICK_SMOOTHING
        }
        lastPublishNanos = now

        snapshot.publishedAtNanos = now
        snapshot.tickNanos = tickNanos
        snapshots.publish()
    }

    private companion object {
        /** Поля [history] на частицу: номер снимка, ключ, x, y, dir, prevX, prevY, — — 32 байта. */
        const val HISTORY_STRIDE = 8

        /** Поля [nonCellHistory] на частицу: номер снимка, ключ, x, y — 16 байт. */
        const val NON_CELL_STRIDE = 4

        const val NANOS_PER_SECOND = 1_000_000_000L

        /** Сглаживание [tickNanos]: новое измерение входит пятой частью. */
        const val TICK_SMOOTHING = 5L
    }
}

/**
 * Всё, что рендеру нужно от одного тика: буферы инстансов в раскладке GPU и строка
 * состояния. Один из трёх слотов [TripleBuffer]; пишет поток симуляции, читает рендер.
 */
class RenderSnapshot(cellCapacity: Int, overlayCapacity: Int, pheromoneCapacity: Int) {
    /**
     * Инстансы клеток в раскладке GPU (CellInstanceBuffer.write), [instanceCount] штук.
     *
     * Обычный int-массив, а не direct-буфер: поток симуляции пишет сюда каждую частицу
     * каждый тик, и запись в массив дешевле десяти вызовов DirectByteBuffer.put на
     * инстанс. В буфер для GPU массив копирует GL-поток одной копией (memcpy) — и только
     * когда пришёл новый снимок, а не каждый кадр (RenderSystem). 40 байт × 100k = 4 МБ
     * на слот.
     */
    var instances = IntArray(cellCapacity * CellInstanceBuffer.STRUCT_INTS)
        private set
    var instanceCount = 0

    /** Массив минимум на [count] инстансов; рост ×1.5, старое содержимое не нужно. */
    fun reserveInstances(count: Int): IntArray {
        val needed = count * CellInstanceBuffer.STRUCT_INTS
        if (instances.size < needed) {
            instances = IntArray(maxOf(needed, instances.size / 2 * 3))
        }
        return instances
    }

    val overlay = OverlayInstanceBuffer(overlayCapacity)
    val pheromones = PheromoneInstanceBuffer(pheromoneCapacity)

    /** Под какой фильтр отладки упакованы байты look, см. RenderBufferManager.debugFilter. */
    var filter = DebugFilter.ORIGINAL

    /** Собран ли оверлей: только в снимке, собранном под отладочный режим. */
    var hasOverlay = false

    val spec = RenderSpecificBufferData()

    /** Когда снимок отдан рендеру, System.nanoTime. */
    var publishedAtNanos = 0L

    /** Сглаженный промежуток между снимками, нс: за столько снимок «доигрывает» свой тик. */
    var tickNanos = 0L

    /** Только для PROFILE_INTERPOLATION: номер снимка, наибольший шаг частицы и число переносов. */
    var serial = 0
    var stepMax = 0f
    var stepTeleports = 0

    init {
        // Пустой, но перевёрнутый под чтение: до первой публикации рендер рисует ничто, а
        // не мусор из неинициализированной ёмкости.
        overlay.begin()
        overlay.end()
        pheromones.begin()
        pheromones.end()
    }
}

/**
 * Фигуры оверлея — в буфер снимка вместе с их концами в прошлом тике
 * (OverlayInstanceBuffer: prevFrom/prevTo). Откуда брать прошлые концы, задаёт тот, кто
 * пишет фигуру, перед вызовом палитры:
 *   - [rigid] — фигура жёстко привязана к клетке (стрелка, луч, жгутик): каждый конец
 *     переносится вместе с центром клетки и поворачивается вокруг него на её поворот;
 *   - [points] — у концов свои прошлые позиции (нейросвязь между двумя частицами);
 *   - [still] — фигура стоит (кольца феромонов).
 */
class OverlayMotionSink : DebugCellPalette.OverlaySink {

    lateinit var target: OverlayInstanceBuffer

    private var mode = STILL

    private var centerX = 0f
    private var centerY = 0f
    private var prevCenterX = 0f
    private var prevCenterY = 0f

    /** Поворот от нынешнего направления клетки к прошлому. */
    private var backCos = 1f
    private var backSin = 0f

    private var prevFromX = 0f
    private var prevFromY = 0f
    private var prevToX = 0f
    private var prevToY = 0f

    fun still() {
        mode = STILL
    }

    /**
     * Клетка сейчас в ([x], [y]) смотрит по ([dirCos], [dirSin]), а тиком раньше была в
     * ([prevX], [prevY]) и смотрела по ([prevCos], [prevSin]).
     */
    fun rigid(
        x: Float,
        y: Float,
        dirCos: Float,
        dirSin: Float,
        prevX: Float,
        prevY: Float,
        prevCos: Float,
        prevSin: Float
    ) {
        mode = RIGID
        centerX = x
        centerY = y
        prevCenterX = prevX
        prevCenterY = prevY
        // Поворот R: R · dir = prevDir. cos и sin разности углов — без тригонометрии.
        backCos = dirCos * prevCos + dirSin * prevSin
        backSin = dirCos * prevSin - dirSin * prevCos
    }

    fun points(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        mode = POINTS
        prevFromX = fromX
        prevFromY = fromY
        prevToX = toX
        prevToY = toY
    }

    override fun add(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, params: Int) {
        when (mode) {
            RIGID -> target.put(
                x0, y0, x1, y1, color, params,
                rigidX(x0, y0), rigidY(x0, y0), rigidX(x1, y1), rigidY(x1, y1)
            )
            POINTS -> target.put(x0, y0, x1, y1, color, params, prevFromX, prevFromY, prevToX, prevToY)
            else -> target.put(x0, y0, x1, y1, color, params)
        }
    }

    /**
     * Прошлое положение точки, привязанной к клетке: нынешнее, повёрнутое на поворот
     * клетки вокруг её центра и перенесённое к прошлому центру.
     */
    private fun rigidX(px: Float, py: Float): Float =
        prevCenterX + (px - centerX) * backCos - (py - centerY) * backSin

    private fun rigidY(px: Float, py: Float): Float =
        prevCenterY + (px - centerX) * backSin + (py - centerY) * backCos

    private companion object {
        const val STILL = 0
        const val RIGID = 1
        const val POINTS = 2
    }
}

data class RenderSpecificBufferData(
    var ups: Int = 0,
    var updateTime: Float = 0f,
    var cellsAmount: Int = 0,
    var particleAmount: Int = 0,
    var linksAmount: Int = 0,
    var neuronImpulseInput: Float? = null,
    var neuronImpulseOutput: Float? = null,
    var isCellSelected: Boolean = false,
    var grabbedCellX: Float? = null,
    var grabbedCellY: Float? = null,
    /** Где выбранная клетка была тиком раньше: подсветка и камера едут вместе с её интерполяцией. */
    var grabbedPrevX: Float = 0f,
    var grabbedPrevY: Float = 0f,
    var cellName: String? = null,
    var selectedCellIndex: Int = -1,
    var detailedPerformance: String = ""          // ← добавлено
)
