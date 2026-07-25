package io.github.some_example_name.old.editor.system

import com.badlogic.gdx.Gdx
import io.github.some_example_name.old.core.utils.distanceTo
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.currentTick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.nextStageTick
import io.github.some_example_name.old.editor.entities.CellReplay
import io.github.some_example_name.old.editor.system.logic.ToEditorDataMapper
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.physics.GridManager

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


    fun getAll2LayersNeighboursEditor(
        clickedX: Float,
        clickedY: Float,
        clickedCellIndex: Int
    ): List<Int> {
        val gridGrabbedX = clickedX.toInt()
        val gridGrabbedY = clickedY.toInt()
        val allCells = mutableListOf<Int>()
        for (i in -2..2) {
            for (j in -2..2) {
                if (i == 2 && j == 2) continue
                if (i == -2 && j == 2) continue
                if (i == 2 && j == -2) continue
                if (i == -2 && j == -2) continue
                allCells.addAll(gridManager.getParticles(gridGrabbedX + i, gridGrabbedY + j).toList())
            }
        }
        return allCells.filter { it != clickedCellIndex }
    }

    fun getPositionForNewCell(
        clickedCellIndex: Int,
        symmetryManager: SymmetryManager,
    ): Pair<Float, Float>? {
        val clickedCell = toEditorDataMapper.mapToEditorData(clickedCellIndex)
        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()

        val neighboursAllowedForConnectionIds = getAll2LayersNeighboursEditor(
            clickedCell.x,
            clickedCell.y,
            clickedCellIndex
        )
        neighboursAllowedForConnectionIds.forEach { it ->
            val clickedCell = toEditorDataMapper.mapToEditorData(it)
            xs.add(clickedCell.x)
            ys.add(clickedCell.y)
        }

        val newPoint = symmetryManager.newPoint(clickedCell, xs, ys)
        return newPoint
    }

}
