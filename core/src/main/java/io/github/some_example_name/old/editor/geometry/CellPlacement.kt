package io.github.some_example_name.old.editor.geometry

import io.github.some_example_name.old.systems.physics.CollisionManager.Companion.PARTICLE_MAX_RADIUS
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Где редактор ставит новую клетку при делении и есть ли вокруг клетки место вообще.
 *
 * РАССТОЯНИЯ — В ДОЛЯХ СУММЫ РАДИУСОВ
 * ----------------------------------
 * Раньше расстояния были числами: от родителя 0.6..0.9, от остальных не ближе 0.6. Они
 * подобраны под клетки радиусом 0.5, у которых сумма радиусов ровно 1, и для мелких клеток
 * неверны дважды: дочь радиуса 0.2 ставилась так же далеко, как крупная (и не касалась
 * родителя), а щель, где мелкая клетка свободно помещается, считалась занятой — редактор
 * говорил «места нет», когда оно было.
 *
 * Теперь те же числа — доли суммы радиусов пары: от родителя [CENTER_MIN]..[CENTER_MAX]
 * от (rp + rc), от любой другой клетки не ближе [KEEP] от (rc + rj). При всех радиусах 0.5
 * это ровно прежние 0.6, 0.9 и 0.6, так что геномы из клеток по умолчанию растут как раньше.
 *
 * КАК ИЩЕТСЯ ТОЧКА
 * ----------------
 * Прежний поиск перебирал 101 радиус × 360 углов и для каждой точки — всех соседей:
 * ~36 тысяч точек на клик. Радиусов теперь 31 (от [CHILD_RADIUS_MIN] до
 * [CHILD_RADIUS_MAX]), и место ищется под каждый, так что перебор стоил бы в 31 раз больше.
 * Вместо него на каждой окружности поиска считается, какую дугу закрывает каждый сосед
 * ([CircleCover]), а на свободных дугах ближайшая к цели точка находится сразу: это угол на
 * цель, если он свободен, иначе ближний конец свободной дуги. Ответ точный для данной
 * окружности, а не до градуса.
 *
 * Цель — как и раньше, центр редактора ([targetX], [targetY]): тело растёт к середине.
 */
object CellPlacement {

    /** Ближе всего к родителю — в долях суммы радиусов родителя и дочери. */
    const val CENTER_MIN = 0.6

    /** Дальше всего от родителя — в долях суммы радиусов. */
    const val CENTER_MAX = 0.9

    /** Не ближе к любой другой клетке — в долях суммы радиусов дочери и этой клетки. */
    const val KEEP = 0.6

    /** Радиусы дочери, которые предлагает диалог деления: 0.20..0.50 через 0.01. */
    const val CHILD_RADIUS_MIN = 0.2f
    const val CHILD_RADIUS_MAX = PARTICLE_MAX_RADIUS
    const val CHILD_RADIUS_STEP = 0.01f
    val CHILD_RADIUS_COUNT = ((CHILD_RADIUS_MAX - CHILD_RADIUS_MIN) / CHILD_RADIUS_STEP).roundToInt() + 1

    /** Радиус дочери номер [step]; округлён до сотых, как значение слайдера. */
    fun childRadius(step: Int): Float = (CHILD_RADIUS_MIN * 100f + step).roundToInt() / 100f

    /** Номер ближайшего допустимого радиуса дочери. */
    fun childRadiusStep(radius: Float): Int =
        ((radius - CHILD_RADIUS_MIN) / CHILD_RADIUS_STEP).roundToInt().coerceIn(0, CHILD_RADIUS_COUNT - 1)

    /**
     * Сколько окружностей поиска между ближней и дальней. Прежний поиск брал 101; дуги
     * меняются с радиусом плавно, а лучшая точка всё равно уточняется на окружности через
     * цель (см. [findPosition]).
     */
    private const val RING_STEPS = 31

    /**
     * Свободная дуга уже этой (полградуса) — не место: на её концах точка стоит вплотную к
     * границе запрета, и ошибка округления решала бы, годится ли она. Концы свободных дуг
     * поэтому и отодвигаются внутрь на [EDGE_MARGIN].
     */
    private const val MIN_GAP = PI / 360.0
    private const val EDGE_MARGIN = MIN_GAP / 2.0

    /**
     * Как далеко от родителя может стоять клетка, которая помешает дочери: дальняя
     * окружность плюс запрет вокруг соседа при самых крупных радиусах. Соседей дальше
     * собирать незачем.
     */
    fun neighbourReach(parentRadius: Float, maxChildRadius: Float = CHILD_RADIUS_MAX): Float =
        (CENTER_MAX * (parentRadius + maxChildRadius) + KEEP * (maxChildRadius + PARTICLE_MAX_RADIUS)).toFloat()

    /**
     * Лучшее место для дочери радиуса [childRadius] у родителя ([parentX], [parentY],
     * [parentRadius]) среди соседей ([nx], [ny], [nr], первые [count]). Null — места нет.
     */
    fun findPosition(
        parentX: Float, parentY: Float, parentRadius: Float,
        childRadius: Float,
        nx: FloatArray, ny: FloatArray, nr: FloatArray, count: Int,
        targetX: Float, targetY: Float
    ): Pair<Float, Float>? {
        val cover = CircleCover(count)
        val px = parentX.toDouble()
        val py = parentY.toDouble()
        val near = CENTER_MIN * (parentRadius + childRadius)
        val far = CENTER_MAX * (parentRadius + childRadius)

        val tdx = targetX - px
        val tdy = targetY - py
        val td = sqrt(tdx * tdx + tdy * tdy)
        // Родитель в самой цели (зигота в центре): все точки окружности равноудалены от
        // цели, и прежний поиск брал первую по углу от нуля — так и здесь.
        val targetAngle = if (td > 0.0) StrictMath.atan2(tdy, tdx) else 0.0

        var bestX = Double.NaN
        var bestY = Double.NaN
        var bestDistance2 = Double.MAX_VALUE

        // Окружности поиска от ближней к дальней и ещё одна — через саму цель (если она в
        // пределах): на ней лежит ближайшая к цели точка, если там свободно.
        for (step in 0..RING_STEPS) {
            val ring = if (step < RING_STEPS) {
                near + (far - near) * step / (RING_STEPS - 1)
            } else {
                td.coerceIn(near, far)
            }
            val angle = bestAngleOnRing(px, py, ring, childRadius, nx, ny, nr, count, targetAngle, cover)
            if (angle.isNaN()) continue
            val x = px + ring * StrictMath.cos(angle)
            val y = py + ring * StrictMath.sin(angle)
            val dx = x - targetX
            val dy = y - targetY
            val distance2 = dx * dx + dy * dy
            if (distance2 < bestDistance2) {
                bestDistance2 = distance2
                bestX = x
                bestY = y
            }
        }
        return if (bestX.isNaN()) null else bestX.toFloat() to bestY.toFloat()
    }

    /**
     * Угол на окружности [ring] вокруг родителя, ближайший к [targetAngle] среди свободных,
     * или NaN, если свободного нет.
     */
    private fun bestAngleOnRing(
        px: Double, py: Double, ring: Double, childRadius: Float,
        nx: FloatArray, ny: FloatArray, nr: FloatArray, count: Int,
        targetAngle: Double, cover: CircleCover
    ): Double {
        cover.clear()
        for (k in 0 until count) {
            val dx = nx[k] - px
            val dy = ny[k] - py
            val d = sqrt(dx * dx + dy * dy)
            val keep = KEEP * (childRadius + nr[k])
            // Вся окружность внутри запрета соседа.
            if (d + ring <= keep) { cover.blockAll(); break }
            // Окружность и запрет не пересекаются.
            if (d - ring >= keep || ring - d >= keep) continue
            // Полуширина закрытой дуги — из треугольника (родитель, сосед, точка
            // окружности на расстоянии keep от соседа), по теореме косинусов.
            val cos = ((ring * ring + d * d - keep * keep) / (2.0 * ring * d)).coerceIn(-1.0, 1.0)
            cover.block(StrictMath.atan2(dy, dx), StrictMath.acos(cos), k)
        }
        cover.computeFree(MIN_GAP)

        var best = Double.NaN
        var bestDelta = Double.MAX_VALUE
        for (k in 0 until cover.freeCount) {
            val whole = cover.freeEnd[k] - cover.freeStart[k] >= CircleCover.TWO_PI
            val start = if (whole) cover.freeStart[k] else cover.freeStart[k] + EDGE_MARGIN
            val end = if (whole) cover.freeEnd[k] else cover.freeEnd[k] - EDGE_MARGIN
            // Цель в свободной дуге — лучше её направления ничего нет.
            if (inArc(targetAngle, start, end)) return targetAngle
            val toStart = angleDistance(targetAngle, start)
            if (toStart < bestDelta) { bestDelta = toStart; best = start }
            val toEnd = angleDistance(targetAngle, end)
            if (toEnd < bestDelta) { bestDelta = toEnd; best = end }
        }
        return best
    }

    /** Лежит ли угол [a] на дуге от [start] до [end] против часовой (end может быть > 2π). */
    private fun inArc(a: Double, start: Double, end: Double): Boolean {
        var x = a - start
        x -= CircleCover.TWO_PI * Math.floor(x / CircleCover.TWO_PI)
        return x <= end - start
    }

    /** Угловое расстояние между направлениями, 0..π. */
    private fun angleDistance(a: Double, b: Double): Double {
        var x = abs(a - b)
        x -= CircleCover.TWO_PI * Math.floor(x / CircleCover.TWO_PI)
        return if (x > PI) CircleCover.TWO_PI - x else x
    }
}
