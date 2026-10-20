package io.github.some_example_name.old.editor.system.logic

import io.github.some_example_name.old.editor.geometry.CellPlacement
import kotlinx.serialization.Serializable

/**
 * Места для дочери при делении — под каждый радиус, который предлагает диалог
 * (CellPlacement.childRadius(step)): x[step], y[step], NaN — под этот радиус места нет.
 *
 * Считаются логикой в момент клика, а не диалогом: диалог только показывает и собирает
 * команду, и итоговая позиция попадает в TryToDivide значением, как и раньше. Крупной
 * клетке места может не хватить там, где мелкая помещается, поэтому диалог по этой таблице
 * ограничивает слайдер радиуса.
 *
 * Пусто — в командах из журналов, записанных до таблицы: тогда диалог работает по одной
 * позиции, как раньше.
 */
@Serializable
class DivisionPlaces(
    val x: List<Float> = emptyList(),
    val y: List<Float> = emptyList()
) {

    /** Место для дочери радиуса [radius] (ближайшего допустимого) или null. */
    fun positionFor(radius: Float): Pair<Float, Float>? {
        if (x.isEmpty()) return null
        val step = CellPlacement.childRadiusStep(radius)
        val px = x[step]
        return if (px.isNaN()) null else px to y[step]
    }

    /** Самый крупный радиус дочери, для которого есть место, или null. */
    val maxRadius: Float?
        get() {
            for (step in x.indices.reversed()) if (!x[step].isNaN()) return CellPlacement.childRadius(step)
            return null
        }

    val isEmpty: Boolean get() = x.isEmpty()
}
