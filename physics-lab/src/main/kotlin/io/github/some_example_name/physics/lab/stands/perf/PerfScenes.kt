package io.github.some_example_name.physics.lab.stands.perf

import io.github.some_example_name.physics.lab.stands.Family
import io.github.some_example_name.physics.lab.stands.GenomeBodies
import io.github.some_example_name.physics.lab.stands.Limits
import io.github.some_example_name.physics.lab.stands.SLOW
import io.github.some_example_name.physics.lab.stands.SPEED_CAP
import io.github.some_example_name.physics.lab.stands.Scenario
import io.github.some_example_name.physics.lab.stands.ShapeSpec
import io.github.some_example_name.physics.lab.stands.Shapes
import io.github.some_example_name.physics.lab.stands.StandBody
import io.github.some_example_name.physics.lab.stands.StandWorld
import io.github.some_example_name.physics.lab.stands.Energy
import io.github.some_example_name.physics.lab.stands.TERRAIN_STEP
import io.github.some_example_name.physics.lab.stands.Variation
import io.github.some_example_name.physics.lab.stands.boneAll
import io.github.some_example_name.physics.lab.stands.boneCore
import io.github.some_example_name.physics.lab.stands.boneShell
import io.github.some_example_name.physics.lab.stands.boneSpine
import io.github.some_example_name.physics.lab.stands.cavity
import io.github.some_example_name.physics.lab.stands.fill
import io.github.some_example_name.physics.lab.stands.linksPerTick
import io.github.some_example_name.physics.lab.stands.muscleAll
import io.github.some_example_name.physics.lab.stands.muscleSegments
import io.github.some_example_name.physics.lab.stands.muscleTopBottom
import java.util.Random
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * СЦЕНЫ ЗАМЕРА ПРОИЗВОДИТЕЛЬНОСТИ.
 *
 * Две группы. «Обычная игра» — то, из чего состоит мир игрока: малые и большие тела
 * сталкиваются, мышцы работают, никто не спит. По ней считается среднее время тика и
 * эффективность распараллеливания. «Тяжёлые края» — случаи, где у решателя дорожает одна
 * стадия: тела из одной мембраны (каждая клетка — на контуре), тысячи одиночных частиц
 * (вся работа — поиск касаний), набитая полость, рельеф, тела, разбитые в обломки (пересборка
 * топологии), рост сотен тел разом.
 *
 * Чтобы сцена оставалась одной и той же от первого тика до последнего, тела раз в сорок тиков
 * получают новый случайный толчок (иначе среда всё остановит за сотню тиков и замер пойдёт по
 * спящему миру), мышцы работают без остановки, сон выключен. Размер — множитель [scale]:
 * единица — около двадцати тысяч частиц на сцену.
 *
 * Эти же сцены стоят в каталоге стендов (семейство «Нагрузка») в четверть размера: там их
 * можно посмотреть глазами и там их судит монитор правил.
 */

private const val PULL = 0.5f
private const val LOOSE = 1.5f
private const val FOREVER = 1_000_000

class PerfScene(
    val id: String, val title: String, val about: String,
    /** Входит ли сцена в «обычную игру» — в среднее, по которому судят о скорости в целом. */
    val standard: Boolean,
    val build: StandWorld.(scale: Double) -> Unit,
)

/**
 * Поле тел в рамке мира: [shapes] — по фигуре на тело, расставлены решёткой с просветом [gap];
 * каждое летит со своей скоростью до [speed] и раз в [again] тиков получает новый толчок; мышцы
 * работают всё время, у каждого тела со своим сдвигом.
 */
private fun StandWorld.field(shapes: List<ShapeSpec>, gap: Double, speed: Double, seed: Long, again: Int = 40): List<StandBody> {
    val rnd = Random(seed)
    val cell = 2 * shapes.maxOf { it.reach } + gap
    val cols = ceil(sqrt(shapes.size * 1.15)).toInt().coerceAtLeast(1)
    val rows = ceil(shapes.size.toDouble() / cols).toInt().coerceAtLeast(1)
    val width = ceil(cols * cell + 4.0).toInt().let { it + it % 2 }
    val height = ceil(rows * cell + 4.0).toInt().let { it + it % 2 }
    arena(width, height)
    val bodies = ArrayList<StandBody>(shapes.size)
    for ((k, s) in shapes.withIndex()) bodies.add(body(s, 2.0 + (k % cols + 0.5) * cell, 2.0 + (k / cols + 0.5) * cell, 2 * Math.PI * rnd.nextDouble()))
    fun push() {
        for (b in bodies) {
            val a = 2 * Math.PI * rnd.nextDouble()
            val v = speed * (0.5 + 0.5 * rnd.nextDouble())
            kick(b, v * cos(a), v * sin(a))
        }
    }
    push()
    during(1, FOREVER) { t -> if (t % again == 0) push() }
    val movers = bodies.filter { it.shape.muscleGroups > 0 }
    if (movers.isNotEmpty()) {
        val phase = IntArray(movers.size) { rnd.nextInt(60) }
        during(1, FOREVER) { t ->
            if (t % 5 == 0) for ((i, b) in movers.withIndex()) {
                val step = (t + phase[i]) / 30
                for (g in 0 until b.shape.muscleGroups) muscle(b, g, if ((step + g) % 2 == 0) PULL else LOOSE)
            }
        }
    }
    return bodies
}

private fun count(base: Int, scale: Double): Int = maxOf(2, Math.round(base * scale).toInt())

/** Набор из [n] фигур по кругу из [pool]. */
private fun cycle(pool: List<ShapeSpec>, n: Int): List<ShapeSpec> = (0 until n).map { pool[it % pool.size] }

val PERF_SCENES: List<PerfScene> = listOf(
    // ---------------------------------------------------------------- обычная игра
    PerfScene("small", "толпа малых тел", "Малые тела по 30–60 клеток, половина — с работающими мышцами; тесно, до связи за тик.", true) { scale ->
        val pool = listOf(Shapes.disc(3), Shapes.square(7), Shapes.rect(12, 3).muscleAll(), Shapes.disc(4).muscleAll(), Shapes.star(5, 6, 3), Shapes.rect(10, 4).muscleTopBottom())
        field(cycle(pool, count(420, scale)), 0.8, 1.0, 11)
    },
    PerfScene("big", "большие тела", "Тела по 1800 клеток с мышцами по половинам; до полусвязи за тик.", true) { scale ->
        field(cycle(listOf(Shapes.disc(24).muscleTopBottom(), Shapes.rect(60, 30).muscleTopBottom()), count(12, scale)), 1.5, 0.5, 12)
    },
    PerfScene("mixed", "большие и малые вместе", "Несколько тел по 1800 клеток и толпа малых между ними; мышцы работают.", true) { scale ->
        val big = cycle(listOf(Shapes.disc(24).muscleTopBottom()), count(5, scale))
        val small = cycle(listOf(Shapes.disc(3), Shapes.rect(12, 3).muscleAll(), Shapes.square(7), Shapes.disc(4).muscleAll()), count(220, scale))
        // Малые — горстями вокруг: в общем поле ячейка была бы под большое тело, и мир вышел бы пустым.
        val all = ArrayList<ShapeSpec>()
        for (k in big.indices) { all.add(big[k]) }
        val bodies = field(all, 14.0, 0.5, 13)
        val rnd = Random(13)
        val v = view!!
        var placed = 0
        var tries = 0
        val spots = ArrayList<DoubleArray>()
        while (placed < small.size && tries++ < small.size * 60) {
            val s = small[placed]
            val x = v[0] + 2.0 + s.reach + rnd.nextDouble() * (v[2] - v[0] - 4.0 - 2 * s.reach)
            val y = v[1] + 2.0 + s.reach + rnd.nextDouble() * (v[3] - v[1] - 4.0 - 2 * s.reach)
            if (bodies.any { val c = centerOf(it); Math.hypot(c[0] - x, c[1] - y) < it.shape.reach + s.reach + 0.5 }) continue
            if (spots.any { Math.hypot(it[0] - x, it[1] - y) < it[2] + s.reach + 0.5 }) continue
            spots.add(doubleArrayOf(x, y, s.reach))
            val b = body(s, x, y, 2 * Math.PI * rnd.nextDouble())
            val a = 2 * Math.PI * rnd.nextDouble()
            kick(b, cos(a), sin(a))
            if (s.muscleGroups > 0) { val shift = rnd.nextInt(60); during(1, FOREVER) { t -> if (t % 5 == 0) muscles(b, if (((t + shift) / 30) % 2 == 0) PULL else LOOSE) } }
            placed++
        }
    },
    PerfScene("worms", "стая червей", "Черви 60×4 с бегущей волной мышц, вплотную друг к другу.", true) { scale ->
        field(cycle(listOf(Shapes.rect(60, 4).muscleSegments(8)), count(80, scale)), 0.6, 0.6, 14)
    },
    PerfScene("bones", "тела с костями и мышцами", "Тела с костными ядрами, оболочками и хребтами; у части работают мышцы.", true) { scale ->
        val pool = listOf(Shapes.square(10).boneCore(), Shapes.disc(6).boneShell(), Shapes.rect(18, 6).boneSpine().muscleTopBottom(), Shapes.square(8).boneAll(), Shapes.rect(18, 6).boneSpine())
        field(cycle(pool, count(190, scale)), 0.8, 1.0, 15)
    },
    PerfScene("genomes", "зоопарк настоящих геномов", "Тела геномов от 60 до 800 клеток вперемешку; мышцы работают.", true) { scale ->
        val picks = GenomeBodies.entries.filter { it.cells in 60..800 }
        val list = ArrayList<ShapeSpec>()
        var cells = 0
        var k = 0
        while (picks.isNotEmpty() && cells < 20000 * scale) { val e = picks[k++ % picks.size]; list.add(GenomeBodies.shape(e.slug)); cells += e.cells }
        if (list.isEmpty()) list.addAll(cycle(listOf(Shapes.disc(6)), count(150, scale)))
        field(list, 1.0, 0.8, 16)
    },
    // ---------------------------------------------------------------- тяжёлые края
    PerfScene("ribbons", "тела из одной мембраны", "Ленты 2×25: каждая клетка — на контуре, внутренних нет. Вся работа — касания.", false) { scale ->
        field(cycle(listOf(Shapes.ribbon(25), Shapes.rope(30), Shapes.ring(8, 6)), count(420, scale)), 0.6, 1.0, 21)
    },
    PerfScene("ragged", "длинный рваный контур", "Кораллы, звёзды и гребёнки: контур втрое длиннее, чем у круга той же площади.", false) { scale ->
        val pool = listOf(Shapes.coral(1, 5), Shapes.star(5, 8, 3), Shapes.comb(4, 2, 5, 3), Shapes.coral(3, 7), Shapes.gear(5, 6), Shapes.cross(6, 4))
        field(cycle(pool, count(160, scale)), 0.8, 1.0, 22)
    },
    PerfScene("gas", "газ одиночных частиц", "Одни свободные частицы в ящике рельефа, шаг 0.9, скорость 2 связи за тик.", false) { scale ->
        val side = ceil(sqrt(20000 * scale * 0.9 * 0.78) / TERRAIN_STEP).toInt()
        val c = cavity(side, side, jitter = false)
        fill(c, 0.9, 2.0)
        world.deepKillOn = false
        val rnd = Random(23)
        during(1, FOREVER) { t ->
            if (t % 40 == 0) for (id in balls) {
                val p = world.freeParticle(id)
                val a = 2 * Math.PI * rnd.nextDouble()
                setSceneVelocity(p, linksPerTick(2.0) * cos(a), linksPerTick(2.0) * sin(a))
            }
        }
    },
    PerfScene("packed", "полость, набитая частицами", "Замкнутая полость, набитая частицами плотнее касания (шаг 0.52): давление не уходит никогда.", false) { scale ->
        val side = ceil(sqrt(20000 * scale * 0.52 * 0.45) / TERRAIN_STEP).toInt()
        val c = cavity(side, side, jitter = false)
        fill(c, 0.52)
        world.deepKillOn = false
    },
    PerfScene("cave", "тела в пещере со столбами", "Малые тела летают в полости рельефа со столбами: много неподвижных частиц рядом с подвижными.", false) { scale ->
        val n = count(300, scale)
        val pool = listOf(Shapes.disc(3), Shapes.square(7), Shapes.rect(12, 3).muscleAll(), Shapes.disc(4).boneShell())
        val cell = 2 * pool.maxOf { it.reach } + 2.0
        val cols = ceil(sqrt(n * 1.15)).toInt()
        val rows = ceil(n.toDouble() / cols).toInt()
        val per = cell / TERRAIN_STEP
        val c = cavity(ceil(cols * per).toInt() + 2, ceil(rows * per).toInt() + 2, jitter = true) { ix, iy ->
            val gx = (ix - 1) / per; val gy = (iy - 1) / per
            val onX = Math.abs(gx - Math.round(gx)) * per < 1.2 && Math.round(gx).toInt() in 1 until cols
            val onY = Math.abs(gy - Math.round(gy)) * per < 1.2 && Math.round(gy).toInt() in 1 until rows
            !(onX && onY)
        }
        val rnd = Random(24)
        val bodies = ArrayList<StandBody>()
        for (k in 0 until n) bodies.add(body(pool[k % pool.size], c.minX + TERRAIN_STEP + (k % cols + 0.5) * cell, c.minY + TERRAIN_STEP + (k / cols + 0.5) * cell))
        during(0, FOREVER) { t ->
            if (t % 40 == 0) for (b in bodies) { val a = 2 * Math.PI * rnd.nextDouble(); kick(b, cos(a), sin(a)) }
            if (t % 5 == 0) for (b in bodies) if (b.shape.muscleGroups > 0) muscles(b, if ((t / 30) % 2 == 0) PULL else LOOSE)
        }
    },
    PerfScene("debris", "тела, разбитые в обломки", "Каждое третье тело летит на потолке скорости: ткань рвётся, мир полон обломков и пересборок.", false) { scale ->
        val pool = listOf(Shapes.square(8), Shapes.disc(5), Shapes.rect(14, 4), Shapes.square(8).boneCore())
        val bodies = field(cycle(pool, count(260, scale)), 0.8, 1.0, 25, again = FOREVER)
        val rnd = Random(25)
        during(0, FOREVER) { t ->
            if (t % 60 == 0) for ((i, b) in bodies.withIndex()) if ((i + t / 60) % 3 == 0) { val a = 2 * Math.PI * rnd.nextDouble(); kick(b, SPEED_CAP * cos(a), SPEED_CAP * sin(a)) }
        }
    },
    PerfScene("growth", "сотни тел растут разом", "Все тела растут из зигот одновременно, по клетке раз в три тика, вплотную друг к другу.", false) { scale ->
        val pool = listOf(Shapes.disc(6), Shapes.square(10), Shapes.rect(16, 5), Shapes.square(10).boneCore())
        val n = count(170, scale)
        val cell = 2 * pool.maxOf { it.reach } + 0.4
        val cols = ceil(sqrt(n * 1.15)).toInt()
        val rows = ceil(n.toDouble() / cols).toInt()
        arena(ceil(cols * cell + 4.0).toInt().let { it + it % 2 }, ceil(rows * cell + 4.0).toInt().let { it + it % 2 })
        for (k in 0 until n) {
            val s = pool[k % pool.size]
            growing(s, 2.0 + (k % cols + 0.5) * cell, 2.0 + (k / cols + 0.5) * cell, seed = s.nearest(0.0, 0.0), start = k % 3, period = SLOW.period, batch = SLOW.batch)
        }
    },
    PerfScene("crush", "давка", "Все тела сходятся в середину рамки и давят друг друга: касаний больше, чем где-либо.", false) { scale ->
        val pool = listOf(Shapes.disc(3), Shapes.square(7), Shapes.rect(12, 3), Shapes.star(5, 6, 3))
        val bodies = field(cycle(pool, count(380, scale)), 0.8, 0.0, 26, again = FOREVER)
        val v = view!!
        val cx = (v[0] + v[2]) / 2; val cy = (v[1] + v[3]) / 2
        during(0, FOREVER) { t ->
            if (t % 20 == 0) for (b in bodies) {
                val c = centerOf(b)
                val len = Math.hypot(cx - c[0], cy - c[1]).coerceAtLeast(1e-6)
                kick(b, 0.5 * (cx - c[0]) / len, 0.5 * (cy - c[1]) / len)
            }
        }
    },
    PerfScene("asleep", "спящий мир и одно бодрое тело", "Все тела спят, движется одно малое: сколько стоит мир, которому нечего делать. Сон здесь включён.", false) { scale ->
        val pool = listOf(Shapes.disc(3), Shapes.square(7), Shapes.rect(12, 3), Shapes.disc(6))
        val rnd = Random(27)
        val shapes = cycle(pool, count(400, scale))
        val cell = 2 * shapes.maxOf { it.reach } + 1.0
        val cols = ceil(sqrt(shapes.size * 1.15)).toInt()
        val rows = ceil(shapes.size.toDouble() / cols).toInt()
        arena(ceil(cols * cell + 4.0).toInt().let { it + it % 2 }, ceil(rows * cell + 4.0).toInt().let { it + it % 2 })
        val bodies = ArrayList<StandBody>()
        for ((k, s) in shapes.withIndex()) bodies.add(body(s, 2.0 + (k % cols + 0.5) * cell, 2.0 + (k / cols + 0.5) * cell))
        val runner = bodies[0]
        during(0, FOREVER) { t -> if (t % 30 == 0) { val a = 2 * Math.PI * rnd.nextDouble(); kick(runner, 0.5 * cos(a), 0.5 * sin(a)) } }
    },
)

/** Сцены замера, которым сон нужен включённым: всем остальным он выключается. */
internal val KEEPS_SLEEP = setOf("asleep")

/** Собрать сцену замера в мире [w] размера [scale]. */
internal fun PerfScene.setup(w: StandWorld, scale: Double) {
    if (id !in KEEPS_SLEEP) w.noSleep()
    build(w, scale)
}

/** Те же сцены в каталоге стендов, в четверть размера: посмотреть глазами и проверить правилами. */
internal fun perfFamily(): List<Scenario> {
    val family = Family("21. Нагрузка: сцены замера производительности")
    for (s in PERF_SCENES) {
        family.add("perf/${s.id}", "нагрузка: ${s.title}",
            s.about + " Это сцена замера времени тика (gradlew :physics-lab:standsPerf) в четверть размера: тела не спят и раз в сорок тиков получают новый толчок.",
            "Сцена идёт триста тиков и не обязана успокоиться. Числа конечны, клетки не оказываются в чужой ткани и в рельефе, энергия не берётся из ничего. " +
                "Разрывы и гибель клеток здесь допустимы: толчки грубые.", 300,
            Limits.CRASH.copy(name = "нагрузка: разрушение допустимо, покоя не ждём", energy = Energy.DRIVEN, calm = false, jitter = false), Variation.ONLY_BASE) {
            s.setup(this, 0.25)
            // В спящем мире бодрствует одно тело, столкновений по замыслу почти нет; в остальных сценах нагрузки тела обязаны сталкиваться.
            if (s.id == "asleep") idle("сцена нагрузки со спящим миром: столкновений по замыслу почти нет")
            else mustCollide(0.25, "не меньше четверти тел сцены нагрузки с чем-нибудь столкнулись")
        }
    }
    return family.list
}
