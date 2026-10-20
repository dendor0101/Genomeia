package io.github.some_example_name.old.systems.render

import io.github.some_example_name.old.cells.eyeData
import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.core.DEBUG_CHECKS
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralLinkEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.features.settings.GlobalSettings
import io.github.some_example_name.old.systems.simulation.SimulationData
import io.github.some_example_name.render.RenderMode
import io.github.some_example_name.render.pack.CellInstanceBuffer
import io.github.some_example_name.render.pack.OverlayInstanceBuffer
import kotlin.math.round
import kotlin.math.sqrt
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

    // Двойные буферы
    private val cellBuffers = arrayOf(
        RenderCellBufferData(initialCellCapacity),
        RenderCellBufferData(initialCellCapacity)
    )
    private val pheromoneBuffers = arrayOf(
        PheromoneBufferData(initialPheromoneCapacity),
        PheromoneBufferData(initialPheromoneCapacity)
    )
    private val overlayBuffers = arrayOf(
        RenderOverlayBufferData(initialOverlayCapacity),
        RenderOverlayBufferData(initialOverlayCapacity)
    )
    private val specificBuffer0 = RenderSpecificBufferData()
    private val specificBuffer1 = RenderSpecificBufferData()

    /**
     * ОДИН индекс на буферы клеток и оверлея — они обязаны переключаться вместе.
     *
     * Оверлей рисуется поверх клеток и по их координатам: стрелка начинается в центре
     * клетки, нейросвязь соединяет два центра. Возьми поток рендера клетки одного тика, а
     * оверлей другого — стрелки и связи отставали бы от тела на кадр.
     *
     * Раньше вместо оверлея здесь был буфер связей с позициями ВНУТРИ буфера клеток, и
     * рассинхрон был хуже: индексы переключались в разных местах, рендер успевал взять
     * новые клетки и старые связи, и линии соединяли чужие организмы. Отсюда и правило
     * одного индекса; теперь оверлей несёт готовые координаты, но правило осталось.
     *
     * Феромоны и specific самодостаточны: они не ссылаются на чужие буферы, поэтому у них
     * свои индексы и своё время переключения.
     */
    private val frameFrontIndex = AtomicInteger(0)
    private val pheromoneFrontIndex = AtomicInteger(0)
    private val specificFrontIndex = AtomicInteger(0)

    /**
     * Индекс согласованной пары буферов «клетки + оверлей».
     *
     * Поток рендера обязан прочитать его ОДИН раз за кадр и дальше брать оба буфера по нему.
     * Если читать через два отдельных геттера, симуляция успеет переключиться между ними —
     * и получится ровно тот же рассинхрон, от которого мы здесь избавляемся.
     */
    fun frontFrameIndex(): Int = frameFrontIndex.get()

    fun cellBuffer(frameIndex: Int): RenderCellBufferData = cellBuffers[frameIndex]
    fun overlayBuffer(frameIndex: Int): RenderOverlayBufferData = overlayBuffers[frameIndex]

    fun getCurrentPheromoneBuffer(): PheromoneBufferData = pheromoneBuffers[pheromoneFrontIndex.get()]
    fun getCurrentSpecificBufferData(): RenderSpecificBufferData =
        if (specificFrontIndex.get() == 0) specificBuffer0 else specificBuffer1

    // ==================== Отладочный режим ====================

    /**
     * Собирается ли в текущем [updateBuffer] отладочный кадр. Режим читается ОДИН раз за
     * вызов: переключи его GL-поток посреди сборки — и половина клеток получила бы
     * отладочные цвета, а половина обычные.
     */
    private var debugFrame = false

    /** Оверлей, который собирается в текущем [updateBuffer]. */
    private var overlayBack = overlayBuffers[0]

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
     * neuronImpulseOutput) читаются в том же слотовом порядке, то есть потоком.
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

        for (i in 0 until nonCells.size) {
            val particleIndex = nonCells.getInt(i)

            back.x[writeIndex] = px[particleIndex]
            back.y[writeIndex] = py[particleIndex]
            back.color[writeIndex] = pColor[particleIndex]

            // Углы намеренно нулевые: у не-клетки направления нет. В шейдере нулевой байт
            // распаковывается в -1, то есть доворот получается постоянный, а не случайный.
            back.shape[writeIndex] = CellInstanceBuffer.shape(
                cosByte = 0,
                sinByte = 0,
                radiusByte = CellInstanceBuffer.radiusByte(pRadius[particleIndex]),
                energyByte = 0
            )
            back.type[writeIndex] = CellInstanceBuffer.type(
                cellType = nonCellType,
                noiseSeed = particleIndex,
                // Без ядра в ностальгическом режиме: ядро — признак клетки.
                isCell = false
            )
            // В отладке — плоская и непрозрачная, как и была.
            back.look[writeIndex] = 0

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
        val x = px[particleIndex]
        val y = py[particleIndex]
        val debug = debugFrame

        val angleCos = cellEntity.angleCos[cellIndex]
        val angleSin = cellEntity.angleSin[cellIndex]
        val cellType = cellEntity.cellType[cellIndex].toInt()

        back.x[writeIndex] = x
        back.y[writeIndex] = y
        back.color[writeIndex] =
            if (debug) palette.color(cellType, pColor[particleIndex]) else pColor[particleIndex]

        // Радиус — физический, без degreeOfShortening. Укорочение мышцы меняет длину её
        // связей, а не размер клетки: сжимавшийся вместе с ним круг мигал на каждом
        // сигнале. Сжатие видно по самому телу и, в отладке, по яркости мышцы.
        val radius = pRadius[particleIndex]

        back.shape[writeIndex] = CellInstanceBuffer.shape(
            cosByte = CellInstanceBuffer.angleByte(angleCos),
            sinByte = CellInstanceBuffer.angleByte(angleSin),
            radiusByte = CellInstanceBuffer.radiusByte(radius),
            energyByte = CellInstanceBuffer.energyByte(cellEntity.energy[cellIndex])
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
        // порядка сборки буфера не зависит вообще. По нему же отладка раскладывает рисунок.
        back.type[writeIndex] = CellInstanceBuffer.type(cellType = cellType, noiseSeed = particleIndex)

        if (debug) {
            // Сигнал действия — выход клетки: мышца сжимается, хвост тянет, присоска
            // присасывается ровно по нему. От него анимация рисунка, см. DebugCellPalette.
            val signal = cellEntity.neuronImpulseOutput[cellIndex]
            back.look[writeIndex] = palette.look(cellType, signal)
            writeDirection(cellIndex, cellType, particleIndex, x, y, angleCos, angleSin, radius, signal)
        } else {
            back.look[writeIndex] = 0
        }
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
                overlayBack, cellType, x, y, angleCos, angleSin, radius,
                range = specialEntity.eyeData(cellIndex).visibilityRange,
                signal = signal
            )
        } else if (palette.hasFlagellum(cellType)) {
            palette.writeFlagellum(overlayBack, cellType, x, y, angleCos, angleSin, radius, signal, seed = particleIndex)
        } else if (palette.hasArrow(cellType)) {
            palette.writeArrow(overlayBack, x, y, angleCos, angleSin, radius, signal)
        }
    }

    /** Радиусы действия феромонов — кольцами, под всем остальным оверлеем. */
    private fun writePheromoneRings(overlay: RenderOverlayBufferData) = with(pheromoneEntity) {
        val params = OverlayInstanceBuffer.params(OverlayInstanceBuffer.KIND_RING, 0f, 0)
        for (bufIndex in 0..<aliveList.size) {
            val i = aliveList.getInt(bufIndex)
            overlay.add(x[i], y[i], sqrt(radiusSquared[i]), 0f, color[i], params)
        }
    }

    /**
     * Нейросвязи — от источника к приёмнику, с сигналом, который источник отдаёт в связь.
     *
     * Направление то же, что у NeuralLinkManager.transportNeuralSignal: при
     * isLink1NeuralDirected сигнал идёт из второй клетки в первую, иначе из первой во
     * вторую. Физические связи в отладке не рисуются вовсе.
     */
    private fun writeNeuralLinks(overlay: RenderOverlayBufferData) = with(neuralLinkEntity) {
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

            // Индекс связи — ключ фазы импульсов: закреплён за связью на всю её жизнь.
            palette.writeNeuralLink(
                overlay, px[from], py[from], px[to], py[to], color[i], output[source], seed = i
            )
        }
    }

    fun updateBuffer(performanceInfo: String = "") {
        // Общий back-индекс на клетки и оверлей: оба буфера собираются в него и публикуются
        // одним переключением в конце, чтобы рендер не увидел их вразнобой.
        val frameBackIndex = 1 - frameFrontIndex.get()

        debugFrame = mode.drawsDebugOverlay
        overlayBack = overlayBuffers[frameBackIndex]
        overlayBack.clear()

        // Порядок записи оверлея — порядок отрисовки: кольца феромонов снизу, стрелки и
        // лучи (их пишет writeCell) над ними, нейросвязи сверху.
        if (debugFrame) writePheromoneRings(overlayBack)

        // ==================== CELL ====================
        val cellBack = cellBuffers[frameBackIndex]
        cellBack.ensureCapacity(particleEntity.aliveList.size)

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

        // ==================== NEURAL LINKS ====================
        if (debugFrame) writeNeuralLinks(overlayBack)

        // Единственная публикация пары «клетки + оверлей».
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
                        " ${cellEntity.formulas[cellEntity.getActivationFuncType(cellIndex)].label} " +
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
    var shape = IntArray(capacity)
    var type = IntArray(capacity)

    /** Вид в отладке (CellLook); в остальных режимах ноль. */
    var look = IntArray(capacity)

    fun ensureCapacity(minCapacity: Int) {
        if (minCapacity > capacity) {
            val newCapacity = if (capacity == 0) minCapacity else (capacity * 2).coerceAtLeast(minCapacity)
            capacity = newCapacity

            x = x.copyOf(newCapacity)
            y = y.copyOf(newCapacity)
            color = color.copyOf(newCapacity)
            shape = shape.copyOf(newCapacity)
            type = type.copyOf(newCapacity)
            look = look.copyOf(newCapacity)
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

/**
 * Фигуры оверлея отладочного режима одного тика — поля OverlayInstanceBuffer, уже
 * упакованные потоком симуляции. GL-поток только переписывает их в буфер инстансов.
 *
 * Координаты готовые, мировые: стрелка и связь не ссылаются на буфер клеток, поэтому
 * его порядок (арены организмов) оверлею безразличен.
 */
class RenderOverlayBufferData(initialCapacity: Int) : DebugCellPalette.OverlaySink {
    var capacity = initialCapacity
        private set
    var size = 0
        private set

    var x0 = FloatArray(capacity)
        private set
    var y0 = FloatArray(capacity)
        private set
    var x1 = FloatArray(capacity)
        private set
    var y1 = FloatArray(capacity)
        private set
    var color = IntArray(capacity)
        private set
    var params = IntArray(capacity)
        private set

    fun clear() {
        size = 0
    }

    override fun add(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, params: Int) {
        if (size == capacity) grow(size + 1)
        this.x0[size] = x0
        this.y0[size] = y0
        this.x1[size] = x1
        this.y1[size] = y1
        this.color[size] = color
        this.params[size] = params
        size++
    }

    private fun grow(minCapacity: Int) {
        val newCapacity = (capacity * 2).coerceAtLeast(minCapacity).coerceAtLeast(16)
        capacity = newCapacity
        x0 = x0.copyOf(newCapacity)
        y0 = y0.copyOf(newCapacity)
        x1 = x1.copyOf(newCapacity)
        y1 = y1.copyOf(newCapacity)
        color = color.copyOf(newCapacity)
        params = params.copyOf(newCapacity)
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
