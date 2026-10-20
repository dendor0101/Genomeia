package io.github.some_example_name.old.editor.baking

import io.github.some_example_name.old.editor.geometry.CircleCover
import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Внутренние клетки выросшего тела — те, до которых снаружи не дотронуться.
 *
 * КРИТЕРИЙ — ВНЕШНИЙ КОНТУР ТЕЛА
 * ------------------------------
 * Каждая клетка — диск своего настоящего радиуса. Граница объединения этих дисков — это
 * замкнутые линии из дуг, каждая дуга лежит на окружности одной клетки. Одна из линий
 * внешняя: по ней тело и касается мира. Граничные клетки — ровно те, чьи дуги на внешней
 * линии; остальные внутренние.
 *
 * Так граница БЕЗ ШВОВ: соседние по контуру клетки переходят одна в другую в точке
 * пересечения своих окружностей, то есть перекрываются. Между ними нет щели, и частица
 * любого размера — даже меньше самой мелкой клетки — до внутренней клетки не доберётся,
 * не столкнувшись с граничной. Внутренние же клетки в мире не сталкиваются вовсе (их нет
 * в сетке коллизий), поэтому любая щель в контуре — дыра в теле.
 *
 * ПОЧЕМУ НЕ «НЕТ МЕСТА ДЛЯ НОВОЙ КЛЕТКИ», КАК РАНЬШЕ
 * --------------------------------------------------
 * Прежний признак был взят у редактора: клетка внутренняя, если рядом нельзя поставить
 * новую. Он отвечает на другой вопрос и с разными радиусами ошибается в обе стороны:
 *  - щель, куда клетка редактора (с её запасом до соседей) не помещается, а мелкая
 *    частица мира проходит, считалась закрытой — клетка за ней внутренняя, хотя открыта
 *    миру. Это и есть шов;
 *  - у мелких клеток окружности поиска (0.6..0.9 при любых радиусах) выходили далеко за
 *    соседей, и граничными оказывались два-три ряда вместо одного — лишние клетки в
 *    сетке коллизий.
 * Полости внутри тела (кольцо из клеток) по новому признаку закрыты: их линии контура
 * внутренние, и снаружи до них не добраться.
 *
 * КАК СЧИТАЕТСЯ
 * -------------
 * 1. Для каждой клетки — какие дуги её окружности не закрыты соседями ([CircleCover]).
 * 2. Самая левая точка тела (минимум x − r) точно на внешней линии. С её дуги идём по
 *    контуру: дуга кончается там, где начинается диск соседа, и контур продолжается на его
 *    окружности — дугой, которая начинается в этой же точке. Пока не вернёмся к началу.
 * 3. Тело может быть из нескольких кусков, не перекрывающихся друг с другом (связь между
 *    клетками, которые только касаются). У каждого куска свой внешний контур — шаг 2
 *    повторяется для каждого.
 * Если обход не сошёлся (вырожденная геометрия, ошибка округления), граничной считается
 * каждая клетка с открытой дугой: лишняя клетка в сетке коллизий безопаснее шва.
 *
 * Считается только при запекании, в редакторе: сложность и аллокации здесь не важны,
 * а результат лежит в геноме и в мире только читается.
 */
object InteriorCells {

    /**
     * Открытая дуга короче этой (тысячная доля градуса) — шум округления в точке, где
     * сходятся три окружности, а не щель. Сам контур через такую точку всё равно пройдёт:
     * переход ищется по ближайшему началу дуги.
     */
    private const val MIN_ARC = PI / 180_000.0

    /**
     * Для каждой клетки ([xs], [ys] — её позиция во взрослом теле, [rs] — радиус):
     * внутренняя ли она.
     */
    fun find(xs: FloatArray, ys: FloatArray, rs: FloatArray): BooleanArray {
        val n = xs.size
        val neighbours = findOverlaps(xs, ys, rs)
        val arcs = collectArcs(xs, ys, rs, neighbours)
        val boundary = BooleanArray(n)

        // Куски тела: связность по перекрытию дисков.
        val component = components(n, neighbours)
        val traced = BooleanArray(n)
        for (start in 0 until n) {
            val c = component[start]
            if (traced[c]) continue
            traced[c] = true
            if (!traceOuter(c, component, xs, ys, rs, neighbours, arcs, boundary)) {
                // Не сошлось — весь кусок по старой надёжной логике: открыт — значит граничный.
                for (i in 0 until n) if (component[i] == c && arcs.cellFrom[i] < arcs.cellTo[i]) boundary[i] = true
            }
        }
        return BooleanArray(n) { !boundary[it] }
    }

    /** Открытые дуги всех клеток подряд; дуги клетки i — индексы cellFrom[i] until cellTo[i]. */
    private class Arcs(n: Int) {
        val cellFrom = IntArray(n)
        val cellTo = IntArray(n)
        var cell = IntArray(64)
        var start = DoubleArray(64)
        var end = DoubleArray(64)
        var count = 0

        fun add(i: Int, s: Double, e: Double) {
            if (count == cell.size) {
                cell = cell.copyOf(count * 2); start = start.copyOf(count * 2); end = end.copyOf(count * 2)
            }
            cell[count] = i; start[count] = s; end[count] = e
            count++
        }
    }

    /** Для каждой клетки — клетки, чьи диски перекрывают её диск. */
    private fun findOverlaps(xs: FloatArray, ys: FloatArray, rs: FloatArray): Array<IntArrayList> {
        val n = xs.size
        val result = Array(n) { IntArrayList() }
        // Попарно: при запекании это ~26 млн пар у тела в 5120 клеток, десятки мс.
        for (i in 0 until n) for (j in i + 1 until n) {
            val dx = (xs[j] - xs[i]).toDouble()
            val dy = (ys[j] - ys[i]).toDouble()
            val sum = (rs[i] + rs[j]).toDouble()
            if (dx * dx + dy * dy >= sum * sum) continue
            result[i].add(j)
            result[j].add(i)
        }
        return result
    }

    private fun collectArcs(
        xs: FloatArray, ys: FloatArray, rs: FloatArray, neighbours: Array<IntArrayList>
    ): Arcs {
        val n = xs.size
        val arcs = Arcs(n)
        val cover = CircleCover(16)
        for (i in 0 until n) {
            cover.clear()
            val ri = rs[i].toDouble()
            val list = neighbours[i]
            for (k in 0 until list.size) {
                val j = list.getInt(k)
                val rj = rs[j].toDouble()
                val dx = (xs[j] - xs[i]).toDouble()
                val dy = (ys[j] - ys[i]).toDouble()
                val d = sqrt(dx * dx + dy * dy)
                // Клетка целиком внутри соседа. Совпавшие диски закрывают друг друга, и
                // открытой осталась бы ни одна — поэтому из двух одинаковых открыта та,
                // что с меньшим номером.
                if (d + ri < rj || (d + ri == rj && (ri != rj || j < i))) { cover.blockAll(); break }
                // Сосед целиком внутри клетки — её окружность не трогает.
                if (d + rj <= ri) continue
                val cos = ((ri * ri + d * d - rj * rj) / (2.0 * ri * d)).coerceIn(-1.0, 1.0)
                cover.block(StrictMath.atan2(dy, dx), StrictMath.acos(cos), j)
            }
            cover.computeFree(MIN_ARC)
            arcs.cellFrom[i] = arcs.count
            for (k in 0 until cover.freeCount) arcs.add(i, cover.freeStart[k], cover.freeEnd[k])
            arcs.cellTo[i] = arcs.count
        }
        return arcs
    }

    /** Номер куска (наименьший индекс клетки в нём) для каждой клетки. */
    private fun components(n: Int, neighbours: Array<IntArrayList>): IntArray {
        val component = IntArray(n) { -1 }
        val stack = IntArrayList()
        for (s in 0 until n) {
            if (component[s] != -1) continue
            component[s] = s
            stack.add(s)
            while (!stack.isEmpty) {
                val i = stack.removeInt(stack.size - 1)
                val list = neighbours[i]
                for (k in 0 until list.size) {
                    val j = list.getInt(k)
                    if (component[j] != -1) continue
                    component[j] = s
                    stack.add(j)
                }
            }
        }
        return component
    }

    /**
     * Обходит внешний контур куска [c] и отмечает его клетки в [boundary]. False — обход
     * не вернулся в начало.
     */
    private fun traceOuter(
        c: Int, component: IntArray,
        xs: FloatArray, ys: FloatArray, rs: FloatArray,
        neighbours: Array<IntArrayList>, arcs: Arcs, boundary: BooleanArray
    ): Boolean {
        // Самая левая точка куска: на ней диск ничей другой не лежит, и она на внешней линии.
        var left = -1
        for (i in component.indices) {
            if (component[i] != c || arcs.cellFrom[i] == arcs.cellTo[i]) continue
            if (left == -1 || xs[i] - rs[i] < xs[left] - rs[left]) left = i
        }
        if (left == -1) return false
        val first = arcAt(arcs, left, PI)
        if (first == -1) return false

        var arc = first
        var steps = 0
        do {
            boundary[arcs.cell[arc]] = true
            arc = nextArc(arc, xs, ys, rs, neighbours, arcs)
            if (arc == -1 || ++steps > arcs.count) return false
        } while (arc != first)
        return true
    }

    /** Дуга клетки [i], на которой лежит угол [angle], или -1. */
    private fun arcAt(arcs: Arcs, i: Int, angle: Double): Int {
        for (k in arcs.cellFrom[i] until arcs.cellTo[i]) {
            var a = angle - arcs.start[k]
            a -= CircleCover.TWO_PI * Math.floor(a / CircleCover.TWO_PI)
            if (a <= arcs.end[k] - arcs.start[k]) return k
        }
        return -1
    }

    /**
     * Следующая дуга контура: та, что начинается в конце дуги [arc]. Ищется по ближайшему
     * началу среди дуг самой клетки и её соседей, а не по «чей диск оборвал дугу»: в точке,
     * где сходятся три окружности, ошибка округления решает, чей диск формально первый, а
     * ближайшее начало от этого не зависит.
     */
    private fun nextArc(
        arc: Int, xs: FloatArray, ys: FloatArray, rs: FloatArray,
        neighbours: Array<IntArrayList>, arcs: Arcs
    ): Int {
        val i = arcs.cell[arc]
        val endAngle = arcs.end[arc]
        // Окружность целиком открыта — контур из одной клетки.
        if (endAngle - arcs.start[arc] >= CircleCover.TWO_PI) return arc
        val qx = xs[i] + rs[i] * StrictMath.cos(endAngle)
        val qy = ys[i] + rs[i] * StrictMath.sin(endAngle)

        var best = -1
        var bestDistance2 = Double.MAX_VALUE
        val list = neighbours[i]
        for (k in -1 until list.size) {
            val j = if (k == -1) i else list.getInt(k)
            for (a in arcs.cellFrom[j] until arcs.cellTo[j]) {
                if (a == arc) continue
                val sx = xs[j] + rs[j] * StrictMath.cos(arcs.start[a])
                val sy = ys[j] + rs[j] * StrictMath.sin(arcs.start[a])
                val dx = sx - qx
                val dy = sy - qy
                val distance2 = dx * dx + dy * dy
                if (distance2 < bestDistance2) {
                    bestDistance2 = distance2
                    best = a
                }
            }
        }
        // Дальше заметной доли радиуса — это не продолжение, а сбой геометрии.
        val tolerance = 1e-3 * rs[i]
        return if (bestDistance2 <= tolerance * tolerance) best else -1
    }
}
