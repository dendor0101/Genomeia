package io.github.some_example_name.old.systems.physics

import com.badlogic.gdx.math.MathUtils.clamp
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.systems.simulation.SimulationSystem

class PheromonesManager(
    val pheromoneEntity: PheromoneEntity,
    val substrateSettings: SubstrateSettings
) {

    private fun diffusePheromones(cm: SimulationSystem, x1: Int, y1: Int, x2: Int, y2: Int) = with(pheromoneEntity) {
//        val cellIndex1 = y1 * gridCellWidthSize + x1
//        val cellIndex2 = y2 * gridCellWidthSize + x2
//        if (cellIndex1 == cellIndex2) return
//        if (cellIndex1 < 0 || cellIndex1 >= GRID_SIZE || cellIndex2 < 0 || cellIndex2 >= GRID_SIZE) return
//        val dR = pheromoneR[cellIndex2] - pheromoneR[cellIndex1]
//        val dG = pheromoneG[cellIndex2] - pheromoneG[cellIndex1]
//        val dB = pheromoneB[cellIndex2] - pheromoneB[cellIndex1]
//        val diffusionRate = globalSettings.rateOfPheromoneDiffusion
//        pheromoneEntity.pheromoneR[cellIndex1] += dR * diffusionRate
//        pheromoneEntity.pheromoneR[cellIndex2] -= dR * diffusionRate
//        pheromoneEntity.pheromoneG[cellIndex1] += dG * diffusionRate
//        pheromoneEntity.pheromoneG[cellIndex2] -= dG * diffusionRate
//        pheromoneEntity.pheromoneB[cellIndex1] += dB * diffusionRate
//        pheromoneEntity.pheromoneB[cellIndex2] -= dB * diffusionRate
    }

    fun pheromoneUpdate(cm: SimulationSystem, x: Int, y: Int) = with(pheromoneEntity) {
//        // Diffuse pheromones between neighboring chunks and slightly reduce their concentration
//        val cellIndex = y * gridCellWidthSize + x
//        val degradationRate = globalSettings.rateOfPheromoneDegradation
//        pheromoneR[cellIndex] -= degradationRate
//        pheromoneG[cellIndex] -= degradationRate
//        pheromoneB[cellIndex] -= degradationRate
//        // TODO: This operation is just a simple box blur, I'm like 99% sure there is some simple optimisation for this
//        for (i in -1..1) {
//            if (x + i !in 0..<gridCellWidthSize) continue
//            for (j in -1..1) {
//                if (y + j !in 0..<gridCellHeightSize) continue
//                diffusePheromones(cm, x, y, x + i, y + j)
//            }
//        }
//        // Clamp between 0 and 1
//        pheromoneR[cellIndex] = clamp(pheromoneR[cellIndex], 0f, 1f)
//        pheromoneG[cellIndex] = clamp(pheromoneG[cellIndex], 0f, 1f)
//        pheromoneB[cellIndex] = clamp(pheromoneB[cellIndex], 0f, 1f)
    }
}
