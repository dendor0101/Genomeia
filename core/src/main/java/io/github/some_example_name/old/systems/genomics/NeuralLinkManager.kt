package io.github.some_example_name.old.systems.genomics

import io.github.some_example_name.old.core.DEBUG_CHECKS
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.NeuralLinkEntity
import io.github.some_example_name.old.systems.simulation.Phase
import io.github.some_example_name.old.systems.simulation.ThreadManager

class NeuralLinkManager(
    val cellEntity: CellEntity,
    val neuralLinkEntity: NeuralLinkEntity,
    /** Воркеры для [iterateInParallel]; null — только однопоточный [iterate] (меню). */
    private val threadManager: ThreadManager? = null
) {

    /**
     * Нейрофаза по аренам: организм — работа, его нейросвязи подряд по слотам арены.
     *
     * Без гонок, потому что нейросвязь пишет только во вход своей клетки-приёмника
     * (neuronImpulseInput), а обе её клетки — одного организма (связи резолвятся по
     * organToIdToIndex внутри организма). Разные организмы — разные клетки.
     *
     * Детерминизм: сумма (или произведение — у нейронов с isSum = false) входа клетки
     * копится в порядке слотов её арены, а он не зависит от воркеров. Связи из общего пула
     * (арены нет или она переполнилась) идут после всех арен, одним потоком, в порядке
     * aliveList — тоже детерминированном.
     *
     * RCM здесь не нужен: на нейросвязь приходится одно чтение выхода и одна запись входа,
     * а нейросвязей в разы меньше физических. Важна не лента, а то, что тело больше не
     * ждёт своей очереди в общем однопоточном проходе.
     */
    fun iterateInParallel() {
        val threads = threadManager ?: return iterate()
        val organEntity = cellEntity.organEntity
        val organs = organEntity.aliveList
        val alive = neuralLinkEntity.isAlive
        threads.runWorkStage(organs.size, Phase.NEURAL) { work, _ ->
            val organIndex = organs.getInt(work)
            if (organEntity.hasNeuralArena(organIndex)) {
                val from = organEntity.neuralArenaBase[organIndex]
                val to = organEntity.neuralArenaEnd(organIndex)
                for (linkIndex in from until to) {
                    if (!alive[linkIndex]) continue
                    if (DEBUG_CHECKS) verifySameOrgan(linkIndex, organIndex)
                    transportNeuralSignal(linkIndex)
                }
            }
        }

        if (neuralLinkEntity.poolLinkCount == 0) return
        val list = neuralLinkEntity.aliveList
        for (i in 0 until list.size) {
            val linkIndex = list.getInt(i)
            if (neuralLinkEntity.arenaOwnerOf(linkIndex) == -1) transportNeuralSignal(linkIndex)
        }
    }

    private fun verifySameOrgan(linkIndex: Int, organIndex: Int) {
        val a = neuralLinkEntity.links1[linkIndex]
        val b = neuralLinkEntity.links2[linkIndex]
        if (cellEntity.organIndex[a] != organIndex || cellEntity.organIndex[b] != organIndex) {
            throw IllegalStateException(
                "нейросвязь $linkIndex арены организма $organIndex соединяет клетки $a " +
                    "(organIndex=${cellEntity.organIndex[a]}) и $b (organIndex=${cellEntity.organIndex[b]}) — " +
                    "её посчитал бы не тот воркер"
            )
        }
    }

    /** Однопоточно, по aliveList: меню (DISingleThreadSimulationContainer). */
    fun iterate() {
        with(neuralLinkEntity) {
            aliveList.forEach { linkIndex ->
                transportNeuralSignal(
                    linkIndex
                )
            }
        }
    }

    fun transportNeuralSignal(linkIndex: Int) = with(cellEntity) {
        val linkCellA = neuralLinkEntity.links1[linkIndex]
        val linkCellB = neuralLinkEntity.links2[linkIndex]

        if (DEBUG_CHECKS) {
            if (!isAliveAndSameGen(linkCellA, neuralLinkEntity.linksGeneration1[linkIndex]) ||
                !isAliveAndSameGen(linkCellB, neuralLinkEntity.linksGeneration2[linkIndex])
            ) {
                throw IllegalStateException(
                    "живая нейронная связь $linkIndex ссылается на мёртвую клетку: " +
                        "A=$linkCellA B=$linkCellB — detachAllNeuralLinks не был вызван"
                )
            }
        }

        val isLink1NeuralDirected = neuralLinkEntity.isLink1NeuralDirected[linkIndex]
        val signalToCellIndex = if (isLink1NeuralDirected) linkCellA else linkCellB
        val signalFromCellIndex = if (isLink1NeuralDirected) linkCellB else linkCellA

        val neuronImpulseOutput = neuronImpulseOutput[signalFromCellIndex]

        if (isNeural[signalToCellIndex]) {
            if (getIsSum(signalToCellIndex)) {
                neuronImpulseInput[signalToCellIndex] += neuronImpulseOutput
            } else {
                neuronImpulseInput[signalToCellIndex] *= neuronImpulseOutput
            }
        } else {
            neuronImpulseInput[signalToCellIndex] += neuronImpulseOutput
        }
    }
}
