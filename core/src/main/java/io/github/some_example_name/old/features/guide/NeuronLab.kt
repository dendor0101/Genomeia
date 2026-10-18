package io.github.some_example_name.old.features.guide

import io.github.some_example_name.old.cells.base.CellTypeList
import io.github.some_example_name.old.cells.base.Formula
import io.github.some_example_name.old.cells.base.Formulas
import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.NeuralEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.systems.simulation.SimulationData
import io.github.some_example_name.old.systems.simulation.SingleThreadSimulationSystem

/**
 * Настройки нейрона на стенде формул: то, что игрок крутит ползунками. Меняется только
 * командами GuideIntent (см. GuideViewModel).
 */
class NeuronLabSettings {
    var formulaKey: String = Formulas.DEFAULT.key
    var a = 1f
    var b = 0f
    var c = 0f
    var isSum = true
    val inputs = FloatArray(MAX_INPUTS).also { it[0] = 1f }
    var inputCount = 2

    /** Вход нейрона x — так же, как его собирает симуляция: сумма или произведение входов. */
    fun x(): Float {
        var x = if (isSum) 0f else 1f
        for (i in 0 until inputCount) x = if (isSum) x + inputs[i] else x * inputs[i]
        return x
    }

    companion object {
        const val MAX_INPUTS = 4
        const val MIN_VALUE = -10f
        const val MAX_VALUE = 10f
    }
}

/**
 * Один нейрон для стенда формул в гайде — НАСТОЯЩИЙ: своя CellEntity на одну клетку со
 * своими NeuralEntity и SimulationData, и выход считает та же activation, что и мир.
 * Поэтому стенд показывает ровно то, что сделает нейрон в игре, и не может разойтись с
 * формулами, когда их поправят.
 *
 * ПОЧЕМУ НЕ ИНТЕРФЕЙС ВМЕСТО CellEntity
 * -------------------------------------
 * Проще было бы дать формулам интерфейс состояния нейрона и реализовать его здесь. Замер
 * 17.10 (JBR 21, 100k нейронов, только вызовы формул): с одной реализацией разницы нет
 * (~1.02 мс), но как только загружена вторая (этот стенд), вызовы внутри формул
 * перестают быть мономорфными: +12..15% на всю оставшуюся жизнь процесса. Поэтому тип в
 * Formula.compute остаётся CellEntity, а стенд держит свою маленькую CellEntity.
 *
 * Частицы, свои данные и органы стенду не нужны: формулы читают только поля нейрона,
 * энергию и время. Под них передаются чужие сущности (редактора), которых стенд не
 * трогает: addCell он не вызывает, клетка 0 становится нейроном через addNeural.
 */
class NeuronLab(
    cellList: CellTypeList,
    particleEntity: ParticleEntity,
    specialEntity: SpecialEntity,
    organEntity: OrganEntity,
    substrateSettings: SubstrateSettings
) {
    private val simulationData = SimulationData()
    private val cells = CellEntity(
        cellsStartMaxAmount = 1,
        particleEntity = particleEntity,
        simulationData = simulationData,
        substrateSettings = substrateSettings,
        cellList = cellList,
        neuralEntity = NeuralEntity(1, cellList),
        specialEntity = specialEntity,
        organEntity = organEntity
    )

    /** История сигнала во времени — для формул с [Formula.dependsOnTime]. */
    val historyX = FloatArray(HISTORY)
    val historyY = FloatArray(HISTORY)

    /** Сколько точек истории уже есть (до [HISTORY]); последняя — свежая. */
    var historySize = 0
        private set

    /** Последний выход нейрона во времени. */
    var lastY = 0f
        private set

    private var configuredFormula: String? = null
    private var tickAccumulator = 0f

    init {
        cells.addNeural(NEURON, cellList.indexOfKey("neuron"))
        // Для y = energy: пол-бака, чтобы на графике была не голая единица.
        cells.maxEnergy[NEURON] = 10f
        cells.energy[NEURON] = 5f
        // Для y = links amount: связей с соседями у нейрона стенда нет, пусть будет как у
        // клетки в середине тела.
        cells.linkAmount[NEURON] = LINKS
    }

    fun formula(settings: NeuronLabSettings): Formula = Formulas.byKey(settings.formulaKey)

    /** Перенести настройки в нейрон. Смена формулы начинает её память и время с нуля. */
    fun configure(settings: NeuronLabSettings) {
        if (configuredFormula != settings.formulaKey) {
            configuredFormula = settings.formulaKey
            cells.setActivationFuncType(NEURON, Formulas.requireIndex(settings.formulaKey).toByte())
            restart()
        }
        cells.setA(NEURON, settings.a)
        cells.setB(NEURON, settings.b)
        cells.setC(NEURON, settings.c)
        cells.setIsSum(NEURON, settings.isSum)
    }

    /**
     * Кривая y(x) для формулы без памяти: [ys] по точкам [xs]. Такие формулы читают только
     * a, b, c, поэтому сто вызовов подряд ничего не портят.
     */
    fun curve(xs: FloatArray, ys: FloatArray) {
        for (i in xs.indices) ys[i] = cells.activation(NEURON, xs[i])
    }

    /** Выход формулы без памяти при входе [x]. */
    fun output(x: Float): Float = cells.activation(NEURON, x)

    /**
     * Прожить [delta] секунд тиками мира (60 в секунду) с входом [x]: для формул с памятью
     * и временем. Не больше [MAX_TICKS_PER_FRAME] за кадр — после паузы окна стенд не
     * догоняет время рывком.
     */
    fun advance(delta: Float, x: Float) {
        tickAccumulator = (tickAccumulator + delta).coerceAtMost(MAX_TICKS_PER_FRAME * TICK)
        while (tickAccumulator >= TICK) {
            tickAccumulator -= TICK
            tick(x)
        }
    }

    private fun tick(x: Float) {
        simulationData.tickCounter++
        simulationData.timeSimulation += TICK
        lastY = cells.activation(NEURON, x)
        if (historySize < HISTORY) {
            historySize++
        } else {
            System.arraycopy(historyX, 1, historyX, 0, HISTORY - 1)
            System.arraycopy(historyY, 1, historyY, 0, HISTORY - 1)
        }
        historyX[historySize - 1] = x
        historyY[historySize - 1] = lastY
    }

    /** Время, память и история — с нуля. */
    fun restart() {
        simulationData.tickCounter = 0
        simulationData.timeSimulation = 0f
        cells.setRemember(NEURON, 0f)
        cells.setDTime(NEURON, -1f)
        historySize = 0
        lastY = 0f
        tickAccumulator = 0f
    }

    companion object {
        /** Тиков мира в секунде — как у симуляции (DELTA_SIM_TICK_TIME). */
        const val TICKS_PER_SECOND = 60

        private const val NEURON = 0
        private const val TICK = SingleThreadSimulationSystem.DELTA_SIM_TICK_TIME
        /** 5 секунд мира. */
        private const val HISTORY = 5 * TICKS_PER_SECOND
        private const val MAX_TICKS_PER_FRAME = 4
        private const val LINKS = 4
    }
}
