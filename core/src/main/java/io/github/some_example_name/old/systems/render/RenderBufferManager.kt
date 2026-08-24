package io.github.some_example_name.old.systems.render

import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.core.DEBUG_CHECKS
import io.github.some_example_name.old.cells.Eye
import io.github.some_example_name.old.cells.base.formulaType
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralLinkEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.systems.simulation.SimulationData
import io.github.some_example_name.render.RenderSettings
import io.github.some_example_name.render.pack.CellInstanceBuffer
import kotlin.math.round
import java.util.concurrent.atomic.AtomicInteger

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
    initialLinkCapacity: Int = 50_000,
    initialPheromoneCapacity: Int = 1_000
) {

    // Двойные буферы
    private val cellBuffers = arrayOf(
        RenderCellBufferData(initialCellCapacity),
        RenderCellBufferData(initialCellCapacity)
    )
    private val pheromoneBuffers = arrayOf(
        PheromoneBufferData(initialPheromoneCapacity),
        PheromoneBufferData(initialPheromoneCapacity)
    )
    private val linkBuffers = arrayOf(
        RenderLinkBufferData(initialLinkCapacity),
        RenderLinkBufferData(initialLinkCapacity)
    )
    private val specificBuffer0 = RenderSpecificBufferData()
    private val specificBuffer1 = RenderSpecificBufferData()

    /**
     * ОДИН индекс на буферы клеток и связей — они обязаны переключаться вместе.
     *
     * Буфер связей хранит не индексы клеток, а positionInAlive, то есть позиции внутри
     * БУФЕРА ЧАСТИЦ. Эти позиции осмысленны только для того буфера частиц, который собран
     * в том же вызове updateBuffer.
     *
     * Раньше индексы были отдельные и переключались в разных местах: клетки сразу после
     * своей сборки, связи — в конце, уже после сборки феромонов. В это окно поток рендера
     * успевал взять НОВЫЙ буфер клеток и СТАРЫЙ буфер связей. Порядок aliveList меняется
     * каждый тик (удаление делает swap-with-last), поэтому старые позиции указывали на
     * произвольные другие частицы — на экране это связи между чужими организмами, а при
     * уменьшении числа клеток позиция уезжала за границу и линия уходила в ноль.
     *
     * Феромоны и specific самодостаточны: они не ссылаются на чужие буферы, поэтому у них
     * свои индексы и своё время переключения.
     */
    private val frameFrontIndex = AtomicInteger(0)
    private val pheromoneFrontIndex = AtomicInteger(0)
    private val specificFrontIndex = AtomicInteger(0)

    /**
     * Индекс согласованной пары буферов «клетки + связи».
     *
     * Поток рендера обязан прочитать его ОДИН раз за кадр и дальше брать оба буфера по нему.
     * Если читать через два отдельных геттера, симуляция успеет переключиться между ними —
     * и получится ровно тот же рассинхрон, от которого мы здесь избавляемся.
     */
    fun frontFrameIndex(): Int = frameFrontIndex.get()

    fun cellBuffer(frameIndex: Int): RenderCellBufferData = cellBuffers[frameIndex]
    fun linkBuffer(frameIndex: Int): RenderLinkBufferData = linkBuffers[frameIndex]

    fun getCurrentPheromoneBuffer(): PheromoneBufferData = pheromoneBuffers[pheromoneFrontIndex.get()]
    fun getCurrentSpecificBufferData(): RenderSpecificBufferData =
        if (specificFrontIndex.get() == 0) specificBuffer0 else specificBuffer1

    /**
     * Частица -> её позиция в буфере клеток текущего кадра.
     *
     * Раньше эту роль играл particleEntity.positionInAlive: буфер собирался ровно в порядке
     * aliveList, поэтому позиция в списке и позиция в буфере совпадали. Теперь буфер
     * собирается по аренам организмов, и совпадения больше нет — а буферу связей нужна
     * именно позиция В БУФЕРЕ, потому что шейдер по ней берёт координаты концов линии.
     *
     * Заполняется в том же проходе, что и сам буфер, поэтому лишних обходов не добавляет.
     */
    private var renderPosition = IntArray(0)

    private fun ensureRenderPositionCapacity(size: Int) {
        if (renderPosition.size < size) renderPosition = IntArray(size)
    }

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
     * particleIndexes. Все шесть полей CellEntity (angleCos/Sin, degreeOfShortening,
     * energy, cellType) читаются в том же слотовом порядке, то есть потоком.
     *
     * Ветки isCell внутри цикла тоже больше нет: здесь заведомо только клетки.
     */
    private fun writeOrganismCells(back: RenderCellBufferData, startIndex: Int): Int {
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
                if (DEBUG_CHECKS) {
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

                writeCell(back, writeIndex, cellIndex, particleIndex, px, py, pColor, pRadius)
                renderPosition[particleIndex] = writeIndex
                writeIndex++
            }
        }
        return writeIndex
    }

    /**
     * Клетки вне арен (organIndex == -1 либо у организма арены нет).
     *
     * Проход стоит O(живых клеток), поэтому включается только когда такие клетки реально
     * есть: на стенде их нет, и холостой обход всего мира каждый кадр был бы заметен.
     */
    private fun writeOrphanCells(back: RenderCellBufferData, startIndex: Int): Int {
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

            writeCell(back, writeIndex, cellIndex, particleIndex, px, py, pColor, pRadius)
            renderPosition[particleIndex] = writeIndex
            writeIndex++
        }
        return writeIndex
    }

    /** Субстанции и прочие не-клетки — своим списком, без единого обращения в CellEntity. */
    private fun writeNonCellParticles(back: RenderCellBufferData, startIndex: Int): Int {
        var writeIndex = startIndex

        val nonCells = particleEntity.nonCellList
        val px = particleEntity.x
        val py = particleEntity.y
        val pColor = particleEntity.color
        val pRadius = particleEntity.radius

        // Тип «не клетка» один на всех — считается один раз, а не на каждой частице.
        val nonCellType = (cellList.size + 1)
        val writeDirected = !RenderSettings.usePostProcess

        for (i in 0 until nonCells.size) {
            val particleIndex = nonCells.getInt(i)

            back.x[writeIndex] = px[particleIndex]
            back.y[writeIndex] = py[particleIndex]
            back.color[writeIndex] = pColor[particleIndex]

            // Углы намеренно нулевые: у не-клетки направления нет. В шейдере нулевой байт
            // распаковывается в -1, то есть доворот получается постоянный, а не случайный.
            back.packed1[writeIndex] = CellInstanceBuffer.packed1(
                cosByte = 0,
                sinByte = 0,
                radiusByte = CellInstanceBuffer.radiusByte(pRadius[particleIndex])
            )
            back.packed2[writeIndex] = CellInstanceBuffer.packed2(
                energyByte = 0,
                cellType = nonCellType,
                noiseSeed = particleIndex
            )

            if (writeDirected) {
                back.directedAngleCos[writeIndex] = 0f
                back.directedAngleSin[writeIndex] = 0f
            }

            renderPosition[particleIndex] = writeIndex
            writeIndex++
        }
        return writeIndex
    }

    private fun writeCell(
        back: RenderCellBufferData,
        writeIndex: Int,
        cellIndex: Int,
        particleIndex: Int,
        px: FloatArray,
        py: FloatArray,
        pColor: IntArray,
        pRadius: FloatArray
    ) {
        back.x[writeIndex] = px[particleIndex]
        back.y[writeIndex] = py[particleIndex]
        back.color[writeIndex] = pColor[particleIndex]

        val angleCos = cellEntity.angleCos[cellIndex]
        val angleSin = cellEntity.angleSin[cellIndex]
        val cellType = cellEntity.cellType[cellIndex].toInt()

        val visibleRadius = pRadius[particleIndex] * cellEntity.degreeOfShortening[cellIndex]

        back.packed1[writeIndex] = CellInstanceBuffer.packed1(
            cosByte = CellInstanceBuffer.angleByte(angleCos),
            sinByte = CellInstanceBuffer.angleByte(angleSin),
            radiusByte = CellInstanceBuffer.radiusByte(visibleRadius)
        )
        // Старшие 16 бит — устойчивый ключ шума для шейдера.
        //
        // Шейдер доворачивает текстуру на случайный угол, и раньше брал его из
        // gl_InstanceID, то есть из позиции в буфере. Пока буфер собирался в порядке
        // aliveList, позиция живой клетки не менялась, и доворот был постоянным.
        // Теперь порядок задаёт арена: рождение клетки в более раннем слоте сдвигает
        // все последующие, и во время роста доворот пересчитывался каждый тик — текстуры
        // визуально крутились у всего тела, пока организм не дорастал.
        //
        // Индекс частицы это слот арены: он закреплён за клеткой на всю жизнь и от
        // порядка сборки буфера не зависит вообще.
        back.packed2[writeIndex] = CellInstanceBuffer.packed2(
            energyByte = CellInstanceBuffer.energyByte(cellEntity.energy[cellIndex]),
            cellType = cellType,
            noiseSeed = particleIndex
        )

        if (!RenderSettings.usePostProcess) {
            val cell = cellList[cellType]
            val length = when {
                cell is Eye -> specialEntity.getVisibilityRange(cellIndex)
                cell.isDirected -> 1f
                else -> 0f
            }
            back.directedAngleCos[writeIndex] = angleCos * length
            back.directedAngleSin[writeIndex] = angleSin * length
        }
    }

    fun updateBuffer(performanceInfo: String = "") {
        // Общий back-индекс на клетки и связи: оба буфера собираются в него и публикуются
        // одним переключением в конце, чтобы рендер не увидел их вразнобой.
        val frameBackIndex = 1 - frameFrontIndex.get()

        // ==================== CELL ====================
        val cellBack = cellBuffers[frameBackIndex]
        cellBack.ensureCapacity(particleEntity.aliveList.size)
        ensureRenderPositionCapacity(particleEntity.isAlive.size)

        var writeIndex = writeOrganismCells(cellBack, 0)
        writeIndex = writeOrphanCells(cellBack, writeIndex)
        writeIndex = writeNonCellParticles(cellBack, writeIndex)

        // Буфер обязан содержать РОВНО все живые частицы: клетки организмов, клетки вне
        // арен и не-клетки в сумме дают aliveList. Расхождение означает, что какие-то
        // частицы либо не попали в буфер, либо попали дважды — а обе беды выглядят как
        // «часть картинки ведёт себя странно», а не как явная ошибка.
        if (DEBUG_CHECKS && writeIndex != particleEntity.aliveList.size) {
            throw IllegalStateException(
                "рендер: в буфер записано $writeIndex частиц, а живых " +
                    "${particleEntity.aliveList.size} (клеток ${cellEntity.aliveList.size}, " +
                    "не-клеток ${particleEntity.nonCellList.size}, " +
                    "вне арен ${cellEntity.orphanCellCount})"
            )
        }

        cellBack.renderCellBufferSize = writeIndex
        // Переключения тут больше нет: клетки публикуются вместе со связями, в самом конце.

        // ==================== PHEROMONE ===============
        val backPheromoneIndex = 1 - pheromoneFrontIndex.get()
        val back = pheromoneBuffers[backPheromoneIndex]
        with(pheromoneEntity) {
            val needed = aliveList.size

            back.ensureCapacity(needed)

            for (bufIndex in 0..<aliveList.size) {
                val i = aliveList.getInt(bufIndex)
                back.x[bufIndex] = x[i]
                back.y[bufIndex] = y[i]
                back.a[bufIndex] = time[i]
                back.color[bufIndex] = color[i]
                back.radiusSquared[bufIndex] = radiusSquared[i]
            }

            back.pheromoneBufferSize = aliveList.size
        }
        pheromoneFrontIndex.set(backPheromoneIndex)

        // ==================== LINK ====================
        val linkBack = linkBuffers[frameBackIndex]
        if (!RenderSettings.usePostProcess) {
            val needed = linkEntity.aliveList.size + neuralLinkEntity.aliveList.size
            val back = linkBack

            back.ensureCapacity(needed)

            var writeIndex = 0

            // длинные нейролинки
            with(neuralLinkEntity) {
                for (bufIndex in aliveList.indices) {
                    val i = aliveList.getInt(bufIndex)
                    val linkCellA = links1[i]
                    val linkCellB = links2[i]
                    val linkCellAIsDead = !cellEntity.isAlive[linkCellA] || cellEntity.getGeneration(linkCellA) != linksGeneration1[i]
                    val linkCellBIsDead = !cellEntity.isAlive[linkCellB] || cellEntity.getGeneration(linkCellB) != linksGeneration2[i]
                    if (linkCellAIsDead || linkCellBIsDead) continue

                    val particleAIndex = cellEntity.getParticleIndex(linkCellA)
                    val particleBIndex = cellEntity.getParticleIndex(linkCellB)
                    // Клетка без частицы — уже испорченное состояние, но обращаться по
                    // индексу -1 нельзя: это выход за границу массива, а не битая картинка.
                    if (particleAIndex == -1 || particleBIndex == -1) continue

                    // Позиция В БУФЕРЕ КЛЕТОК этого же кадра, а не в aliveList: буфер
                    // собирается по аренам организмов, и его порядок с aliveList больше
                    // не совпадает. Карта заполняется в том же проходе, что и буфер.
                    val positionA = renderPosition[particleAIndex]
                    val positionB = renderPosition[particleBIndex]

                    back.cellA[writeIndex] = positionA
                    back.cellB[writeIndex] = positionB

                    // длинные нейролинки всегда neural-directed
                    back.isNeuralDirected[writeIndex] = if (isLink1NeuralDirected[i]) 1 else 0

                    writeIndex++
                }
            }

            // обычные линки
            with(linkEntity) {
                for (bufIndex in aliveList.indices) {
                    val i = aliveList.getInt(bufIndex)
                    val linkCellA = links1[i]
                    val linkCellB = links2[i]
                    val linkCellAIsDead = !cellEntity.isAlive[linkCellA] || cellEntity.getGeneration(linkCellA) != linksGeneration1[i]
                    val linkCellBIsDead = !cellEntity.isAlive[linkCellB] || cellEntity.getGeneration(linkCellB) != linksGeneration2[i]
                    if (linkCellAIsDead || linkCellBIsDead) continue

                    val particleAIndex = cellEntity.getParticleIndex(linkCellA)
                    val particleBIndex = cellEntity.getParticleIndex(linkCellB)
                    // Клетка без частицы — уже испорченное состояние, но обращаться по
                    // индексу -1 нельзя: это выход за границу массива, а не битая картинка.
                    if (particleAIndex == -1 || particleBIndex == -1) continue

                    // Позиция В БУФЕРЕ КЛЕТОК этого же кадра, а не в aliveList: буфер
                    // собирается по аренам организмов, и его порядок с aliveList больше
                    // не совпадает. Карта заполняется в том же проходе, что и буфер.
                    val positionA = renderPosition[particleAIndex]
                    val positionB = renderPosition[particleBIndex]

                    back.cellA[writeIndex] = positionA
                    back.cellB[writeIndex] = positionB

                    back.isNeuralDirected[writeIndex] = -1

                    writeIndex++
                }
            }

            back.renderLinkAmount = writeIndex
        } else {
            // Связи не рисуются, но буфер публикуется вместе с клетками — обнуляем, чтобы
            // в нём не осталось позиций от позапрошлого кадра.
            linkBack.renderLinkAmount = 0
        }

        // Единственная публикация пары «клетки + связи».
        frameFrontIndex.set(frameBackIndex)

        // ==================== SPECIFIC ====================
        val specificBackIndex = 1 - specificFrontIndex.get()
        val specificBack = if (specificBackIndex == 0) specificBuffer0 else specificBuffer1

        with(specificBack) {
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
                val cellType = cellEntity.cellType[cellIndex].toInt()
                cellName = cellList[cellType].name +
                    if (cellEntity.isNeural[cellIndex])
                        " ${formulaType[cellEntity.getActivationFuncType(cellIndex)]} " +
                            "${cellEntity.getA(cellIndex)} ${cellEntity.getB(cellIndex)} ${cellEntity.getC(cellIndex)}"
                    else ""
            } else {
                neuronImpulseInput = null
                neuronImpulseOutput = null
                isCellSelected = false
                grabbedCellX = null
                grabbedCellY = null
                cellName = null
            }
        }
        specificFrontIndex.set(specificBackIndex)
    }
}

class RenderCellBufferData(initialCapacity: Int) {
    var capacity = initialCapacity
    var renderCellBufferSize = 0

    var x = FloatArray(capacity)
    var y = FloatArray(capacity)
    var color = IntArray(capacity)
    var packed1 = IntArray(capacity)
    var packed2 = IntArray(capacity)
    var directedAngleCos = FloatArray(capacity)
    var directedAngleSin = FloatArray(capacity)

    fun ensureCapacity(minCapacity: Int) {
        if (minCapacity > capacity) {
            val newCapacity = if (capacity == 0) minCapacity else (capacity * 2).coerceAtLeast(minCapacity)
            capacity = newCapacity

            x = x.copyOf(newCapacity)
            y = y.copyOf(newCapacity)
            color = color.copyOf(newCapacity)
            packed1 = packed1.copyOf(newCapacity)
            packed2 = packed2.copyOf(newCapacity)
            directedAngleCos = directedAngleCos.copyOf(newCapacity)
            directedAngleSin = directedAngleSin.copyOf(newCapacity)
        }
    }
}

class PheromoneBufferData(initialCapacity: Int) {
    var capacity = initialCapacity
    var pheromoneBufferSize = 0

    var x = FloatArray(initialCapacity)
    var y = FloatArray(initialCapacity)
    var a = FloatArray(initialCapacity)
    var color = IntArray(initialCapacity)
    var radiusSquared = FloatArray(initialCapacity)

    fun ensureCapacity(minCapacity: Int) {
        if (minCapacity > capacity) {
            val newCapacity = if (capacity == 0) minCapacity else (capacity * 2).coerceAtLeast(minCapacity)
            capacity = newCapacity

            x = x.copyOf(newCapacity)
            y = y.copyOf(newCapacity)
            a = a.copyOf(newCapacity)
            color = color.copyOf(newCapacity)
            radiusSquared = radiusSquared.copyOf(newCapacity)
        }
    }
}

class RenderLinkBufferData(initialCapacity: Int) {
    var capacity = initialCapacity
    var renderLinkAmount = 0

    var cellA = IntArray(capacity)
    var cellB = IntArray(capacity)
    var isNeuralDirected = ByteArray(capacity)

    fun ensureCapacity(minCapacity: Int) {
        if (minCapacity > capacity) {
            val newCapacity = if (capacity == 0) minCapacity else (capacity * 2).coerceAtLeast(minCapacity)
            capacity = newCapacity

            cellA = cellA.copyOf(newCapacity)
            cellB = cellB.copyOf(newCapacity)
            isNeuralDirected = isNeuralDirected.copyOf(newCapacity)
        }
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
    var cellName: String? = null,
    var selectedCellIndex: Int = -1,
    var detailedPerformance: String = ""          // ← добавлено
)
