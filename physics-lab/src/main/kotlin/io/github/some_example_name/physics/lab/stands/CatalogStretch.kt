package io.github.some_example_name.physics.lab.stands

import kotlin.math.sin

/*
 * СЕМЕЙСТВО «РАСТЯЖЕНИЕ, СЖАТИЕ И ПРИСОСКИ».
 *
 * До сих пор тело деформировали только удары, мышь и его же мышцы — силой. Здесь его
 * деформируют ходом: два зажима берут тело за края и разводят, сводят, сдвигают и скручивают на
 * заданную величину (StandWorld.Clamp: клетки с хваткой 1, которые сценарий переставляет каждый
 * тик). Так видно, что ткань делает на растяжении в 3% (обязана держать и вернуться), в 10% (за
 * жёстким пределом длины связи — где рвётся) и в 30% (рвётся ли чисто или взрывается).
 *
 * Вторая половина — присоски. В игре клетка SuctionCup держится за дно хваткой 0..1
 * (PhysicsWorld.setGrip): при хватке 1 её не сдвинуть ничем. Это самая жёсткая опора, какая у
 * организма бывает, и самое суровое испытание правила «мышца не рвёт свою ткань»: червю,
 * приклеенному обоими концами, сокращаться некуда.
 */

private const val PULL = 0.5f
private const val REST = 1f
private const val LOOSE = 1.5f

/** Ход правого зажима: сдвиг в долях длины и высоты тела и поворот, по тикам; [end] — тик, когда зажимы отпускают. */
private class Move(val id: String, val title: String, val about: String, val expect: String, val end: Int, val limits: Limits,
                   /** Наибольшее и наименьшее отношение расстояния между зажимами к исходному, которого ход обязан достичь; 0 — не проверяется. */
                   val reachMax: Double = 0.0, val reachMin: Double = 0.0,
                   val at: (t: Int) -> DoubleArray)

/** Туда, подержать, обратно: доля хода по тикам 20..140, 140..220, 220..340. */
private fun thereAndBack(t: Int): Double = ramp(t, 20, 140) - ramp(t, 220, 340)

private val HOLDS = "Зажимы держат края намертво. "
private val RETURNS = "После возврата и отпускания тело принимает прежнюю форму, останавливается и засыпает."
private val TEARS_OK = Limits.CRASH.copy(name = "ход за пределом прочности: разрыв допустим", energy = Energy.DRIVEN)
private val SOME_TEARS = Limits("ход у предела прочности: порваться может до 10% связей", tornShare = 0.10, deadShare = 0.02, energy = Energy.DRIVEN, shape = false, inversions = false)

private val MOVES = listOf(
    Move("stretch3", "растяжение на 3%", "Правый зажим за 120 тиков уходит вправо на 3% длины тела, стоит 80 тиков и возвращается.",
        HOLDS + "Три процента — внутри предела длины связи (5%): ткань тянется без единого разрыва. " + RETURNS, 350, Limits.DRIVEN, reachMax = 1.025) { t ->
        doubleArrayOf(0.03 * thereAndBack(t), 0.0, 0.0)
    },
    Move("stretch10", "растяжение на 10%", "Правый зажим за 120 тиков уходит вправо на 10% длины тела, стоит 80 тиков и возвращается.",
        HOLDS + "Десять процентов — за жёстким пределом длины связи: ткань вправе порваться местами (до 10% связей), но не лопнуть целиком и не взорваться; после отпускания движение затухает.",
        350, SOME_TEARS) { t -> doubleArrayOf(0.10 * thereAndBack(t), 0.0, 0.0) },
    Move("stretch30", "растяжение на 30%", "Правый зажим за 120 тиков уходит вправо на 30% длины тела и стоит до тика 220, затем возвращается.",
        HOLDS + "Ткань рвётся — так и должно быть. Но рвётся она по месту, без взрыва: скорости не подскакивают до потолка, обрывки не проходят друг сквозь друга, движение затухает.",
        350, TEARS_OK.copy(capHits = false)) { t -> doubleArrayOf(0.30 * thereAndBack(t), 0.0, 0.0) },
    Move("squeeze10", "сжатие на 10%", "Правый зажим за 120 тиков подходит к левому на 10% длины тела, стоит 80 тиков и возвращается.",
        HOLDS + "Ткань сжимается без разрывов и гибели клеток, не складывается в себя. " + RETURNS, 350, Limits.DRIVEN, reachMin = 0.92) { t ->
        doubleArrayOf(-0.10 * thereAndBack(t), 0.0, 0.0)
    },
    Move("squeeze30", "сжатие на 30%", "Правый зажим за 120 тиков подходит к левому на 30% длины тела, стоит 80 тиков и возвращается.",
        HOLDS + "Тело выгибается или собирается складкой, но клетки не входят в свою ткань, треугольники не выворачиваются, ничего не рвётся. " + RETURNS,
        350, Limits.DRIVEN, reachMin = 0.75) { t -> doubleArrayOf(-0.30 * thereAndBack(t), 0.0, 0.0) },
    Move("squeeze60", "сжатие на 60%", "Правый зажим за 120 тиков подходит к левому на 60% длины тела, стоит 80 тиков и возвращается.",
        HOLDS + "Сжатие запредельное: ткань вправе порваться и потерять клетки. Но ничего не взрывается, клетки не остаются внутри своей ткани, движение затухает.",
        350, TEARS_OK) { t -> doubleArrayOf(-0.60 * thereAndBack(t), 0.0, 0.0) },
    Move("shear", "сдвиг на четверть высоты", "Правый зажим за 120 тиков поднимается на четверть высоты тела, стоит 80 тиков и возвращается.",
        HOLDS + "Ткань перекашивается без разрывов и вывернутых треугольников. " + RETURNS, 350, Limits.DRIVEN) { t ->
        doubleArrayOf(0.0, 0.25 * thereAndBack(t), 0.0)
    },
    Move("twist30", "скручивание на 30°", "Правый зажим за 120 тиков поворачивается на 30° вокруг своей середины, стоит 80 тиков и возвращается.",
        HOLDS + "У зажима ткань сильно перекошена: местный разрыв допустим (до 10% связей), но ничего не взрывается; после отпускания движение затухает.",
        350, SOME_TEARS) { t -> doubleArrayOf(0.0, 0.0, Math.toRadians(30.0) * thereAndBack(t)) },
    Move("twist180", "скручивание на 180°", "Правый зажим за 120 тиков поворачивается на пол-оборота вокруг своей середины и возвращается.",
        HOLDS + "Ткань у зажима рвётся — так и должно быть; но без взрыва, и клетки не остаются внутри своей ткани. Движение затухает.",
        350, TEARS_OK) { t -> doubleArrayOf(0.0, 0.0, Math.PI * thereAndBack(t)) },
    Move("cycle", "качание ±3% четыреста тиков", "Правый зажим 400 тиков ходит туда-сюда на 3% длины тела с периодом 40 тиков.",
        HOLDS + "Ткань работает как пружина: ни разрывов, ни накопленного ухода формы, ни дрожи. " + RETURNS, 430, Limits.DRIVEN) { t ->
        doubleArrayOf(if (t in 20..420) 0.03 * sin(2 * Math.PI * (t - 20) / 40.0) * ramp(t, 20, 60) * (1 - ramp(t, 380, 420)) else 0.0, 0.0, 0.0)
    },
    Move("snap", "растянули на 4% и разом отпустили", "Правый зажим за 120 тиков уходит вправо на 4% длины тела, стоит до тика 200, и оба зажима разом отпускают.",
        "Тело сокращается рывком и колеблется, как отпущенная резинка: без разрывов, без прироста энергии сверх запасённой, без дрожи. " + RETURNS,
        200, Limits.DRIVEN, reachMax = 1.03) { t -> doubleArrayOf(0.04 * ramp(t, 20, 140), 0.0, 0.0) },
    Move("jerk", "рывок на 25% за шесть тиков", "Правый зажим за шесть тиков уходит вправо на четверть длины тела и стоит там до тика 150.",
        HOLDS + "Рывок сильнее любой ткани: она вправе порваться. Но без взрыва: скорости не подскакивают до потолка, обрывки не проходят друг сквозь друга, движение затухает.",
        150, TEARS_OK.copy(capHits = false)) { t -> doubleArrayOf(0.25 * ramp(t, 20, 26), 0.0, 0.0) },
)

internal fun stretchFamily(): List<Scenario> {
    val family = Family("14. Растяжение, сжатие и присоски")
    val samples = listOf(
        Shapes.rect(16, 6), Shapes.square(10), Shapes.disc(6), Shapes.ring(7, 4), Shapes.rect(24, 3), Shapes.uShape(12, 9, 3),
        Shapes.rect(18, 6).boneSpine(), Shapes.rect(18, 6).bonePlates(3), Shapes.rect(16, 6).muscleAll(),
    )
    for (shape in samples) {
        val left = edgeCells(shape, -1.0, 0.0); val right = edgeCells(shape, 1.0, 0.0)
        for (move in MOVES) {
            val ticks = move.end + if (move.limits.tornShare > 0.0) CRASH_TICKS else settle(0.5)
            family.add("pull/${shape.id}/${move.id}", "${shape.name}: ${move.title}",
                "Тело взято двумя зажимами за левый и правый край. " + move.about,
                move.expect + (if (shape.boneCount > 0) " Кости не мнутся." else ""), ticks, move.limits) {
                val b = body(shape, 0.0, 0.0)
                val length = 2 * shape.halfWidth; val height = 2 * shape.halfHeight
                var fixed: StandWorld.Clamp? = null; var moving: StandWorld.Clamp? = null
                at(10) { fixed = clamp(b, left); moving = clamp(b, right) }
                during(11, move.end) { t ->
                    val m = move.at(t)
                    fixed?.moveTo(0.0, 0.0)
                    moving?.moveTo(m[0] * length, m[1] * height, m[2])
                }
                at(move.end + 1) { fixed?.release(); moving?.release() }
                val l0 = centerOfCells(b, left); val r0 = centerOfCells(b, right)
                val rest = Math.hypot(r0[0] - l0[0], r0[1] - l0[1])
                probe("между зажимами к исходному") {
                    val l = centerOfCells(b, left); val r = centerOfCells(b, right)
                    Math.hypot(r[0] - l[0], r[1] - l[1]) / rest
                }
                if (move.reachMax > 0.0) expectMaxAtLeast("зажим растянул тело", "между зажимами к исходному", 11, move.end, move.reachMax)
                if (move.reachMin > 0.0) expectMinAtMost("зажим сжал тело", "между зажимами к исходному", 11, move.end, move.reachMin)
            }
        }
    }

    // --- присоски: клетки держатся за дно ---
    /** Проба «присоски ушли с места» и ожидание, что они стоят, пока держат. */
    fun StandWorld.expectHeld(b: StandBody, cups: IntArray, from: Int, until: Int) =
        expectStill("присоски ушли с места, связей", b, cups, from, until, 0.02)

    val holders = listOf(Shapes.rect(16, 6), Shapes.disc(6), Shapes.square(10), Shapes.rect(18, 6).boneSpine(), Shapes.star(5, 8, 3))
    for (shape in holders) {
        val cups = edgeCells(shape, 0.0, -1.0)
        val top = shape.extreme(0.3, 1.0)
        family.add("cup/hold/${shape.id}/drag", "${shape.name}: присоски держат, мышь тянет за верх",
            "Нижний край тела — присоски с хваткой 1. Мышь берёт верхнюю клетку и плавно уводит её на четыре единицы вверх и вбок, затем отпускает.",
            "Присоски не сдвигаются ни на сотую связи; тело тянется за курсором, но не рвётся; после отпускания возвращает форму, останавливается и засыпает.",
            300 + settle(0.5), Limits.DRIVEN) {
            val b = body(shape, 0.0, 0.0)
            at(5) { grip(b, cups, 1f) }
            val sx = shape.x[top]; val sy = shape.y[top]
            mousePath(b, top, 20, 3, (0..60).map { doubleArrayOf(sx + 2.5 * it / 60.0, sy + 4.0 * it / 60.0) })
            expectHeld(b, cups, 6, 300 + settle(0.5))
        }
        family.add("cup/hold/${shape.id}/yank", "${shape.name}: присоски держат, мышь рвёт тело",
            "Нижний край тела — присоски с хваткой 1. Мышь берёт верхнюю клетку, курсор разом уходит на двенадцать единиц и стоит там 60 тиков.",
            "Присоски остаются на месте, что бы ни случилось с телом. Ткань вправе порваться, но ничего не взрывается; движение затухает.",
            CRASH_TICKS, YANK) {
            val b = body(shape, 0.0, 0.0)
            at(5) { grip(b, cups, 1f) }
            at(20) { grab(b, top); dragTo(shape.x[top] + 5.0, shape.y[top] + 12.0) }
            at(80) { release() }
            expectHeld(b, cups, 6, CRASH_TICKS)
        }
        for (hit in listOf(BUMP, HIT, RAM, CRASH)) {
            val ticks = if (hit.rough) CRASH_TICKS else settle(hit.speed)
            family.add("cup/hit/${shape.id}/${hit.id}", "${shape.name} на присосках: ${hit.title} сбоку",
                "Нижний край тела — присоски с хваткой 1. Слева в тело летит круг со скоростью ${hit.speed} связи за тик.",
                "Присоски остаются на месте: тело не может уйти от удара и принимает его целиком. Клетки не оказываются в чужой ткани. " + hit.expect,
                ticks, hit.limits.copy(energy = Energy.DRIVEN)) {
                val ram = Shapes.disc(3)
                val apart = separation(ram, shape, CONTACT_DISTANCE) + START_GAP
                val b = body(shape, 0.0, 0.0)
                val r = body(ram, -apart, 0.0)
                grip(b, cups, 1f)
                kick(r, hit.speed, 0.0)
                expectMet(r, b, 60, hit.speed)
                expectHeld(b, cups, 1, ticks)
            }
        }
    }

    // Червь, приклеенный обоими концами: мышцам сокращаться некуда.
    val worms = listOf(Shapes.rect(24, 4).muscleAll(), Shapes.rect(16, 6).muscleAll(), Shapes.rect(30, 3).muscleAll(), Shapes.rect(24, 6).muscleTopBottom(), Shapes.rect(24, 4).muscleSegments(6))
    for (worm in worms) {
        val ends = edgeCells(worm, -1.0, 0.0, 1.1) + edgeCells(worm, 1.0, 0.0, 1.1)
        for (drive in listOf(SQUEEZE, PUMP, FLICKER, SWAP)) {
            if (drive === SWAP && worm.muscleGroups < 2) continue
            val ticks = drive.active + settle(0.5)
            family.add("cup/anchored/${worm.id}/${drive.id}", "${worm.name}, приклеен обоими концами: ${drive.title}",
                "Оба конца тела — присоски с хваткой 1. " + drive.about,
                "Мышцы тянут тело, которому некуда сократиться, — и не рвут его: ни одного разрыва и ни одной погибшей клетки. Присоски не сдвигаются. " +
                    "После расслабления тело возвращает форму и засыпает.", ticks, if (drive === FLICKER) FLICKERING else Limits.DRIVEN) {
                val b = body(worm, 0.0, 0.0)
                at(5) { grip(b, ends, 1f) }
                drive.script(this, b)
                expectHeld(b, ends, 6, ticks)
            }
        }
        // Один конец приклеен, второй свободен: тело стягивается к присоске.
        val tail = edgeCells(worm, -1.0, 0.0, 1.1)
        family.add("cup/one-end/${worm.id}", "${worm.name}, приклеен одним концом: сжатие целиком",
            "Левый конец тела — присоски с хваткой 1, правый свободен. Все мышцы сокращаются до предела, держат и отпускают.",
            "Тело стягивается к присоске: правый конец подходит к левому хотя бы на четверть длины. Присоски не сдвигаются, ткань не рвётся; после расслабления тело возвращает форму и засыпает.",
            SQUEEZE.active + settle(0.5), Limits.DRIVEN) {
            val b = body(worm, 0.0, 0.0)
            at(5) { grip(b, tail, 1f) }
            SQUEEZE.script(this, b)
            expectHeld(b, tail, 6, SQUEEZE.active + settle(0.5))
            val a = worm.extreme(-1.0, 0.0); val c = worm.extreme(1.0, 0.0)
            val rest = worm.x[c] - worm.x[a]
            probe("длина к исходной") { distance(b, a, c) / rest }
            if (worm.muscleGroups == 1) expectMinAtMost("тело стянулось к присоске", "длина к исходной", 20, SQUEEZE.active, 0.75)
        }
    }

    // Шаг гусеницы: присоски по очереди держат концы, мышцы сокращают и распускают тело.
    for (worm in listOf(Shapes.rect(24, 4).muscleAll(), Shapes.rect(16, 6).muscleAll(), Shapes.rect(30, 3).muscleAll())) {
        val back = edgeCells(worm, -1.0, 0.0, 1.1); val front = edgeCells(worm, 1.0, 0.0, 1.1)
        val cycles = 6; val period = 80
        family.add("cup/inchworm/${worm.id}", "${worm.name}: шаг гусеницы на присосках",
            "Шесть шагов по 80 тиков: передние присоски держат — мышцы сокращают тело и подтягивают хвост; затем держат задние — мышцы отпускают, и голова уходит вперёд.",
            "Тело идёт вперёд хотя бы на две связи за шесть шагов. Присоска, которая держит, не проскальзывает; мышцы не рвут ткань; в конце всё отпущено, тело возвращает форму и засыпает.",
            20 + cycles * period + settle(0.5), Limits.DRIVEN) {
            val b = body(worm, 0.0, 0.0)
            for (k in 0 until cycles) {
                at(20 + k * period) { grip(b, front, 1f); grip(b, back, 0f); muscles(b, PULL) }
                at(20 + k * period + period / 2) { grip(b, front, 0f); grip(b, back, 1f); muscles(b, LOOSE) }
            }
            at(20 + cycles * period) { grip(b, front, 0f); grip(b, back, 0f); muscles(b, REST) }
            val start = centerOf(b)
            probe("путь тела вперёд, связей") { (centerOf(b)[0] - start[0]) / GAME_LINK }
            expectMaxAtLeast("гусеница прошла вперёд", "путь тела вперёд, связей", 20, 20 + cycles * period, 2.0)
        }
    }

    // Неполная хватка: тело на присосках ползёт за курсором.
    for ((id, g) in listOf("g50" to 0.5f, "g90" to 0.9f, "g99" to 0.99f)) {
        val shape = Shapes.rect(16, 6)
        val cups = edgeCells(shape, 0.0, -1.0)
        family.add("cup/slip/$id", "брусок на присосках с хваткой $g: мышь тянет его вбок",
            "Нижний край бруска — присоски с хваткой $g. Мышь берёт правую клетку и плавно уводит её на шесть единиц вправо, затем отпускает.",
            "Присоски проскальзывают тем медленнее, чем сильнее хватка; тело тянется, но не рвётся и не дрожит; после отпускания останавливается и засыпает.",
            300 + settle(0.5), Limits.DRIVEN.copy(name = "внешняя сила, без повреждений; форма не судится", shape = false)) {
            val b = body(shape, 0.0, 0.0)
            at(5) { grip(b, cups, g) }
            val cell = shape.extreme(1.0, 0.0)
            val sx = shape.x[cell]; val sy = shape.y[cell]
            mousePath(b, cell, 20, 3, (0..70).map { doubleArrayOf(sx + 6.0 * it / 70.0, sy) })
            at(250) { grip(b, cups, 0f) }
        }
    }
    family.add("cup/blink", "брусок на присосках, хватка мигает, мышь тянет",
        "Нижний край бруска — присоски; их хватка каждые три тика переключается между 1 и 0. Мышь плавно уводит правую клетку на шесть единиц вправо.",
        "Тело идёт за курсором рывками, как ему велит хватка, но не рвётся, не взрывается и не входит в себя; после отпускания останавливается и засыпает.",
        300 + settle(0.5), Limits.DRIVEN.copy(name = "внешняя сила, без повреждений; рывки задаёт сценарий", shape = false, jitter = false)) {
        val shape = Shapes.rect(16, 6)
        val cups = edgeCells(shape, 0.0, -1.0)
        val b = body(shape, 0.0, 0.0)
        during(5, 250) { t -> grip(b, cups, if ((t / 3) % 2 == 0) 1f else 0f) }
        at(251) { grip(b, cups, 0f) }
        val cell = shape.extreme(1.0, 0.0)
        val sx = shape.x[cell]; val sy = shape.y[cell]
        mousePath(b, cell, 20, 3, (0..70).map { doubleArrayOf(sx + 6.0 * it / 70.0, sy) })
    }

    // Присоска на кости: кость держится одной клеткой, её тянут за другую.
    for (shape in listOf(Shapes.square(8).boneAll(), Shapes.rect(12, 4).boneAll(), Shapes.square(10).boneCore())) {
        val cluster = shape.boneClusters().maxByOrNull { it.size } ?: continue
        val held = cluster.minByOrNull { shape.lx[it] + shape.ly[it] } ?: continue
        val pulled = cluster.maxByOrNull { shape.lx[it] + shape.ly[it] } ?: continue
        family.add("cup/bone/${shape.id}", "${shape.name}: костная клетка — присоска, кость тянут за другой угол",
            "Одна клетка кости держится за дно хваткой 1. Мышь берёт клетку той же кости в дальнем углу и плавно уводит её на четыре единицы, затем отпускает.",
            "Кость не мнётся: либо стоит на месте вся, либо поворачивается вокруг присоски, но расстояния между её клетками прежние. Ничего не рвётся; после отпускания всё замирает и засыпает.",
            300 + settle(0.5), Limits.DRIVEN) {
            val b = body(shape, 0.0, 0.0)
            at(5) { grip(b, held, 1f) }
            val sx = shape.x[pulled]; val sy = shape.y[pulled]
            mousePath(b, pulled, 20, 3, (0..60).map { doubleArrayOf(sx + 4.0 * it / 60.0, sy + 1.5 * it / 60.0) })
            at(250) { grip(b, held, 0f) }
        }
    }

    // Рост от приклеенной зиготы.
    for (shape in listOf(Shapes.disc(5), Shapes.rect(16, 4), Shapes.square(10).boneCore())) for (pace in listOf(SLOW, LAYERS)) {
        val seed = shape.extreme(-1.0, 0.0)
        family.add("cup/grow/${shape.id}/${pace.id}", "${shape.name}: рост от приклеенной зиготы, ${pace.name}",
            "Зигота — крайняя левая клетка тела — держится за дно хваткой 1; тело растёт от неё.",
            "Зигота остаётся на месте, тело вырастает целиком и в задуманную форму без разрывов и гибели клеток; после роста останавливается и засыпает.",
            pace.ticks(shape, seed) + settle(0.3), GROW) {
            val b = growing(shape, 0.0, 0.0, seed = seed, period = pace.period, batch = pace.batch)
            grip(b, seed, 1f)
            expectStill("зигота ушла с места, связей", b, intArrayOf(seed), 0, pace.ticks(shape, seed) + settle(0.3), 0.02)
        }
    }
    return family.list
}
