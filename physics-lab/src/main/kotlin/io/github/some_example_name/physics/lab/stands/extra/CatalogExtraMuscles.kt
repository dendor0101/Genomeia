package io.github.some_example_name.physics.lab.stands

import kotlin.math.abs

/*
 * СЕМЕЙСТВО «МЫШЦЫ В ТРУДНЫХ УСЛОВИЯХ». Обычные мышечные сцены сокращают тело в пустоте. Здесь
 * мышцам тесно и мешают: кольцо давит на тело внутри своей дыры, клешня зажимает шарик, червь
 * упирается торцом в стену или сидит в коридоре, два мышечных тела толкают друг друга в
 * противофазе, мышь тянет тело против сокращения, удар приходит в разные моменты сокращения,
 * половина мышц погибла, часть тела зажата зажимом, команды меняются ступеньками или выходят за
 * пределы 0.5–1.5, удержания длятся тысячи тиков, сгибатель и разгибатель борются, тонкая лента и
 * нить, костное ядро под ударом, змейка у рельефа.
 *
 * Правило одно на всё семейство: мышца не рвёт собственную ткань, и ни своя, ни чужая клетка не
 * проходит сквозь ткань. Мышцы вносят энергию (Energy.DRIVEN), дёрганье — FLICKERING, удары —
 * Limits.hit(скорость) с энергией от мышц. Команда мышце — Float: 0.5 сокращение, 1 покой, 1.5 расслабление.
 */

private const val PULL = 0.5f
private const val REST = 1f
private const val LOOSE = 1.5f

/** Проба «насколько тело изменило форму»: отклонение расстояния между крайними клетками от исходного, в долях. */
private fun StandWorld.bodyChange(name: String, b: StandBody) {
    val s = b.shape
    val a = s.extreme(-1.0, 0.0); val c = s.extreme(1.0, 0.0)
    val rest = Math.hypot(s.x[a] - s.x[c], s.y[a] - s.y[c])
    probe(name) { abs(distance(b, a, c) / rest - 1.0) }
}

/** Команда мышцам ступеньками: с тика [start] каждые два тика команда идёт от [from] к [to] за [steps] шагов. */
private fun StandWorld.stepped(b: StandBody, start: Int, from: Float, to: Float, steps: Int) {
    for (k in 0..steps) at(start + 2 * k) { muscles(b, from + (to - from) * k / steps) }
}

/** Бегущая волна: группы мышц сокращаются по очереди, каждая на [period] тиков, с тика [from] до [to]. */
private fun StandWorld.waveRun(b: StandBody, from: Int, to: Int, period: Int) {
    val n = b.shape.muscleGroups
    during(from, to) { t ->
        val head = ((t - from) / period) % n
        for (g in 0 until n) muscle(b, g, if (g == head || g == (head + 1) % n) PULL else REST)
    }
}

/** Два одинаковых тела в ряд с зазором START_GAP: левое стоит в отрицательной половине оси X. */
private fun StandWorld.pairSide(shape: ShapeSpec): Pair<StandBody, StandBody> {
    val apart = separation(shape, shape, CONTACT_DISTANCE) + START_GAP
    return body(shape, -apart / 2, 0.0) to body(shape, apart / 2, 0.0)
}

/** Снаряд [ram] слева от тела [target] (стоящего в начале координат) на зазоре START_GAP. Скорость ему даёт сценарий. */
private fun StandWorld.striker(target: StandBody, ram: ShapeSpec): StandBody {
    val apart = separation(ram, target.shape, CONTACT_DISTANCE) + START_GAP
    return body(ram, -apart, 0.0)
}

/** Тело летит в стену справа: стена ставится у торца с зазором START_GAP, тело подталкивают к ней на 0.3 связи за тик. */
private fun StandWorld.toWall(shape: ShapeSpec, wall: Layout): StandBody {
    val gap = shape.halfWidth + GAME_RADIUS + START_GAP + TERRAIN_RADIUS + TERRAIN_JITTER / 2
    place(wall, gap)
    val b = body(shape, approach(shape, 0.0, 0.0, 0.0, 1.0, 0.0), 0.0)
    kick(b, 0.3, 0.0)
    return b
}

/** Тело в коридоре: плиты рельефа ставятся от x = 0, тело — посередине прохода (x = 6). */
private fun StandWorld.inCorridor(shape: ShapeSpec, corridor: Layout): StandBody {
    place(corridor, 0.0)
    // Червь в коридоре плит не касается по постановке: условие сцены — что мышцы его согнули.
    return body(shape, 6.0, 0.0).also { mustDeform(it, 0.03, "мышцы изменили форму червя в коридоре") }
}

/** Клешня сжимает тело [inner], стоящее в её просвете на высоте [y]: верхний слой основания тянет, нижний отпускает. */
private fun StandWorld.clawSqueeze(claw: ShapeSpec, inner: ShapeSpec, y: Double) {
    val b = body(claw, 0.0, 0.0)
    val c = body(inner, 0.0, y)
    at(20) { muscle(b, 0, PULL); muscle(b, 1, LOOSE) }
    at(150) { muscles(b, REST) }
    probe("зазор клешни и тела, связей") { clearance(b, c) / GAME_LINK }
    expectMinAtMost("зубцы подошли к телу", "зазор клешни и тела, связей", 20, 150, 0.4)
}

/**
 * Оба тела сжимаются мышцами, сходятся сжатыми и расправляются, упираясь друг в друга. Прежняя
 * постановка («левое сжимается, правое расслабляется, и они толкаются») ничего не проверяла:
 * сжатое тело от соседа только отходит, а расслабленная мышца сама тело не раздвигает — тела
 * не встречались вовсе (15.11: зазор не падал ниже двух с половиной связей).
 */
private fun StandWorld.duoScript(a: StandBody, c: StandBody) {
    at(5) { muscles(a, PULL); muscles(c, PULL) }
    at(40) { kick(a, 0.6, 0.0); kick(c, -0.6, 0.0) }
    at(120) { muscles(a, REST); muscles(c, REST) }
    expectMet(a, c, 220, 1.2)
}

internal fun extraMusclesFamily(): List<Scenario> {
    val family = Family("16. Доп.: мышцы в трудных условиях")
    val wall = terrainLayouts().first { it.id == "wall" }
    val corridor = terrainLayouts().first { it.id == "corridor" }
    val spikes = terrainLayouts().first { it.id == "spikes" }
    val ledge = terrainLayouts().first { it.id == "ledge" }

    // --- мышечное кольцо сжимает тело внутри своей дыры ---
    // Дыра ring(outer, inner): inner связей по 0.6 единицы, у ring(9,6) это радиус 3.6. Тела внутри
    // меньше дыры с запасом: круг радиуса 2 — около 1.7 с клетками, квадрат 3 — около 1.4,
    // квадрат 2 — около 0.9 в дыре 2.4 у ring(7,4).
    val rings = listOf(
        Triple("ring9-6/disc2", Shapes.ring(9, 6), Shapes.disc(2)),
        Triple("ring9-6/brick3", Shapes.ring(9, 6), Shapes.square(3).boneAll()),
        Triple("ring7-4/brick2", Shapes.ring(7, 4), Shapes.disc(1).boneAll()),
        Triple("ring11-8/disc2", Shapes.ring(11, 8), Shapes.disc(2)),
    )
    for ((label, ring, inner) in rings) {
        val ticks = 150 + settle(0.5)
        family.add("xmus/ring/$label", "кольцо $label: сжимает тело внутри себя",
            "Внутри мышечного кольца стоит тело, не касаясь его. Кольцо сокращается до предела с тика 20 до 150 и отпускает.",
            "Кольцо давит внутрь, но ни одна клетка не проходит сквозь ткань: ни кольцо не рвётся, ни тело внутри не входит в кольцо. " +
                "После расслабления тело возвращает форму и засыпает.",
            ticks, Limits.DRIVEN) {
            val outer = body(ring.muscleAll(), 0.0, 0.0)
            val center = body(inner, 0.0, 0.0)
            at(20) { muscles(outer, PULL) }
            at(150) { muscles(outer, REST) }
            probe("зазор кольца и тела, связей") { clearance(outer, center) / GAME_LINK }
            expectMinAtMost("кольцо подошло к телу", "зазор кольца и тела, связей", 20, 150, 1.0)
        }
    }
    // Долгое сжатие кольца: центр кольца не должен ползти, тело внутри — не рваться.
    family.add("xmus/ring/long", "кольцо 9/6: держит тело внутри две тысячи тиков",
        "Кольцо 9/6 сжимается до предела с тика 20 и держит 2000 тиков; внутри стоит круг радиуса 2. Затем отпускает.",
        "Ни кольцо, ни круг не рвутся и не проникают друг в друга; центр кольца не ползёт; после расслабления тело возвращает форму и засыпает.",
        2000 + settle(0.1), Limits.DRIVEN) {
        val outer = body(Shapes.ring(9, 6).muscleAll(), 0.0, 0.0)
        val center = body(Shapes.disc(2), 0.0, 0.0)
        var start: DoubleArray? = null
        at(20) { muscles(outer, PULL); start = centerOf(outer) }
        at(2000) { muscles(outer, REST) }
        probe("центр кольца ушёл от места, связей") {
            val s = start
            if (s == null) 0.0 else {
                val c = centerOf(outer)
                Math.hypot(c[0] - s[0], c[1] - s[1]) / GAME_LINK
            }
        }
        probe("зазор кольца и тела, связей") { clearance(outer, center) / GAME_LINK }
        expectMaxAtMost("центр кольца не ползёт", "центр кольца ушёл от места, связей", 20, 2000, 1.0)
        expectMinAtMost("кольцо подошло к телу", "зазор кольца и тела, связей", 20, 2000, 1.0)
    }

    // --- клешня: зубцы сжимают шарик или малое тело в своём просвете ---
    // Просвет uShape(14,12,4): свободен при |x| < 2.1 и y выше -1.5, поэтому кирпич 1.2 поперёк уместится.
    val claw = clawShape()
    val clawTicks = 150 + settle(0.5)
    family.add("xmus/claw/ball", "клешня: сжимает шарик между зубцами",
        "Клешня стоит, свободный шарик лежит в её просвете. Верхний слой основания тянет, нижний отпускает: зубцы сходятся на 150 тиков.",
        "Шарик вправе выскользнуть из просвета наружу, но не проходит сквозь зубец; клешня не рвётся; после расслабления форма возвращается и всё засыпает.",
        clawTicks, Limits.DRIVEN) {
        val b = body(claw, 0.0, 0.0)
        val id = ball(0.0, 1.0)
        at(20) { muscle(b, 0, PULL); muscle(b, 1, LOOSE) }
        at(150) { muscles(b, REST) }
        probe("шарик от центра клешни, связей") {
            val p = world.freeParticle(id)
            Math.hypot(gameX(p), gameY(p) - centerOf(b)[1]) / GAME_LINK
        }
        // Ожидания «шарик остался в клешне» нет: зажатая с двух сторон частица законно выскальзывает наружу, как
        // сжатая пальцами косточка. Сквозь зубец она пройти не вправе — это ловит правило монитора.
    }
    family.add("xmus/claw/brick", "клешня: сжимает костный кирпич в просвете",
        "Костный кирпич 3×3 стоит в просвете клешни. Верхний слой основания тянет, нижний отпускает: зубцы сходятся на 150 тиков.",
        "Кирпич не проходит сквозь зубцы и не мнётся; зубцы касаются его; клешня не рвётся; после расслабления всё засыпает.",
        clawTicks, Limits.DRIVEN) {
        clawSqueeze(claw, Shapes.square(3).boneAll(), 0.8)
    }
    family.add("xmus/claw/soft2", "клешня: сжимает малый мягкий квадрат в просвете",
        "Мягкий квадрат 2×2 стоит в просвете клешни. Верхний слой основания тянет, нижний отпускает: зубцы сходятся на 150 тиков.",
        "Квадрат не проходит сквозь зубцы; клешня не рвётся; после расслабления форма возвращается и всё засыпает.",
        clawTicks, Limits.DRIVEN) {
        clawSqueeze(claw, Shapes.disc(1), 0.8)
    }
    family.add("xmus/claw/brick-ramp", "клешня: кирпич в просвете, команды ступеньками",
        "Команды основания меняются ступеньками по 0.05 каждые два тика: от 1.0 до 0.5 и обратно. Кирпич стоит в просвете.",
        "Зубцы сходятся плавно; кирпич не проходит сквозь них; клешня не рвётся; после покоя всё засыпает.",
        clawTicks, Limits.DRIVEN) {
        val b = body(claw, 0.0, 0.0)
        body(Shapes.square(3).boneAll(), 0.0, 0.8)
        stepped(b, 20, 1f, 0.5f, 10)
        stepped(b, 140, 0.5f, 1f, 10)
        bodyChange("изменение формы клешни", b)
        expectMaxAtLeast("клешня сжималась", "изменение формы клешни", 20, 160, 0.03)
    }

    // --- червь упирается торцом в стену рельефа и сокращается-расслабляется ---
    val wallTicks = 200 + settle(0.5)
    family.add("xmus/wall/rect16-4", "червь 16×4 у стены: все мышцы сокращаются",
        "Червь летит торцом в ровную стену и упирается в неё. С тика 60 до 200 все мышцы сокращаются, затем отпускают.",
        "Торец упирается в стену и не входит в неё; мышцы тянут червя от стены, не рвя ткань; после расслабления форма возвращается и всё засыпает.",
        wallTicks, Limits.DRIVEN) {
        val b = toWall(Shapes.rect(16, 4).muscleAll(), wall)
        expectReached(b, wallTicks)
        at(60) { muscles(b, PULL) }
        at(200) { muscles(b, REST) }
        bodyChange("изменение формы", b)
        expectMaxAtLeast("мышцы изменили форму", "изменение формы", 60, 200, 0.03)
    }
    family.add("xmus/wall/rect24-4-flicker", "червь 24×4 у стены: дёрганье мышц",
        "Червь упёрся торцом в стену. С тика 60 до 200 мышцы переключаются между сокращением и расслаблением каждые два тика.",
        "Торец держится у стены и не входит в неё; червь дрожит по команде, но не рвётся; после покоя возвращает форму и засыпает.",
        201 + settle(0.5), FLICKERING) {
        val b = toWall(Shapes.rect(24, 4).muscleAll(), wall)
        expectReached(b, 201 + settle(0.5))
        during(60, 200) { t -> muscles(b, if ((t / 2) % 2 == 0) PULL else LOOSE) }
        at(201) { muscles(b, REST) }
    }
    family.add("xmus/wall/rect20-6-swap", "червь 20×6 у стены: верх тянет, низ отпущен, затем наоборот",
        "Червь упёрся торцом в стену. С тика 60 верхняя половина сокращается, нижняя отпущена; с тика 200 наоборот; с тика 340 покой.",
        "Червь гнётся, торец остаётся у стены и не входит в неё; ткань не рвётся; после покоя возвращает форму и засыпает.",
        340 + settle(0.5), Limits.DRIVEN) {
        val b = toWall(Shapes.rect(20, 6).muscleTopBottom(), wall)
        expectReached(b, 340 + settle(0.5))
        at(60) { muscle(b, 0, PULL); muscle(b, 1, LOOSE) }
        at(200) { muscle(b, 0, LOOSE); muscle(b, 1, PULL) }
        at(340) { muscles(b, REST) }
        bodyChange("изменение формы", b)
        expectMaxAtLeast("червь согнулся", "изменение формы", 60, 340, 0.03)
    }
    family.add("xmus/wall/disc6-ramp", "круг радиуса 6 у стены: команды ступеньками",
        "Круг упёрся в стену. Команды мышцам идут ступеньками по 0.05 каждые два тика от 1.0 до 0.5 с тика 60 и обратно с тика 140.",
        "Круг плавно сжимается и так же плавно возвращается; в стену не входит, не рвётся; после покоя засыпает.",
        160 + settle(0.5), Limits.DRIVEN) {
        val b = toWall(Shapes.disc(6).muscleAll(), wall)
        expectReached(b, 160 + settle(0.5))
        stepped(b, 60, 1f, 0.5f, 10)
        stepped(b, 140, 0.5f, 1f, 10)
        bodyChange("изменение формы", b)
        expectMaxAtLeast("круг сжимался", "изменение формы", 60, 160, 0.03)
    }

    // --- червь между двумя плитами рельефа: тесный коридор ---
    val corridorTicks = 280 + settle(0.5)
    family.add("xmus/corridor/rect12-3-pump", "червь 12×3 в коридоре: качание",
        "Червь стоит посередине коридора шириной 4 единицы между двумя плитами. Три раза подряд сокращается и расслабляется.",
        "Червь не касается плит и не входит в них; ткань не рвётся; коридор его не ломает; после покоя возвращает форму и засыпает.",
        corridorTicks, Limits.DRIVEN) {
        val b = inCorridor(Shapes.rect(12, 3).muscleAll(), corridor)
        for (k in 0 until 3) { at(40 + k * 80) { muscles(b, PULL) }; at(80 + k * 80) { muscles(b, LOOSE) } }
        at(280) { muscles(b, REST) }
        bodyChange("изменение формы", b)
        expectMaxAtLeast("червь качался", "изменение формы", 40, 280, 0.03)
    }
    family.add("xmus/corridor/rect12-3-swap", "червь 12×3 в коридоре: верх и низ по очереди",
        "Верхняя половина тянет, нижняя отпущена — червь прижимается к одной плите; затем наоборот.",
        "Червь гнётся внутри коридора, не выходит в плиту и не рвётся; после покоя возвращает форму и засыпает.",
        corridorTicks, Limits.DRIVEN) {
        val b = inCorridor(Shapes.rect(12, 3).muscleTopBottom(), corridor)
        at(40) { muscle(b, 0, PULL); muscle(b, 1, LOOSE) }
        at(140) { muscle(b, 0, LOOSE); muscle(b, 1, PULL) }
        at(240) { muscles(b, REST) }
        bodyChange("изменение формы", b)
        expectMaxAtLeast("червь гнулся", "изменение формы", 40, 240, 0.03)
    }
    family.add("xmus/corridor/rect10-3-wave", "червь 10×3 в коридоре: бегущая волна",
        "Пять сегментов по очереди сокращаются вдоль коридора с тика 20 до 260.",
        "Волна бежит по телу, червь не входит в плиты; ткань не рвётся; после покоя возвращает форму и засыпает.",
        260 + settle(0.5), Limits.DRIVEN) {
        val b = inCorridor(Shapes.rect(10, 3).muscleSegments(5), corridor)
        waveRun(b, 20, 260, 20)
        bodyChange("изменение формы", b)
        expectMaxAtLeast("волна прошла", "изменение формы", 20, 260, 0.03)
    }
    family.add("xmus/corridor/rect12-3-deadend", "червь 12×3 в тупике коридора: толчок и сжатие",
        "Червя толкают к тупику коридора (0.4 связи за тик), он упирается в конец; с тика 60 до 200 все мышцы сокращаются.",
        "Червь упирается в тупик и не входит в него; мышцы сжимают его, не рвя ткань; после покоя возвращает форму и засыпает.",
        200 + settle(0.5), Limits.DRIVEN) {
        val b = inCorridor(Shapes.rect(12, 3).muscleAll(), corridor)
        at(20) { kick(b, 0.4, 0.0) }
        expectReached(b, 200 + settle(0.5))
        at(60) { muscles(b, PULL) }
        at(200) { muscles(b, REST) }
        bodyChange("изменение формы", b)
        expectMaxAtLeast("червь сжался в тупике", "изменение формы", 60, 200, 0.03)
    }

    // --- два мышечных тела вплотную толкают друг друга в противофазе ---
    val pairTicks = 220 + settle(0.5)
    family.add("xmus/duo/rect12-4", "два червя 12×4: сжатые сходятся и расправляются друг в друга",
        "Два одинаковых мышечных тела стоят в трёх связях друг от друга. На тике 5 оба сжимаются до предела, на тике 40 летят навстречу со скоростью 0.6 связи за тик и встречаются сжатыми; на тике 120 мышцы отпускают, и тела расправляются, упираясь друг в друга.",
        "Расправляющаяся ткань расталкивает соседа, а не входит в него: клетки не оказываются в чужой ткани, ничего не рвётся; после покоя тела возвращают форму и засыпают.",
        pairTicks, Limits.DRIVEN) {
        val (a, c) = pairSide(Shapes.rect(12, 4).muscleAll())
        duoScript(a, c)
        bodyChange("изменение формы левого", a)
        expectMaxAtLeast("левый менял форму", "изменение формы левого", 20, 220, 0.03)
    }
    family.add("xmus/duo/disc5", "два круга радиуса 5: сжатые сходятся и расправляются друг в друга",
        "Два одинаковых мышечных тела стоят в трёх связях друг от друга. На тике 5 оба сжимаются до предела, на тике 40 летят навстречу со скоростью 0.6 связи за тик и встречаются сжатыми; на тике 120 мышцы отпускают, и тела расправляются, упираясь друг в друга.",
        "Расправляющаяся ткань расталкивает соседа, а не входит в него: клетки не оказываются в чужой ткани, ничего не рвётся; после покоя тела возвращают форму и засыпают.",
        pairTicks, Limits.DRIVEN) {
        val (a, c) = pairSide(Shapes.disc(5).muscleAll())
        duoScript(a, c)
        bodyChange("изменение формы левого", a)
        expectMaxAtLeast("левый менял форму", "изменение формы левого", 20, 220, 0.03)
    }
    family.add("xmus/duo/square8-core", "два квадрата 8 с костным ядром: сжатые сходятся и расправляются друг в друга",
        "Два одинаковых мышечных тела стоят в трёх связях друг от друга. На тике 5 оба сжимаются до предела, на тике 40 летят навстречу со скоростью 0.6 связи за тик и встречаются сжатыми; на тике 120 мышцы отпускают, и тела расправляются, упираясь друг в друга.",
        "Расправляющаяся ткань расталкивает соседа, а не входит в него: клетки не оказываются в чужой ткани, ничего не рвётся; после покоя тела возвращают форму и засыпают. Кость в ядре не мнётся.",
        pairTicks, Limits.DRIVEN) {
        val (a, c) = pairSide(Shapes.square(8).boneCore().muscleAll())
        duoScript(a, c)
        bodyChange("изменение формы левого", a)
        expectMaxAtLeast("левый менял форму", "изменение формы левого", 20, 220, 0.03)
    }

    // --- мышь тянет тело против сокращения ---
    family.add("xmus/mouse/rect16-4-pull", "червь 16×4: мышцы сокращены, мышь тянет вправо",
        "Все мышцы сокращаются с тика 20 до 200. Мышь берёт правую клетку и плавно уводит её на пять единиц вправо, против сокращения.",
        "Тело тянется за курсором и не рвётся; мышцы работают и не рвут ткань; после отпускания возвращает форму и засыпает.",
        260 + settle(0.5), Limits.DRIVEN) {
        val shape = Shapes.rect(16, 4).muscleAll()
        val b = body(shape, 0.0, 0.0)
        val cell = shape.extreme(1.0, 0.0)
        val sx = shape.x[cell]; val sy = shape.y[cell]
        at(20) { muscles(b, PULL) }
        at(200) { muscles(b, REST) }
        mousePath(b, cell, 20, 3, (0..60).map { doubleArrayOf(sx + 5.0 * it / 60.0, sy) })
        expectTravelled("путь тела", b, IntArray(b.count) { it }, 20, 260, 1.0, "мышь протащила тело")
    }
    family.add("xmus/mouse/rect12-6-lr", "червь 12×6: левая половина сокращена, мышь тянет левый край влево",
        "Левая половина мышц сокращается, правая расслаблена, с тика 20 до 200. Мышь берёт левый край и плавно уводит его на четыре единицы влево.",
        "Тело тянется за курсором, сжатая часть не рвётся; после отпускания возвращает форму и засыпает.",
        260 + settle(0.5), Limits.DRIVEN) {
        val shape = Shapes.rect(12, 6).muscleLeftRight()
        val b = body(shape, 0.0, 0.0)
        val cell = shape.extreme(-1.0, 0.0)
        val sx = shape.x[cell]; val sy = shape.y[cell]
        at(20) { muscle(b, 0, PULL); muscle(b, 1, LOOSE) }
        at(200) { muscles(b, REST) }
        mousePath(b, cell, 20, 3, (0..60).map { doubleArrayOf(sx - 4.0 * it / 60.0, sy) })
        expectTravelled("путь тела", b, IntArray(b.count) { it }, 20, 260, 1.0, "мышь протащила тело")
    }
    family.add("xmus/mouse/disc5-loose", "круг радиуса 5: мышцы расслаблены, мышь тянет правый край влево",
        "Все мышцы расслаблены с тика 20 до 200. Мышь берёт правый край и уводит его на пять единиц влево.",
        "Тело растягивается под рукой, но не рвётся и не проходит сквозь себя; после отпускания возвращает форму и засыпает.",
        260 + settle(0.5), Limits.DRIVEN) {
        val shape = Shapes.disc(5).muscleAll()
        val b = body(shape, 0.0, 0.0)
        val cell = shape.extreme(1.0, 0.0)
        val sx = shape.x[cell]; val sy = shape.y[cell]
        at(20) { muscles(b, LOOSE) }
        at(200) { muscles(b, REST) }
        mousePath(b, cell, 20, 3, (0..60).map { doubleArrayOf(sx - 5.0 * it / 60.0, sy) })
        expectTravelled("путь тела", b, IntArray(b.count) { it }, 20, 260, 1.0, "мышь протащила тело")
    }

    // --- удар в разные моменты сокращения ---
    for (strike in listOf(10, 30, 50, 70)) {
        family.add("xmus/strike/rect16-6/t$strike", "червь 16×6: удар в тик $strike, сокращение с тика 20",
            "Червь сокращается с тика 20 до 120. В тик $strike в него летит круг радиуса 3 со скоростью 2 связи за тик: удар попадает в разную фазу сокращения.",
            "Удар приходит в разные моменты сокращения; ткань держит: чужой круг не проходит сквозь червя, клетки не оказываются в чужой ткани; после расслабления тело возвращает форму и засыпает.",
            120 + settle(0.5), Limits.hit(HIT.speed).copy(energy = Energy.DRIVEN)) {
            val target = body(Shapes.rect(16, 6).muscleAll(), 0.0, 0.0)
            val ram = striker(target, Shapes.disc(3))
            at(20) { muscles(target, PULL) }
            at(120) { muscles(target, REST) }
            at(strike) { kick(ram, HIT.speed, 0.0) }
            expectMet(ram, target, strike + 80, HIT.speed)
            bodyChange("изменение формы", target)
            expectMaxAtLeast("червь менял форму", "изменение формы", 20, 120, 0.03)
        }
    }

    // --- половина мышечных клеток погибла: оставшиеся работают дальше ---
    family.add("xmus/killed/rect16-6", "червь 16×6: половина мышц группы 0 погибла на тике 10",
        "На тике 10 погибает каждая вторая клетка первой группы мышц. С тика 20 группа 0 тянет, группа 1 отпущена; с тика 150 покой.",
        "Оставшиеся мышцы продолжают работать и сокращают тело; ткань не рвётся сверх погибших клеток; после расслабления тело возвращает форму и засыпает.",
        150 + settle(0.5), Limits.DRIVEN) {
        val shape = Shapes.rect(16, 6).muscleTopBottom()
        val b = body(shape, 0.0, 0.0)
        at(10) {
            val dead = (0 until b.count).filter { b.shape.muscle[it] == 0 && it % 2 == 1 }.toIntArray()
            killCells(b, dead)
        }
        at(20) { muscle(b, 0, PULL); muscle(b, 1, LOOSE) }
        at(150) { muscles(b, REST) }
        bodyChange("изменение формы", b)
        expectMaxAtLeast("оставшиеся мышцы работают", "изменение формы", 20, 150, 0.03)
    }
    family.add("xmus/killed/disc6-all", "круг радиуса 6: половина клеток погибла на тике 10",
        "На тике 10 погибает каждая вторая клетка круга, в том числе мышечная. С тика 20 все оставшиеся мышцы сокращаются, с тика 150 покой.",
        "Оставшиеся мышцы сокращают круг, гибели сверх задуманного нет; ткань не рвётся; после покоя круг возвращает форму и засыпает.",
        150 + settle(0.5), Limits.DRIVEN) {
        val b = body(Shapes.disc(6).muscleAll(), 0.0, 0.0)
        at(10) { killCells(b, (0 until b.count).filter { it % 2 == 0 }.toIntArray()) }
        at(20) { muscles(b, PULL) }
        at(150) { muscles(b, REST) }
        bodyChange("изменение формы", b)
        // У круга после гибели половины клеток осталась полукруглая мышца: она стягивает себя, но расстояние между
        // крайними клетками меняет слабее, чем червь (замер 15.11 — 2%), поэтому порог вдвое ниже.
        expectMaxAtLeast("оставшиеся мышцы работают", "изменение формы", 20, 150, 0.015)
    }

    // --- мышцы сокращаются на теле, часть которого зажата ---
    family.add("xmus/clamp/rect16-4-held", "червь 16×4: левый край зажат, мышцы сокращаются",
        "Левый край червя держит зажим намертво. С тика 20 до 160 все мышцы сокращаются.",
        "Зажатый край не сдвигается ни на сотую связи; правая часть тянется к зажиму, ткань не рвётся; после покоя возвращает форму и засыпает.",
        160 + settle(0.5), Limits.DRIVEN) {
        val shape = Shapes.rect(16, 4).muscleAll()
        val b = body(shape, 0.0, 0.0)
        val left = edgeCells(shape, -1.0, 0.0)
        at(5) { clamp(b, left) }
        at(20) { muscles(b, PULL) }
        at(160) { muscles(b, REST) }
        expectStill("зажим ушёл с места, связей", b, left, 6, 160 + settle(0.5), 0.02)
        bodyChange("изменение формы", b)
        expectMaxAtLeast("мышцы работают", "изменение формы", 20, 160, 0.03)
    }
    family.add("xmus/clamp/rect16-4-pulled", "червь 16×4: левый зажим держит, правый едет внутрь",
        "Левый край зажат намертво; правый зажим с тика 20 до 140 плавно уходит внутрь тела на 20% его длины. Мышцы сокращаются с тика 20 до 160.",
        "Правый край тянется зажимом, тело сжимается, но ткань не рвётся и клетки не входят в свою ткань; после отпускания возвращает форму и засыпает.",
        160 + settle(0.5), Limits.DRIVEN) {
        val shape = Shapes.rect(16, 4).muscleAll()
        val b = body(shape, 0.0, 0.0)
        val left = edgeCells(shape, -1.0, 0.0); val right = edgeCells(shape, 1.0, 0.0)
        val length = 2 * shape.halfWidth
        val l0 = centerOfCells(b, left); val r0 = centerOfCells(b, right)
        val rest = Math.hypot(r0[0] - l0[0], r0[1] - l0[1])
        var moving: StandWorld.Clamp? = null
        at(5) { clamp(b, left); moving = clamp(b, right) }
        during(20, 140) { t -> moving?.moveTo(-0.2 * length * ramp(t, 20, 140), 0.0) }
        at(20) { muscles(b, PULL) }
        at(160) { muscles(b, REST); moving?.release() }
        probe("между зажимами к исходному") {
            val l = centerOfCells(b, left); val r = centerOfCells(b, right)
            Math.hypot(r[0] - l[0], r[1] - l[1]) / rest
        }
        expectMinAtMost("зажим сжал тело", "между зажимами к исходному", 20, 160, 0.9)
    }

    // --- команды мышцам: ступеньки, резкий перелом ---
    family.add("xmus/cmd/ramp/rect16-4", "червь 16×4: команды ступеньками от 1.0 до 0.5 и обратно",
        "Команды мышцам меняются ступеньками по 0.05 каждые два тика: с тика 20 от 1.0 до 0.5, держится до 140, затем обратно до 1.0.",
        "Тело ведёт себя не хуже, чем при резкой команде: сжимается и возвращается плавно, без рывков, рвань и взрыва; после покоя засыпает.",
        160 + settle(0.5), Limits.DRIVEN) {
        val b = body(Shapes.rect(16, 4).muscleAll(), 0.0, 0.0)
        stepped(b, 20, 1f, 0.5f, 10)
        stepped(b, 140, 0.5f, 1f, 10)
        bodyChange("изменение формы", b)
        expectMaxAtLeast("тело сжалось", "изменение формы", 20, 160, 0.03)
    }
    family.add("xmus/cmd/ramp/disc6", "круг радиуса 6: команды ступеньками от 1.0 до 0.5 и обратно",
        "Те же ступеньки, что у червя, но на круге: с тика 20 от 1.0 до 0.5, с тика 140 обратно до 1.0.",
        "Круг сжимается и возвращается плавно, не рвёт ткань и не проходит сквозь себя; после покоя засыпает.",
        160 + settle(0.5), Limits.DRIVEN) {
        val b = body(Shapes.disc(6).muscleAll(), 0.0, 0.0)
        stepped(b, 20, 1f, 0.5f, 10)
        stepped(b, 140, 0.5f, 1f, 10)
        bodyChange("изменение формы", b)
        expectMaxAtLeast("круг сжался", "изменение формы", 20, 160, 0.03)
    }
    family.add("xmus/cmd/abrupt/rect16-4", "червь 16×4: команда 0.5 сразу, сразу отпускание",
        "Для сравнения с плавным ходом: команда 0.5 мгновенно с тика 20, мгновенный покой с тика 140.",
        "Резкая команда сжимает тело рывком; ткань не рвётся; после покоя тело возвращает форму и засыпает.",
        140 + settle(0.5), Limits.DRIVEN) {
        val b = body(Shapes.rect(16, 4).muscleAll(), 0.0, 0.0)
        at(20) { muscles(b, PULL) }
        at(140) { muscles(b, REST) }
        bodyChange("изменение формы", b)
        expectMaxAtLeast("тело сжалось", "изменение формы", 20, 140, 0.03)
    }

    // --- разные амплитуды команд: сжатие слабее и сильнее, растяжение до 1.5 ---
    val amplitudes = listOf("c09" to 0.9f, "c08" to 0.8f, "c07" to 0.7f, "c06" to 0.6f, "c05" to 0.5f, "c11" to 1.1f, "c13" to 1.3f, "c15" to 1.5f)
    for ((tag, cmd) in amplitudes) {
        family.add("xmus/amp/rect16-4/$tag", "червь 16×4: команда $cmd удерживается",
            "Все мышцы держат команду $cmd с тика 20 до 160, затем покой.",
            "Тело меняет форму пропорционально команде, без разрывов и гибели клеток; после покоя возвращает форму и засыпает.",
            160 + settle(0.5), Limits.DRIVEN) {
            val b = body(Shapes.rect(16, 4).muscleAll(), 0.0, 0.0)
            at(20) { muscles(b, cmd) }
            at(160) { muscles(b, REST) }
            if (cmd >= 1f) idle("расслабление: проверяется, что отпущенная мышца сама тело не меняет")
            bodyChange("изменение формы", b)
            // Команда больше единицы — расслабление: мышца становится податливой, но сама тело не раздвигает
            // (PhysicsWorld.setCellScale: «максимальная податливость на растяжение под внешней нагрузкой»).
            if (cmd < 1f) expectMaxAtLeast("тело изменило форму", "изменение формы", 20, 160, 0.02)
            else expectMaxAtMost("отпущенная мышца сама тело не меняет", "изменение формы", 20, 160, 0.02)
        }
    }

    // --- очень долгое удержание ---
    family.add("xmus/long/rect16-4", "червь 16×4: сокращение держится 2000 тиков",
        "Все мышцы сокращаются с тика 20 и держат 2000 тиков; затем покой.",
        "Тело не ползёт и не рвётся за две тысячи тиков; центр тела стоит на месте; после покоя возвращает форму и засыпает.",
        2000 + settle(0.1), Limits.DRIVEN) {
        val b = body(Shapes.rect(16, 4).muscleAll(), 0.0, 0.0)
        var start: DoubleArray? = null
        at(20) { muscles(b, PULL); start = centerOf(b) }
        at(2000) { muscles(b, REST) }
        probe("центр тела ушёл от места, связей") {
            val s = start
            if (s == null) 0.0 else {
                val c = centerOf(b)
                Math.hypot(c[0] - s[0], c[1] - s[1]) / GAME_LINK
            }
        }
        expectMaxAtMost("тело не ползёт", "центр тела ушёл от места, связей", 20, 2000, 1.0)
    }

    // --- сгибатель и разгибатель сокращаются одновременно и борются ---
    val fightTicks = 180 + settle(0.5)
    family.add("xmus/fight/arm30x7", "сустав 30×7: сгибатель и разгибатель сокращаются вместе",
        "Обе группы мышц сустава сокращаются с тика 20 до 180: сгибатель тянет вверх, разгибатель вниз, и они борются.",
        "Ни одна мышца не рвёт ткань; кости не мнутся; тело сжимается ровно и не гнётся сильнее, чем позволяет ткань; после покоя возвращает форму и засыпает.",
        fightTicks, Limits.DRIVEN) {
        val b = body(armShape(30, 7), 0.0, 0.0)
        at(20) { muscle(b, 0, PULL); muscle(b, 1, PULL) }
        at(180) { muscles(b, REST) }
        bodyChange("изменение формы", b)
        expectMaxAtLeast("тело сжалось", "изменение формы", 20, 180, 0.03)
    }
    family.add("xmus/fight/claw", "клешня: сгибатель и разгибатель сокращаются вместе",
        "Оба слоя основания клешни сокращаются с тика 20 до 180: зубцы тянутся в обе стороны сразу.",
        "Клешня не рвётся и не выворачивается; шарика в ней нет; после покоя возвращает форму и засыпает.",
        fightTicks, Limits.DRIVEN) {
        val b = body(claw, 0.0, 0.0)
        at(20) { muscle(b, 0, PULL); muscle(b, 1, PULL) }
        at(180) { muscles(b, REST) }
        bodyChange("изменение формы", b)
        expectMaxAtLeast("клешня сжалась", "изменение формы", 20, 180, 0.03)
    }
    family.add("xmus/fight/rect16-4-tb", "червь 16×4: верх и низ сокращаются вместе",
        "Верхняя и нижняя половины сокращаются одновременно с тика 20 до 180 и тянут друг друга.",
        "Ткань между половинами не рвётся и не складывается внутрь себя; после покоя возвращает форму и засыпает.",
        fightTicks, Limits.DRIVEN) {
        val b = body(Shapes.rect(16, 4).muscleTopBottom(), 0.0, 0.0)
        at(20) { muscle(b, 0, PULL); muscle(b, 1, PULL) }
        at(180) { muscles(b, REST) }
        bodyChange("изменение формы", b)
        expectMaxAtLeast("червь сжался", "изменение формы", 20, 180, 0.03)
    }
    family.add("xmus/fight/arm20x5-flicker", "сустав 20×5: сгибатель и разгибатель дёргаются вместе",
        "С тика 20 до 220 обе группы мышц сокращаются и расслабляются одновременно, переключаясь каждые два тика.",
        "Дрожь задаёт сценарий; ткань не рвётся, кости не мнутся; после покоя возвращает форму и засыпает.",
        221 + settle(0.5), FLICKERING) {
        val b = body(armShape(20, 5), 0.0, 0.0)
        during(20, 220) { t ->
            val c = if ((t / 2) % 2 == 0) PULL else LOOSE
            muscle(b, 0, c); muscle(b, 1, c)
        }
        at(221) { muscles(b, REST) }
        bodyChange("изменение формы", b)
        expectMaxAtLeast("сустав работал", "изменение формы", 20, 220, 0.03)
    }

    // --- мышцы на тонкой ленте и на нити ---
    family.add("xmus/thin/ribbon20", "лента 2×20: все мышцы сокращаются",
        "Лента в два ряда клеток сокращается целиком с тика 20 до 140.",
        "Тонкая ткань с треугольниками не выворачивается и не рвётся; после покоя возвращает форму и засыпает.",
        140 + settle(0.5), Limits.DRIVEN) {
        val b = body(Shapes.ribbon(20).muscleAll(), 0.0, 0.0)
        at(20) { muscles(b, PULL) }
        at(140) { muscles(b, REST) }
        bodyChange("изменение формы", b)
        expectMaxAtLeast("лента сжалась", "изменение формы", 20, 140, 0.03)
    }
    family.add("xmus/thin/ribbon24-swap", "лента 2×24: левая половина тянет, затем правая",
        "Левая половина ленты сокращается, правая отпущена с тика 20; с тика 150 наоборот; с тика 300 покой.",
        "Лента гнётся, треугольники не выворачиваются, ткань не рвётся; после покоя возвращает форму и засыпает.",
        300 + settle(0.5), Limits.DRIVEN) {
        val b = body(Shapes.ribbon(24).muscleLeftRight(), 0.0, 0.0)
        at(20) { muscle(b, 0, PULL); muscle(b, 1, LOOSE) }
        at(150) { muscle(b, 0, LOOSE); muscle(b, 1, PULL) }
        at(300) { muscles(b, REST) }
        bodyChange("изменение формы", b)
        expectMaxAtLeast("лента гнулась", "изменение формы", 20, 300, 0.03)
    }
    family.add("xmus/thin/rope16-pump", "нить из 16 клеток: три сокращения подряд",
        "Нить из одной цепочки клеток, без треугольников, мышцы сокращают её три раза по 40 тиков с паузами по 40 тиков.",
        "Нить держат только связи: она не рвётся сверх допуска и не растягивается дальше длины связи; после покоя возвращает форму и засыпает.",
        280 + settle(0.5), Limits.DRIVEN) {
        val b = body(Shapes.rope(16).muscleAll(), 0.0, 0.0)
        for (k in 0 until 3) { at(40 + k * 80) { muscles(b, PULL) }; at(80 + k * 80) { muscles(b, LOOSE) } }
        at(280) { muscles(b, REST) }
        bodyChange("изменение формы", b)
        expectMaxAtLeast("нить сжималась", "изменение формы", 40, 280, 0.03)
    }

    // --- мышечные тела с костным ядром под ударом в момент сокращения ---
    val boneBodies = listOf(
        Triple("square10-core", Shapes.square(10).boneCore(), "квадрат 10 с костным ядром"),
        Triple("disc6-shell", Shapes.disc(6).boneShell(), "круг 6 с костной оболочкой"),
    )
    for ((label, body0, title) in boneBodies) for (kickAt in listOf(20, 60)) {
        family.add("xmus/bonehit/$label/t$kickAt", "$title: удар в тик $kickAt сокращения",
            "Тело сокращается с тика 20 до 160. В тик $kickAt в него летит круг радиуса 3 со скоростью 2 связи за тик.",
            "Удар приходит в мышечное тело с костями во время сокращения: кости не мнутся, клетки не проходят сквозь ткань; после расслабления тело возвращает форму и засыпает.",
            160 + settle(HIT.speed), Limits.hit(HIT.speed).copy(energy = Energy.DRIVEN)) {
            val target = body(body0.muscleAll(), 0.0, 0.0)
            val ram = striker(target, Shapes.disc(3))
            at(20) { muscles(target, PULL) }
            at(160) { muscles(target, REST) }
            at(kickAt) { kick(ram, HIT.speed, 0.0) }
            expectMet(ram, target, kickAt + 100, HIT.speed)
        }
    }

    // --- змейка с волной по телу у рельефа ---
    for ((tag, layout) in listOf("wall" to wall, "spikes" to spikes, "ledge" to ledge)) {
        family.add("xmus/snake/$tag", "змейка 22: волна проходит рядом с рельефом «${layout.name}»",
            "Змейка стоит рядом с рельефом «${layout.name}», не касаясь его. Волна сокращений бежит по её сегментам с тика 20 до 260.",
            "Волна изгибает змейку, но она не входит в рельеф и не рвёт ткань; после покоя возвращает форму и засыпает.",
            260 + settle(0.5), Limits.DRIVEN) {
            val shape = Shapes.snake(22, 3, 3).muscleSegments(8)
            place(layout, shape.halfWidth + 2.5)
            val b = body(shape, 0.0, 0.0)
            waveRun(b, 20, 260, 20)
            mustDeform(b, 0.03, "волна изогнула змейку")
            bodyChange("изменение формы", b)
            expectMaxAtLeast("волна прошла по змейке", "изменение формы", 20, 260, 0.03)
        }
    }

    return family.list
}
