package io.github.some_example_name.physics.lab.stands

import java.util.Random
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * СЕМЕЙСТВО «ТОЛПА»: много разных тел в одном мире, и всё сразу. Тела всех видов — мягкие, с
 * костями, с мышцами — стоят в рамке мира со стенкой (как в игре), летят в случайные стороны,
 * сталкиваются, мышцы у них работают, вперемешку летают свободные частицы и растут новые тела.
 *
 * Отдельные сцены каталога проверяют один случай в чистом виде; толпа — их сочетания, которых
 * руками не перечислить: удар в тело, которое в этот миг сжато мышцей, прижато к стенке и
 * задето третьим. Состав и расстановка — по зерну случайности, поэтому сцена повторяется
 * точь-в-точь, а новых сочетаний можно получить сколько угодно, поменяв зерно.
 */

private const val PULL = 0.5f
private const val REST = 1f
private const val LOOSE = 1.5f

/** Из чего собрана толпа. */
private class Mix(val id: String, val name: String, val soft: Boolean = true, val bones: Boolean = false, val muscles: Boolean = false,
                  val balls: Int = 0, val growing: Int = 0)

private val MIXES = listOf(
    Mix("soft", "мягкие тела"),
    Mix("bones", "мягкие и костные тела", bones = true),
    Mix("muscles", "тела с работающими мышцами", soft = false, muscles = true),
    Mix("balls", "мягкие тела и свободные частицы", balls = 60),
    Mix("growth", "взрослые тела и растущие", growing = 4),
    Mix("all", "всё сразу: мягкие, костные, мышцы, частицы, рост", bones = true, muscles = true, balls = 40, growing = 3),
)

/** Насколько быстро летят тела толпы. */
private class Pace2(val id: String, val name: String, val speed: Double)

private val PACES = listOf(Pace2("slow", "медленно, до 0.3 связи за тик", 0.3), Pace2("mid", "до 1 связи за тик", 1.0), Pace2("fast", "до 3 связей за тик", 3.0))

private fun softPool() = listOf(Shapes.square(7), Shapes.disc(3), Shapes.rect(10, 3), Shapes.star(5, 6, 3), Shapes.blob(2, 4), Shapes.ring(5, 3),
    Shapes.triangle(6), Shapes.ellipse(6, 3), Shapes.cross(4, 3), Shapes.crescent(5), Shapes.ribbon(9), Shapes.lShape(7, 3))
private fun bonePool() = listOf(Shapes.square(8).boneCore(), Shapes.disc(5).boneShell(), Shapes.rect(12, 5).boneSpine(), Shapes.square(6).boneAll(),
    Shapes.rect(14, 5).bonePlates(3), Shapes.disc(2).boneAll())
private fun musclePool() = listOf(Shapes.rect(12, 3).muscleAll(), Shapes.rect(16, 4).muscleTopBottom(), Shapes.disc(4).muscleAll(), Shapes.rect(14, 4).muscleSegments(4),
    Shapes.ring(5, 3).muscleAll(), Shapes.rect(18, 5).boneSpine().muscleTopBottom(), Shapes.ellipse(6, 3).muscleLeftRight())

/**
 * Собрать толпу: [count] тел из набора [mix] в рамке мира, каждое со своей случайной скоростью до
 * [speed] и своим углом; мышцы работают [active] тиков. Возвращает поставленные тела.
 */
private fun StandWorld.crowd(mix: Mix, count: Int, speed: Double, seed: Long, active: Int): List<StandBody> {
    val rnd = Random(seed)
    val pool = ArrayList<ShapeSpec>()
    if (mix.soft) pool += softPool()
    if (mix.bones) pool += bonePool()
    if (mix.muscles) pool += musclePool()
    val picked = (0 until count).map { pool[rnd.nextInt(pool.size)] }
    val grow = (0 until mix.growing).map { listOf(Shapes.disc(4), Shapes.rect(10, 4), Shapes.square(7).boneCore())[it % 3] }
    // Ячейка расстановки — под самое большое тело в его нынешней вариации плюс просвет: на старте никто никого не касается.
    val cell = 2 * (picked + grow).maxOf { it.reach } + 1.2
    val places = count + mix.growing + ceil(mix.balls / 9.0).toInt()
    val cols = ceil(sqrt(places.toDouble() * 1.4)).toInt()
    val rows = ceil(places.toDouble() / cols).toInt()
    val width = ceil(cols * cell + 4.0).toInt().let { it + it % 2 }
    val height = ceil(rows * cell + 4.0).toInt().let { it + it % 2 }
    arena(width, height)
    val order = (0 until cols * rows).shuffled(rnd)
    fun spot(k: Int) = doubleArrayOf(2.0 + (order[k] % cols + 0.5) * cell, 2.0 + (order[k] / cols + 0.5) * cell)

    val bodies = ArrayList<StandBody>()
    for (k in 0 until count) {
        val s = spot(k)
        val b = body(picked[k], s[0], s[1], 2 * Math.PI * rnd.nextDouble())
        val a = 2 * Math.PI * rnd.nextDouble()
        val v = speed * (0.4 + 0.6 * rnd.nextDouble())
        kick(b, v * cos(a), v * sin(a), spin = (rnd.nextDouble() - 0.5) * 0.02)
        bodies.add(b)
    }
    for (k in 0 until mix.growing) {
        val s = spot(count + k)
        val shape = grow[k]
        growing(shape, s[0], s[1], seed = shape.nearest(0.0, 0.0), start = 10 + 7 * k, period = SLOW.period, batch = SLOW.batch)
    }
    // Частицы — горстями по девять в свободных ячейках, каждая со своей скоростью.
    var left = mix.balls
    var at = count + mix.growing
    while (left > 0) {
        val s = spot(at++)
        for (i in 0 until minOf(9, left)) {
            val a = 2 * Math.PI * rnd.nextDouble()
            ball(s[0] + (i % 3 - 1) * 0.9, s[1] + (i / 3 - 1) * 0.9, speed * cos(a), speed * sin(a))
        }
        left -= 9
    }
    // Мышцы: у каждого тела свой ритм, чтобы сокращения приходились на разные моменты ударов.
    val movers = bodies.filter { it.shape.muscleGroups > 0 }
    if (movers.isNotEmpty()) {
        val phase = movers.map { rnd.nextInt(60) }
        val period = movers.map { 30 + rnd.nextInt(50) }
        during(10, active) { t ->
            for ((i, b) in movers.withIndex()) {
                val step = (t + phase[i]) / period[i]
                for (g in 0 until b.shape.muscleGroups) muscle(b, g, if ((step + g) % 2 == 0) PULL else LOOSE)
            }
        }
        at(active + 1) { for (b in movers) muscles(b, REST) }
    }
    return bodies
}

internal fun crowdFamily(): List<Scenario> {
    val family = Family("13. Толпа")
    for (mix in MIXES) for (count in listOf(10, 24, 48)) for (pace in PACES) {
        // Большая быстрая толпа со всеми добавками — самая тяжёлая сцена каталога; хватает одной скорости.
        if (count == 48 && pace.id == "mid") continue
        val driven = mix.muscles || mix.growing > 0
        val active = 500
        val base = Limits.hit(2 * pace.speed)
        // В толпе покой наступает не скоро: от сцены нужно, чтобы движение затухало, а сон — только у тихой толпы без мышц.
        val limits = base.copy(name = base.name + if (driven) "; энергию вносят мышцы и рост" else "", energy = if (driven) Energy.DRIVEN else Energy.CLOSED,
            sleep = base.sleep && !driven && pace.speed <= 0.3, shape = base.shape && pace.speed <= 0.3 && !driven)
        val ticks = (if (driven) active else 0) + (if (pace.speed <= 0.3) settle(0.6) + 200 else 900)
        for (seed in 1..2) {
            if (seed == 2 && count != 24) continue
            family.add("crowd/${mix.id}/n$count/${pace.id}-s$seed", "толпа из $count тел: ${mix.name}; ${pace.name}; расклад №$seed",
                "В рамке мира со стенкой $count тел (${mix.name}) стоят вперемешку и разлетаются в случайные стороны: ${pace.name}." +
                    (if (mix.muscles) " Мышцы у тел сокращаются и расслабляются, у каждого в своём ритме, $active тиков." else "") +
                    (if (mix.balls > 0) " Между телами летают ${mix.balls} свободных частиц." else "") +
                    (if (mix.growing > 0) " В свободных местах растут ещё ${mix.growing} тела." else ""),
                "Тела сталкиваются друг с другом и со стенкой, как придётся. Что бы ни сошлось, клетки не оказываются в чужой ткани и не проходят сквозь неё, " +
                    "никто не выходит за рамку, энергия не берётся из ничего, никто не дрожит. " +
                    (if (base.tornShare <= 0.0) "Ничего не рвётся и не гибнет. " else "Порваться может до ${Math.round(base.tornShare * 100)}% связей. ") +
                    "Движение затухает" + (if (limits.sleep) ", и вся толпа засыпает." else "."), ticks, limits) {
                crowd(mix, count, pace.speed, seed.toLong() * 7919 + count, active)
                val v = view!!
                expectAllInside(v[0], v[1], v[2], v[3], ticks)
            }
        }
    }

    // --- толпа на потолке скорости: часть тел летит так быстро, как только разрешает решатель ---
    for (mix in listOf(MIXES[0], MIXES[1], MIXES[5])) for (count in listOf(12, 30)) {
        family.add("crowd/${mix.id}/n$count/max", "толпа из $count тел: ${mix.name}; каждое третье — на потолке скорости",
            "В рамке мира $count тел (${mix.name}); каждое третье летит со скоростью ${SPEED_CAP.toInt()} связей за тик, остальные — до одной связи за тик.",
            "Удары на пределе: ткань вправе порваться и потерять клетки. Но клетки не оказываются в чужой ткани и не проходят сквозь неё, никто не выходит за рамку, " +
                "скорость ни у кого не превышает потолка, энергия не берётся из ничего, движение обломков затухает.",
            900, Limits.CRASH.copy(energy = if (mix.muscles || mix.growing > 0) Energy.DRIVEN else Energy.CLOSED)) {
            val bodies = crowd(mix, count, 1.0, 31L * count + 5, 400)
            val rnd = Random(count.toLong())
            for ((i, b) in bodies.withIndex()) if (i % 3 == 0) {
                val a = 2 * Math.PI * rnd.nextDouble()
                kick(b, SPEED_CAP * cos(a), SPEED_CAP * sin(a))
            }
        }
    }

    // --- давка: толпу сгоняют в кучу ---
    for (mix in listOf(MIXES[0], MIXES[1], MIXES[2], MIXES[5])) for (speed in listOf(0.4, 1.5)) {
        val driven = mix.muscles || mix.growing > 0
        val base = Limits.hit(2 * speed)
        family.add("crowd/${mix.id}/crush/v$speed", "давка: тридцать тел сходятся в одну точку, ${mix.name}, скорость $speed",
            "Тридцать тел (${mix.name}) стоят в рамке мира и все летят к её середине со скоростью $speed связи за тик.",
            "В середине куча: тела сминаются со всех сторон разом. Клетки не оказываются в чужой ткани, " +
                (if (base.tornShare <= 0.0) "ничего не рвётся и не гибнет, " else "порваться может до ${Math.round(base.tornShare * 100)}% связей, ") +
                "энергия не берётся из ничего, никто не дрожит; куча расползается и успокаивается.",
            (if (driven) 400 else 0) + settle(speed) + 400,
            base.copy(name = base.name + "; давка", energy = if (driven) Energy.DRIVEN else Energy.CLOSED, sleep = false, shape = false)) {
            val bodies = crowd(mix, 30, 0.0, 977L, 400)
            val v = view!!
            val cx = (v[0] + v[2]) / 2; val cy = (v[1] + v[3]) / 2
            for (b in bodies) {
                val c = centerOf(b)
                val len = Math.hypot(cx - c[0], cy - c[1]).coerceAtLeast(1e-6)
                kick(b, speed * (cx - c[0]) / len, speed * (cy - c[1]) / len)
            }
        }
    }

    // --- толпа в рельефе: пещера с перегородками ---
    for (mix in listOf(MIXES[0], MIXES[2], MIXES[5])) for (pace in listOf(PACES[0], PACES[1])) {
        val driven = mix.muscles || mix.growing > 0
        val base = Limits.hit(2 * pace.speed)
        family.add("crowd/${mix.id}/cave/${pace.id}", "толпа в пещере с перегородками: ${mix.name}; ${pace.name}",
            "Замкнутая полость в рельефе со столбами внутри; в ней двадцать тел (${mix.name}) разлетаются в случайные стороны: ${pace.name}.",
            "Тела бьются о столбы и стенки и друг о друга. Ничто не входит в рельеф и не выходит из полости, клетки не оказываются в чужой ткани, " +
                (if (base.tornShare <= 0.0) "ничего не рвётся и не гибнет; " else "порваться может до ${Math.round(base.tornShare * 100)}% связей; ") + "движение затухает.",
            (if (driven) 400 else 0) + 900,
            base.copy(name = base.name + "; пещера", energy = if (driven) Energy.DRIVEN else Energy.CLOSED, sleep = false, shape = false)) {
            val rnd = Random(4242)
            val pool = ArrayList<ShapeSpec>()
            if (mix.soft) pool += softPool()
            if (mix.bones) pool += bonePool()
            if (mix.muscles) pool += musclePool()
            val picked = (0 until 20).map { pool[rnd.nextInt(pool.size)] }
            val cell = 2 * picked.maxOf { it.reach } + 1.5
            // Пять на четыре ячейки под тела; столбы рельефа — в узлах между ячейками, три на три клетки карты.
            val cols = ceil(5 * cell / TERRAIN_STEP).toInt() + 2; val rows = ceil(4 * cell / TERRAIN_STEP).toInt() + 2
            val per = cell / TERRAIN_STEP
            val c = cavity(cols, rows, jitter = true) { ix, iy ->
                val gx = (ix - 1) / per; val gy = (iy - 1) / per
                val nearX = Math.abs(gx - Math.round(gx)) * per < 1.6 && Math.round(gx).toInt() in 1..4
                val nearY = Math.abs(gy - Math.round(gy)) * per < 1.6 && Math.round(gy).toInt() in 1..3
                !(nearX && nearY)
            }
            val movers = ArrayList<StandBody>()
            for (k in 0 until 20) {
                val x = c.minX + TERRAIN_STEP + (k % 5 + 0.5) * cell; val y = c.minY + TERRAIN_STEP + (k / 5 + 0.5) * cell
                val b = body(picked[k], x, y, 2 * Math.PI * rnd.nextDouble())
                val a = 2 * Math.PI * rnd.nextDouble()
                kick(b, pace.speed * cos(a), pace.speed * sin(a))
                if (b.shape.muscleGroups > 0) movers.add(b)
            }
            if (movers.isNotEmpty()) {
                during(10, 400) { t -> for ((i, b) in movers.withIndex()) for (g in 0 until b.shape.muscleGroups) muscle(b, g, if (((t + 17 * i) / 40 + g) % 2 == 0) PULL else LOOSE) }
                at(401) { for (b in movers) muscles(b, REST) }
            }
            expectAllInside(c.minX - CAVITY_SLACK, c.minY - CAVITY_SLACK, c.maxX + CAVITY_SLACK, c.maxY + CAVITY_SLACK, (if (driven) 400 else 0) + 900, "частиц вне полости")
        }
    }
    return family.list
}
