package io.github.some_example_name.physics.lab.stands

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * РАССТАНОВКА ПО НАСТОЯЩЕМУ КОНТУРУ.
 *
 * Сцены ставят тела «в трёх связях друг от друга» или «почти вплотную». Считать это от
 * полуширины фигуры нельзя: у подковы, полумесяца, гребёнки ближайшие клетки двух тел стоят
 * вовсе не на краях рамки, и на «лёгком касании» тела останавливались, не долетев (15.11:
 * четыре такие сцены показали нулевой толчок). Поэтому зазор меряется по самим клеткам:
 * сколько телу лететь по прямой до первого касания.
 *
 * Касание — по радиусам касания клеток (ShapeSpec.contact): у тела с длинными связями или с
 * мелкими клетками они другие, и «вплотную» для него — другое расстояние между центрами.
 *
 * Цена: клетки контура одной фигуры × клетки контура другой, один раз при сборке сцены
 * (тело в 2000 клеток — около 150 клеток контура, то есть десятки тысяч пар).
 */

/**
 * Расстояние между центрами клеток разных тел на ровной решётке, с которого они касаются:
 * радиус касания клетки — половина связи. Решатель добавляет к нему 5% на сшивание мембраны
 * (CT_SEAL), то есть толкать начинает с 0.63.
 */
const val CONTACT_DISTANCE = GAME_LINK

/**
 * Расстояние между центрами пары клеток с суммой радиусов касания [sum], отвечающее расстоянию
 * [dist] у пары клеток ровной решётки: та же доля от суммы. У самой ровной решётки — ровно
 * [dist], без пересчёта: её сцены обязаны собираться до бита так же, как до появления вариаций.
 */
internal fun pairDistance(sum: Double, dist: Double): Double =
    if (abs(sum - CONTACT_DISTANCE) < 1e-9) dist else dist * sum / CONTACT_DISTANCE

/** Клетки контура фигуры, повёрнутые на [angle] и сдвинутые в ([cx], [cy]): x, y и радиус касания подряд. */
internal fun outline(s: ShapeSpec, cx: Double, cy: Double, angle: Double): DoubleArray {
    val cs = cos(angle); val sn = sin(angle)
    val x = s.x; val y = s.y; val c = s.contact
    val out = DoubleArray(3 * s.count)
    var n = 0
    for (k in 0 until s.count) {
        if (c[k] <= 0.0) continue
        out[n++] = cx + x[k] * cs - y[k] * sn
        out[n++] = cy + x[k] * sn + y[k] * cs
        out[n++] = c[k]
    }
    return out.copyOf(n)
}

/**
 * Сколько единиц игры фигуре [a], стоящей в ([ax], [ay]), лететь вдоль ([dx], [dy]) до того,
 * как какая-нибудь её клетка окажется на расстоянии касания от клетки фигуры [b], стоящей в
 * ([bx], [by]). [dist] — это расстояние для пары клеток ровной решётки (0.6 — касание, 0.8 —
 * рядом, 0.5 — с перекрытием); у других пар оно в той же доле от суммы их радиусов касания.
 * Бесконечность — если по этой прямой фигуры не встречаются.
 */
fun travelToContact(a: ShapeSpec, ax: Double, ay: Double, angleA: Double,
                    b: ShapeSpec, bx: Double, by: Double, angleB: Double,
                    dx: Double, dy: Double, dist: Double = CONTACT_DISTANCE): Double {
    return travel(outline(a, ax, ay, angleA), outline(b, bx, by, angleB), dx, dy, dist)
}

/** То же для двух наборов клеток (x, y, радиус касания подряд): сколько первому лететь вдоль ([dx], [dy]) до касания со вторым. */
internal fun travel(pa: DoubleArray, pb: DoubleArray, dx: Double, dy: Double, dist: Double): Double {
    val len = sqrt(dx * dx + dy * dy)
    val ux = dx / len; val uy = dy / len
    var best = Double.POSITIVE_INFINITY
    var i = 0
    while (i < pa.size) {
        var j = 0
        while (j < pb.size) {
            val rx = pb[j] - pa[i]; val ry = pb[j + 1] - pa[i + 1]
            val along = rx * ux + ry * uy
            val across = rx * uy - ry * ux
            val reach = pairDistance(pa[i + 2] + pb[j + 2], dist)
            // Только то, что впереди по ходу: к клетке за спиной (дальняя стенка рамки мира) тело не летит.
            if (along > 0.0 && across < reach && across > -reach) {
                val t = along - sqrt(reach * reach - across * across)
                if (t < best) best = t
            }
            j += 3
        }
        i += 3
    }
    return best
}

/**
 * На сколько центр фигуры [b] должен стоять правее центра [a], чтобы ближайшие клетки двух тел
 * стояли на расстоянии [dist] (в счёте ровной решётки: 0.6 — касание). [offset] — сдвиг
 * первого тела по Y.
 */
fun separation(a: ShapeSpec, b: ShapeSpec, dist: Double, offset: Double = 0.0, angleA: Double = 0.0, angleB: Double = 0.0): Double {
    val far = a.reach + b.reach + 4.0
    val travel = travelToContact(a, 0.0, offset, angleA, b, far, 0.0, angleB, 1.0, 0.0, dist)
    // Тела разнесены по высоте так, что по прямой не встречаются: ставятся на расстоянии описанных кругов.
    return if (travel.isInfinite()) a.reach + b.reach else far - travel
}

/** Неподвижные частицы сцены набором клеток: x, y и радиус касания подряд. */
internal fun StandWorld.rocksOutline(): DoubleArray {
    val rocks = DoubleArray(3 * fixed.size)
    val radius = world.state.radius
    for (k in fixed.indices) {
        val p = world.freeParticle(fixed[k])
        rocks[3 * k] = gameX(p); rocks[3 * k + 1] = gameY(p)
        rocks[3 * k + 2] = radius[p] / PHYSICS_PER_GAME * ShapeSpec.LONE_CONTACT_SCALE
    }
    return rocks
}

/**
 * Сколько единиц игры фигуре [s], стоящей в ([x], [y]), лететь вдоль ([dx], [dy]) до первого
 * касания с уже поставленными неподвижными частицами рельефа и стенки. Бесконечность — если
 * по этой прямой она их не встречает.
 */
fun StandWorld.travelToTerrain(s: ShapeSpec, x: Double, y: Double, angle: Double, dx: Double, dy: Double): Double =
    travel(outline(s, x, y, angle), rocksOutline(), dx, dy, CONTACT_DISTANCE)

/**
 * Наименьший зазор между кругами касания двух тел, уже стоящих на сцене, в единицах игры:
 * отрицательный — тела поставлены с перекрытием. Нужен проверке каталога «тела на старте не
 * касаются» и сценам, которые расставляют много тел по готовым координатам.
 */
fun StandWorld.clearance(a: StandBody, b: StandBody): Double {
    val st = world.state
    val k = PHYSICS_PER_GAME
    val ca = a.shape.contact; val cb = b.shape.contact
    var best = Double.MAX_VALUE
    for (i in 0 until a.count) {
        if (ca[i] <= 0.0) continue
        val pi = a.particle(i)
        val x = st.px[pi] / k; val y = st.py[pi] / k
        for (j in 0 until b.count) {
            if (cb[j] <= 0.0) continue
            val pj = b.particle(j)
            val dx = st.px[pj] / k - x; val dy = st.py[pj] / k - y
            val gap = sqrt(dx * dx + dy * dy) - (ca[i] + cb[j])
            if (gap < best) best = gap
        }
    }
    return best
}
