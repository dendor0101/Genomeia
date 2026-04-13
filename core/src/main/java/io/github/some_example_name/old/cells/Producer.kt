package io.github.some_example_name.old.cells

import com.badlogic.gdx.math.MathUtils
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.DISimulationContainer.zygote
import io.github.some_example_name.old.core.utils.redColors
import io.github.some_example_name.old.systems.physics.ParticlePhysicsSystem.Companion.PARTICLE_MAX_RADIUS

class Producer(cellTypeId: Int): Cell(
    defaultColor = redColors[4],
    cellTypeId = cellTypeId,
    isDirected = true,
    isNeural = true
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        val impulse = neuronImpulseOutput[cellIndex]

        neuronImpulseOutput[cellIndex] = 0f
        if(energy[cellIndex] < substrateSettings.cellsSettings[cellType[cellIndex].toInt()].maxEnergy || impulse < 1) return

        //TODO Make the impulse increase smoothly from 0 to 1, and have the producer divide at the moment when the impulse equals 1
        val organIndex = organIndex[cellIndex]
        val genomeIndex = organEntity.genomeIndex[organIndex] // TODO сделать выбор sub-genome
        val genome = genomeManager.genomes[genomeIndex]
        var counter = 0
        genome.genomeStageInstruction.forEach {
            counter += it.cellActions.size
        }

        val color: Int = zygote.defaultColor.toIntBits()
        val radius: Float = PARTICLE_MAX_RADIUS
        val cellType: Int = zygote.cellTypeId
        val parentIndex: Int = cellIndex
        val angle: Float = angle[cellIndex]
        val angleDiff: Float = 0f
        val colorDifferentiation: Int = 7
        val visibilityRange: Float = 4.25f
        val a: Float = 1f
        val b: Float = 0f
        val c: Float = 0f
        val isSum: Boolean = true
        val activationFuncType: Int = 0
        val divideAngle = this.angleDiff[cellIndex] + this.angle[cellIndex]
        val x = getX(cellIndex) + MathUtils.cos(divideAngle) * 0.05f
        val y = getY(cellIndex) + MathUtils.sin(divideAngle) * 0.05f

        worldCommandsManager.worldCommandBuffer[threadId].push(
            type = WorldCommandType.ADD_CELL,
            booleans = booleanArrayOf(isSum),
            floats = floatArrayOf(x, y, radius, angle, angleDiff, visibilityRange, a, b, c),
            ints = intArrayOf(
                color,
                0,
                cellType,
                organIndex,
                parentIndex,
                colorDifferentiation,
                activationFuncType
            )
        )

        neuronImpulseOutput[cellIndex] = 1f
        energy[cellIndex] -= energy[cellIndex]
    }
}
