package io.github.some_example_name.old.systems.pheromone

import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.systems.pheromone.PheromonesManager.Companion.K
import io.github.some_example_name.old.systems.pheromone.PheromonesManager.Companion.P

class PheromonesManager(
    val pheromoneEntity: PheromoneEntity,
    val worldCommandsManager: WorldCommandsManager,
    val particleEntity: ParticleEntity,
    val cellEntity: CellEntity
) {

    fun newGridCell(newX: Float, newY: Float, emitterIndex: Int, threadId: Int) {
        if (particleEntity.isCell[emitterIndex]) {
            val cellIndex = particleEntity.holderEntityIndex[emitterIndex]
            if (cellEntity.neuronImpulseOutput[cellIndex] > 0f) {
                worldCommandsManager.worldCommandBuffer[threadId].push(
                    type = WorldCommandType.ADD_PHEROMONE,
                    ints = intArrayOf(emitterIndex, cellEntity.pheromoneType[cellIndex]),
                    floats = floatArrayOf(newX, newY)
                )
            }
        } else {
            worldCommandsManager.worldCommandBuffer[threadId].push(
                type = WorldCommandType.ADD_PHEROMONE,
                ints = intArrayOf(emitterIndex, 0),
                floats = floatArrayOf(newX, newY)
            )
        }
    }

    fun iterate() = with(pheromoneEntity) {
        aliveList.forEach { index ->
            val emitterIndex = emitterIndex[index]

            if (emitterIndex == -1) {
                time[index] -= 0.0005f
                radiusSquared[index] = getSquaredRadius(A = time[index])
                if (time[index] <= 0) {
                    worldCommandsManager.worldCommandBuffer[0].push(
                        type = WorldCommandType.DELETE_PHEROMONE,
                        ints = intArrayOf(index, pheromoneEntity.getGeneration(index))
                    )
                }
            } else {
                val emitterX = particleEntity.x(emitterIndex).toInt()
                val emitterY = particleEntity.y(emitterIndex).toInt()

                val maxLevel = if (particleEntity.isCell[emitterIndex]) {
                    cellEntity.neuronImpulseOutput[particleEntity.holderEntityIndex[emitterIndex]]
                } else 0.25f * particleEntity.radius[emitterIndex]

                if (emitterX == x[index].toInt() && emitterY == y[index].toInt() && maxLevel > 0) {
                    if (time[index] < maxLevel) {
                        time[index] += 0.0005f
                        if (time[index] > maxLevel) {
                            time[index] = maxLevel
                        }
                        radiusSquared[index] = getSquaredRadius(A = time[index])
                    } else if (time[index] > maxLevel) {
                        time[index] -= 0.0005f
                        radiusSquared[index] = getSquaredRadius(A = time[index])
                    }
                } else {
                    time[index] -= 0.0005f
                    radiusSquared[index] = getSquaredRadius(A = time[index])
                    if (time[index] <= 0) {
                        worldCommandsManager.worldCommandBuffer[0].push(
                            type = WorldCommandType.DELETE_PHEROMONE,
                            ints = intArrayOf(index, pheromoneEntity.getGeneration(index))
                        )
                    }
                }
            }
        }
    }

    inline fun findAllPheromonesInPoint(
        posX: Float,
        posY: Float,
        type: Int,
        iterate: (Int) -> Unit
    ) {
        val cellSize = MAXIMUM_PHEROMONE_SPREAD_DIAMETER.toFloat()

        val gx = (posX / cellSize).toInt()
        val gy = (posY / cellSize).toInt()

        val fracX = (posX % cellSize) / cellSize
        val fracY = (posY % cellSize) / cellSize

        pheromoneEntity.pack(gx, gy).iteratePheromoneMapGrid(type, iterate)

        if (fracX >= 0.5f) {
            pheromoneEntity.pack(gx + 1, gy).iteratePheromoneMapGrid(type, iterate)
        } else if (gx > 0) {
            pheromoneEntity.pack(gx - 1, gy).iteratePheromoneMapGrid(type, iterate)
        }

        if (fracY >= 0.5f) {
            pheromoneEntity.pack(gx, gy + 1).iteratePheromoneMapGrid(type, iterate)
        } else if (gy > 0) {
            pheromoneEntity.pack(gx, gy - 1).iteratePheromoneMapGrid(type, iterate)
        }

        val diagX = if (fracX >= 0.5f) gx + 1 else gx - 1
        val diagY = if (fracY >= 0.5f) gy + 1 else gy - 1

        if (diagX >= 0 && diagY >= 0) {
            pheromoneEntity.pack(diagX, diagY).iteratePheromoneMapGrid(type, iterate)
        }
    }

    inline fun Int.iteratePheromoneMapGrid(type: Int, iterate: (Int) -> Unit) {
        pheromoneEntity.pheromoneMapGrid[type]?.get(this)?.forEach(iterate)
    }

    fun f(x: Float, a: Float): Float {
        return a / (1f + K * x)
    }

    companion object {
        //Maximum pheromone spread diameter
        const val MAXIMUM_PHEROMONE_SPREAD_DIAMETER = 32
        const val K = 0.4f
        const val P = 0.01f
    }

}


fun getSquaredRadius(
    A: Float
): Float {
    if (A <= P) return 0f
    return (A / P - 1f) / K
}
