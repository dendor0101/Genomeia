package io.github.some_example_name.physics.lab.stands

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/*
 * СЕМЕЙСТВА «МЫШЦЫ» И «МЫШЦЫ С КОСТЯМИ».
 *
 * Главное требование к мышце: она не рвёт собственную ткань, как бы сильно и резко ни
 * сокращалась. Поэтому у всех сцен правила «без повреждений»: ни одного разрыва и ни одной
 * погибшей клетки, после расслабления тело возвращает форму и засыпает. Сверх того у сцены
 * есть ожидание «мышца работает»: тело заметно меняет форму, сустав гнётся, кости сходятся.
 *
 * Команда мышцы — как в игре: 0.5 — сокращение вдвое (сигнал −1), 1 — покой, 1.5 — расслабление
 * (сигнал +1). Плавность хода к цели — дело решателя.
 */

private const val L = GAME_LINK
private val R = GAME_ROW

/** Сокращение, покой, расслабление. */
private const val PULL = 0.5f
private const val REST = 1f
private const val LOOSE = 1.5f

/** Допуски сцены с дёрганьем: всё как у прочих мышечных сцен, но правило дрожи не применяется. */
internal val FLICKERING = Limits.DRIVEN.copy(name = "внешняя сила, без повреждений; дрожь задаёт сценарий", jitter = false)

/** Как работают мышцы тела: действия по тикам и сколько тиков они длятся. */
internal class Drive(val id: String, val title: String, val about: String, val active: Int, val script: StandWorld.(StandBody) -> Unit)

internal val SQUEEZE = Drive("squeeze", "сжатие целиком", "Все мышцы сокращаются до предела, держат 130 тиков и отпускают.", 150) { b ->
    at(20) { muscles(b, PULL) }
    at(150) { muscles(b, REST) }
}
internal val PUMP = Drive("pump", "качание", "Все мышцы шесть раз подряд сокращаются до предела и расслабляются, по 20 тиков на ход.", 260) { b ->
    for (k in 0 until 6) { at(20 + k * 40) { muscles(b, PULL) }; at(40 + k * 40) { muscles(b, LOOSE) } }
    at(260) { muscles(b, REST) }
}
internal val FLICKER = Drive("flicker", "дёрганье", "Все мышцы 200 тиков подряд переключаются между сокращением и расслаблением каждые два тика.", 221) { b ->
    during(20, 220) { t -> muscles(b, if ((t / 2) % 2 == 0) PULL else LOOSE) }
    at(221) { muscles(b, REST) }
}
internal val SWAP = Drive("swap", "попеременно", "Чётные группы мышц сокращаются, нечётные расслабляются; через 150 тиков наоборот; затем покой.", 320) { b ->
    val n = b.shape.muscleGroups
    at(20) { for (g in 0 until n) muscle(b, g, if (g % 2 == 0) PULL else LOOSE) }
    at(170) { for (g in 0 until n) muscle(b, g, if (g % 2 == 0) LOOSE else PULL) }
    at(320) { muscles(b, REST) }
}
internal val SWAP_FAST = Drive("swapfast", "попеременно быстро", "Чётные и нечётные группы мышц меняются ролями каждые 30 тиков, шесть раз.", 200) { b ->
    val n = b.shape.muscleGroups
    for (k in 0 until 6) at(20 + k * 30) { for (g in 0 until n) muscle(b, g, if ((g + k) % 2 == 0) PULL else LOOSE) }
    at(200) { muscles(b, REST) }
}
internal val WAVE = Drive("wave", "бегущая волна", "Группы мышц сокращаются по очереди, каждая на 20 тиков; волна проходит по телу шесть раз.", 381) { b ->
    val n = b.shape.muscleGroups
    during(20, 380) { t ->
        val head = ((t - 20) / 10) % n
        for (g in 0 until n) muscle(b, g, if (g == head || g == (head + 1) % n) PULL else REST)
    }
    at(381) { muscles(b, REST) }
}
internal val HOLD = Drive("hold", "долгое удержание", "Первая группа мышц сокращается до предела и держит 400 тиков, остальные в покое.", 420) { b ->
    at(20) { muscle(b, 0, PULL) }
    at(420) { muscles(b, REST) }
}

/** Наибольшее относительное изменение расстояний между крайними клетками тела — «насколько тело изменило форму». */
private fun StandWorld.shapeChange(b: StandBody): () -> Double {
    val s = b.shape
    val tips = intArrayOf(s.extreme(-1.0, 0.0), s.extreme(1.0, 0.0), s.extreme(0.0, -1.0), s.extreme(0.0, 1.0))
    val pairs = ArrayList<IntArray>()
    for (i in tips.indices) for (j in i + 1 until tips.size) if (tips[i] != tips[j]) pairs.add(intArrayOf(tips[i], tips[j]))
    val rest = pairs.map { sqrt((s.x[it[0]] - s.x[it[1]]) * (s.x[it[0]] - s.x[it[1]]) + (s.y[it[0]] - s.y[it[1]]) * (s.y[it[0]] - s.y[it[1]])) }
    return { pairs.indices.maxOfOrNull { abs(distance(b, pairs[it][0], pairs[it][1]) / rest[it] - 1.0) } ?: 0.0 }
}

/** Отношение расстояния между клетками [a] и [c] тела к исходному. */
private fun StandWorld.spanRatio(b: StandBody, a: Int, c: Int): () -> Double {
    val s = b.shape
    val rest = sqrt((s.x[a] - s.x[c]) * (s.x[a] - s.x[c]) + (s.y[a] - s.y[c]) * (s.y[a] - s.y[c]))
    return { distance(b, a, c) / rest }
}

/** Угол в градусах между отрезками клеток [a1]→[a2] и [b1]→[b2] тела, со знаком. */
private fun StandWorld.bendAngle(b: StandBody, a1: Int, a2: Int, b1: Int, b2: Int): () -> Double = {
    // В координатах сцены: у отражённой сцены знак угла тот же, что у исходной.
    val ux = gameX(b.particle(a2)) - gameX(b.particle(a1)); val uy = gameY(b.particle(a2)) - gameY(b.particle(a1))
    val vx = gameX(b.particle(b2)) - gameX(b.particle(b1)); val vy = gameY(b.particle(b2)) - gameY(b.particle(b1))
    Math.toDegrees(atan2(ux * vy - uy * vx, ux * vx + uy * vy))
}

private fun Family.muscleScene(prefix: String, shape: ShapeSpec, drive: Drive, extra: String = "", check: (StandWorld.(StandBody, Int) -> Unit)? = null) {
    val ticks = drive.active + settle(0.5)
    add("$prefix/${shape.id}/${drive.id}", "${shape.name}: ${drive.title}", drive.about + extra,
        "Тело заметно меняет форму, но мышцы не рвут свою ткань: ни одного разрыва и ни одной погибшей клетки, клетки не входят в свою ткань. " +
            "После расслабления тело возвращает исходную форму, останавливается и засыпает." + (if (shape.boneCount > 0) " Кости не мнутся." else ""),
        // Дёрганье переключает мышцу каждые два тика: частицы обязаны идти за командой туда-сюда, и дрожью это не считается.
        ticks, if (drive === FLICKER) FLICKERING else Limits.DRIVEN) {
        val b = body(shape, 0.0, 0.0)
        drive.script(this, b)
        probe("изменение формы") { shapeChange(b)() }
        expectMaxAtLeast("мышцы изменили форму тела", "изменение формы", 20, drive.active, 0.03)
        check?.invoke(this, b, drive.active)
    }
}

internal fun muscleFamily(): List<Scenario> {
    val family = Family("6. Мышцы")
    val worms = listOf(Shapes.rect(12, 3), Shapes.rect(20, 4), Shapes.rect(30, 4), Shapes.rect(40, 6), Shapes.rect(16, 2), Shapes.rect(10, 8))
    for (worm in worms) {
        val left = worm.extreme(-1.0, 0.0); val right = worm.extreme(1.0, 0.0)
        val whole = worm.muscleAll()
        family.muscleScene("muscle", whole, SQUEEZE) { b, active ->
            probe("длина к исходной") { spanRatio(b, left, right)() }
            expectMinAtMost("тело сократилось хотя бы на треть", "длина к исходной", 20, active, 0.67)
        }
        family.muscleScene("muscle", whole, PUMP)
        family.muscleScene("muscle", whole, FLICKER)
        val halves = worm.muscleTopBottom()
        family.muscleScene("muscle", halves, SWAP, " Верхняя половина тянет, нижняя отпускает: тело сворачивается дугой, затем в другую сторону.") { b, active ->
            probe("между концами к исходному") { spanRatio(b, left, right)() }
            expectMinAtMost("тело согнулось: концы сблизились", "между концами к исходному", 20, active, 0.9)
        }
        family.muscleScene("muscle", halves, HOLD)
        family.muscleScene("muscle", worm.muscleLeftRight(), SWAP)
        family.muscleScene("muscle", worm.muscleSegments(6), WAVE)
    }
    // Не только черви.
    family.muscleScene("muscle", Shapes.disc(6).muscleAll(), SQUEEZE)
    family.muscleScene("muscle", Shapes.disc(6).muscleAll(), PUMP)
    family.muscleScene("muscle", Shapes.disc(6).muscleAll(), FLICKER)
    family.muscleScene("muscle", Shapes.disc(6).muscleRim(), SQUEEZE)
    family.muscleScene("muscle", Shapes.disc(6).muscleRim(), PUMP)
    family.muscleScene("muscle", Shapes.ring(7, 4).muscleAll(), SQUEEZE)
    family.muscleScene("muscle", Shapes.ring(7, 4).muscleAll(), PUMP)
    family.muscleScene("muscle", Shapes.star(5, 8, 3).muscleArms(5, 3.0), WAVE)
    family.muscleScene("muscle", Shapes.star(5, 8, 3).muscleArms(5, 3.0), SQUEEZE)
    family.muscleScene("muscle", Shapes.cross(6, 4).muscleArms(4, 2.0), SWAP)
    family.muscleScene("muscle", Shapes.square(10).muscleQuadrants(), SWAP_FAST)
    family.muscleScene("muscle", Shapes.uShape(12, 9, 3).muscleLeftRight(), SWAP)
    family.muscleScene("muscle", Shapes.blob(2, 6).muscleTopBottom(), SWAP)
    family.muscleScene("muscle", Shapes.dumbbell(3, 6, 3).muscleBand(0.5), SQUEEZE)
    family.muscleScene("muscle", Shapes.hourglass(10, 12, 4).muscleBand(0.3), PUMP)
    family.muscleScene("muscle", Shapes.snake(22, 3, 3).muscleSegments(8), WAVE)
    family.muscleScene("muscle", Shapes.ellipse(8, 4).muscleTopBottom(), SWAP)
    family.muscleScene("muscle", Shapes.ribbon(20).muscleSegments(5), WAVE)
    return family.list
}

// --- связки мышц с костями ---

/** Две кости в ряд с мягким суставом между ними, сгибатель сверху и разгибатель снизу. */
internal fun armShape(cols: Int, rows: Int): ShapeSpec {
    val s = Shapes.rect(cols, rows)
    return s.withBones("arm", "две кости с суставом") { x, y, _ -> abs(y) <= R * 1.1 && abs(x) >= 1.6 * L }
        .withMuscles("flex", "сгибатель и разгибатель") { x, y, _ ->
            if (abs(x) > 0.62 * s.halfWidth) -1 else if (y > R * 1.5) 0 else if (y < -R * 1.5) 1 else -1
        }.renamed("arm${cols}x$rows", "сустав $cols×$rows: две кости, сгибатель и разгибатель")
}

/** Два костных блока на концах и мышца между ними. */
internal fun pistonShape(): ShapeSpec {
    val s = Shapes.rect(24, 5)
    return s.withBones("ends", "кости на концах") { x, _, _ -> abs(x) >= 5 * L }
        .withMuscles("mid", "мышца между костями") { x, _, _ -> if (abs(x) < 4.4 * L) 0 else -1 }
        .renamed("piston", "поршень: две кости и мышца между ними")
}

/** Подкова с костными зубцами; основание — мышца в два слоя, верхний и нижний. */
internal fun clawShape(): ShapeSpec {
    val s = Shapes.uShape(14, 12, 4)
    val base = -s.halfHeight + 3 * L
    return s.withBones("prongs", "костные зубцы") { _, y, _ -> y > base + 1.5 * L }
        .withMuscles("base", "мышцы в основании") { _, y, _ -> if (y > base) -1 else if (y > base - 1.6 * L) 0 else 1 }
        .renamed("claw", "клешня: костные зубцы на мышечном основании")
}

/** Костные рёбра поперёк тела через равные промежутки, между ними мышцы. */
internal fun ribsShape(): ShapeSpec {
    val s = Shapes.rect(32, 6)
    fun slot(x: Double) = Math.floorMod(Math.floor((x + s.halfWidth) / L + 0.25).toInt(), 6)
    return s.withBones("ribs", "костные рёбра") { x, _, _ -> slot(x) < 2 }
        .withMuscles("between", "мышцы между рёбрами") { x, _, _ ->
            if (slot(x) < 2) -1 else Math.floor((x + s.halfWidth) / L / 6).toInt() % 2
        }.renamed("ribs", "гармошка: костные рёбра и мышцы между ними")
}

/** Хребет из позвонков по оси и мышцы по бокам. */
internal fun fishShape(): ShapeSpec {
    val s = Shapes.rect(36, 7)
    fun slot(x: Double) = Math.floorMod(Math.floor((x + s.halfWidth) / L + 0.25).toInt(), 6)
    return s.withBones("spine", "хребет из позвонков") { x, y, _ -> y >= -R * 0.1 && y <= R * 1.1 && slot(x) < 5 }
        .withMuscles("flanks", "мышцы по бокам") { _, y, _ -> if (y > R * 1.5) 0 else if (y < -R * 0.6) 1 else -1 }
        .renamed("fish", "рыба: хребет из позвонков и мышцы по бокам")
}

/** Тяжёлая костная голова на мышечном хвосте. */
internal fun tadpoleShape(): ShapeSpec {
    val head = -6 * L
    val s = Lattice.shape("tadpole", "головастик", 16 * L) { x, y ->
        (x - head) * (x - head) + y * y <= 16 * L * L + 1e-6 || (x >= -3 * L && x <= 12 * L && abs(y) <= R * 1.1)
    }
    return s.withBones("head", "костная голова") { x, y, _ -> (x - head) * (x - head) + y * y <= 9 * L * L }
        .withMuscles("tail", "мышцы хвоста") { x, y, _ -> if (x < -1.5 * L) -1 else if (y > R * 0.3) 0 else if (y < -R * 0.3) 1 else -1 }
        .renamed("tadpole", "головастик: костная голова и мышечный хвост")
}

/** Костное ядро в круге и мышечное кольцо вокруг него. */
internal fun coreRingShape(): ShapeSpec {
    val s = Shapes.disc(7)
    return s.withBones("core", "костное ядро") { x, y, _ -> x * x + y * y <= 9 * L * L }
        .withMuscles("ring", "мышечное кольцо") { x, y, _ -> if (x * x + y * y >= 20 * L * L) 0 else -1 }
        .renamed("corering", "круг с костным ядром в мышечном кольце")
}

/** Три костных блока и две мышцы между ними. */
internal fun tugShape(): ShapeSpec {
    val s = Shapes.rect(30, 5)
    return s.withBones("blocks", "три костных блока") { x, _, _ -> abs(x) >= 10 * L || abs(x) <= 2 * L }
        .withMuscles("pair", "две мышцы") { x, _, _ -> if (abs(x) >= 9.4 * L || abs(x) <= 2.6 * L) -1 else if (x < 0) 0 else 1 }
        .renamed("tug", "перетягивание: три кости и две мышцы")
}

internal fun leverFamily(): List<Scenario> {
    val family = Family("7. Мышцы с костями")
    val drives = listOf(SWAP, SWAP_FAST, FLICKER)

    for (shape in listOf(armShape(30, 7), armShape(20, 5), armShape(44, 9))) for (drive in drives) {
        val a1 = shape.nearest(-shape.halfWidth, 0.0); val a2 = shape.nearest(-1.8 * L, 0.0)
        val b1 = shape.nearest(1.8 * L, 0.0); val b2 = shape.nearest(shape.halfWidth, 0.0)
        family.muscleScene("lever", shape, drive, " Сгибатель и разгибатель работают друг против друга: сустав гнётся в обе стороны.") { b, active ->
            probe("изгиб сустава, градусы") { bendAngle(b, a1, a2, b1, b2)() }
            if (drive !== FLICKER) {
                expectMaxAtLeast("сустав согнулся в одну сторону", "изгиб сустава, градусы", 20, active, 8.0)
                expectMinAtMost("сустав согнулся в другую сторону", "изгиб сустава, градусы", 20, active, -8.0)
            }
        }
    }
    val piston = pistonShape()
    for (drive in listOf(SQUEEZE, PUMP, FLICKER)) {
        val a = piston.extreme(-1.0, 0.0); val c = piston.extreme(1.0, 0.0)
        family.muscleScene("lever", piston, drive, " Мышца стягивает две кости.") { b, active ->
            probe("между костями к исходному") { spanRatio(b, a, c)() }
            if (drive !== FLICKER) expectMinAtMost("кости сошлись", "между костями к исходному", 20, active, 0.88)
        }
    }
    val claw = clawShape()
    for (drive in drives) {
        val a = claw.extreme(-1.0, 1.0); val c = claw.extreme(1.0, 1.0)
        family.muscleScene("lever", claw, drive, " Верхний слой основания тянет, нижний отпускает: зубцы сходятся; затем наоборот.") { b, active ->
            probe("между зубцами к исходному") { spanRatio(b, a, c)() }
            if (drive !== FLICKER) {
                expectMinAtMost("зубцы сошлись", "между зубцами к исходному", 20, active, 0.95)
                expectMaxAtLeast("зубцы разошлись", "между зубцами к исходному", 20, active, 1.05)
            }
        }
    }
    val ribs = ribsShape()
    for (drive in listOf(SQUEEZE, SWAP, FLICKER)) {
        val a = ribs.extreme(-1.0, 0.0); val c = ribs.extreme(1.0, 0.0)
        family.muscleScene("lever", ribs, drive, " Мышцы между рёбрами стягивают гармошку.") { b, active ->
            probe("длина к исходной") { spanRatio(b, a, c)() }
            if (drive === SQUEEZE) expectMinAtMost("гармошка сжалась", "длина к исходной", 20, active, 0.88)
        }
    }
    val fish = fishShape()
    for (drive in drives) {
        val a1 = fish.nearest(-fish.halfWidth, R * 0.5); val a2 = fish.nearest(-fish.halfWidth / 3, R * 0.5)
        val b1 = fish.nearest(fish.halfWidth / 3, R * 0.5); val b2 = fish.nearest(fish.halfWidth, R * 0.5)
        family.muscleScene("lever", fish, drive, " Мышцы по бокам гнут хребет то в одну, то в другую сторону.") { b, active ->
            probe("изгиб хребта, градусы") { bendAngle(b, a1, a2, b1, b2)() }
            if (drive !== FLICKER) {
                expectMaxAtLeast("хребет согнулся в одну сторону", "изгиб хребта, градусы", 20, active, 6.0)
                expectMinAtMost("хребет согнулся в другую сторону", "изгиб хребта, градусы", 20, active, -6.0)
            }
        }
    }
    val tadpole = tadpoleShape()
    for (drive in drives) {
        val a1 = tadpole.nearest(-6 * L, 0.0); val a2 = tadpole.nearest(-3 * L, 0.0)
        val b1 = tadpole.nearest(6 * L, 0.0); val b2 = tadpole.nearest(12 * L, 0.0)
        family.muscleScene("lever", tadpole, drive, " Хвост машет при тяжёлой костной голове.") { b, active ->
            probe("отклонение хвоста, градусы") { bendAngle(b, a1, a2, b1, b2)() }
            if (drive !== FLICKER) {
                expectMaxAtLeast("хвост отклонился в одну сторону", "отклонение хвоста, градусы", 20, active, 6.0)
                expectMinAtMost("хвост отклонился в другую сторону", "отклонение хвоста, градусы", 20, active, -6.0)
            }
        }
    }
    val core = coreRingShape()
    for (drive in listOf(SQUEEZE, PUMP, FLICKER)) {
        family.muscleScene("lever", core, drive, " Мышечное кольцо давит на костное ядро: кость не должна смяться, ткань между ними — порваться.")
    }
    val tug = tugShape()
    for (drive in drives) {
        val mid = tug.nearest(0.0, 0.0); val a = tug.extreme(-1.0, 0.0); val c = tug.extreme(1.0, 0.0)
        family.muscleScene("lever", tug, drive, " Две мышцы тянут средний костный блок в разные стороны по очереди.") { b, active ->
            probe("средняя кость от середины, связей") {
                val w = world
                val mx = (w.x[b.particle(a)] + w.x[b.particle(c)]) / 2; val my = (w.y[b.particle(a)] + w.y[b.particle(c)]) / 2
                val ax = w.x[b.particle(c)] - w.x[b.particle(a)]; val ay = w.y[b.particle(c)] - w.y[b.particle(a)]
                val len = sqrt(ax * ax + ay * ay)
                ((w.x[b.particle(mid)] - mx) * ax + (w.y[b.particle(mid)] - my) * ay) / len / PHYSICS_LINK
            }
            if (drive !== FLICKER) {
                expectMaxAtLeast("среднюю кость утянуло в одну сторону", "средняя кость от середины, связей", 20, active, 0.4)
                expectMinAtMost("среднюю кость утянуло в другую сторону", "средняя кость от середины, связей", 20, active, -0.4)
            }
        }
    }
    return family.list
}
