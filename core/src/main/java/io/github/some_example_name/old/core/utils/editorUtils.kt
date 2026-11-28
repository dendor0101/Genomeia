package io.github.some_example_name.old.core.utils

import kotlin.math.*

const val START_EDITOR_CELL_X = 64f
const val START_EDITOR_CELL_Y = 64f

// Поиск места для новой клетки — editor/geometry/CellPlacement: расстояния там в долях
// суммы радиусов, а не прежними числами под клетки радиусом 0.5.

/**
 * Перетаскиваемая клетка не уходит от родителя дальше [DRAG_MAX_DISTANCE] и не подходит
 * ближе [DRAG_MIN_DISTANCE] — в долях суммы их радиусов.
 *
 * Раньше пределы были числами, 0.125 и 0.75, и для мелких клеток неверны: две клетки по 0.2
 * (сумма 0.4) расходились на 0.75, то есть с щелью между собой, а связь оставалась. Доли
 * взяты от двух клеток максимального радиуса 0.5 (сумма 1), где прежние числа и
 * подбирались, — для них поведение то же.
 */
fun setMinMaxDistForChildCellToParent(
    childCellX: Float, childCellY: Float, childRadius: Float,
    parentCellX: Float, parentCellY: Float, parentRadius: Float
): Pair<Float, Float> {
    val dx = childCellX - parentCellX
    val dy = childCellY - parentCellY
    val distance = sqrt(dx * dx + dy * dy)

    val sum = childRadius + parentRadius
    val minDistance = DRAG_MIN_DISTANCE * sum
    val maxDistance = DRAG_MAX_DISTANCE * sum

    return when {
        // Ровно в центре родителя направления нет — отодвигаем вправо, а не делим на ноль.
        distance == 0f -> Pair(parentCellX + minDistance, parentCellY)
        distance < minDistance -> {
            // Если слишком близко, перемещаем на границу minDistance
            val scale = minDistance / distance
            Pair(parentCellX + dx * scale, parentCellY + dy * scale)
        }
        distance > maxDistance -> {
            // Если слишком далеко, перемещаем на границу maxDistance
            val scale = maxDistance / distance
            Pair(parentCellX + dx * scale, parentCellY + dy * scale)
        }
        // Если расстояние в пределах, оставляем как есть
        else -> Pair(childCellX, childCellY)
    }
}

/** Ближе всего к родителю при перетаскивании — доля суммы радиусов (было 0.125 при сумме 1). */
const val DRAG_MIN_DISTANCE = 0.125f

/** Дальше всего от родителя при перетаскивании — доля суммы радиусов (было 0.75 при сумме 1). */
const val DRAG_MAX_DISTANCE = 0.75f
