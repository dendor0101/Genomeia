package io.github.some_example_name.physics.lab.stands

import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/*
 * СЕМЕЙСТВО «ОДИНОЧНЫЕ ЧАСТИЦЫ». Свободную частицу не держит ничто, кроме касаний: если в
 * мембране тела или в рельефе есть дыра, она её найдёт. Здесь набитые под завязку полости
 * (система обязана успокоиться, а не взорваться), «газ» в ящике, пули в ткань на предельной
 * скорости и костяные снаряды.
 */

private const val T = TERRAIN_STEP

/** Полость в рельефе: её свободные клетки карты и рамка их центров в единицах игры. */
internal class Cavity(val terrain: StandTerrain, val wall: Int, val cols: Int, val rows: Int, val open: (ix: Int, iy: Int) -> Boolean) {
    fun isOpen(ix: Int, iy: Int): Boolean = ix in 0 until cols && iy in 0 until rows && open(ix, iy)
    /** Свободна ли точка игры: её клетка карты и соседние по сторонам — не рельеф. */
    fun free(x: Double, y: Double): Boolean {
        val ix = terrain.cellOfX(x) - wall; val iy = terrain.cellOfY(y) - wall
        return isOpen(ix, iy)
    }
    // Рамка — по кругам касания стенки, а не по границе клеток карты: частицы стенки выступают
    // внутрь полости на TERRAIN_PROTRUDE, и всё, что считается от рамки (набивка, «не вышел»), идёт от них.
    val minX: Double get() = terrain.centerX(wall) - T / 2 + TERRAIN_PROTRUDE
    val minY: Double get() = terrain.centerY(wall) - T / 2 + TERRAIN_PROTRUDE
    val maxX: Double get() = terrain.centerX(wall + cols - 1) + T / 2 - TERRAIN_PROTRUDE
    val maxY: Double get() = terrain.centerY(wall + rows - 1) + T / 2 - TERRAIN_PROTRUDE
}

/** Замкнутая полость [cols] × [rows] клеток карты со стенками в три клетки; [open] вырезает форму внутри. */
internal fun StandWorld.cavity(cols: Int, rows: Int, jitter: Boolean, open: (ix: Int, iy: Int) -> Boolean = { _, _ -> true }): Cavity {
    val wall = 3
    val t = terrain(0.0, 0.0, cols + 2 * wall, rows + 2 * wall, jitter) { ix, iy ->
        val cx = ix - wall; val cy = iy - wall
        !(cx in 0 until cols && cy in 0 until rows && open(cx, cy))
    }
    return Cavity(t, wall, cols, rows, open)
}

/** Заполнить полость частицами по шестиугольной сетке с шагом [spacing]; возвращает их число. */
internal fun StandWorld.fill(c: Cavity, spacing: Double, speed: Double = 0.0, seed: Long = 5L): Int {
    val rnd = Random(seed)
    // Шаг — в счёте частиц радиуса 0.5; у вариаций с мелкими частицами он сжимается вместе со средним радиусом.
    val step = spacing * Variation.current.ballScale
    val rowStep = step * 0.8660254
    var count = 0
    var row = 0
    var y = c.minY + 0.45
    while (y <= c.maxY - 0.45) {
        var x = c.minX + 0.45 + if (row % 2 == 1) step / 2 else 0.0
        while (x <= c.maxX - 0.45) {
            // Частица ставится, если свободна её точка и точки на 0.3 в стороны: не в стене и не вплотную к ней.
            if (c.free(x, y) && c.free(x - 0.3, y) && c.free(x + 0.3, y) && c.free(x, y - 0.3) && c.free(x, y + 0.3)) {
                val a = 2 * Math.PI * rnd.nextDouble()
                ball(x, y, speed * cos(a), speed * sin(a))
                count++
            }
            x += step
        }
        y += rowStep; row++
    }
    return count
}

/**
 * Насколько центр частицы вправе выйти за рамку полости (она идёт по кругам касания стенки),
 * единиц игры. Стенка — сплошной ковёр частиц, и частица, прижатая к ней, стоит центром не ближе
 * своего радиуса касания от рамки; под давлением набитой полости — чуть ближе. Центр за рамкой
 * больше чем на 0.25 — частица ушла в стенку глубже собственного радиуса: вышла.
 */
internal const val CAVITY_SLACK = 0.25

/** Проба и ожидание «ни одна частица не вышла из полости». */
internal fun StandWorld.expectContained(c: Cavity, until: Int) {
    probe("частиц вне полости") {
        var out = 0
        for (id in balls) {
            val p = world.freeParticle(id)
            if (world.state.cellDead[p]) continue
            val x = gameX(p); val y = gameY(p)
            if (x < c.minX - CAVITY_SLACK || x > c.maxX + CAVITY_SLACK || y < c.minY - CAVITY_SLACK || y > c.maxY + CAVITY_SLACK) out++
        }
        out.toDouble()
    }
    expectMaxAtMost("частицы остались в полости", "частиц вне полости", 1, until, 0.0)
}

/** Свободная частица, летящая в центр тела под углом [angle] со скоростью [speed]; возвращает её номер. */
private fun StandWorld.bullet(reach: Double, angle: Double, speed: Double): Int {
    val d = reach + START_GAP + 1.0
    return ball(-d * cos(angle), -d * sin(angle), speed * cos(angle), speed * sin(angle))
}

internal fun particleFamily(): List<Scenario> {
    val family = Family("5. Одиночные частицы")
    val packed = Limits.PACKED

    // --- набитые полости: смерть от касаний выключена, система обязана успокоиться ---
    class Room(val id: String, val name: String, val cols: Int, val rows: Int, val open: (Int, Int) -> Boolean)
    val rooms = listOf(
        Room("box", "квадратная полость", 9, 9) { _, _ -> true },
        Room("slot", "длинная щель", 24, 4) { _, _ -> true },
        Room("round", "круглая полость", 13, 13) { ix, iy -> (ix - 6) * (ix - 6) + (iy - 6) * (iy - 6) <= 38 },
        Room("ell", "полость уголком", 12, 12) { ix, iy -> ix < 5 || iy < 5 },
        Room("neck", "две полости с перешейком", 19, 8) { ix, iy -> ix < 8 || ix > 10 || iy in 3..4 },
    )
    class Pack(val id: String, val name: String, val spacing: Double)
    val packs = listOf(Pack("tight", "вплотную", 0.6), Pack("over15", "сжато на 15%", 0.52), Pack("over25", "сжато на 25%", 0.45))
    for (room in rooms) for (pack in packs) for (jitter in listOf(false, true)) {
        if (jitter && pack.id != "over15") continue
        val tag = if (jitter) "-jitter" else ""
        family.add("ball/packed/${room.id}/${pack.id}$tag", "${room.name}, набита ${pack.name}${if (jitter) ", стенки со сдвигом" else ""}",
            "Замкнутая полость в рельефе заполнена свободными частицами: шаг ${pack.spacing} при расстоянии касания 0.6. " +
                "Смерть от глубокого касания выключена." + (if (jitter) " Частицы стенок сдвинуты случайно, как в игре." else " Стенки ровные."),
            "Частицы расталкиваются до упора в стенки, давление выравнивается, и всё замирает и засыпает. Ни одна частица не выходит из полости, не гибнет и не разгоняется до потолка скорости.",
            2500, packed, Variation.BALLS) {
            val c = cavity(room.cols, room.rows, jitter, room.open)
            fill(c, pack.spacing)
            // Смерть от глубокого касания выключена на всю сцену: флаг хранится в состоянии мира и
            // переходит в касания при их сборке, повторять его каждый тик не нужно.
            world.deepKillOn = false
            expectContained(c, 2500)
        }
    }

    // --- газ: частицы со случайными скоростями в ящике ---
    for ((id, name, spacing, speed) in listOf(
        Quad("thin", "редкий газ", 1.6, 2.0), Quad("dense", "плотный газ", 0.9, 2.0), Quad("hot", "быстрый газ", 1.6, 6.0),
    )) {
        family.add("ball/gas/$id", "$name в ящике", "Ящик из рельефа, частицы стоят с шагом $spacing и разлетаются в случайные стороны со скоростью $speed связи за тик.",
            "Частицы сталкиваются друг с другом и со стенками. Энергия только убывает, ни одна частица не выходит из ящика и не гибнет; движение затухает.",
            900, Limits.INTACT.copy(name = "газ: энергия убывает", sleep = false, capHits = speed > 3), Variation.BALLS) {
            val c = cavity(18, 18, jitter = false)
            fill(c, spacing, speed)
            expectContained(c, 900)
        }
    }

    // --- пули в ткань ---
    class Target(val id: String, val name: String, val shape: ShapeSpec, val angle: Double)
    val targets = listOf(
        Target("square10", "квадрат 10", Shapes.square(10), 0.0),
        Target("disc6", "круг радиуса 6", Shapes.disc(6), 0.0),
        Target("ribbon", "лента 2×16 поперёк", Shapes.ribbon(16), Math.PI / 2),
        Target("ring", "кольцо 6/3", Shapes.ring(6, 3), 0.0),
        Target("u-open", "подкова вырезом к пуле", Shapes.uShape(12, 9, 3), Math.PI / 2),
        Target("core", "квадрат 10 с костным ядром", Shapes.square(10).boneCore(), 0.0),
        Target("blob", "клякса", Shapes.blob(2, 6), 0.0),
        Target("rope", "нить из 16 клеток поперёк", Shapes.rope(16), Math.PI / 2),
    )
    for (t in targets) for (speed in listOf(2.0, 7.5)) for (deg in listOf(0, 35)) {
        val fast = speed > 3
        val slow = true
        family.add("ball/bullet/${t.id}/v$speed-a$deg", "пуля в тело «${t.name}»: скорость $speed, угол $deg°",
            "Свободная частица летит в центр тела со скоростью $speed связи за тик под углом $deg°.",
            (if (fast) "Пуля на пределе скорости: ткань вправе порваться, но сквозь целую ткань пуля не проходит и внутри живой ткани не оказывается; ничего не взрывается, движение затухает."
            else "Пуля отскакивает от мембраны: не входит в ткань и не проходит сквозь неё, ничего не рвётся; к концу всё стоит и спит."),
            if (fast) CRASH_TICKS else settle(speed, slow), if (fast) Limits.CRASH else Limits.INTACT) {
            body(t.shape, 0.0, 0.0, t.angle)
            // Прошла ли пуля сквозь мембрану, судит правило монитора «никто не проходит сквозь ткань»:
            // оно видит само пересечение связи. Проверка «пуля оказалась за телом» тут врала —
            // под углом пуля законно соскальзывает вдоль ленты и улетает за её конец.
            bullet(t.shape.reach, Math.toRadians(deg.toDouble()), speed)
        }
    }

    // --- залп, поток и песок ---
    family.add("ball/volley/v2", "залп из двенадцати частиц в квадрат 12", "Двенадцать частиц в ряд летят в квадрат со скоростью 2 связи за тик.",
        "Частицы отскакивают от мембраны и толкают квадрат; ни одна не входит в ткань, ничего не рвётся; к концу всё стоит и спит.", settle(2.0, true), Limits.INTACT) {
        val s = Shapes.square(12)
        val b = body(s, 0.0, 0.0)
        for (k in 0 until 12) ball(-(s.halfWidth + START_GAP + 1.0), (k - 5.5) * 0.62, 2.0, 0.0)
        expectPushed(b, 2.0, 400, share = 0.01)
    }
    family.add("ball/volley/v6", "залп из двенадцати частиц в квадрат 12 на пределе", "Двенадцать частиц в ряд летят в квадрат со скоростью 6 связей за тик.",
        "Ткань вправе порваться, но частицы не оказываются внутри живой ткани и ничего не взрывается; движение затухает.", CRASH_TICKS, Limits.CRASH) {
        val s = Shapes.square(12)
        body(s, 0.0, 0.0)
        for (k in 0 until 12) ball(-(s.halfWidth + START_GAP + 1.0), (k - 5.5) * 0.62, 6.0, 0.0)
    }
    family.add("ball/stream", "поток частиц в круг", "Тридцать частиц одна за другой, раз в четыре тика, летят в круг со скоростью 3 связи за тик.",
        "Круг сминается под потоком и отъезжает, частицы отскакивают. Ни одна не входит в ткань, ничего не рвётся и не гибнет; движение затухает.",
        900, Limits.INTACT.copy(name = "поток: без повреждений", sleep = false)) {
        val s = Shapes.disc(6)
        body(s, 0.0, 0.0)
        for (k in 0 until 30) at(k * 4) { ball(-(s.reach + START_GAP + 1.0), 0.0, 3.0, 0.0) }
    }
    family.add("ball/sand", "шестьдесят медленных частиц со всех сторон", "Шестьдесят частиц по кругу летят к квадрату со скоростью 0.3 связи за тик.",
        "Частицы облепляют квадрат и останавливаются. Ни одна не входит в ткань, ничего не рвётся; к концу всё стоит и спит.", settle(0.3, true) + 200, Limits.INTACT) {
        val s = Shapes.square(10)
        body(s, 0.0, 0.0)
        val r = s.reach + START_GAP + 2.0
        for (k in 0 until 60) {
            val a = 2 * Math.PI * k / 60
            ball(r * cos(a), r * sin(a), -0.3 * cos(a), -0.3 * sin(a))
        }
    }

    // --- костяные снаряды ---
    val shots = listOf(Shapes.disc(1).boneAll(), Shapes.rect(6, 2).boneAll(), Shapes.triangle(3).boneAll())
    val aims = listOf(targets[0], targets[2], targets[3], targets[4])
    for (shot in shots) for (t in aims) for (hit in listOf(HIT, CRASH)) {
        val crash = hit.rough
        val ticks = if (crash) CRASH_TICKS else settle(hit.speed)
        family.add("ball/boneshot/${shot.id}/${t.id}/${hit.id}", "${shot.name} в тело «${t.name}»: ${hit.title}",
            "Маленькое костное тело летит в мягкое со скоростью ${hit.speed} связи за тик.",
            "Снаряд не входит в ткань и не мнётся сам. " + hit.expect, ticks, hit.limits) {
            val b = body(t.shape, 0.0, 0.0, t.angle)
            val s = body(shot, -(t.shape.reach + shot.reach + START_GAP), 0.0)
            kick(s, hit.speed, 0.0)
            expectPushed(b, hit.speed, ticks, share = 0.005)
        }
    }

    // --- частица о частицу и о стену ---
    for (speed in listOf(0.15, 2.0, 7.5)) {
        val fast = speed > 3
        family.add("ball/pair/v$speed", "две частицы навстречу, скорость $speed", "Две свободные частицы летят друг в друга, каждая со скоростью $speed связи за тик.",
            "Частицы отскакивают и не проходят друг сквозь друга; энергия не растёт, обе живы; движение затухает.",
            900, Limits.INTACT.copy(name = "частицы: энергия убывает", sleep = false, capHits = fast), Variation.BALLS) {
            val left = ball(-2.0, 0.0, speed, 0.0)
            val right = ball(2.0, 0.0, -speed, 0.0)
            probe("правая минус левая") { gameX(world.freeParticle(right)) - gameX(world.freeParticle(left)) }
            expectMinAtLeast("частицы не поменялись местами", "правая минус левая", 1, 900, 0.0)
        }
    }
    family.add("ball/cradle", "маятник Ньютона из восьми частиц", "Восемь частиц стоят в ряд почти вплотную, девятая бьёт в торец со скоростью 2 связи за тик.",
        "Удар проходит по ряду; порядок частиц не меняется, энергия не растёт, никто не гибнет; движение затухает.",
        900, Limits.INTACT.copy(name = "частицы: энергия убывает", sleep = false), Variation.BALLS) {
        val row = (0 until 8).map { ball(it * 0.62, 0.0) }
        val hammer = ball(-3.0, 0.0, 2.0, 0.0)
        probe("наименьший шаг в ряду") {
            val xs = (listOf(hammer) + row).map { gameX(world.freeParticle(it)) }
            (1 until xs.size).minOf { xs[it] - xs[it - 1] }
        }
        expectMinAtLeast("частицы не поменялись местами", "наименьший шаг в ряду", 1, 900, 0.0)
    }
    val wall = terrainLayouts().first { it.id == "wall" }
    for (speed in listOf(0.6, 2.0, 7.5)) for (jitter in listOf(false, true)) {
        val tag = if (jitter) "-jitter" else ""
        family.add("ball/wall/v$speed$tag", "частица в стену, скорость $speed${if (jitter) ", стена со сдвигом" else ""}",
            "Свободная частица летит в стену рельефа со скоростью $speed связи за тик.",
            "Частица отскакивает от стены и не входит в неё; энергия не растёт, частица жива; движение затухает.",
            700, Limits.INTACT.copy(name = "частицы: энергия убывает", sleep = false, capHits = speed > 3), Variation.BALLS) {
            place(wall, 4.0, jitter)
            val id = ball(0.0, 0.3, speed, 0.0)
            probe("частица по X") { gameX(world.freeParticle(id)) }
            expectMaxAtMost("частица не ушла в стену", "частица по X", 1, 700, 4.0)
            view = doubleArrayOf(-8.0, -6.0, 9.0, 6.0)
        }
    }
    val pocket = terrainLayouts().first { it.id == "pocket" }
    family.add("ball/cup", "сорок частиц залетают в карман", "Сорок частиц летят в карман рельефа со скоростью 0.6 связи за тик.",
        "Частицы набиваются в карман, расталкиваются и замирают. Ни одна не входит в рельеф и не гибнет; к концу всё стоит и спит.",
        2500, Limits.INTACT, Variation.BALLS) {
        place(pocket, 6.0)
        for (k in 0 until 40) ball(-(k / 8) * 1.3, (k % 8 - 3.5) * 1.1, 0.6, 0.0)
        view = doubleArrayOf(-8.0, -9.0, 20.0, 9.0)
    }
    return family.list
}

/** Четвёрка значений для перечисления вариантов сцены. */
internal data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
