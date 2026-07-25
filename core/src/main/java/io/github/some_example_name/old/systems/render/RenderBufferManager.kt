package io.github.some_example_name.old.systems.render

import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.base.formulaType
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.systems.simulation.SimulationData
import kotlin.math.round
import java.util.concurrent.atomic.AtomicInteger

class RenderBufferManager(
    val simulationData: SimulationData,
    val cellEntity: CellEntity,
    val particleEntity: ParticleEntity,
    val pheromoneEntity: PheromoneEntity,
    val linkEntity: LinkEntity,
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
        PheromoneBufferData(initialPheromoneCapacity),   // подбери нужный начальный размер (можно initialCellCapacity)
        PheromoneBufferData(initialPheromoneCapacity)
    )
    private val linkBuffers = arrayOf(
        RenderLinkBufferData(initialLinkCapacity),
        RenderLinkBufferData(initialLinkCapacity)
    )
    private val specificBuffer0 = RenderSpecificBufferData()
    private val specificBuffer1 = RenderSpecificBufferData()

    private val cellFrontIndex = AtomicInteger(0)
    private val pheromoneFrontIndex = AtomicInteger(0)
    private val linkFrontIndex = AtomicInteger(0)
    private val specificFrontIndex = AtomicInteger(0)

    fun getCurrentCellBuffer(): RenderCellBufferData = cellBuffers[cellFrontIndex.get()]
    fun getCurrentPheromoneBuffer(): PheromoneBufferData = pheromoneBuffers[pheromoneFrontIndex.get()]
    fun getCurrentLinkBuffer(): RenderLinkBufferData = linkBuffers[linkFrontIndex.get()]
    fun getCurrentSpecificBufferData(): RenderSpecificBufferData =
        if (specificFrontIndex.get() == 0) specificBuffer0 else specificBuffer1

    fun updateBuffer() {
        // ==================== CELL ====================
        with(particleEntity) {
            val needed = aliveList.size
            val backIndex = 1 - cellFrontIndex.get()
            val back = cellBuffers[backIndex]

            back.ensureCapacity(needed)

            for (bufIndex in 0..<aliveList.size) {
                val i = aliveList.getInt(bufIndex)
                back.x[bufIndex] = x[i]
                back.y[bufIndex] = y[i]

                // LibGDX Color.toIntBits() = ABGR8888 (R in low byte)
                val c = color[i]
                back.colorR[bufIndex] = (c and 0xFF) / 255f
                back.colorG[bufIndex] = ((c ushr 8) and 0xFF) / 255f
                back.colorB[bufIndex] = ((c ushr 16) and 0xFF) / 255f

                if (isCell[i]) {
                    val cellIndex = holderEntityIndex[i]

                    back.radius[bufIndex] = radius[i] * cellEntity.degreeOfShortening[cellIndex]
                    // energy/10 matches previous packed-byte path (byte/255 ≈ energy/10)
                    back.energy[bufIndex] = (cellEntity.energy[cellIndex] / 10f).coerceIn(0f, 1f)
                    back.cellType[bufIndex] = cellEntity.cellType[cellIndex].toFloat()
                    back.angleCos[bufIndex] = cellEntity.angleCos[cellIndex]
                    back.angleSin[bufIndex] = cellEntity.angleSin[cellIndex]

                    if (!doesUsePostProcess) {
                        val length = when (cellEntity.cellType[cellIndex].toInt()) {
                            14 -> specialEntity.getVisibilityRange(cellIndex)
                            3 -> 1f
                            9 -> 1f
                            18 -> 1f
                            else -> 0f
                        }
                        with(cellEntity) {
                            val cos = angleCos[cellIndex]
                            val sin = angleSin[cellIndex]
                            back.directedAngleCos[bufIndex] = cos * length
                            back.directedAngleSin[bufIndex] = sin * length
                        }
                    }
                } else {
                    back.radius[bufIndex] = radius[i]
                    back.energy[bufIndex] = 0f
                    back.cellType[bufIndex] = (cellList.size + 1).toFloat()
                    back.angleCos[bufIndex] = 0f
                    back.angleSin[bufIndex] = 0f

                    if (!doesUsePostProcess) {
                        back.directedAngleCos[bufIndex] = 0f
                        back.directedAngleSin[bufIndex] = 0f
                    }
                }
            }

            back.renderCellBufferSize = aliveList.size
        }
        cellFrontIndex.set(1 - cellFrontIndex.get())   // swap

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
        if (!doesUsePostProcess) {
            val needed = linkEntity.aliveList.size
            val backIndex = 1 - linkFrontIndex.get()
            val back = linkBuffers[backIndex]

            back.ensureCapacity(needed)

            with(linkEntity) {
                for (bufIndex in 0..<aliveList.size) {
                    val i = aliveList.getInt(bufIndex)
                    val linkCellA = links1[i]
                    val linkCellB = links2[i]
                    val linkCellAIsDead = !cellEntity.isAlive[linkCellA] || cellEntity.getGeneration(linkCellA) != linksGeneration1[i]
                    val linkCellBIsDead = !cellEntity.isAlive[linkCellB] || cellEntity.getGeneration(linkCellB) != linksGeneration2[i]
                    if (linkCellAIsDead || linkCellBIsDead) continue
                    val particleAIndex = cellEntity.getParticleIndex(links1[i])
                    val particleBIndex = cellEntity.getParticleIndex(links2[i])

                    back.cellA[bufIndex] = particleEntity.positionInAlive[particleAIndex]
                    back.cellB[bufIndex] = particleEntity.positionInAlive[particleBIndex]

                    back.isNeuralDirected[bufIndex] = if (isNeuronLink[i]) {
                        if (isLink1NeuralDirected[i]) 1 else 0
                    } else {
                        if (isStickyLink[i]) 3 else -1
                    }
                }
                back.renderLinkAmount = aliveList.size
            }
            linkFrontIndex.set(backIndex)
        }

        // ==================== SPECIFIC ====================
        val specificBackIndex = 1 - specificFrontIndex.get()
        val specificBack = if (specificBackIndex == 0) specificBuffer0 else specificBuffer1

        with(specificBack) {
            ups = simulationData.ups
            updateTime = round(1e5f / simulationData.ups) / 100f
            cellsAmount = cellEntity.lastId - cellEntity.deadStack.size + 1
            particleAmount = particleEntity.lastId - particleEntity.deadStack.size + 1
            linksAmount = linkEntity.lastId - linkEntity.deadStack.size + 1

            val cellIndex = simulationData.selectedCellIndex
            if (cellIndex != -1 && cellEntity.isAlive[cellIndex]) {
                selectedCellIndex = cellEntity.cellGenomeId[cellIndex]//cellIndex
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
    /** Unpacked RGB in 0..1 (from ABGR8888 Color.toIntBits). */
    var colorR = FloatArray(capacity)
    var colorG = FloatArray(capacity)
    var colorB = FloatArray(capacity)
    var radius = FloatArray(capacity)
    /** energy/10, clamped 0..1 — matches previous packed-byte visual scale. */
    var energy = FloatArray(capacity)
    var cellType = FloatArray(capacity)
    var angleCos = FloatArray(capacity)
    var angleSin = FloatArray(capacity)
    var directedAngleCos = FloatArray(capacity)
    var directedAngleSin = FloatArray(capacity)

    fun ensureCapacity(minCapacity: Int) {
        if (minCapacity > capacity) {
            val newCapacity = if (capacity == 0) minCapacity else (capacity * 2).coerceAtLeast(minCapacity)
            capacity = newCapacity

            x = x.copyOf(newCapacity)
            y = y.copyOf(newCapacity)
            colorR = colorR.copyOf(newCapacity)
            colorG = colorG.copyOf(newCapacity)
            colorB = colorB.copyOf(newCapacity)
            radius = radius.copyOf(newCapacity)
            energy = energy.copyOf(newCapacity)
            cellType = cellType.copyOf(newCapacity)
            angleCos = angleCos.copyOf(newCapacity)
            angleSin = angleSin.copyOf(newCapacity)
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
    var selectedCellIndex: Int = -1
)
