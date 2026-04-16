package io.github.some_example_name.old.systems.render

import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.base.formulaType
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.systems.simulation.SimulationData
import kotlin.math.round

class RenderBufferManager(
    val simulationData: SimulationData,
    val cellEntity: CellEntity,
    val particleEntity: ParticleEntity,
    val linkEntity: LinkEntity,
    val cellList: List<Cell>,
    val specialEntity: SpecialEntity
) {

    val renderSpecificBufferData = RenderSpecificBufferData()
    val renderCellBufferData = RenderCellBufferData(2500_000)
    val renderLinkBufferData = RenderLinkBufferData(2500_000)

    //TODO resize
    //TODO parallel updateBuffer
    //TODO Triple buffer

    fun updateBuffer() {
        synchronized(renderCellBufferData) {
            with(particleEntity) {
                for (bufIndex in 0..<aliveList.size) {
                    val i = aliveList.getInt(bufIndex)
                    renderCellBufferData.x[bufIndex] = x[i]
                    renderCellBufferData.y[bufIndex] = y[i]
                    renderCellBufferData.color[bufIndex] = color[i]
                    if (isCell[i]) {
                        val cellIndex = holderEntityIndex[i]

//                        val bAngle = ((cellEntity.angle[cellIndex] / (2f * Math.PI.toFloat())) * 255f + 0.5f).toInt().coerceIn(0, 255)
//                        val bAx = (/*cellEntity.ax[cellIndex]*/0 * 255f + 0.5f).toInt().coerceIn(0, 255)
//                        val bAy = (/*cellEntity.ay[cellIndex]*/0 * 255f + 0.5f).toInt().coerceIn(0, 255)
//                        val bRadius = (((radius[i] - 0.1f) / 0.4f) * 255f + 0.5f).toInt().coerceIn(0, 255)
//                        val bEnergy = ((cellEntity.energy[cellIndex] / 10f) * 255f + 0.5f).toInt().coerceIn(0, 255)
//                        val bCell = cellEntity.cellType[cellIndex].toInt().coerceIn(0, 255)
//
//                        renderCellBufferData.packed1[bufIndex] = bAngle or (bAx shl 8) or (bAy shl 16) or (bRadius shl 24)
//                        renderCellBufferData.packed2[bufIndex] = bEnergy or (bCell shl 8)


                        val cd = cellEntity.angleDiffCos[cellIndex]
                        val sd = cellEntity.angleDiffSin[cellIndex]

                        val cos = cellEntity.angleCos[cellIndex] * cd + cellEntity.angleSin[cellIndex] * sd
                        val sin = cellEntity.angleSin[cellIndex] * cd - cellEntity.angleCos[cellIndex] * sd

                        // нормализация [-1;1] → [0;255]
                        val cosByte = ((cos * 0.5f + 0.5f) * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val sinByte = ((sin * 0.5f + 0.5f) * 255f + 0.5f).toInt().coerceIn(0, 255)

                        // bAx и bAy больше не нужны — они были 0
                        val bRadius = (((radius[i] - 0.1f) / 0.4f) * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val bEnergy = ((cellEntity.energy[cellIndex] / 10f) * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val bCell   = cellEntity.cellType[cellIndex].toInt().coerceIn(0, 255)

                        // packed1:
                        //   биты  0-15  → угол 16 бит
                        //   биты 16-23  → 0 (бывший bAy)
                        //   биты 24-31  → radius (остаётся на своём месте)
                        renderCellBufferData.packed1[bufIndex] = /*bAngle16*/(cosByte) or (sinByte shl 8) or (bRadius shl 24)

                        // packed2 остаётся без изменений
                        renderCellBufferData.packed2[bufIndex] = bEnergy or (bCell shl 8)

                        if (!usePostProcess) {

                            val length = when (cellEntity.cellType[cellIndex].toInt()) {
                                14 -> specialEntity.getVisibilityRange(cellIndex)
                                3 -> 1f
                                else -> 0f
                            }

                            renderCellBufferData.directedAngleCos[bufIndex] = cellEntity.angleCos[cellIndex] * length
                            renderCellBufferData.directedAngleSin[bufIndex] = cellEntity.angleSin[cellIndex] * length
                        }
                    } else {
                        val bRadius = (((radius[i] - 0.1f) / 0.4f) * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val bCell = (cellList.size + 1).coerceIn(0, 255)

                        renderCellBufferData.packed1[bufIndex] = 0 or (bRadius shl 24)
                        renderCellBufferData.packed2[bufIndex] = 0 or (bCell shl 8)

                        if (!usePostProcess) {
                            renderCellBufferData.directedAngleCos[bufIndex] = 0f
                            renderCellBufferData.directedAngleSin[bufIndex] = 0f
                        }
                    }
                }
                renderCellBufferData.renderCellBufferSize = aliveList.size
            }
        }

        if (!usePostProcess) {
            synchronized(renderLinkBufferData) {
                with(linkEntity) {
                    for (bufIndex in 0..<aliveList.size) {
                        val i = aliveList.getInt(bufIndex)
                        val particleAIndex = cellEntity.getParticleIndex(links1[i])
                        val particleBIndex = cellEntity.getParticleIndex(links2[i])
                        renderLinkBufferData.cellA[bufIndex] =
                            particleEntity.positionInAlive[particleAIndex]
                        renderLinkBufferData.cellB[bufIndex] =
                            particleEntity.positionInAlive[particleBIndex]

                        renderLinkBufferData.isNeuralDirected[bufIndex] = if (isNeuronLink[i]) {
                            if (isLink1NeuralDirected[i]) 1 else 0
                        } else {
                            if (isStickyLink[i]) 3 else -1
                        }
                    }
                    renderLinkBufferData.renderLinkAmount = aliveList.size
                }
            }
        }

        synchronized(renderSpecificBufferData) {
            with(renderSpecificBufferData) {
                ups = simulationData.ups
                updateTime = round(1e5f / simulationData.ups) / 100f
                cellsAmount = cellEntity.lastId - cellEntity.deadStack.size + 1
                particleAmount = particleEntity.lastId - particleEntity.deadStack.size + 1
                linksAmount = linkEntity.lastId - linkEntity.deadStack.size + 1
                val cellIndex = simulationData.selectedCellIndex
                if (cellIndex != -1) {
                    selectedCellIndex = cellIndex
                    neuronImpulseInput = cellEntity.neuronImpulseInput[cellIndex]
                    neuronImpulseOutput = cellEntity.neuronImpulseOutput[cellIndex]
                    isCellSelected = true
                    grabbedCellX = cellEntity.getX(cellIndex)
                    grabbedCellY = cellEntity.getY(cellIndex)
                    val cellType = cellEntity.cellType[cellIndex].toInt()
                    cellName = cellList[cellType].name + if (cellEntity.isNeural[cellIndex]) "${formulaType[cellEntity.getActivationFuncType(cellIndex)]} ${cellEntity.getA(cellIndex)} ${cellEntity.getB(cellIndex)} ${cellEntity.getC(cellIndex)}" else ""
                } else {
                    neuronImpulseInput = null
                    neuronImpulseOutput = null
                    isCellSelected = false
                    grabbedCellX = null
                    grabbedCellY = null
                    cellName = null
                }
            }
        }
    }
}

class RenderCellBufferData(maxAmountParticle: Int) {
    var renderCellBufferSize = 0
    var x = FloatArray(maxAmountParticle)
    var y = FloatArray(maxAmountParticle)
    var color = IntArray(maxAmountParticle)
    var packed1 = IntArray(maxAmountParticle)
    var packed2 = IntArray(maxAmountParticle)
    var directedAngleCos = FloatArray(maxAmountParticle)
    var directedAngleSin = FloatArray(maxAmountParticle)
}

class RenderLinkBufferData(maxAmountLink: Int) {
    var renderLinkAmount = 0
    var cellA = IntArray(maxAmountLink)
    var cellB = IntArray(maxAmountLink)
    var isNeuralDirected = ByteArray(maxAmountLink)
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
