package io.github.some_example_name.old.systems.genomics

import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.DEBUG_CHECKS
import io.github.some_example_name.old.core.DISimulationContainer.energyTransportRate
import io.github.some_example_name.old.core.DISimulationContainer.threadCount
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.simulation.Phase
import io.github.some_example_name.old.systems.simulation.ThreadManager
import kotlin.math.sqrt

class CellSystem(
    val cellEntity: CellEntity,
    val linkEntity: LinkEntity,
    val organEntity: OrganEntity,
    val genomeManager: GenomeManager,
    val worldCommandsManager: WorldCommandsManager,
    val gridManager: GridManager,
    val divideManager: DivideManager,
    val mutateManager: MutateManager,
    val threadManager: ThreadManager?
): Disposable {

    /**
     * Типы клеток массивом вместо List<Cell>.
     *
     * cellList[type].doOnTick(...) вызывается для каждой живой клетки каждый тик.
     * Через List это invokeinterface List.get + checkcast, через массив — один aaload.
     * Состав списка после старта не меняется, поэтому копия безопасна.
     */
    private val cells: Array<Cell> = cellEntity.cellList.toTypedArray()

    fun iterateCellInParallel() = with(cellEntity) {
        if (threadManager == null) return@with
        val size = aliveList.size

        if (size == 0) return

        // ОБХОД ПО АРЕНАМ.
        //
        // Прежняя нарезка шла блоками aliveList, а он хранит клетки в порядке добавления
        // и перемешивается swap-with-last при каждой смерти. То есть блок доставался
        // воркеру произвольным набором клеток со всего мира: одна арена, вторая, третья,
        // обратно в первую. Диапазон арены обходится подряд, и клетки одного тела читаются
        // одним потоком.
        //
        // Заодно это делает владение явным: организм целиком принадлежит одному воркеру,
        // а не размазан по блокам.
        val organs = organEntity.aliveList
        val organCount = organs.size

        // Последняя работа — клетки вне арен: так живут клетки редактора (арены там
        // выключены). В мире их нет — потомку организм с ареной заводится раньше его
        // зиготы (OrganismSpawner). Работа стоит O(aliveList) с одной дешёвой проверкой, поэтому
        // включается только когда такие клетки есть, иначе это был бы холостой проход
        // по всем клеткам мира на одном воркере.
        val hasOrphans = orphanCellCount > 0
        val workCount = organCount + if (hasOrphans) 1 else 0
        if (workCount == 0) return

        threadManager.runWorkStage(workCount, Phase.CELLS) { work, workerId ->
            if (work < organCount) {
                val organIndex = organs.getInt(work)
                if (organEntity.hasArena(organIndex)) {
                    val from = organEntity.cellArenaBase[organIndex]
                    val to = organEntity.cellArenaEnd(organIndex)
                    for (cellIndex in from until to) {
                        // Дырки в арене: слот умершей клетки не переиспользуется.
                        if (!isAlive[cellIndex]) continue
                        processCell(cellIndex, workerId)
                    }
                }
            } else {
                for (i in 0 until aliveList.size) {
                    val cellIndex = aliveList.getInt(i)
                    if (organEntity.hasArena(organIndex[cellIndex])) continue
                    processCell(cellIndex, workerId)
                }
            }
        }
    }

    fun processCell(cellIndex: Int, threadId: Int = 0) = with(cellEntity) {
        if (!isAlive[cellIndex]) return

        val isNeural = isNeural[cellIndex]

        // Проверка на NaN — отладочная. DEBUG_CHECKS это const val, поэтому при false
        // блок вырезается компилятором целиком: ни двух лишних чтений из массивов,
        // ни двух сравнений, ни конкатенации строки, ни throw в горячем методе.
        if (DEBUG_CHECKS &&
            (neuronImpulseInput[cellIndex].isNaN() || neuronImpulseOutput[cellIndex].isNaN())
        ) {
            throw Exception(
                "neuronImpulseInput $cellIndex is Nan ${cells[cellType[cellIndex].toInt()].name} " +
                    "${neuronImpulseInput[cellIndex]} ${neuronImpulseOutput[cellIndex]}"
            )
        }

        if (isNeural) {
            if (getIsNeuronTransportable(cellIndex)) {
                val impulse = activation(cellIndex, neuronImpulseInput[cellIndex])
                neuronImpulseOutput[cellIndex] = impulse
            }
        } else {
            neuronImpulseOutput[cellIndex] = neuronImpulseInput[cellIndex]
        }

        cells[cellType[cellIndex].toInt()].doOnTick(cellIndex = cellIndex, threadId = threadId)

        if (isNeural) {
            neuronImpulseInput[cellIndex] = if (getIsSum(cellIndex)) 0f else 1f
        } else {
            neuronImpulseInput[cellIndex] = 0f
        }

        if (energy[cellIndex] < 0f) {
            // Скалярный push: без intArrayOf и без arraycopy на два int'а.
            worldCommandsManager.worldCommandBuffer[threadId].push(
                WorldCommandType.DELETE_CELL,
                cellIndex,
                getGeneration(cellIndex)
            )
        }

        genomicTransformations(cellIndex, threadId)
    }

    //TODO лишний раз считает sqrt и читает данные getX getY
    fun processCellAngle(cellIndex: Int, parentCellIndex: Int) = with(cellEntity) {
        val dx = getX(cellIndex) - getX(parentCellIndex)
        val dy = getY(cellIndex) - getY(parentCellIndex)

        val len = sqrt(dx * dx + dy * dy)
        val toChildCos = dx / len
        val toChildSin = dy / len

        val cd = angleCompensationCos[cellIndex]
        val sd = angleCompensationSin[cellIndex]

        val parentCos = toChildCos * cd - toChildSin * sd
        val parentSin = toChildSin * cd + toChildCos * sd

        val directedCos = angleDirectedCos[cellIndex]
        val directedSin = angleDirectedSin[cellIndex]

        angleCos[cellIndex] = parentCos * directedCos - parentSin * directedSin
        angleSin[cellIndex] = parentSin * directedCos + parentCos * directedSin
    }

    fun genomicTransformations(cellIndex: Int, threadId: Int = 0) = with(cellEntity) {
        val organIndex = organIndex[cellIndex]
        if (!organEntity.alreadyGrownUp[organIndex]) {
            if (organEntity.justChangedStage[organIndex]) {
                val currentStage = genomeManager.genomes[organEntity.genomeIndex[organIndex]]
                    .genomeStageInstruction[organEntity.stage[organIndex]]
                // Зигота-пустышка действий не получает вовсе — как клетка, которой в этой
                // стадии делать нечего. Счётчики стадии её тогда не ждут, и организм
                // растёт дальше без того, что выросло бы из неё.
                val action =
                    if (isDormantZygote(cellIndex)) null
                    else currentStage.cellActions[cellGenomeId[cellIndex]]
                val isDivideNotNull = action?.divide != null
                val isMutateNotNull = action?.mutate != null

                cellActions[cellIndex] = action

                isDividedInThisStage[cellIndex] = !isDivideNotNull
                isMutateInThisStage[cellIndex] = !isMutateNotNull

                if (isDivideNotNull) {
                    //TODO Make a more accurate energy calculation
                    energyNecessaryToDivide[cellIndex] = 3.0f
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        WorldCommandType.DIVIDE_ALIVE_CELL_ACTION_COUNTER,
                        organIndex
                    )

                }

                if (isMutateNotNull) {
                    //TODO Make a more accurate energy calculation
                    energyNecessaryToMutate[cellIndex] = 2.0f
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        WorldCommandType.MUTATE_ALIVE_CELL_ACTION_COUNTER,
                        organIndex
                    )

                }
            }
            mutateManager.mutateCell(cellIndex, threadId)
            divideManager.divideCell(cellIndex, threadId)
        }
    }

    /**
     * Сравнение заполненности двух клеток без делений.
     *
     * Было: e1/m1 < e2/m2 и потом ещё раз e1/m1 != e2/m2 — это 4 деления на каждую связь
     * за тик. Деление float на x86 — ~11-14 циклов латентности и, в отличие от умножения,
     * плохо пайплайнится (один divider на порт), поэтому в цепочке зависимостей оно
     * упирается в latency, а не в throughput.
     *
     * Стало: кросс-умножение e1*m2 vs e2*m1 — два умножения (латентность ~4 цикла,
     * throughput 2/такт) и они независимы, поэтому считаются параллельно.
     * Плюс результаты сравнения переиспользуются вместо повторного вычисления.
     *
     * Знак неравенства сохраняется, потому что maxEnergy всегда > 0 (см. CellSettings).
     * Точность не хуже исходной: делений, каждое из которых округляет, стало ноль.
     *
     * Сколько переносить — [energyTransferAmount].
     */
    fun transportEnergy(linkCell1: Int, linkCell2: Int) = with(cellEntity) {
        val energy1 = energy[linkCell1]
        val energy2 = energy[linkCell2]
        val amount = energyTransferAmount(
            energy1, energy2, maxEnergy[linkCell1], maxEnergy[linkCell2], energyTransportRate
        )
        if (amount != 0f) {
            energy[linkCell1] = energy1 + amount
            energy[linkCell2] = energy2 - amount
        }
    }

    override fun dispose() {

    }
}

/**
 * Сколько энергии перенести по связи между двумя клетками: плюс — первой прибавить, второй
 * убавить; минус — наоборот. Заполненность сравнивается кросс-умножением (см.
 * CellSystem.transportEnergy).
 *
 * ПОРЦИЯ НЕ БОЛЬШЕ ПОЛОВИНЫ РАЗНИЦЫ
 * ---------------------------------
 * Раньше по связи всегда шла полная порция [rate] в сторону менее заполненной клетки.
 * Почти выровненные клетки от этого перескакивали через равенство и каждый тик менялись
 * ролями: энергия дрожала на ±порцию, а с ней моргало ядро в ностальгическом режиме.
 * Теперь у равенства порция — половина того, что выровняло бы пару ровно:
 * x = (e2·m1 − e1·m2) / (m1 + m2) переносит клетки точно в равную заполненность, а
 * половина гасит перелёт, когда у клетки несколько связей. Вдали от равенства порция та
 * же, что раньше, и деление — только в ветке у равенства.
 */
fun energyTransferAmount(energy1: Float, energy2: Float, maxEnergy1: Float, maxEnergy2: Float, rate: Float): Float {
    // > 0 — вторая заполнена сильнее, энергия идёт в первую.
    val excess = energy2 * maxEnergy1 - energy1 * maxEnergy2
    val maxEnergySum = maxEnergy1 + maxEnergy2
    // При такой разнице половина выравнивающей порции как раз равна rate.
    val fullStep = 2f * rate * maxEnergySum
    return when {
        excess >= fullStep -> rate
        excess <= -fullStep -> -rate
        else -> 0.5f * excess / maxEnergySum
    }
}
