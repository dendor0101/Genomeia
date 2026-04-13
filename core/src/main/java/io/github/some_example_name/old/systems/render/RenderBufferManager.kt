package io.github.some_example_name.old.systems.render

import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.simulation.SimulationData
import kotlin.math.round

class RenderBufferManager(
    val simulationData: SimulationData,
    val cellEntity: CellEntity,
    val particleEntity: ParticleEntity,
    val linkEntity: LinkEntity,
    val cellList: List<Cell>
) {

    val renderSpecificBufferData = RenderSpecificBufferData()
    val renderCellBufferData = RenderCellBufferData(2500_000)
    val renderLinkBufferData = RenderLinkBufferData(2500_000)

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


                        val angle = cellEntity.angle[cellIndex]
                        val angleNorm = (angle + Math.PI.toFloat()) / (2f * Math.PI.toFloat())
                        val bAngle16 = (angleNorm * 65535f + 0.5f).toInt().coerceIn(0, 65535)

                        // bAx и bAy больше не нужны — они были 0
                        val bRadius = (((radius[i] - 0.1f) / 0.4f) * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val bEnergy = ((cellEntity.energy[cellIndex] / 10f) * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val bCell   = cellEntity.cellType[cellIndex].toInt().coerceIn(0, 255)

                        // packed1:
                        //   биты  0-15  → угол 16 бит
                        //   биты 16-23  → 0 (бывший bAy)
                        //   биты 24-31  → radius (остаётся на своём месте)
                        renderCellBufferData.packed1[bufIndex] = bAngle16 or (bRadius shl 24)

                        // packed2 остаётся без изменений
                        renderCellBufferData.packed2[bufIndex] = bEnergy or (bCell shl 8)
                    } else {
                        val bRadius = (((radius[i] - 0.1f) / 0.4f) * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val bCell = (cellList.size + 1).coerceIn(0, 255)

                        renderCellBufferData.packed1[bufIndex] = 0 or (bRadius shl 24)
                        renderCellBufferData.packed2[bufIndex] = 0 or (bCell shl 8)
                    }
                }
                renderCellBufferData.renderCellBufferSize = aliveList.size
            }
        }

        synchronized(renderLinkBufferData) {
            with(linkEntity) {
                for (bufIndex in 0..<aliveList.size) {
                    val i = aliveList.getInt(bufIndex)
                    val particleAIndex = cellEntity.getParticleIndex(links1[i])
                    val particleBIndex = cellEntity.getParticleIndex(links2[i])
                    renderLinkBufferData.cellA[bufIndex] = particleEntity.positionInAlive[particleAIndex]
                    renderLinkBufferData.cellB[bufIndex] = particleEntity.positionInAlive[particleBIndex]
                }
                renderLinkBufferData.renderLinkAmount = aliveList.size
            }
        }

        synchronized(renderSpecificBufferData) {
            with(renderSpecificBufferData) {
                ups = simulationData.ups
                updateTime = round(1e5f / simulationData.ups) / 100f
                cellsAmount = cellEntity.lastId - cellEntity.deadStack.size + 1
                particleAmount = particleEntity.lastId - particleEntity.deadStack.size + 1
                linksAmount = linkEntity.lastId - linkEntity.deadStack.size + 1
                if (simulationData.selectedCellIndex != -1) {
                    neuronImpulseInput =
                        cellEntity.neuronImpulseInput[simulationData.selectedCellIndex]
                    neuronImpulseOutput =
                        cellEntity.neuronImpulseOutput[simulationData.selectedCellIndex]
                    isCellSelected = true
                    grabbedCellX = cellEntity.getX(simulationData.selectedCellIndex)
                    grabbedCellY = cellEntity.getY(simulationData.selectedCellIndex)
                    val cellType = cellEntity.cellType[simulationData.selectedCellIndex].toInt()
                    cellName = cellList[cellType].name
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
}

class RenderLinkBufferData(maxAmountLink: Int) {
    var renderLinkAmount = 0
    var cellA = IntArray(maxAmountLink)
    var cellB = IntArray(maxAmountLink)
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
    var cellName: String? = null
)
