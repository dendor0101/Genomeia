package io.github.some_example_name.old.editor.system

import io.github.some_example_name.old.core.utils.distanceTo
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.currentTick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.nextStageTick
import io.github.some_example_name.old.editor.entities.CellReplay
import io.github.some_example_name.old.editor.geometry.CellPlacement
import io.github.some_example_name.old.editor.system.logic.DivisionPlaces
import io.github.some_example_name.old.editor.system.logic.ToEditorDataMapper
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.physics.GridManager
import kotlin.math.ceil

class CellSearchManager(
    val cellReplay: CellReplay,
    val particleEntity: ParticleEntity,
    val gridManager: GridManager,
    val toEditorDataMapper: ToEditorDataMapper
) {

    fun getClickedCellIndex(
        clickX: Float,
        clickY: Float,
        itselfIndex: Int? = null
    ): Pair<Int, Boolean>? {

        val x = clickX.toInt()
        val y = clickY.toInt()

        val fx = clickX - x
        val fy = clickY - y

        val dx = if (fx < 0.5f) intArrayOf(-1, 0) else intArrayOf(0, 1)
        val dy = if (fy < 0.5f) intArrayOf(-1, 0) else intArrayOf(0, 1)

        var bestIndex: Int? = null
        var bestDist = Float.MAX_VALUE
        var isPhantom = false

        for (i in dx) for (j in dy) {
            for (p in gridManager.getParticles(x + i, y + j)) {

                val currentCellIndex = cellReplay.getCellIndex(currentTick, p)
                val index = currentCellIndex ?: cellReplay.getCellIndex(nextStageTick, p) ?: continue
                if (index == itselfIndex) continue

                val px = particleEntity.x[index]
                val py = particleEntity.y[index]
                val r = particleEntity.radius[index]

                val d = distanceTo(clickX, clickY, px, py)

                if (d <= r && d < bestDist) {
                    bestDist = d
                    bestIndex = p
                    isPhantom = currentCellIndex == null
                }
            }
        }

        return bestIndex?.let { it to isPhantom }
    }

    fun getAllCloseNeighboursEditor(
        grabbedX: Float,
        grabbedY: Float,
        grabbedRadius: Float,
        grabbedCellIndex: Int? = null,
        isSort: Boolean = false
    ): List<Int> {

        val gx = grabbedX.toInt()
        val gy = grabbedY.toInt()

        val result = mutableListOf<Int>()

        for (i in -1..1) for (j in -1..1) {
            for (p in gridManager.getParticles(gx + i, gy + j)) {

                if (p == grabbedCellIndex) continue

                val currentCellIndex = cellReplay.getCellIndex(currentTick, p)
                val index = currentCellIndex ?: cellReplay.getCellIndex(nextStageTick, p) ?: continue

                val dx = particleEntity.x[index] - grabbedX
                val dy = particleEntity.y[index] - grabbedY
                val rr = particleEntity.radius[index] + grabbedRadius

                if (dx * dx + dy * dy <= rr * rr) {
                    result.add(p)
                }
            }
        }

        return if (isSort) result.filterNot { it == grabbedCellIndex } else result
    }


    /**
     * Места для дочери клетки [clickedCellIndex] под каждый радиус, который предлагает
     * диалог деления (см. [DivisionPlaces]). Пустых мест нет — таблица всё равно полная, с NaN.
     */
    fun getDivisionPlaces(
        clickedCellIndex: Int,
        symmetryManager: SymmetryManager,
    ): DivisionPlaces {
        val clickedCell = toEditorDataMapper.mapToEditorData(clickedCellIndex)
        val neighbours = collectPlacementNeighbours(clickedCellIndex, symmetryManager)
        val count = CellPlacement.CHILD_RADIUS_COUNT
        val xs = ArrayList<Float>(count)
        val ys = ArrayList<Float>(count)
        for (step in 0 until count) {
            val point = symmetryManager.newPoint(clickedCell, CellPlacement.childRadius(step), neighbours)
            xs.add(point?.first ?: Float.NaN)
            ys.add(point?.second ?: Float.NaN)
        }
        return DivisionPlaces(xs, ys)
    }

    /** Место для дочери радиуса [childRadius] у клетки [clickedCellIndex] или null. */
    fun getPositionForNewCell(
        clickedCellIndex: Int,
        childRadius: Float,
        symmetryManager: SymmetryManager,
    ): Pair<Float, Float>? {
        val clickedCell = toEditorDataMapper.mapToEditorData(clickedCellIndex)
        return symmetryManager.newPoint(clickedCell, childRadius, collectPlacementNeighbours(clickedCellIndex, symmetryManager))
    }

    /**
     * Клетки, которые могут помешать дочери: все в пределах CellPlacement.neighbourReach,
     * а с сеткой симметрии — и до дальнего её узла-кандидата.
     *
     * Раньше брался квадрат 5×5 клеток сетки без углов. Без углов он не доставал до
     * соседа на расстоянии ~1.4 по диагонали, а запрет вокруг соседа дотягивается до 1.5:
     * дочь могла встать вплотную к клетке, которую поиск не видел.
     */
    private fun collectPlacementNeighbours(clickedCellIndex: Int, symmetryManager: SymmetryManager): PlacementNeighbours {
        val cx = particleEntity.x[clickedCellIndex]
        val cy = particleEntity.y[clickedCellIndex]
        val reach = maxOf(
            CellPlacement.neighbourReach(particleEntity.radius[clickedCellIndex]),
            symmetryManager.candidateReach()
        )
        val layers = ceil(reach).toInt()
        val gx = cx.toInt()
        val gy = cy.toInt()
        val result = PlacementNeighbours()
        for (i in -layers..layers) for (j in -layers..layers) {
            for (p in gridManager.getParticles(gx + i, gy + j)) {
                if (p == clickedCellIndex) continue
                val x = particleEntity.x[p]
                val y = particleEntity.y[p]
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy >= reach * reach) continue
                result.add(x, y, particleEntity.radius[p])
            }
        }
        return result
    }

}

/** Соседи для поиска места: позиции и радиусы, первые [count]. */
class PlacementNeighbours {
    var x = FloatArray(32)
        private set
    var y = FloatArray(32)
        private set
    var r = FloatArray(32)
        private set
    var count = 0
        private set

    fun add(px: Float, py: Float, pr: Float) {
        if (count == x.size) {
            x = x.copyOf(count * 2); y = y.copyOf(count * 2); r = r.copyOf(count * 2)
        }
        x[count] = px; y[count] = py; r[count] = pr
        count++
    }
}
