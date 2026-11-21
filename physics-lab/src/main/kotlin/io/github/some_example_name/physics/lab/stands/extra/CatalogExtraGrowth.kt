package io.github.some_example_name.physics.lab.stands

/*
 * СЕМЕЙСТВО «РОСТ В ТРУДНЫХ УСЛОВИЯХ». Обычный рост (семейство «8. Рост») проверяет тело в пустом
 * мире. Здесь тело растёт там, где ему мешают: под ударами, в дыре другого тела, в подкове из
 * рельефа, под мышью, при гибели своих клеток, в углу рамки, между неподвижными частицами, с конца
 * длинной нити, с кончика луча звезды, рядом со столкновениями, под толчками и на разных темпах.
 *
 * Что проверяется сверх общих правил монитора: родились все клетки (погибшие по сценарию тоже
 * считаются родившимися), кости включились, новорождённая ткань не вошла в рельеф и не вышла из
 * рамки, снаряд и соседнее тело действительно дошли до растущего тела (иначе сцена пройдёт, ничего
 * не проверив). Там, где монитор этого не видит, стоит своя проба.
 */

/** Удар в растущее тело: местный разрыв до 4% связей допустим, взрыва нет. Растущее тело формы не судится. */
private val XG_HIT = Limits.hit(2.0).copy(energy = Energy.DRIVEN, shape = false)

/** Очень быстрый рост: по четыре клетки за тик, тело собирается за считаные тики и не успевает принять форму. */
private val XG_FASTEST = Pace("fastest", "очень быстро: по четыре клетки за тик", 1, 4)

/** Очень медленный рост: по клетке раз в десять тиков, соседи успевают затихнуть между рождениями. */
private val XG_CRAWL = Pace("crawl", "очень медленно: по клетке раз в десять тиков", 10, 1)

private const val XG_GROWN = "Тело вырастает целиком и в задуманную форму: ни одного разрыва и ни одной погибшей клетки, ничего не взрывается. "
private const val XG_SETTLED = "После роста тело останавливается и засыпает."

/** Тики сцены: рост темпом [pace] от клетки [seed] и осадок после него. */
private fun xgTicks(shape: ShapeSpec, seed: Int, pace: Pace): Int =
    pace.ticks(shape, seed) + settle(0.3, !shape.hasTissue)

/** Сколько клеток тела уже родилось; погибшие тоже считаются родившимися. */
private fun StandWorld.xgBorn(b: StandBody): Int {
    val st = world.state
    var n = 0
    for (k in 0 until b.count) if (!st.cellUnborn[b.particle(k)]) n++
    return n
}

/** Сколько клеток тела родилось и при этом живо. */
private fun StandWorld.xgAlive(b: StandBody): Int {
    val st = world.state
    var n = 0
    for (k in 0 until b.count) {
        val p = b.particle(k)
        if (!st.cellDead[p] && !st.cellUnborn[p]) n++
    }
    return n
}

/** Ожидание «тело выросло целиком»: к концу сцены родились все клетки. */
private fun StandWorld.xgExpectBorn(b: StandBody, until: Int, name: String) {
    probe(name) { xgBorn(b).toDouble() }
    // Окно — вся сцена: число рождённых не убывает, а сцена, где всё уснуло, кончается раньше срока, и в окне из
    // двух последних тиков проб могло не оказаться вовсе (15.11: так «не выросли» сорок девять сцен, выросших целиком).
    expectMaxAtLeast("тело выросло целиком", name, 1, until, b.count - 0.5)
}

/** Ожидание «кости включились»: к концу сцены все костные клетки родились и стоят костью. */
private fun StandWorld.xgExpectBones(b: StandBody, until: Int, name: String) {
    probe(name) {
        val st = world.state
        var n = 0
        for (k in 0 until b.count) {
            val p = b.particle(k)
            if (!st.cellUnborn[p] && !st.cellDead[p] && world.isBone(p)) n++
        }
        n.toDouble()
    }
    expectMaxAtLeast("кости включились", name, 1, until, b.shape.boneCount - 0.5)
}

/** Средняя дистанция живых клеток тела от начала координат, в единицах игры. */
private fun StandWorld.xgMeanRadius(b: StandBody): Double {
    val st = world.state
    var s = 0.0
    var n = 0
    for (k in 0 until b.count) {
        val p = b.particle(k)
        if (st.cellDead[p]) continue
        s += Math.hypot(gameX(p), gameY(p))
        n++
    }
    return if (n == 0) 0.0 else s / n
}

/**
 * Проба «тела соприкоснулись»: наименьшее расстояние между живыми родившимися клетками двух тел,
 * в единицах игры. Клетки касаются при расстоянии около единицы (два радиуса); порог 1.3 с запасом.
 * Нерождённые клетки не считаются: они ещё не стоят в мире. Без пробы сцена, где снаряд пролетел
 * мимо растущего тела, прошла бы все правила, ничего не проверив.
 */
private fun StandWorld.xgTouch(a: StandBody, b: StandBody, until: Int, name: String) {
    probe(name) {
        val st = world.state
        var best = Double.MAX_VALUE
        for (i in 0 until a.count) {
            val p = a.particle(i)
            if (st.cellDead[p] || st.cellUnborn[p]) continue
            for (j in 0 until b.count) {
                val q = b.particle(j)
                if (st.cellDead[q] || st.cellUnborn[q]) continue
                val dx = gameX(p) - gameX(q)
                val dy = gameY(p) - gameY(q)
                val d = dx * dx + dy * dy
                if (d < best) best = d
            }
        }
        if (best == Double.MAX_VALUE) 99.0 else Math.sqrt(best)
    }
    expectMinAtMost("тела соприкоснулись", name, 1, until, 1.3)
}

/**
 * Снаряд-тело: стоит слева от растущего тела (его радиус [reach]) на расстоянии START_GAP и летит
 * вправо со скоростью [speed] связей за тик. Толчок дан за два тика до [hitAt]: разбег на три связи
 * занимает полтора тика, так что удар приходится примерно на [hitAt].
 */
private fun StandWorld.xgBodyShot(s: ShapeSpec, reach: Double, hitAt: Int, speed: Double): StandBody {
    val shot = body(s, -(reach + s.reach + START_GAP), 0.0)
    at(hitAt - 2) { kick(shot, speed, 0.0) }
    return shot
}

/**
 * Свободная частица: появляется слева в тик [hitAt] и летит вправо со скоростью [speed]. Проба
 * следит за её координатой по X: она должна дойти до края растущего тела (до -[reach]).
 */
private fun StandWorld.xgBallShot(reach: Double, hitAt: Int, speed: Double, until: Int) {
    val d = reach + START_GAP + 0.5
    var id = -1
    at(hitAt) { id = ball(-d, 0.0, speed, 0.0) }
    probe("снаряд по X") { if (id < 0) -d else gameX(world.freeParticle(id)) }
    expectMaxAtLeast("снаряд долетел до тела", "снаряд по X", hitAt, until, -reach)
}

/** Подкова из рельефа: стенки открыты сверху, внутри около пяти единиц; тело растёт из её середины. */
private fun xgHorseshoe(family: Family, id: String, what: String, grower: ShapeSpec, pace: Pace, limits: Limits, about: String) {
    val cell = grower.nearest(0.0, 0.0)
    val ticks = xgTicks(grower, cell, pace)
    family.add("xgrow/horseshoe/$id", "подкова из рельефа: растёт $what, ${pace.name}",
        "Подкова из неподвижного рельефа открыта сверху; её внутренность — около пяти единиц. В неё из середины растёт $what. $about",
        XG_GROWN + XG_SETTLED, ticks, limits) {
        // Стенки толщиной три клетки карты; внутренность — восемь клеток, её середина в начале координат.
        terrain(-6.5 * TERRAIN_STEP, -8.0 * TERRAIN_STEP, 14, 14, false) { ix, iy -> ix < 3 || ix > 10 || iy < 3 }
        val g = growing(grower, grower.x[cell], grower.y[cell], seed = cell, period = pace.period, batch = pace.batch)
        // Малому телу подкова просторна, и стенок оно не коснётся: тогда условие сцены — что оно выросло.
        if (grower.reach + GAME_LINK <= 2.6) must("тело выросло (до стенок подковы оно не достаёт)") { g.growth!!.done }
        xgExpectBorn(g, ticks, "родилось клеток")
    }
}

/** Рост из клетки на самом краю фигуры: клетка выбирается по направлению ([dx], [dy]) до крайней точки. */
private fun xgEdgeScene(family: Family, id: String, name: String, shape: ShapeSpec, dx: Double, dy: Double, pace: Pace) {
    val cell = shape.extreme(dx, dy)
    val ticks = xgTicks(shape, cell, pace)
    family.add("xgrow/edge/$id", name,
        "Тело растёт из клетки на самом краю фигуры, ${pace.name}. Впадины фигуры ткань должна дорасти без провалов и разрывов.",
        XG_GROWN + XG_SETTLED, ticks, GROW) {
        val g = growing(shape, shape.x[cell], shape.y[cell], seed = cell, period = pace.period, batch = pace.batch)
        xgExpectBorn(g, ticks, "родилось клеток")
    }
}

internal fun extraGrowthFamily(): List<Scenario> {
    val family = Family("17. Доп.: рост в трудных условиях")

    // --- снаряды в растущее тело: в 10%, 50% и 90% пути роста ---
    // Мягкий круг, костный кирпич и свободная частица по-разному принимают удар на ещё не выросшем
    // теле. Мишень — квадрат 10, растёт из середины по клетке раз в три тика.
    val shotShape = Shapes.square(10)
    val shotSeed = shotShape.nearest(0.0, 0.0)
    val shotGrowth = SLOW.ticks(shotShape, shotSeed)
    for (frac in listOf(0.1, 0.5, 0.9)) for (kind in listOf("soft", "bone", "ball")) {
        val pct = (frac * 100).toInt()
        val hitAt = (shotGrowth * frac).toInt()
        val ticks = xgTicks(shotShape, shotSeed, SLOW) + 60
        val what = when (kind) {
            "soft" -> "мягкий круг радиуса 2"
            "bone" -> "костный кирпич 3×3"
            else -> "свободная частица"
        }
        family.add("xgrow/shot/$kind/at$pct", "квадрат 10 растёт, в $pct% роста летит $what",
            "Квадрат растёт из середины. В тике, отвечающем $pct% роста, в него летит $what со скоростью 2 связи за тик.",
            "Удар приходится на растущую ткань: местный разрыв допустим (до 4% связей), взрыва нет, снаряд не входит в живую ткань. Тело дорастает целиком и засыпает.",
            ticks, XG_HIT) {
            val reach = shotShape.reach
            val g = growing(shotShape, shotShape.x[shotSeed], shotShape.y[shotSeed], seed = shotSeed, period = SLOW.period, batch = SLOW.batch)
            if (kind == "ball") {
                xgBallShot(reach, hitAt, 2.0, ticks)
            } else {
                val s = if (kind == "soft") Shapes.disc(2) else Shapes.square(3).boneAll()
                val shot = xgBodyShot(s, reach, hitAt, 2.0)
                xgTouch(shot, g, ticks, "снаряд: зазор до тела")
            }
            xgExpectBorn(g, ticks, "родилось клеток")
        }
    }

    // --- тело растёт внутри дыры другого тела и распирает его ---
    // Дыра кольца меньше взрослого круга: круг давит на кольцо изнутри, кольцо отходит наружу.
    class Hole(val id: String, val name: String, val ring: ShapeSpec, val grower: ShapeSpec)
    val holes = listOf(
        Hole("r6-d2", "кольцо 6/3, растёт круг 2", Shapes.ring(6, 3), Shapes.disc(2)),
        Hole("r6-d3", "кольцо 6/3, растёт круг 3", Shapes.ring(6, 3), Shapes.disc(3)),
        Hole("r8-d3", "кольцо 8/4, растёт круг 3", Shapes.ring(8, 4), Shapes.disc(3)),
        Hole("r8-d4", "кольцо 8/4, растёт круг 4", Shapes.ring(8, 4), Shapes.disc(4)),
    )
    for (h in holes) {
        val cell = h.grower.nearest(0.0, 0.0)
        val ticks = xgTicks(h.grower, cell, SLOW) + 60
        family.add("xgrow/hole/${h.id}", "${h.name}: тело растёт в дыре",
            "Кольцо стоит в начале координат, а в его дыре из середины растёт круг. " +
                (if (h.id == "r6-d3" || h.id == "r8-d4") "Взрослый круг в дыру не помещается." else "Взрослый круг входит в дыру впритык, не распирая кольца."),
            "Кольцо отходит наружу и может порваться в пределах допуска; круг растёт целиком, не входит в рельеф и не выходит за кольцо. После роста всё засыпает.",
            ticks, GROW_JAM) {
            val ring = body(h.ring, 0.0, 0.0)
            val r0 = xgMeanRadius(ring)
            val g = growing(h.grower, h.grower.x[cell], h.grower.y[cell], seed = cell, period = SLOW.period, batch = SLOW.batch)
            probe("кольцо: средний радиус") { xgMeanRadius(ring) }
            // Распирает кольцо только круг крупнее дыры: круг 2 в кольце 6/3 и круг 3 в кольце 8/4 входят впритык.
            if (h.id == "r6-d3" || h.id == "r8-d4") expectMaxAtLeast("кольцо распёрто наружу", "кольцо: средний радиус", 1, ticks, r0 + 0.1)
            xgExpectBorn(g, ticks, "родилось клеток")
        }
    }

    // --- тело растёт внутри подковы из рельефа ---
    // Круг радиуса 2 входит впритык (зазор около 0.2 единицы), круг радиуса 3 — нет и вдавливается в стенки.
    xgHorseshoe(family, "d2-slow", "круг радиуса 2", Shapes.disc(2), SLOW, GROW,
        "Круг помещается в подкову впритык.")
    xgHorseshoe(family, "d2-layers", "круг радиуса 2", Shapes.disc(2), LAYERS, GROW,
        "Тот же круг, но слоями: все клетки очередного слоя рождаются разом.")
    xgHorseshoe(family, "d3-slow", "круг радиуса 3", Shapes.disc(3), SLOW, GROW_JAM,
        "Круг шире подковы: он вдавливается в стенки, ткань вправе порваться, но не войти в рельеф.")

    // --- зиготу тянут мышью по кругу, пока она растёт ---
    // Мышь ведёт клетку-зиготу по окружности радиусом 2 единицы: растущее тело волочится за ней
    // и одновременно рождает клетки. Проверяется, что рост не рвёт захват и не рвёт ткань.
    for (shape in listOf(Shapes.square(8), Shapes.disc(5), Shapes.star(5, 8, 3))) {
        val cell = shape.nearest(0.0, 0.0)
        val ticks = maxOf(SLOW.ticks(shape, cell), 182) + settle(0.3, !shape.hasTissue)
        family.add("xgrow/pull/${shape.id}", "${shape.name}: зиготу тянут мышью по кругу, пока растёт",
            "Мышь берёт зиготу и ведёт её по окружности радиусом 2 единицы, по два тика на точку. Тело растёт по клетке раз в три тика.",
            "Растущее тело волочится за мышью, не рвётся и не теряет клеток; после отпускания мыши дорастает, затихает и засыпает.",
            ticks, GROW) {
            val sx = shape.x[cell]
            val sy = shape.y[cell]
            val g = growing(shape, sx, sy, seed = cell, period = SLOW.period, batch = SLOW.batch)
            mousePath(g, cell, 0, 2, circlePath(sx, sy, 2.0, 90))
            xgExpectBorn(g, ticks, "родилось клеток")
        }
    }

    // --- посреди роста гибнут клетки; остальное дорастает ---
    // Гибель задана сценарием: клетки убиваются тем же вызовом, что и в игре. Три клетки, родившиеся
    // незадолго до тика гибели, — это вершина ещё растущей ткани.
    for ((id, name, shape, frac) in listOf(
        Quad("sq8-early", "квадрат 8: клетки гибнут в начале роста", Shapes.square(8), 0.3),
        Quad("sq8-late", "квадрат 8: клетки гибнут ближе к концу роста", Shapes.square(8), 0.6),
        Quad("disc5-mid", "круг 5: клетки гибнут в середине роста", Shapes.disc(5), 0.45),
        Quad("ribbon14", "лента 14: клетки гибнут у растущего конца", Shapes.ribbon(14), 0.5),
    )) {
        val cell = shape.nearest(0.0, 0.0)
        val order = shape.growthFrom(cell).order
        val growT = SLOW.ticks(shape, cell)
        val killAt = (growT * frac).toInt()
        val recent = maxOf(4, killAt / SLOW.period)
        val victims = IntArray(3) { order[recent - 3 + it] }
        val ticks = growT + settle(0.3, !shape.hasTissue) + 60
        family.add("xgrow/kill/$id", name,
            "Тело растёт из середины по клетке раз в три тика. В тике $killAt на нём гибнут три клетки, родившиеся незадолго до того; остальные растут дальше.",
            "Гибель задана сценарием, поэтому потеря клеток допустима. Остальная ткань дорастает: новые клетки рождаются у живых соседей, ничто не взрывается и не входит в рельеф.",
            ticks, GROW) {
            val g = growing(shape, shape.x[cell], shape.y[cell], seed = cell, period = SLOW.period, batch = SLOW.batch)
            at(killAt) { killCells(g, victims) }
            probe("живых клеток") { xgAlive(g).toDouble() }
            expectMaxAtLeast("остальное дожило", "живых клеток", 1, ticks, shape.count - victims.size - 0.5)
            xgExpectBorn(g, ticks, "родилось клеток")
        }
    }

    // --- два тела растут навстречу друг другу в коридоре рельефа ---
    // Коридор шириной около 4.7 единицы; каждый круг радиуса 2 растёт из своей зиготы, а зиготы
    // подталкивают друг к другу. Круги должны встретиться посреди коридора.
    val corridor = terrainLayouts().first { it.id == "corridor" }
    val twin = Shapes.disc(2)
    val twinCell = twin.nearest(0.0, 0.0)
    for (pace in listOf(SLOW, LAYERS, XG_FASTEST)) {
        val ticks = xgTicks(twin, twinCell, pace) + 60
        family.add("xgrow/meet/${pace.id}", "два круга растут навстречу в коридоре, ${pace.name}",
            "Два круга радиуса 2 растут каждый из своей зиготы в узком коридоре рельефа. Зиготы подталкивают друг к другу, и круги встречаются посреди коридора.",
            "Тела упираются друг в друга и отходят, не проходя друг сквозь друга; ни одна клетка не входит в рельеф коридора; оба тела дорастают и засыпают.",
            ticks, GROW) {
            place(corridor, -6.0)
            val a = growing(twin, -2.5 + twin.x[twinCell], twin.y[twinCell], seed = twinCell, period = pace.period, batch = pace.batch)
            val b = growing(twin, 2.5 + twin.x[twinCell], twin.y[twinCell], seed = twinCell, period = pace.period, batch = pace.batch)
            at(0) {
                kickCells(a, intArrayOf(twinCell), 0.4, 0.0)
                kickCells(b, intArrayOf(twinCell), -0.4, 0.0)
            }
            xgTouch(a, b, ticks, "тела соприкоснулись")
            xgExpectBorn(a, ticks, "родилось клеток: левое")
            xgExpectBorn(b, ticks, "родилось клеток: правое")
        }
    }

    // --- тело растёт в углу рамки мира ---
    // Рамка 24 на 24 единицы из неподвижных частиц; центр квадрата поставлен почти в угол.
    val corner = Shapes.square(8)
    val cornerCell = corner.nearest(0.0, 0.0)
    for (pace in listOf(SLOW, XG_CRAWL)) {
        val ticks = xgTicks(corner, cornerCell, pace)
        family.add("xgrow/corner/${pace.id}", "квадрат 8 растёт в углу рамки, ${pace.name}",
            "Рамка мира 24 на 24 единицы из неподвижных частиц. Квадрат растёт из середины, поставленной в точку (4; 4), почти в угол рамки.",
            "Тело упирается в стенки рамки и отодвигается от них; ни одна частица не выходит за рамку, ничего не рвётся и не гибнет; после роста тело засыпает.",
            ticks, GROW) {
            arena(24, 24)
            val g = growing(corner, 4.0 + corner.x[cornerCell], 4.0 + corner.y[cornerCell], seed = cornerCell, period = pace.period, batch = pace.batch)
            expectAllInside(0.0, 0.0, 24.0, 24.0, ticks)
            xgExpectBorn(g, ticks, "родилось клеток")
        }
    }

    // --- тело растёт между двумя неподвижными частицами рельефа ---
    // Частицы стоят по обе стороны от взрослого края на расстоянии 1.4 единицы; тело растёт прямо на них.
    for (axis in listOf("x", "y")) {
        val shape = if (axis == "x") Shapes.square(8) else Shapes.disc(5)
        val cell = shape.nearest(0.0, 0.0)
        val ticks = xgTicks(shape, cell, SLOW) + 60
        family.add("xgrow/rock/$axis-${shape.id}", "${shape.name}: растёт между двумя частицами рельефа, ${if (axis == "x") "слева и справа" else "сверху и снизу"}",
            "Две неподвижные частицы рельефа стоят по обе стороны от тела на расстоянии 1.4 единицы от его взрослого края. Тело растёт из середины прямо на них.",
            XG_GROWN + "Частицы рельефа не сдвигаются и не пускают тело внутрь себя; " + XG_SETTLED,
            ticks, GROW) {
            val h = if (axis == "x") shape.halfWidth else shape.halfHeight
            if (axis == "x") {
                rock(-(h + 1.4), 0.0)
                rock(h + 1.4, 0.0)
            } else {
                rock(0.0, -(h + 1.4))
                rock(0.0, h + 1.4)
            }
            val g = growing(shape, shape.x[cell], shape.y[cell], seed = cell, period = SLOW.period, batch = SLOW.batch)
            // Частицы рельефа стоят в 1.4 от взрослого края: тело их не касается по постановке.
            must("тело выросло рядом с частицами рельефа") { g.growth!!.done }
            xgExpectBorn(g, ticks, "родилось клеток")
        }
    }

    // --- длинная нить и лента из сорока клеток растут с одного конца ---
    val rope = Shapes.rope(40)
    val ribbon = Shapes.ribbon(40)
    for ((id, shape, cell) in listOf(
        Triple("rope40-end", rope, rope.extreme(-1.0, 0.0)),
        Triple("ribbon40-end", ribbon, ribbon.extreme(-1.0, 0.0)),
        Triple("rope40-mid", rope, rope.nearest(0.0, 0.0)),
    )) {
        val ticks = xgTicks(shape, cell, SLOW) + 60
        family.add("xgrow/long/$id", "${shape.name}: рост из ${if (id.endsWith("mid")) "середины" else "конца"}",
            "Длинное тело из сорока клеток растёт по клетке раз в три тика. Нить держится только связями, ленту держат связи и треугольники.",
            XG_GROWN + "Длинное тело не рвётся на куски при росте. " + XG_SETTLED,
            ticks, GROW) {
            val g = growing(shape, shape.x[cell], shape.y[cell], seed = cell, period = SLOW.period, batch = SLOW.batch)
            xgExpectBorn(g, ticks, "родилось клеток")
        }
    }

    // --- рост из клетки на самом краю вогнутой фигуры ---
    xgEdgeScene(family, "star-tip", "звезда 5: рост с кончика луча", Shapes.star(5, 8, 3), 0.0, -1.0, SLOW)
    xgEdgeScene(family, "u-arm", "подкова 12×9: рост с конца левого плеча", Shapes.uShape(12, 9, 3), -1.0, 1.0, SLOW)
    xgEdgeScene(family, "comb-tooth", "гребёнка: рост с кончика зубца", Shapes.comb(4, 2, 4, 3), 0.0, 1.0, SLOW)
    xgEdgeScene(family, "comb-tooth-layers", "гребёнка: слоями с кончика зубца", Shapes.comb(4, 2, 4, 3), 0.0, 1.0, LAYERS)

    // --- рядом с растущим телом сталкиваются другие тела ---
    // Круг растёт в начале координат; выше него два квадрата летят друг в друга, и круг не должен
    // задеться ими ни на одном тике.
    val busyGrow = Shapes.disc(5)
    val busyCell = busyGrow.nearest(0.0, 0.0)
    val busyBox = Shapes.square(6)
    for (speed in listOf(0.6, 2.0)) {
        val ticks = xgTicks(busyGrow, busyCell, SLOW) + 60
        val limits = if (speed > 1.0) Limits.hit(speed).copy(energy = Energy.DRIVEN) else GROW
        family.add("xgrow/busy/v$speed", "рядом растёт круг, а два квадрата сталкиваются, скорость $speed",
            "Круг растёт в начале координат. Выше него два квадрата: левый летит вправо со скоростью $speed связи за тик, правый стоит.",
            "Квадраты сталкиваются и не проходят друг сквозь друга; круг растёт целиком и не задевается ими; после всего тела засыпают.",
            ticks, limits) {
            val g = growing(busyGrow, busyGrow.x[busyCell], busyGrow.y[busyCell], seed = busyCell, period = SLOW.period, batch = SLOW.batch)
            val left = body(busyBox, -7.0, 8.0)
            val right = body(busyBox, 2.5, 8.0)
            kick(left, speed, 0.0)
            expectMet(left, right, 240, speed = speed)
            xgExpectBorn(g, ticks, "родилось клеток")
        }
    }

    // --- растущее тело толкают каждые 30 тиков ---
    // Толчок дан одной растущей клетке (зиготе): телу целиком скорость не задают, и он должен
    // сохранить форму, пока рождаются новые клетки.
    for (horizontal in listOf(true, false)) {
        val shape = if (horizontal) Shapes.square(8) else Shapes.rect(16, 4)
        val cell = shape.nearest(0.0, 0.0)
        val ticks = xgTicks(shape, cell, SLOW) + 60
        family.add("xgrow/kick/${if (horizontal) "square8-side" else "rect16-vertical"}",
            "${shape.name}: растущее тело толкают каждые 30 тиков, ${if (horizontal) "вбок" else "вверх и вниз"}",
            "Растущее тело шесть раз толкают зиготу: каждые 30 тиков толчок меняет направление. Толчки идут по ${if (horizontal) "горизонтали" else "вертикали"}, со скоростью 0.6 связи за тик.",
            XG_GROWN + XG_SETTLED,
            ticks, GROW) {
            val g = growing(shape, shape.x[cell], shape.y[cell], seed = cell, period = SLOW.period, batch = SLOW.batch)
            for (k in 0 until 6) {
                val push = if (k % 2 == 0) 0.6 else -0.6
                val vx = if (horizontal) push else 0.0
                val vy = if (horizontal) 0.0 else push
                at(30 * k) { kickCells(g, intArrayOf(cell), vx, vy) }
            }
            xgExpectBorn(g, ticks, "родилось клеток")
        }
    }

    // --- очень быстрый и очень медленный рост ---
    for (shape in listOf(Shapes.square(8), Shapes.disc(5), Shapes.rect(16, 4))) for (pace in listOf(XG_FASTEST, XG_CRAWL)) {
        val cell = shape.nearest(0.0, 0.0)
        val ticks = xgTicks(shape, cell, pace)
        family.add("xgrow/pace/${shape.id}/${pace.id}", "${shape.name}: рост, ${pace.name}",
            "Тело растёт из середины: ${pace.name}.",
            XG_GROWN + XG_SETTLED, ticks, GROW) {
            val g = growing(shape, shape.x[cell], shape.y[cell], seed = cell, period = pace.period, batch = pace.batch)
            xgExpectBorn(g, ticks, "родилось клеток")
        }
    }

    // --- рост тел с костями: из кости и из мягкой ткани ---
    // Раскладки костей разные: ядро, оболочка, хребет, пластины и целиком кость. Растёт из кости
    // (она включена с первого тика) и из мягкой ткани (кость включается, когда клетка доросла).
    val boneCases = listOf(
        Pair("core", Shapes.square(10).boneCore()),
        Pair("shell", Shapes.disc(6).boneShell()),
        Pair("spine", Shapes.rect(18, 6).boneSpine()),
        Pair("plates", Shapes.rect(18, 6).bonePlates(3)),
        Pair("all", Shapes.square(8).boneAll()),
    )
    for ((kind, shape) in boneCases) {
        val boneSeed = (0 until shape.count).first { shape.bone[it] }
        val softSeed = (0 until shape.count).firstOrNull { !shape.bone[it] }
        val seeds = mutableListOf(Triple(boneSeed, "bone", "из кости"))
        if (softSeed != null) seeds.add(Triple(softSeed, "soft", "из мягкой ткани"))
        for ((seed, tag, from) in seeds) {
            val ticks = xgTicks(shape, seed, SLOW) + 60
            family.add("xgrow/bones/$kind/$tag", "${shape.name}, кости «$kind»: рост $from",
                "Тело с раскладкой костей «$kind» растёт $from, по клетке раз в три тика. Кости включаются, когда клетка доросла.",
                XG_GROWN + "Кости получаются той формы, что в описании, а не той, в какой их застал рост. " + XG_SETTLED,
                ticks, GROW) {
                val g = growing(shape, shape.x[seed], shape.y[seed], seed = seed, period = SLOW.period, batch = SLOW.batch)
                xgExpectBorn(g, ticks, "родилось клеток")
                xgExpectBones(g, ticks, "костных клеток")
            }
        }
    }

    // --- большой круг растёт слоями и быстро ---
    val big = Shapes.disc(12)
    val bigCell = big.nearest(0.0, 0.0)
    for (pace in listOf(LAYERS, XG_FASTEST)) {
        val ticks = xgTicks(big, bigCell, pace)
        family.add("xgrow/big/disc12-${pace.id}", "круг радиуса 12: рост, ${pace.name}",
            "Большое тело из нескольких сотен клеток растёт из середины.",
            XG_GROWN + XG_SETTLED, ticks, GROW) {
            val g = growing(big, big.x[bigCell], big.y[bigCell], seed = bigCell, period = pace.period, batch = pace.batch)
            xgExpectBorn(g, ticks, "родилось клеток")
        }
    }

    return family.list
}
