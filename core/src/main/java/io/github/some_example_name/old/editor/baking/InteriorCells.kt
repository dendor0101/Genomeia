package io.github.some_example_name.old.editor.baking

import io.github.some_example_name.old.core.utils.MAX_DISTANCE_TO_CENTER
import io.github.some_example_name.old.core.utils.MIN_DISTANCE_TO_CENTER
import io.github.some_example_name.old.core.utils.MIN_DISTANCE_TO_OTHERS
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Внутренние клетки выросшего тела — те, рядом с которыми нет места для новой клетки.
 *
 * КРИТЕРИЙ — ТОТ ЖЕ, ЧТО У РЕДАКТОРА
 * ---------------------------------
 * Редактор ищет место для деления (findNewOptimalCellPosition) так: точка на расстоянии
 * от MIN_DISTANCE_TO_CENTER до MAX_DISTANCE_TO_CENTER от клетки и не ближе
 * MIN_DISTANCE_TO_OTHERS к любой другой. Нет такой точки — клетку нельзя делить, то есть
 * она со всех сторон закрыта соседями. Это и есть «внутренняя»: признак геометрический,
 * поэтому верен для любой сетки и любой формы тела, а не только для треугольной решётки,
 * под которую подобран прежний порог в шесть связей.
 *
 * ПОЧЕМУ НЕ ПЕРЕБОРОМ, КАК В РЕДАКТОРЕ
 * -----------------------------------
 * Редактор перебирает 101 радиус × 360 углов на одну клетку, по которой кликнули. Здесь
 * клеток тысячи («Megalodon» — 5120), и тот же перебор стоил бы ~36 тысяч точек × соседей
 * на каждую. Вместо этого на каждом радиусе считается, какую ДУГУ окружности закрывает
 * каждый сосед (точки окружности ближе MIN_DISTANCE_TO_OTHERS к нему), и проверяется,
 * закрывают ли дуги всю окружность. Это точный ответ для данного радиуса, а не выборка.
 *
 * Считается только при запекании, в редакторе: сложность и аллокации здесь не важны,
 * а результат лежит в геноме и в мире только читается.
 */
object InteriorCells {

    /** Шаг по радиусу: у редактора ~0.003, здесь грубее — дуги меняются плавно. */
    private const val RADIUS_STEPS = 31

    /**
     * Щель уже этой — не щель. У идеальной решётки дуги соседей сходятся встык, и ошибка
     * округления оставила бы между ними волосяной просвет. Редактор такой просвет тоже не
     * видит: его шаг по углу — градус, здесь допуск — половина градуса.
     */
    private const val MIN_GAP = (PI / 360.0).toFloat()

    private const val TWO_PI = (2.0 * PI).toFloat()

    /** Для каждой клетки ([xs], [ys] — её позиция во взрослом теле): внутренняя ли она. */
    fun find(xs: FloatArray, ys: FloatArray): BooleanArray {
        val n = xs.size
        val reach = MAX_DISTANCE_TO_CENTER + MIN_DISTANCE_TO_OTHERS.toFloat()
        val reach2 = reach * reach
        val result = BooleanArray(n)
        val neighbourDistance = FloatArray(n)
        val neighbourAngle = FloatArray(n)

        for (i in 0 until n) {
            // Соседи, которые вообще могут закрыть хоть что-то: дальше reach их диск
            // запрета не дотягивается ни до одной окружности поиска.
            var count = 0
            for (j in 0 until n) {
                if (j == i) continue
                val dx = xs[j] - xs[i]
                val dy = ys[j] - ys[i]
                val d2 = dx * dx + dy * dy
                if (d2 >= reach2) continue
                neighbourDistance[count] = sqrt(d2)
                neighbourAngle[count] = atan2(dy, dx)
                count++
            }
            result[i] = isClosed(neighbourDistance, neighbourAngle, count)
        }
        return result
    }

    /** Нет ли ни на одной окружности поиска точки, свободной от всех соседей. */
    private fun isClosed(distance: FloatArray, angle: FloatArray, count: Int): Boolean {
        if (count == 0) return false
        val keep = MIN_DISTANCE_TO_OTHERS.toFloat()
        val starts = FloatArray(count * 2)
        val ends = FloatArray(count * 2)

        for (step in 0 until RADIUS_STEPS) {
            val r = MIN_DISTANCE_TO_CENTER +
                (MAX_DISTANCE_TO_CENTER - MIN_DISTANCE_TO_CENTER) * step / (RADIUS_STEPS - 1)

            var arcs = 0
            var fullyCovered = false
            for (k in 0 until count) {
                val d = distance[k]
                // Вся окружность внутри диска запрета соседа.
                if (d + r < keep) { fullyCovered = true; break }
                // Окружность и диск не пересекаются.
                if (d - r >= keep || r - d >= keep) continue

                // Полуширина закрытой дуги — из треугольника (центр, сосед, точка
                // окружности на расстоянии keep от соседа), по теореме косинусов.
                val cos = ((r * r + d * d - keep * keep) / (2f * r * d)).coerceIn(-1f, 1f)
                val half = acos(cos)
                var start = angle[k] - half
                var end = angle[k] + half
                // В [0, 2π): дуга через ноль режется надвое.
                if (start < 0f) { start += TWO_PI; end += TWO_PI }
                if (end > TWO_PI) {
                    starts[arcs] = start; ends[arcs] = TWO_PI; arcs++
                    starts[arcs] = 0f; ends[arcs] = end - TWO_PI; arcs++
                } else {
                    starts[arcs] = start; ends[arcs] = end; arcs++
                }
            }
            if (fullyCovered) continue
            if (!covers(starts, ends, arcs)) return false
        }
        return true
    }

    /** Закрывают ли дуги всю окружность [0, 2π) с точностью до [MIN_GAP]. */
    private fun covers(starts: FloatArray, ends: FloatArray, arcs: Int): Boolean {
        if (arcs == 0) return false
        // Вставками: дуг — десяток-другой.
        for (i in 1 until arcs) {
            val s = starts[i]; val e = ends[i]
            var j = i - 1
            while (j >= 0 && starts[j] > s) {
                starts[j + 1] = starts[j]; ends[j + 1] = ends[j]; j--
            }
            starts[j + 1] = s; ends[j + 1] = e
        }
        var reached = 0f
        for (i in 0 until arcs) {
            if (starts[i] > reached + MIN_GAP) return false
            if (ends[i] > reached) reached = ends[i]
        }
        return reached >= TWO_PI - MIN_GAP
    }
}
