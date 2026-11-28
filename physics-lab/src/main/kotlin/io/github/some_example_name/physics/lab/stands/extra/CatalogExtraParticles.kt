package io.github.some_example_name.physics.lab.stands

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/*
 * СЕМЕЙСТВО «ЧАСТИЦЫ И РЕЛЬЕФ» (доп.). Свободные частицы вместе с рельефом и телами — то, до чего
 * основной каталог ещё не дошёл: потоки через воронку, щель и коридор; частица, катящаяся вдоль
 * пола под острым углом; стыки между частицами рельефа и между клетками мембраны; тонкая стенка и
 * одиночный столб; гребёнка, мышь у стены и за углом; нити и ленты под ударами рядов частиц;
 * очередь в одну точку и удар на потолке скорости; тела и частицы в тесной полости; газ с телом и
 * в щели; перешеек между двумя полостями; кольцо с частицами внутри; карман, наполовину набитый
 * частицами; частицы, зажатые между сходящимися телами.
 *
 * Главные правила: ничто не входит в рельеф и не проходит сквозь ткань (для этого у сцен, где
 * частицы могут оказаться в рельефе, есть пробы «частиц в толще рельефа» и «зазор до рельефа»);
 * закрытая система не набирает энергию и обязана успокоиться.
 */

/** Узлы сетки [x0]..[x1] × [y0]..[y1] с шагом [step]: точки для частиц, без случайности. */
private fun xbPoints(x0: Double, x1: Double, y0: Double, y1: Double, step: Double): List<DoubleArray> {
    val out = ArrayList<DoubleArray>()
    var y = y0
    while (y <= y1 + 1e-9) {
        var x = x0
        while (x <= x1 + 1e-9) {
            out.add(doubleArrayOf(x, y))
            x += step
        }
        y += step
    }
    return out
}

/** Точка с запасом 0.3 в стороны свободна в полости: частица не у стены и не в ней (как в fill). */
private fun Cavity.xbRoom(x: Double, y: Double): Boolean =
    free(x, y) && free(x - 0.3, y) && free(x + 0.3, y) && free(x, y - 0.3) && free(x, y + 0.3)

/**
 * Частицы в полости с углами «золотого сечения» вместо случайных направлений: скорость [speed],
 * шаг сетки [step]. Точки, для которых [skip] вернул true, пропускаются (там стоит тело).
 */
private fun StandWorld.xbSwirl(c: Cavity, step: Double, speed: Double, skip: (Double, Double) -> Boolean = { _, _ -> false }) {
    var k = 0
    for (p in xbPoints(c.minX + 0.45, c.maxX - 0.45, c.minY + 0.45, c.maxY - 0.45, step)) {
        if (!c.xbRoom(p[0], p[1]) || skip(p[0], p[1])) continue
        val a = k * 2.39996323
        ball(p[0], p[1], speed * cos(a), speed * sin(a))
        k++
    }
}

/** Поток: [count] свободных частиц через каждые [gap] тиков из точки ([x0], [y0]) со скоростью [vx]. */
private fun StandWorld.xbStream(count: Int, gap: Int, x0: Double, y0: Double, vx: Double) {
    for (k in 0 until count) at(k * gap) { ball(x0, y0, vx, 0.0) }
}

/** Сколько живых свободных частиц стоят внутри клеток рельефа (толща или кромка). Должно быть 0. */
private fun StandWorld.xbInsideRock(): Double {
    var n = 0
    for (id in balls) {
        val p = world.freeParticle(id)
        if (world.state.cellDead[p]) continue
        val x = gameX(p); val y = gameY(p)
        for (t in terrains) if (t.isSolid(t.cellOfX(x), t.cellOfY(y))) { n++; break }
    }
    return n.toDouble()
}

/** Самая правая из живых свободных частиц (0, если их нет). */
private fun StandWorld.xbRightmost(): Double {
    var best = -Double.MAX_VALUE
    for (id in balls) {
        val p = world.freeParticle(id)
        if (world.state.cellDead[p]) continue
        val x = gameX(p)
        if (x > best) best = x
    }
    return if (best == -Double.MAX_VALUE) 0.0 else best
}

/** Наименьшее расстояние от свободной частицы [id] до неподвижных частиц рельефа, единицы игры. */
private fun StandWorld.xbRockGap(id: Int): Double {
    val p = world.freeParticle(id)
    var best = Double.MAX_VALUE
    for (r in fixed) {
        val q = world.freeParticle(r)
        val dx = gameX(p) - gameX(q)
        val dy = gameY(p) - gameY(q)
        val d = Math.sqrt(dx * dx + dy * dy)
        if (d < best) best = d
    }
    return best
}

/** Наименьшее расстояние от свободной частицы [id] до живых клеток тела [b], единицы игры. */
private fun StandWorld.xbBodyGap(b: StandBody, id: Int): Double {
    val p = world.freeParticle(id)
    var best = Double.MAX_VALUE
    for (k in 0 until b.count) {
        val q = b.particle(k)
        if (world.state.cellDead[q]) continue
        val dx = gameX(p) - gameX(q)
        val dy = gameY(p) - gameY(q)
        val d = Math.sqrt(dx * dx + dy * dy)
        if (d < best) best = d
    }
    return best
}

/** Сколько свободных частиц лежит дальше [limit] единиц игры от центра тела [b] (вылетевшие). */
private fun StandWorld.xbOutside(b: StandBody, limit: Double): Double {
    val c = centerOf(b)
    var n = 0
    for (id in balls) {
        val p = world.freeParticle(id)
        if (world.state.cellDead[p]) continue
        if (Math.hypot(gameX(p) - c[0], gameY(p) - c[1]) > limit) n++
    }
    return n.toDouble()
}

/** Высота линии пола под углом [deg] над точкой с абсциссой [x]; пол проходит через (-8; -4). */
private fun xbFloorY(deg: Double, x: Double): Double = -4.0 + tan(Math.toRadians(deg)) * (x + 8.0)

internal fun extraParticlesFamily(): List<Scenario> {
    val family = Family("18. Доп.: частицы и рельеф")

    // --- 1. Поток частиц через воронку, щель и узкий коридор ---
    // Частицы одна за другой летят в проход рельефа. Проверяем, что поток не проходит сквозь стенки
    // и не застревает в их толще; на скорости 3 проход и отскок решаются только касаниями.
    for (layoutId in listOf("funnel", "slit", "corridor")) for (speed in listOf(1.0, 3.0)) {
        val layout = terrainLayouts().first { it.id == layoutId }
        family.add("xball/stream/$layoutId/v$speed", "поток через «${layout.name}», скорость $speed",
            "${layout.about} Двадцать четыре частицы одна за другой, раз в пять тиков, летят в проход со скоростью $speed связи за тик.",
            "Частицы либо проходят в проход, либо отскакивают от стенок. Ни одна не входит в рельеф, никто не гибнет, поток не застревает в стене.",
            900, Limits.INTACT.copy(name = "поток: энергия не растёт", sleep = false), Variation.BALLS) {
            place(layout, 6.0)
            xbStream(24, 5, -3.0, 0.0, speed)
            probe("самая правая частица") { xbRightmost() }
            probe("частиц в толще рельефа") { xbInsideRock() }
            expectMaxAtMost("ни одна частица не в рельефе", "частиц в толще рельефа", 1, 900, 0.0)
            expectMaxAtLeast("поток дошёл до рельефа", "самая правая частица", 1, 900, 4.5)
            view = doubleArrayOf(-4.0, -14.0, 22.0, 14.0)
        }
    }

    // --- 2. Частица катится вдоль пола под острым углом: 5°, 15°, 30°, 60° ---
    // Пол — ступеньки клеток карты под углом. Частица летит по касательной, держась над полом.
    // При малом угле ступеньки почти плоские, при большом пол круто уходит вверх за пределы карты.
    for (deg in listOf(5.0, 15.0, 30.0, 60.0)) {
        val d = deg.toInt()
        family.add("xball/slope/a$d", "частица катится вдоль пола под углом $d°",
            "Пол из клеток карты наклонён на $d° и выглядит ступеньками. Частица летит вдоль пола со скоростью 0.4 связи за тик.",
            "Частица скользит вдоль пола, не уходит под него и не проваливается сквозь ступеньки; ни одна частица не входит в рельеф; энергия не растёт.",
            150, Limits.INTACT.copy(name = "частица у пола: энергия не растёт", sleep = false), Variation.BALLS) {
            terrain(-8.0, -8.0, 40, 40, jitter = false) { ix, iy ->
                val x = -8.0 + ix * TERRAIN_STEP
                val y = -8.0 + iy * TERRAIN_STEP
                y < xbFloorY(deg, x)
            }
            val rad = Math.toRadians(deg)
            val x0 = -6.0
            // Чуть вниз от линии пола: частица, летящая строго вдоль пологого пола, его не касается вовсе (16.11: сцена под 5° шла вхолостую).
            val id = ball(x0, xbFloorY(deg, x0) + 0.9, 0.4 * cos(rad) + 0.1 * sin(rad), 0.4 * sin(rad) - 0.1 * cos(rad))
            probe("над полом") { val p = world.freeParticle(id); gameY(p) - xbFloorY(deg, gameX(p)) }
            probe("частиц в толще рельефа") { xbInsideRock() }
            expectMinAtLeast("частица не ушла под пол", "над полом", 1, 150, -0.3)
            expectMaxAtMost("ни одна частица не в рельефе", "частиц в толще рельефа", 1, 150, 0.0)
            view = doubleArrayOf(-9.0, -9.0, 19.0, 19.0)
        }
    }

    // --- 3. Частица целит точно в стык между двумя частицами рельефа ---
    // Две неподвижные частицы стоят в точках (0; ±0.35): стык шириной 0.7. Частица не должна ни
    // войти в рельеф, ни протиснуться сквозь стык: расстояние до рельефа не меньше 0.4.
    for (speed in listOf(0.8, 2.0)) {
        family.add("xball/rockseam/v$speed", "частица целит в стык двух частиц рельефа, скорость $speed",
            "Две частицы рельефа стоят в точках (0; 0.35) и (0; -0.35): между ними стык шириной 0.7. Свободная частица летит точно в стык слева направо.",
            "Частица не проходит в стык и отскакивает; ни к одной частице рельефа она не подходит ближе 0.4; энергия не растёт.",
            300, Limits.INTACT.copy(name = "частица в стыке: энергия не растёт", sleep = false), Variation.BALLS) {
            rock(0.0, 0.35)
            rock(0.0, -0.35)
            val id = ball(-3.0, 0.0, speed, 0.0)
            probe("частица по X") { gameX(world.freeParticle(id)) }
            probe("зазор до рельефа") { xbRockGap(id) }
            // Запас в треть единицы: на двух связях за тик отскок приходится внутрь тика, и ближайшей точки проба не застаёт.
            expectMaxAtLeast("частица дошла до стыка", "частица по X", 1, 300, -0.9)
            expectMinAtLeast("частица не вошла в рельеф", "зазор до рельефа", 1, 300, 0.4)
            view = doubleArrayOf(-5.0, -3.0, 4.0, 3.0)
        }
    }

    // --- 4. Частица в шов между двумя клетками мембраны ---
    // Квадрат 6: шов между клетками сверху (частица падает точно на стык двух клеток верхнего ряда)
    // и слева (частица летит в стык двух клеток левого столбца). Удар сразу в две клетки.
    for (speed in listOf(0.8, 2.0)) for (side in listOf("top", "left")) {
        val s = Shapes.square(6)
        val sideTitle = if (side == "top") "сверху" else "слева"
        family.add("xball/seam/$side/v$speed", "частица в шов между клетками мембраны: $sideTitle, скорость $speed",
            "Квадрат 6. Свободная частица летит точно в шов между двумя соседними клетками, ${if (side == "top") "сверху вниз" else "слева направо"}, со скоростью $speed связи за тик.",
            "Частица отскакивает от двух клеток сразу, не проходит в шов и не входит в ткань; ткань не рвётся сильнее допуска; к концу всё стоит и спит.",
            300, Limits.hit(speed)) {
            val b = body(s, 0.0, 0.0)
            val id = if (side == "top") ball(0.0, 3.0, 0.0, -speed) else ball(-5.0, 0.0, speed, 0.0)
            probe("зазор до тела") { xbBodyGap(b, id) }
            expectMinAtLeast("частица не вошла в ткань", "зазор до тела", 1, 300, 0.4)
            expectMinAtMost("частица дошла до тела", "зазор до тела", 1, 300, 0.6 + speed * GAME_LINK)
        }
    }

    // --- 5. Тонкая стенка в одну клетку карты: два тела давят на неё навстречу ---
    // Стенка тонкая, так что тела с обеих сторон почти касаются друг друга через неё. Оба летят к
    // стенке; ни одна клетка не должна оказаться в ней или пройти сквозь неё.
    for (speed in listOf(0.3, 1.0)) {
        val s = Shapes.square(6)
        family.add("xball/thinwall/v$speed", "тонкая стенка: два квадрата давят с обеих сторон, скорость $speed",
            "Стенка — один столбец клеток карты толщиной 0.67 и высотой около 27 единиц. Слева и справа по квадрату 6; оба летят к стенке со скоростью $speed связи за тик.",
            "Оба тела упираются в стенку и не проходят сквозь неё; стенка неподвижна, ничего не гибнет; к концу тела стоят и спят.",
            settle(speed) + 150, Limits.hit(speed)) {
            terrain(6.0, -13.3, 1, 40, jitter = false) { _, _ -> true }
            val left = body(s, approach(s, 0.0, 0.0, 0.0, 1.0, 0.0), 0.0)
            val right = body(s, 12.0 - approach(s, 12.0, 0.0, 0.0, -1.0, 0.0), 0.0)
            kick(left, speed, 0.0)
            kick(right, -speed, 0.0)
            expectReached(left, settle(speed) + 150)
            expectReached(right, settle(speed) + 150)
            view = doubleArrayOf(-4.0, -6.0, 14.0, 6.0)
        }
    }

    // --- 6. Одиночный столб из одной клетки карты: тело налетает серединой и краем ---
    // Столб — одна клетка карты. Тело, ударяясь о него краем, поворачивается и уходит вбок; ни одна
    // клетка тела не должна оказаться в столбе.
    for ((label, s) in listOf("sq6" to Shapes.square(6), "disc4" to Shapes.disc(4))) for (edge in listOf(false, true)) {
        family.add("xball/pillar/$label/${if (edge) "edge" else "mid"}", "одиночный столб: ${s.name} налетает ${if (edge) "краем" else "серединой"}",
            "Столб — одна клетка карты на пути тела. ${s.name} летит вправо со скоростью 1 связь за тик и налетает на столб ${if (edge) "краем, а не серединой" else "серединой"}.",
            "Тело упирается в столб, не входит в него и не проходит сквозь него; ни одна клетка не оказывается в толще рельефа; ничего не рвётся сильнее допуска.",
            300, Limits.hit(1.0)) {
            terrain(6.0, 0.0, 1, 1, jitter = false) { _, _ -> true }
            val yOff = if (edge) s.halfHeight * 0.7 else 0.0
            val b = body(s, approach(s, 0.0, yOff, 0.0, 1.0, 0.0), yOff)
            kick(b, 1.0, 0.0)
            expectReached(b, 300)
            view = doubleArrayOf(-4.0, -4.0, 9.0, 4.0)
        }
    }

    // --- 7. Гребёнка рельефа: три столба с промежутками, тело протискивается между ними ---
    // Столбы стоят на шаге 2.67 единицы (каждые четыре клетки карты). Тело летит сверху вниз точно
    // в промежуток между первым и вторым столбом и должно проскользнуть между ними.
    for ((label, s) in listOf("rect2" to Shapes.rect(2, 2), "disc1" to Shapes.disc(1))) {
        family.add("xball/comb/$label", "гребёнка рельефа: ${s.name} протискивается вниз",
            "Три столба клеток карты высотой восемь единиц стоят с промежутками. ${s.name} летит вниз точно в промежуток между первым и вторым столбом со скоростью 0.6 связи за тик.",
            "Тело проходит или застревает в промежутке, не входя в столбы; ни одна клетка не оказывается в толще рельефа; ничего не рвётся.",
            400, Limits.hit(0.6)) {
            terrain(0.0, -4.0, 12, 13, jitter = false) { ix, _ -> ix % 4 == 0 }
            val gapX = 1.33
            val b = body(s, gapX, 6.0 - approach(s, gapX, 6.0, 0.0, 0.0, -1.0))
            kick(b, 0.0, -0.6)
            expectReached(b, 400)
            view = doubleArrayOf(-4.0, -6.0, 8.0, 8.0)
        }
    }

    // --- 8. Мышь тянет тело вдоль стены (трение) и за угол рельефа ---
    // Мышь вдавливает правый край квадрата в стену и ведёт его вверх и вниз. Тело держится трением,
    // но не входит в стену и не рвётся. Вторая сцена — то же за угол: тело тянут по полу к стене и
    // потом вверх вдоль её лица.
    family.add("xball/mouse/wall", "мышь ведёт квадрат вдоль стены",
        "Ровная стена справа. Квадрат 6 прижат к ней; мышь берёт правый край квадрата, вдавливает его в стену и ведёт вверх и вниз на четыре единицы.",
        "Квадрат скользит вдоль стены, трение удерживает его; ни одна клетка не проходит сквозь стенку; после отпускания всё успокаивается.",
        500, Limits.DRIVEN) {
        val s = Shapes.square(6)
        place(terrainLayouts().first { it.id == "wall" }, 6.0, jitter = false)
        val b = body(s, approach(s, 0.0, 0.0, 0.0, 1.0, 0.0), 0.0)
        val cell = s.extreme(1.0, 0.0)
        val p = b.particle(cell)
        val sx = gameX(p); val sy = gameY(p)
        val pts = ArrayList<DoubleArray>()
        for (i in 1..30) pts.add(doubleArrayOf(sx + (START_GAP + 0.4) * i / 30.0, sy))
        for (i in 1..60) pts.add(doubleArrayOf(sx + START_GAP + 0.4, sy - 4.0 + 8.0 * i / 60.0))
        mousePath(b, cell, 150, 3, pts)
        expectReached(b, 500)
        view = doubleArrayOf(-4.0, -6.0, 12.0, 6.0)
    }
    family.add("xball/mouse/corner", "мышь ведёт квадрат 4 по полу и за угол рельефа",
        "Угол рельефа: пол снизу и стена справа. Мышь берёт нижний правый угол квадрата 4, тянет его вправо до стены и затем вверх вдоль её лица.",
        "Квадрат прижимается к полу и к стене, но не входит в них; ни одна клетка не проходит сквозь рельеф; после отпускания расправляется и засыпает.",
        600, Limits.DRIVEN) {
        place(terrainLayouts().first { it.id == "corner" }, 6.0, jitter = false)
        val s = Shapes.square(4)
        val b = body(s, 0.0, -2.6)
        val cell = s.extreme(1.0, -1.0)
        val p = b.particle(cell)
        val sx = gameX(p); val sy = gameY(p)
        val pts = ArrayList<DoubleArray>()
        for (i in 1..30) pts.add(doubleArrayOf(sx + (6.0 - sx) * i / 30.0, sy))
        for (i in 1..60) pts.add(doubleArrayOf(6.0, sy + 4.0 * i / 60.0))
        mousePath(b, cell, 60, 3, pts)
        view = doubleArrayOf(-6.0, -8.0, 10.0, 6.0)
    }

    // --- 9. Частицы разного числа в ряд бьют по нити и по ленте ---
    // Одна, три или шесть частиц одна за другой по одной линии бьют в нить из 12 клеток и в ленту.
    // Нить держится только связями, лента — с треугольниками; обе должны сдвинуться и не рваться
    // сверх допуска. Сила толчка растёт с числом частиц.
    for (n in listOf(1, 3, 6)) for ((label, s) in listOf("rope" to Shapes.rope(12), "ribbon" to Shapes.ribbon(12))) {
        family.add("xball/flock/$label/n$n", "$n частиц в ряд по «${s.name}»",
            "Частицы летят одна за другой по одной линии, каждая со скоростью 2 связи за тик, в ${s.name}.",
            "Тело получает толчки и откатывается; ткань не рвётся сильнее допуска, частицы не входят в неё; к концу всё стоит и спит.",
            400, Limits.hit(2.0)) {
            val b = body(s, 0.0, 0.0)
            for (k in 0 until n) ball(-(s.reach + START_GAP + 1.0) - 0.7 * k, 0.0, 2.0, 0.0)
            expectPushed(b, 2.0, 400, share = 0.02, hitterCells = n)
        }
    }

    // --- 10. Очередь частиц в одну точку мембраны: тридцать частиц подряд на скорости 4 ---
    // Все частицы летят в одну точку тела по одной линии. Тело получает непрерывный поток толчков;
    // ткань может порваться по допуску, но ни одна частица не входит в неё.
    for ((label, s) in listOf("sq10" to Shapes.square(10), "disc6" to Shapes.disc(6))) {
        family.add("xball/queue/$label", "очередь из тридцати частиц в ${s.name}",
            "Тридцать частиц летят одна за другой по одной линии, каждая через три тика, в одну точку тела, со скоростью 4 связи за тик.",
            "Тело получает толчки и отбрасывает частицы; ткань вправе порваться по допуску, но частицы не входят в неё и ничего не взрывается.",
            CRASH_TICKS, Limits.hit(4.0)) {
            val b = body(s, 0.0, 0.0)
            val x0 = -(s.reach + START_GAP + 1.0)
            for (k in 0 until 30) at(k * 3) { ball(x0, 0.0, 4.0, 0.0) }
            expectPushed(b, 4.0, CRASH_TICKS, share = 0.01, hitterCells = 30)
        }
    }

    // --- 11. Частица на потолке скорости в костное тело ---
    // Самый сильный удар, который решатель вообще допускает, — в тело из одной кости. Кость держит
    // форму или рвётся по допуску; частица не проходит в неё и не разгоняется дальше потолка.
    for ((label, s) in listOf("sq8" to Shapes.square(8).boneAll(), "rect6x2" to Shapes.rect(6, 2).boneAll())) {
        family.add("xball/maxbone/$label", "частица на потолке скорости в ${s.name}",
            "Свободная частица летит со скоростью ${MAX_HIT.speed} связи за тик в центр костного тела: самый сильный удар, который допускает решатель.",
            "Кость держит форму или рвётся по допуску, частица не входит в кость и не проходит сквозь неё; скорость никто не превышает; движение затухает.",
            CRASH_TICKS, MAX_HIT.limits) {
            val b = body(s, 0.0, 0.0)
            ball(-(s.reach + START_GAP + 1.0), 0.0, MAX_HIT.speed, 0.0)
            expectPushed(b, MAX_HIT.speed, CRASH_TICKS, share = 0.005, hitterCells = 1)
        }
    }

    // --- 12. Тело в полости, набитой частицами вплотную: мышцы шевелятся внутри ---
    // Полость 14×14: тело в центре, всё вокруг заполнено частицами с шагом 0.62 (чуть больше касания
    // 0.6, так что в начале никто не перекрыт). Мышцы по очереди сжимаются и расслабляются: тело
    // шевелится, но ни одна частица не выходит из полости и не гибнет.
    for ((label, s) in listOf("disc3" to Shapes.disc(3).muscleTopBottom(), "sq5" to Shapes.square(5).muscleLeftRight())) {
        family.add("xball/packed/$label", "тело в тесной полости: ${s.name}, мышцы шевелятся",
            "Полость 14×14 клеток. Тело в центре, вокруг него частицы вплотную (шаг 0.62 при касании 0.6). Мышцы по очереди сжимаются и расслабляются.",
            "Тело шевелится внутри полости, но не выходит из неё; частицы не проходят сквозь ткань и не гибнут; энергия идёт только от мышц.",
            500, Limits.DRIVEN.copy(name = "тесная полость: мышцы", sleep = false)) {
            val c = cavity(14, 14, false)
            val cx = (c.minX + c.maxX) / 2
            val cy = (c.minY + c.maxY) / 2
            val b = body(s, cx, cy)
            for (k in 0 until s.muscleGroups) {
                at(60 + 40 * k) { muscle(b, k, 0.5f) }
                at(120 + 40 * k) { muscle(b, k, 1.5f) }
                at(180 + 40 * k) { muscle(b, k, 1f) }
            }
            // Частицы — вплотную к телу (на расстоянии касания: 0.62 при 0.6): с прежним просветом 1.2 мышцы до них не дотягивались, и сцена шла вхолостую.
            xbSwirl(c, 0.62, 0.0, skip = { x, y -> Math.hypot(x - cx, y - cy) < s.reach + 0.62 })
            mustTouchBalls(b, "тело задело частицы набивки")
            expectContained(c, 500)
        }
    }

    // --- 13. Газ частиц и плавающее в нём тело ---
    // Ящик 18×18 клеток: тело в центре, частицы разлетаются со скоростью 2 связи за тик. Тело
    // сталкивается с газом, газ не проходит сквозь тело и не вылетает из ящика.
    for ((label, s) in listOf("disc3" to Shapes.disc(3), "sq4" to Shapes.square(4))) {
        family.add("xball/gasbody/$label", "газ вокруг тела: ${s.name}",
            "Ящик 18×18 клеток, в нём ${s.name} и частицы с шагом 1.6, разлетающиеся в стороны со скоростью 2 связи за тик.",
            "Газ и тело перемешиваются; частицы не входят в ткань и не выходят из ящика; энергия только убывает.",
            900, Limits.INTACT.copy(name = "газ с телом: энергия убывает", sleep = false)) {
            val c = cavity(18, 18, false)
            val cx = (c.minX + c.maxX) / 2
            val cy = (c.minY + c.maxY) / 2
            body(s, cx, cy)
            xbSwirl(c, 1.6, 2.0, skip = { x, y -> Math.hypot(x - cx, y - cy) < s.reach + 1.2 })
            expectContained(c, 900)
        }
    }

    // --- 14. Газ в длинной щели и в круглой полости ---
    // Частицы без тел: проверяем, что газ в узком и в круглом пространстве не выходит из стен и не
    // разгоняется сверх начальной энергии.
    family.add("xball/gas/slit", "газ в длинной щели 24×4, скорость 2",
        "Щель из рельефа 24 клетки в длину и 4 в ширину. Частицы разлетаются в стороны со скоростью 2 связи за тик.",
        "Частицы отскакивают от стенок щели и друг от друга; ни одна не выходит из щели и не гибнет; энергия убывает.",
        900, Limits.INTACT.copy(name = "газ в щели: энергия убывает", sleep = false), Variation.BALLS) {
        val c = cavity(24, 4, false)
        xbSwirl(c, 0.9, 2.0)
        expectContained(c, 900)
    }
    family.add("xball/gas/round", "газ в круглой полости, скорость 2.5",
        "Круглая полость радиусом около 6 клеток карты. Частицы разлетаются со скоростью 2.5 связи за тик.",
        "Частицы отражаются от круглой стенки и друг от друга; ни одна не выходит наружу; энергия убывает.",
        900, Limits.INTACT.copy(name = "газ в круге: энергия убывает", sleep = false, capHits = false), Variation.BALLS) {
        val c = cavity(13, 13, false) { ix, iy -> (ix - 6) * (ix - 6) + (iy - 6) * (iy - 6) <= 38 }
        xbSwirl(c, 1.0, 2.5)
        expectContained(c, 900)
    }

    // --- 15. Две полости с перешейком: в одной частицы под давлением ---
    // Левая полость набита частицами, правая пуста, соединены узким перешейком. Частицы без скорости
    // (покой) и с толчками (давление): ни одна не должна пройти в правую полость через стену.
    for (speed in listOf(0.0, 1.5)) {
        family.add("xball/neck/v$speed", "две полости с перешейком: ${if (speed == 0.0) "покой" else "давление"}",
            "Левая полость набита частицами с шагом 0.62, правая пуста. Перешеек — две клетки карты. ${if (speed == 0.0) "Частицы стоят без движения." else "Частицы толкаются со скоростью 1.5 связи за тик и давят на перешеек."}",
            "Частицы остаются в левой полости, перешеек их не пропускает; ни одна не входит в рельеф; энергия не растёт.",
            1200, Limits.INTACT.copy(name = "перешеек: энергия не растёт", sleep = false), Variation.BALLS) {
            val c = cavity(19, 8, false) { ix, iy -> ix < 8 || ix > 10 || iy in 3..4 }
            xbSwirl(c, 0.62, speed, skip = { x, _ -> x > c.minX + 8 * TERRAIN_STEP })
            expectContained(c, 1200)
        }
    }

    // --- 16. Кольцо с частицами внутри: сжатие мышцами, толчок и удар снаружи ---
    // Внутри кольца 6/3 — девять частиц. Кольцо сжимают мышцами, толкают или бьют снаружи. Частицы
    // должны остаться внутри кольца; при ударе снаружи внутренние частицы тоже не вылетают.
    for (mode in listOf("squeeze", "kick", "strike")) {
        val ring = if (mode == "squeeze") Shapes.ring(6, 3).muscleRim() else Shapes.ring(6, 3)
        val title = when (mode) {
            "squeeze" -> "сжатие мышцами"
            "kick" -> "толчок"
            else -> "удар снаружи"
        }
        family.add("xball/ring/$mode", "кольцо с частицами внутри: $title",
            "Кольцо 6/3 и девять частиц внутри. ${when (mode) {
                "squeeze" -> "Мышцы ободка сжимают кольцо внутрь, затем отпускают."
                "kick" -> "Кольцо толкают вправо со скоростью 1 связь за тик."
                else -> "В кольцо снаружи бьёт частица со скоростью 2 связи за тик."
            }}",
            "Внутренние частицы остаются внутри кольца и не входят в его ткань; ни одна не вылетает наружу; энергия не растёт сверх того, что дали мышцы или удар.",
            400, when (mode) {
                "squeeze" -> Limits.DRIVEN.copy(name = "кольцо: мышцы", sleep = false)
                "kick" -> Limits.hit(1.0).copy(name = "кольцо: толчок", sleep = false)
                else -> Limits.hit(2.0).copy(name = "кольцо: удар", sleep = false)
            }) {
            val b = body(ring, 0.0, 0.0)
            for (p in xbPoints(-0.8, 0.8, -0.8, 0.8, 0.8)) ball(p[0], p[1])
            when (mode) {
                "squeeze" -> {
                    at(40) { muscles(b, 0.5f) }
                    at(160) { muscles(b, 1.5f) }
                    at(240) { muscles(b, 1f) }
                }
                "kick" -> kick(b, 1.0, 0.0)
                else -> ball(-(ring.reach + START_GAP + 1.0), 0.0, 2.0, 0.0)
            }
            if (mode != "strike") {
                probe("вылетевших частиц") { xbOutside(b, 3.6 + 0.6) }
                expectMaxAtMost("ни одна частица не вылетела наружу", "вылетевших частиц", 1, 400, 0.0)
            }
            view = doubleArrayOf(-6.0, -6.0, 6.0, 6.0)
        }
    }

    // --- 17. Тело падает в карман, уже наполовину набитый частицами ---
    // Карман рельефа открыт влево. Его задняя половина заполнена частицами с шагом 0.8. Квадрат или
    // круг летит в карман и упирается в заднюю стенку, вдавливая частицы вглубь.
    for ((label, s) in listOf("sq6" to Shapes.square(6), "disc4" to Shapes.disc(4))) {
        family.add("xball/pocketfill/$label", "карман наполовину набит, падает ${s.name}",
            "Карман рельефа открыт влево. В его задней половине стоят частицы с шагом 0.8. ${s.name} летит в карман слева со скоростью 1 связь за тик.",
            "Тело вдавливает частицы в глубину кармана и упирается в заднюю стенку; ни одна частица не входит в рельеф; тело не проходит сквозь стенку.",
            600, Limits.hit(1.0)) {
            place(terrainLayouts().first { it.id == "pocket" }, 6.0, jitter = false)
            for (p in xbPoints(7.5, 11.5, -3.2, 3.2, 0.8)) ball(p[0], p[1])
            val b = body(s, approach(s, 0.0, 0.0, 0.0, 1.0, 0.0), 0.0)
            kick(b, 1.0, 0.0)
            probe("частиц в толще рельефа") { xbInsideRock() }
            expectMaxAtMost("ни одна частица не в рельефе", "частиц в толще рельефа", 1, 600, 0.0)
            expectReached(b, 600)
            view = doubleArrayOf(-4.0, -6.0, 18.0, 6.0)
        }
    }

    // --- 18. Частицы зажаты между двумя сходящимися телами ---
    // Два квадрата 8 смотрят друг на друга с промежутком; между ними пять частиц в столбик. Квадраты
    // летят навстречу: частицы оказываются в тесном зажиме между ними.
    for (speed in listOf(0.4, 1.0)) {
        family.add("xball/sandwich/v$speed", "частицы между двумя квадратами, сходятся со скоростью $speed",
            "Два квадрата 8 смотрят друг на друга с промежутком; между ними пять частиц в столбик. Квадраты летят навстречу, каждый со скоростью $speed связи за тик.",
            "Квадраты сжимают частицы, но частицы не входят в ткань и не проходят сквозь неё; тела встречаются; энергия не берётся из ничего.",
            600, Limits.hit(2 * speed).copy(name = "сжатие: энергия не растёт", sleep = false)) {
            val s = Shapes.square(8)
            val h = s.halfWidth
            val a = body(s, -(h + 2.0), 0.0)
            val b = body(s, h + 2.0, 0.0)
            for (k in -2..2) ball(0.0, k * 0.8)
            kick(a, speed, 0.0)
            kick(b, -speed, 0.0)
            probe("зазор частиц до тел") {
                var best = Double.MAX_VALUE
                for (id in balls) best = minOf(best, minOf(xbBodyGap(a, id), xbBodyGap(b, id)))
                best
            }
            expectMinAtLeast("частицы не входят в ткань", "зазор частиц до тел", 1, 600, 0.4)
            expectMet(a, b, 600, 2 * speed)
            view = doubleArrayOf(-8.0, -6.0, 8.0, 6.0)
        }
    }

    return family.list
}
