package io.github.some_example_name.old.editor.baking

import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.genomics.genome.GenomePreview
import it.unimi.dsi.fastutil.ints.IntArrayList

/**
 * Снимок выращенного тела для превью генома (см. [GenomePreview]).
 *
 * Снимается там же и тогда же, что и раскладка (RCMSort): при запекании, когда тело в
 * сущностях редактора выращено целиком. Данные берутся те же, что рисует редактор
 * (EditorRenderSystem.fillBuffer): позиция и радиус частицы, угол клетки, цвет частицы,
 * тип. Поэтому превью выглядит ровно как тело на экране редактора.
 *
 * Выполняется раз на сохранение генома, поэтому код написан на понятность, а не на скорость.
 */
class GenomePreviewCapture(
    private val cellEntity: CellEntity,
    private val particleEntity: ParticleEntity
) {

    /** [organIndex] — организм, который снимаем; в редакторе он всегда 0 (как в RCMSort). */
    fun capture(organIndex: Int = 0): GenomePreview {
        val cells = organCells(organIndex)
        val count = cells.size
        if (count == 0) return GenomePreview()

        // Центр рамки тела вместе с радиусами: превью рисует тело вокруг нуля.
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (k in 0 until count) {
            val cell = cells.getInt(k)
            val x = cellEntity.getX(cell)
            val y = cellEntity.getY(cell)
            val r = cellEntity.getRadius(cell)
            minX = minOf(minX, x - r)
            minY = minOf(minY, y - r)
            maxX = maxOf(maxX, x + r)
            maxY = maxOf(maxY, y + r)
        }
        val centerX = (minX + maxX) * 0.5f
        val centerY = (minY + maxY) * 0.5f

        // Ключи типов — в порядке первой встречи: так снимок одного и того же тела
        // побайтно один и тот же.
        val cellList = cellEntity.cellList
        val keys = ArrayList<String>()
        val keyIndexOfType = IntArray(cellList.size) { -1 }

        val cellType = IntArray(count)
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        val radius = FloatArray(count)
        val angleCos = FloatArray(count)
        val angleSin = FloatArray(count)
        val color = IntArray(count)
        val cellGenomeId = IntArray(count)

        for (k in 0 until count) {
            val cell = cells.getInt(k)
            val type = cellEntity.cellType[cell].toInt()
            if (keyIndexOfType[type] == -1) {
                keyIndexOfType[type] = keys.size
                keys += cellList[type].key
            }
            cellType[k] = keyIndexOfType[type]
            xs[k] = cellEntity.getX(cell) - centerX
            ys[k] = cellEntity.getY(cell) - centerY
            radius[k] = cellEntity.getRadius(cell)
            angleCos[k] = cellEntity.angleCos[cell]
            angleSin[k] = cellEntity.angleSin[cell]
            color[k] = particleEntity.color[cellEntity.particleIndexes[cell]]
            cellGenomeId[k] = cellEntity.cellGenomeId[cell]
        }

        return GenomePreview(
            cellTypeKeys = keys,
            cellType = cellType,
            x = xs,
            y = ys,
            radius = radius,
            angleCos = angleCos,
            angleSin = angleSin,
            colorBytes = GenomePreview.colorBytesOf(color),
            cellGenomeId = cellGenomeId
        )
    }

    private fun organCells(organIndex: Int): IntArrayList {
        val alive = cellEntity.aliveList
        val result = IntArrayList(alive.size)
        for (i in 0 until alive.size) {
            val cell = alive.getInt(i)
            if (cellEntity.organIndex[cell] == organIndex) result.add(cell)
        }
        return result
    }
}
