package io.github.some_example_name.old.editor.tutorial

import io.github.some_example_name.old.cells.base.Formulas
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.NeuralLinkEntity
import it.unimi.dsi.fastutil.ints.IntArrayList

/**
 * Выращенный организм глазами обучения: какие клетки в нём есть и куда идёт сигнал.
 *
 * Снимается с сущностей редактора после выращивания тела (EditorSimulationSystem.simulate):
 * там лежит взрослый организм. Поэтому проверки задания «соедини Controller с Tail» видят
 * ровно то, что потом поплывёт в мире, а не то, как это записано в стадиях генома.
 *
 * Направление нейросвязи — по тому же правилу, что в симуляции (NeuralLinkManager.
 * transportNeuralSignal): при isLink1NeuralDirected сигнал идёт от links2 к links1.
 *
 * Снимок не горячий путь: строится раз на правку генома и только на шагах обучения,
 * которые смотрят на организм. Клеток в учебном организме десятки.
 */
class OrganismSnapshot internal constructor(
    /** Ключ типа по номеру клетки в снимке. */
    val cellTypes: Array<String>,
    /** Ключ формулы по номеру клетки; null — клетка не нейронная. */
    val formulas: Array<String?>,
    /** Коэффициент c формулы нейрона (у ненейронной клетки — 0). */
    val c: FloatArray,
    /** Куда клетка отдаёт сигнал: номера клеток снимка. */
    private val outgoing: Array<IntArrayList>
) {

    val size: Int get() = cellTypes.size

    fun count(cellType: String): Int = cellTypes.count { it == cellType }

    /** Есть ли нейросвязь, по которой сигнал идёт из клетки [from] в клетку [to]. */
    fun hasSignal(from: Int, to: Int): Boolean = outgoing[from].contains(to)

    /** Есть ли прямая нейросвязь от клетки типа [fromType] к клетке типа [toType]. */
    fun hasSignal(fromType: String, toType: String): Boolean =
        cellTypes.indices.any { from ->
            cellTypes[from] == fromType && outgoing[from].any { cellTypes[it] == toType }
        }

    /**
     * Доходит ли сигнал от клетки типа [fromType] до клетки типа [toType] — напрямую или
     * через другие клетки: сигнал идёт по любой цепочке, и игрок, проведший его через
     * третью клетку, ничего не нарушил.
     */
    fun reaches(fromType: String, toType: String): Boolean {
        val reached = reachableFrom(cellTypes.indices.filter { cellTypes[it] == fromType })
        return cellTypes.indices.any { reached[it] && cellTypes[it] == toType }
    }

    /**
     * Есть ли цепочка из [links] нейросвязей подряд через разные клетки (A -> B -> C -> …).
     * Поиск в глубину на [links] шагов: в учебном организме десятки клеток, и глубина мала.
     */
    fun hasChain(links: Int): Boolean {
        val onPath = BooleanArray(size)
        fun walk(cell: Int, left: Int): Boolean {
            if (left == 0) return true
            onPath[cell] = true
            val next = outgoing[cell]
            for (i in 0 until next.size) {
                val to = next.getInt(i)
                if (!onPath[to] && walk(to, left - 1)) {
                    onPath[cell] = false
                    return true
                }
            }
            onPath[cell] = false
            return false
        }
        return cellTypes.indices.any { walk(it, links) }
    }

    /** Нейроны с формулой [formula]. */
    fun neurons(formula: String): List<Int> =
        cellTypes.indices.filter { cellTypes[it] == NEURON && formulas[it] == formula }

    /**
     * Все клетки, до которых доходит сигнал из [from] по цепочке нейросвязей (сами
     * [from] — только если сигнал к ним возвращается по кругу).
     */
    fun reachableFrom(from: List<Int>): BooleanArray {
        val reached = BooleanArray(size)
        val queue = ArrayDeque<Int>()
        from.forEach(queue::addLast)
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            val next = outgoing[cell]
            for (i in 0 until next.size) {
                val to = next.getInt(i)
                if (!reached[to]) {
                    reached[to] = true
                    queue.addLast(to)
                }
            }
        }
        return reached
    }

    /** Сколько клеток типа [cellType] среди отмеченных в [cells]. */
    fun count(cellType: String, cells: BooleanArray): Int =
        cellTypes.indices.count { cells[it] && cellTypes[it] == cellType }

    companion object {
        const val NEURON = "neuron"

        fun of(cellEntity: CellEntity, neuralLinkEntity: NeuralLinkEntity): OrganismSnapshot {
            val alive = cellEntity.aliveList
            val positionOf = HashMap<Int, Int>(alive.size * 2)
            val formulas = Formulas.all()
            val types = Array(alive.size) { position ->
                val cellIndex = alive.getInt(position)
                positionOf[cellIndex] = position
                cellEntity.cellList[cellEntity.cellType[cellIndex].toInt()].key
            }
            val formulaKeys = Array(alive.size) { position ->
                val cellIndex = alive.getInt(position)
                if (cellEntity.isNeural[cellIndex]) formulas[cellEntity.getActivationFuncType(cellIndex)].key else null
            }

            val c = FloatArray(alive.size) { position ->
                val cellIndex = alive.getInt(position)
                if (cellEntity.isNeural[cellIndex]) cellEntity.getC(cellIndex) else 0f
            }

            val outgoing = Array(alive.size) { IntArrayList() }
            val links = neuralLinkEntity.aliveList
            for (i in 0 until links.size) {
                val link = links.getInt(i)
                val a = positionOf[neuralLinkEntity.links1[link]] ?: continue
                val b = positionOf[neuralLinkEntity.links2[link]] ?: continue
                if (neuralLinkEntity.isLink1NeuralDirected[link]) outgoing[b].add(a) else outgoing[a].add(b)
            }
            return OrganismSnapshot(types, formulaKeys, c, outgoing)
        }
    }
}
