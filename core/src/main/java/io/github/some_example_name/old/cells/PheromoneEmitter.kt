package io.github.some_example_name.old.cells

import io.github.some_example_name.old.cells.base.SpecialModData
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.CellSettings
import io.github.some_example_name.old.core.utils.blueColors
import io.github.some_example_name.old.features.editor.dialog.PheromoneSection
import io.github.some_example_name.old.systems.genomics.genome.Action
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

class PheromoneEmitter(cellTypeId: Int) : Cell(
    key = "pheromone_emitter",
    defaultColor = blueColors[3],
    cellTypeId = cellTypeId,
    isNeural = true
) {

    override fun editorSections() = super.editorSections() + PheromoneSection

    /** Своё состояние на клетку: импульс прошлого тика, по фронту которого выпускается феромон. */
    override fun createSpecialData(action: Action): SpecialModData = PheromoneEmitterState()

    override fun doOnTick(cellIndex: Int, threadId: Int): Unit = with(cellEntity) {
        val emitter = specialEntity.getSpecialData(cellIndex) as? PheromoneEmitterState ?: return
        val lastImpulse = emitter.lastImpulse
        val currentImpulse = neuronImpulseOutput[cellIndex]
        emitter.lastImpulse = currentImpulse

        if (lastImpulse <= 0 && currentImpulse > 0) {
            worldCommandsManager.worldCommandBuffer[threadId].push(
                type = WorldCommandType.ADD_PHEROMONE,
                ints = intArrayOf(getParticleIndex(cellIndex), cellEntity.pheromoneType[cellIndex]),
                floats = floatArrayOf(getX(cellIndex), getY(cellIndex))
            )
        }

        if (currentImpulse > 0) {
            setIsPheromoneEmitter(cellIndex, true)
        } else {
            setIsPheromoneEmitter(cellIndex, false)
        }
    }
}

/** Состояние излучателя: импульс прошлого тика. Изменяемое — объект свой у каждой клетки. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("pheromone_emitter")
data class PheromoneEmitterState(
    @ProtoNumber(1) var lastImpulse: Float = 0f
) : SpecialModData
