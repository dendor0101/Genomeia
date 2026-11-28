package io.github.some_example_name.physics.lab.stands

import kotlin.math.cos
import kotlin.math.sin

/*
 * СЕМЕЙСТВА «СОН» И «МАСШТАБ И КРАЯ МИРА».
 *
 * Сон проверяется в каждой сцене каталога (к концу всё обязано уснуть, спящее не двигается).
 * Здесь — то, чего общие правила не видят: спящее тело обязано проснуться от удара, мыши и
 * команды мышце и отозваться так же, как неспящее, а медленно подползающий сосед не должен
 * войти в спящую ткань, как в стену.
 */

/** Сколько живых клеток тела не спит. */
/**
 * Половина просвета между соседними телами, которые сцена ставит рядом «не касаясь», единиц игры.
 * На ровной решётке — прежние 0.4 (сцены обязаны собираться до бита как раньше). У вариаций — по
 * радиусу касания клетки с запасом на сшивание мембраны: у длинных связей он 0.47, и при просвете
 * 0.8 тела стояли внахлёст — 15.11 «куча» и «цепочка» с длинными связями начинались с энергией в
 * 7–12 раз больше внесённой и не засыпали. Это была ошибка расстановки, а не физики.
 */
private fun restGap(s: ShapeSpec): Double =
    if (Variation.current == Variation.BASE) 0.4 else maxOf(0.4, s.contact.max() * 1.05 + 0.1)

internal fun StandWorld.awakeCells(b: StandBody): Int {
    val st = world.state
    var n = 0
    val first = b.first
    for (k in 0 until b.count) { val p = first + k; if (!st.cellDead[p] && !world.isAsleep(p)) n++ }
    return n
}

/** Проба «тело уснуло до события» и ожидание, что за десять тиков до тика [event] оно спало. */
private fun StandWorld.expectAsleepBefore(b: StandBody, event: Int, name: String = "не спит клеток тела") {
    probe(name) { awakeCells(b).toDouble() }
    expectMaxAtMost("тело спало перед событием", name, event - 10, event - 1, 0.0)
}

/** Проба «на сколько связей сдвинулся центр тела от старта» и ожидание, что после тика [from] он сдвинулся хотя бы на [links]. */
private fun StandWorld.expectMoved(b: StandBody, from: Int, until: Int, links: Double, name: String = "смещение тела, связей") {
    val start = centerOf(b)
    probe(name) { val c = centerOf(b); Math.hypot(c[0] - start[0], c[1] - start[1]) / GAME_LINK }
    expectMaxAtLeast("тело сдвинулось от удара", name, from, until, links)
}

internal fun sleepFamily(): List<Scenario> {
    val family = Family("9. Сон")
    val square = Shapes.square(10)
    val wake = 120

    family.add("sleep/bullet", "пуля будит спящее тело", "Квадрат стоит и засыпает; на тике $wake в него летит свободная частица со скоростью 2 связи за тик.",
        "Квадрат спит к моменту удара, просыпается от него и получает толчок, как неспящий; пуля не входит в ткань. К концу всё снова спит.",
        wake + settle(2.0, true), Limits.INTACT) {
        val b = body(square, 0.0, 0.0)
        at(wake) { ball(-(square.halfWidth + 3.0), 0.0, 2.0, 0.0) }
        expectAsleepBefore(b, wake)
        expectMoved(b, wake, wake + 400, 0.02)
    }

    for ((id, speed) in listOf("tap" to 0.15, "bump" to 0.6, "hit" to 2.0)) {
        family.add("sleep/body/$id", "тело будит спящее тело, скорость $speed", "Два квадрата стоят и засыпают; на тике $wake левый получает скорость $speed связи за тик.",
            "Правый квадрат спит к моменту удара, просыпается и получает толчок; клетки не входят в чужую ткань, ничего не рвётся. К концу оба спят.",
            wake + settle(speed), Limits.INTACT) {
            val (a, b) = duel(square, square, 0.0)
            at(wake) { kick(a, speed, 0.0) }
            expectAsleepBefore(b, wake)
            expectPushed(b, speed, wake + settle(speed))
        }
    }

    family.add("sleep/creep", "тело подползает к спящему медленнее порога пробуждения", "Два квадрата стоят и засыпают; на тике $wake левый получает скорость 0.015 связи за тик — ниже порога, с которого касание будит.",
        "Как бы медленно ни подползал сосед, его клетки не входят в спящую ткань: спящее тело либо просыпается, либо держит, как стена.",
        wake + 900, Limits.INTACT.copy(name = "без повреждений, сон не обязателен", sleep = false)) {
        val a = body(square, -(square.halfWidth + 0.45), 0.0)
        body(square, square.halfWidth + 0.45, 0.0)
        during(wake, wake + 600) { kick(a, 0.015, 0.0) }
    }

    family.add("sleep/pile", "тело влетает в четыре спящих", "Четыре квадрата стоят рядом, не касаясь, и засыпают; на тике $wake пятый летит в них со скоростью 0.6 связи за тик.",
        "Спящие просыпаются по мере того, как до них доходит толчок, и расходятся без проникновений и разрывов. К концу все спят.",
        wake + settle(0.6) + 100, Limits.INTACT) {
        val d = square.halfWidth + restGap(square)
        val pile = listOf(body(square, d, d), body(square, d, -d), body(square, d + 2 * d, d), body(square, d + 2 * d, -d))
        val hammer = body(square, -(square.halfWidth + START_GAP + 1.0), 0.0)
        at(wake) { kick(hammer, 0.6, 0.0) }
        expectAsleepBefore(pile[0], wake)
        expectMoved(pile[2], wake, wake + settle(0.6), 0.05, "смещение дальнего тела, связей")
    }

    family.add("sleep/chain", "цепочка спящих тел", "Пять квадратов стоят в ряд, не касаясь, и засыпают; на тике $wake крайний левый получает скорость 0.6 связи за тик.",
        "Толчок идёт по цепочке: каждый следующий просыпается от удара предыдущего. Без проникновений и разрывов; к концу все спят.",
        wake + settle(0.6) + 200, Limits.INTACT) {
        val step = 2 * square.halfWidth + 2 * restGap(square)
        val row = (0 until 5).map { body(square, it * step, 0.0) }
        at(wake) { kick(row[0], 0.6, 0.0) }
        expectAsleepBefore(row[1], wake)
        expectMoved(row[1], wake, wake + 400, 0.05, "смещение второго тела, связей")
    }

    val worm = Shapes.rect(20, 4).muscleAll()
    family.add("sleep/muscle", "команда мышце будит спящее тело", "Мышечный червь стоит и засыпает; на тике $wake мышцы получают команду сократиться, на тике ${wake + 140} — отпустить.",
        "Червь спит к моменту команды, просыпается и сокращается так же, как неспящий; после расслабления возвращает форму и снова засыпает.",
        wake + 140 + settle(0.5), Limits.DRIVEN) {
        val b = body(worm, 0.0, 0.0)
        at(wake) { muscles(b, 0.5f) }
        at(wake + 140) { muscles(b, 1f) }
        expectAsleepBefore(b, wake)
        val left = worm.extreme(-1.0, 0.0); val right = worm.extreme(1.0, 0.0)
        val rest = worm.x[right] - worm.x[left]
        probe("длина к исходной") { distance(b, left, right) / rest }
        expectMinAtMost("спавшее тело сократилось", "длина к исходной", wake + 40, wake + 140, 0.67)
    }

    family.add("sleep/mouse", "мышь будит спящее тело", "Квадрат стоит и засыпает; на тике $wake мышь берёт его крайнюю клетку и ведёт на шесть единиц вправо.",
        "Квадрат спит к моменту захвата, просыпается и идёт за курсором, не рвётся; после отпускания останавливается и засыпает.",
        wake + 250 + settle(0.5), Limits.DRIVEN) {
        val b = body(square, 0.0, 0.0)
        val cell = square.extreme(1.0, 0.0)
        mousePath(b, cell, wake, 3, (0..60).map { doubleArrayOf(square.x[cell] + 6.0 * it / 60.0, square.y[cell]) })
        expectAsleepBefore(b, wake)
        expectMoved(b, wake, wake + 250, 1.0)
    }

    family.add("sleep/yank", "рывок мышью за спящее тело", "Квадрат стоит и засыпает; на тике $wake мышь берёт его крайнюю клетку, курсор разом уходит на 12 единиц.",
        "Квадрат просыпается целиком, а не только схваченная клетка; ткань вправе порваться, но ничего не взрывается; движение затухает.",
        wake + CRASH_TICKS, YANK) {
        val b = body(square, 0.0, 0.0)
        val cell = square.extreme(1.0, 0.0)
        at(wake) { grab(b, cell); dragTo(square.x[cell] + 12.0, square.y[cell] + 5.0) }
        at(wake + 60) { release() }
        expectAsleepBefore(b, wake)
        expectMoved(b, wake, wake + 200, 1.0)
    }

    family.add("sleep/ball-near", "частица спит рядом с телом", "Свободная частица стоит в 0.8 от края квадрата, не касаясь его.",
        "Ничего не происходит: и квадрат, и частица стоят и засыпают.", 150, Limits.INTACT) {
        body(square, 0.0, 0.0)
        ball(square.halfWidth + 0.8, 0.0)
    }

    family.add("sleep/grow-near", "тело растёт вплотную к спящему", "Квадрат стоит и засыпает; на тике $wake рядом начинает расти круг, взрослая раскладка которого заходит в квадрат на единицу.",
        "Растущий круг будит и отодвигает квадрат: ни одна клетка не оказывается в чужой ткани, ничего не рвётся; к концу оба спят.",
        wake + SLOW.ticks(Shapes.disc(5), Shapes.disc(5).nearest(0.0, 0.0)) + settle(0.5), GROW) {
        val disc = Shapes.disc(5)
        val b = body(square, 0.0, 0.0)
        growing(disc, square.halfWidth + disc.halfWidth - 1.0, 0.0, seed = disc.nearest(0.0, 0.0), start = wake, period = SLOW.period, batch = SLOW.batch)
        expectAsleepBefore(b, wake)
        expectMoved(b, wake, wake + 500, 0.5)
    }
    return family.list
}

internal fun scaleFamily(): List<Scenario> {
    val family = Family("10. Масштаб и края мира")
    val big = Shapes.disc(24)
    family.add("big/disc24/rest", "большое тело в покое", "Круг радиуса 24 связи — около 1800 клеток — в позе покоя.",
        "Ничего не происходит: тело не ползёт, не дрожит и засыпает.", 150, Limits.INTACT) {
        body(big, 0.0, 0.0)
    }
    family.add("big/disc24/hit", "малое тело бьёт в большое", "Круг радиуса 6 летит в круг радиуса 24 со скоростью 2 связи за тик.",
        "Большое тело вминается и пружинит; клетки не входят в чужую ткань, ничего не рвётся; к концу оба стоят и спят.", settle(2.0), Limits.INTACT) {
        val (_, target) = duel(Shapes.disc(6), big, 2.0)
        expectPushed(target, 2.0, settle(2.0), share = 0.005)
    }
    family.add("big/disc24/crash", "тело бьёт в большое на пределе", "Круг радиуса 12 летит в круг радиуса 24 со скоростью 6 связей за тик.",
        "Ткань вправе порваться, но тела не проходят друг сквозь друга и ничего не взрывается; движение затухает.", CRASH_TICKS, Limits.CRASH) {
        duel(Shapes.disc(12), big, 6.0)
    }
    val long = Shapes.rect(120, 3)
    family.add("big/worm/rest", "очень длинное тело в покое", "Полоса 120×3 клетки в позе покоя.",
        "Ничего не происходит: полоса не изгибается сама, не ползёт и засыпает.", 150, Limits.INTACT) {
        body(long, 0.0, 0.0)
    }
    family.add("big/worm/bump", "удар в середину очень длинного тела", "Круг радиуса 4 летит снизу в середину полосы 120×3 со скоростью 0.6 связи за тик.",
        "Полоса прогибается, волна уходит к концам и затухает. Без разрывов и проникновений; к концу всё стоит и спит.", settle(0.6) + 300, Limits.INTACT) {
        val b = body(long, 0.0, 0.0)
        val disc = Shapes.disc(4)
        val hammer = body(disc, 0.0, -(long.halfHeight + disc.reach + START_GAP))
        kick(hammer, 0.0, 0.6)
        expectPushed(b, 0.6, 400, share = 0.01)
    }
    family.add("big/crowd", "толпа из двенадцати разных тел", "Двенадцать тел разных фигур стоят по кругу и летят к центру со скоростью 0.4 связи за тик.",
        "Тела сталкиваются, сминаются и расталкиваются. Без разрывов, гибели и проникновений; толпа успокаивается и засыпает.",
        settle(0.4) + 300, Limits.INTACT) {
        val shapes = listOf(Shapes.square(8), Shapes.disc(4), Shapes.rect(12, 4), Shapes.star(5, 7, 3), Shapes.uShape(12, 9, 3), Shapes.blob(2, 5),
            Shapes.square(8).boneCore(), Shapes.ring(6, 3), Shapes.triangle(9), Shapes.cross(6, 4), Shapes.ellipse(8, 4), Shapes.crescent(7))
        val r = 22.0
        shapes.forEachIndexed { k, s ->
            val a = 2 * Math.PI * k / shapes.size
            val b = body(s, r * cos(a), r * sin(a), a)
            kick(b, -0.4 * cos(a), -0.4 * sin(a))
        }
    }

    // --- сцены крупнее одного блока решателя (1000 частиц) ---
    // Их отдельная задача — сверка «один поток = воркеры»: в мелкой сцене блок один, и
    // параллельным стадиям нечего делить. Здесь блоков два-четыре, а внутри — всё, что умеет
    // игра: столкновения многих тел, кости, мышцы, рост, свободные частицы, рельеф, сон.
    family.add("big/herd", "стадо из тридцати шести разных тел", "Тридцать шесть тел восьми фигур стоят двумя кольцами и летят к центру со скоростью 0.6 связи за тик.",
        "Тела сталкиваются, сминаются и расталкиваются. Без разрывов, гибели и проникновений; стадо успокаивается и засыпает.",
        settle(0.6) + 500, Limits.INTACT) {
        val shapes = listOf(Shapes.square(8), Shapes.disc(4), Shapes.rect(12, 4), Shapes.star(5, 7, 3), Shapes.blob(2, 5), Shapes.ring(6, 3), Shapes.triangle(9), Shapes.ellipse(8, 4))
        var k = 0
        // Кольца раздвигаются вместе с телами: у вариации с длинными связями тела в полтора раза крупнее.
        val wide = Variation.current.spread
        for ((r, count) in listOf(20.0 to 14, 32.0 to 22)) for (i in 0 until count) {
            val a = 2 * Math.PI * i / count + r
            val b = body(shapes[k++ % shapes.size], r * wide * cos(a), r * wide * sin(a), a)
            kick(b, -0.6 * cos(a), -0.6 * sin(a))
        }
    }
    family.add("big/boneyard", "тридцать тел с костями сходятся в кучу", "Тридцать тел с костными ядрами, оболочками и хребтами стоят двумя кольцами и летят к центру со скоростью 0.5 связи за тик.",
        "Тела сталкиваются и расталкиваются; кости не мнутся, клетки не входят в чужую ткань, ничего не рвётся; куча успокаивается и засыпает.",
        settle(0.5) + 500, Limits.INTACT) {
        val shapes = listOf(Shapes.square(10).boneCore(), Shapes.disc(6).boneShell(), Shapes.rect(18, 6).boneSpine(), Shapes.square(8).boneAll(), Shapes.rect(18, 6).bonePlates(3))
        var k = 0
        val wide = Variation.current.spread
        for ((r, count) in listOf(22.0 to 12, 36.0 to 18)) for (i in 0 until count) {
            val a = 2 * Math.PI * i / count + r
            val b = body(shapes[k++ % shapes.size], r * wide * cos(a), r * wide * sin(a), a)
            kick(b, -0.5 * cos(a), -0.5 * sin(a))
        }
    }
    family.add("big/school", "стая из двадцати четырёх червей качает мышцами", "Двадцать четыре червя 24×4 стоят тесной стаей; верхняя и нижняя половины каждого попеременно сокращаются, у соседей — со сдвигом по времени.",
        "Черви извиваются и задевают соседей. Мышцы не рвут ткань, клетки не входят ни в свою, ни в чужую ткань; после расслабления стая возвращает форму и засыпает.",
        340 + settle(0.5) + 300, Limits.DRIVEN) {
        val worm = Shapes.rect(24, 4).muscleTopBottom()
        val bodies = ArrayList<StandBody>()
        val wide = Variation.current.spread
        for (row in 0 until 6) for (col in 0 until 4) bodies.add(body(worm, (col - 1.5) * 16.0 * wide, (row - 2.5) * 4.6 * wide))
        during(20, 320) { t ->
            for ((i, b) in bodies.withIndex()) {
                val phase = ((t + i * 13) / 40) % 2
                muscle(b, 0, if (phase == 0) 0.5f else 1.5f)
                muscle(b, 1, if (phase == 0) 1.5f else 0.5f)
            }
        }
        at(321) { for (b in bodies) muscles(b, 1f) }
    }
    family.add("big/nursery", "шестнадцать тел растут вплотную друг к другу", "Шестнадцать зигот стоят решёткой с шагом семь единиц; взрослые круги радиуса 6 чуть шире этого шага. Растут слоями.",
        "Тела вырастают целиком и упираются друг в друга: ни одна клетка не оказывается в чужой ткани, ничего не рвётся и не гибнет. После роста всё замирает и засыпает.",
        LAYERS.ticks(Shapes.disc(6), Shapes.disc(6).nearest(0.0, 0.0)) + settle(0.5) + 300, GROW_TIGHT) {
        val disc = Shapes.disc(6)
        val center = disc.nearest(0.0, 0.0)
        for (row in 0 until 4) for (col in 0 until 4)
            growing(disc, (col - 1.5) * 7.0, (row - 1.5) * 7.0, seed = center, start = (row * 4 + col) % 5, period = LAYERS.period, batch = LAYERS.batch)
    }
    family.add("big/hail", "град из пятисот частиц по телам в кармане рельефа", "Девять тел стоят в кармане рельефа; пятьсот свободных частиц летят в карман со скоростью 1 связь за тик.",
        "Частицы осыпают тела и набиваются в карман. Ни одна не входит в ткань и в рельеф, ничего не рвётся и не гибнет; движение затухает.",
        900, Limits.INTACT.copy(name = "град: без повреждений", sleep = false)) {
        val pocket = terrainLayouts().first { it.id == "pocket" }
        place(pocket, 8.0)
        // Карман: десять единиц в высоту и около одиннадцати в глубину; тела — тремя столбцами по три.
        val shapes = listOf(Shapes.square(5), Shapes.disc(2), Shapes.square(5).boneAll(), Shapes.ribbon(5))
        // Карман рельефа своего размера не меняет: крупным телам (вариации с длинными связями) в нём хватает места на четыре, а не на девять.
        val wide = Variation.current.spread
        val per = if (wide > 1.0) 2 else 3
        val step = if (wide > 1.0) 3.5 * wide else 3.2
        for (i in 0 until per * per) body(shapes[i % shapes.size], 9.8 + (i / per) * step, (i % per - (per - 1) / 2.0) * step)
        for (i in 0 until 500) ball(-2.0 - (i / 20) * 0.9, (i % 20 - 9.5) * 0.75, 1.0, 0.0)
        view = doubleArrayOf(-26.0, -11.0, 26.0, 11.0)
    }
    family.add("big/dormitory", "спящее поле из сорока тел, одно будят ударом", "Сорок квадратов стоят решёткой и спят; на сотом тике маленький круг бьёт в угловой квадрат со скоростью 2 связи за тик.",
        "Просыпается только то, до чего дошёл удар; дальние тела продолжают спать и не двигаются. Потом всё снова засыпает.",
        100 + settle(2.0), Limits.INTACT) {
        val square = Shapes.square(8)
        val step = 2 * square.halfWidth + 1.4
        val bodies = ArrayList<StandBody>()
        for (row in 0 until 5) for (col in 0 until 8) bodies.add(body(square, col * step, row * step))
        val far = bodies.last()
        val hammer = Shapes.disc(2)
        // Молоток стоит рядом с самого начала и спит вместе со всеми: тело, добавленное посреди
        // сцены, сдвинуло бы счёт монитора, который помнит частицы по номерам.
        val h = body(hammer, -(separation(hammer, square, CONTACT_DISTANCE) + START_GAP), 0.0)
        at(100) { kick(h, 2.0, 0.0) }
        mustTouch(h, bodies[0], "молоток долетел до ближнего тела")
        probe("не спит клеток дальнего тела") { awakeCells(far).toDouble() }
        expectMaxAtMost("дальнее тело не проснулось", "не спит клеток дальнего тела", 80, 100 + settle(2.0), 0.0)
        probe("скорость ближнего тела") { speedOf(bodies[0]) }
        expectMaxAtLeast("ближнее тело получило удар", "скорость ближнего тела", 100, 300, 0.02)
    }

    // Далеко от начала координат: та же сцена, что «толчок» у квадрата, но в трёх тысячах единиц от нуля.
    family.add("far/bump", "толчок вдали от начала координат", "Два квадрата в трёх тысячах единиц от нуля; левый летит в правый со скоростью 0.6 связи за тик.",
        "Всё как рядом с нулём: тела сталкиваются и отскакивают без разрывов и проникновений, не дрожат и засыпают. Точности координат хватает.",
        settle(0.6), Limits.INTACT) {
        val s = Shapes.square(10)
        val a = body(s, 3000.0 - (s.halfWidth + GAME_RADIUS + START_GAP / 2), 3000.0)
        val b = body(s, 3000.0 + s.halfWidth + GAME_RADIUS + START_GAP / 2, 3000.0)
        kick(a, 0.6, 0.0)
        expectPushed(b, 0.6, settle(0.6))
    }
    family.add("far/rest", "покой вдали от начала координат", "Квадрат в трёх тысячах единиц от нуля, в позе покоя.",
        "Ничего не происходит: тело не ползёт и не дрожит от нехватки точности и засыпает.", 150, Limits.INTACT) {
        body(Shapes.square(10), 3000.0, 3000.0)
    }

    // Рамка мира со стенкой, как в игре.
    fun StandWorld.expectInside(width: Int, height: Int, until: Int) {
        probe("частиц за рамкой") {
            val st = world.state
            var out = 0
            for (i in 0 until st.n) {
                if (st.cellDead[i]) continue
                val x = gameX(i); val y = gameY(i)
                if (x < 0.0 || x > width.toDouble() || y < 0.0 || y > height.toDouble()) out++
            }
            out.toDouble()
        }
        expectMaxAtMost("никто не вышел за рамку мира", "частиц за рамкой", 1, until, 0.0)
    }
    val runners = listOf(Shapes.square(8), Shapes.square(8).boneAll(), Shapes.square(10).boneCore(), Shapes.ribbon(12))
    for (shape in runners) for (hit in HITS) {
        val ticks = if (hit.rough) CRASH_TICKS else settle(hit.speed)
        family.add("frame/wall/${shape.id}/${hit.id}", "${shape.name} в рамку мира: ${hit.title}",
            "Мир 30×20 со стенкой вдоль рамки. Тело летит вправо в стенку со скоростью ${hit.speed} связи за тик.",
            "Тело упирается в стенку, не входит в неё и не выходит за рамку. " + hit.expect, ticks, hit.limits) {
            arena(30, 20)
            val x0 = 29.0 - shape.halfWidth - GAME_RADIUS - START_GAP
            val b = body(shape, x0 + approach(shape, x0, 10.0, 0.0, 1.0, 0.0), 10.0)
            kick(b, hit.speed, 0.0)
            expectReached(b, ticks)
            expectInside(30, 20, ticks)
        }
        family.add("frame/corner/${shape.id}/${hit.id}", "${shape.name} в угол мира: ${hit.title}",
            "Мир 30×20 со стенкой вдоль рамки. Тело летит наискось в правый нижний угол со скоростью ${hit.speed} связи за тик.",
            "Тело упирается в обе стенки, не входит в них и не выходит за рамку. " + hit.expect, ticks, hit.limits) {
            arena(30, 20)
            val d = shape.halfWidth + GAME_RADIUS + START_GAP
            val shift = approach(shape, 29.0 - d, 1.0 + d, 0.0, 0.7071, -0.7071)
            val b = body(shape, 29.0 - d + shift * 0.7071, 1.0 + d - shift * 0.7071)
            kick(b, hit.speed * 0.7071, -hit.speed * 0.7071)
            expectReached(b, ticks)
            expectInside(30, 20, ticks)
        }
    }
    for (shape in listOf(Shapes.square(8), Shapes.square(8).boneAll(), Shapes.rect(12, 4).muscleAll())) {
        family.add("frame/mouse/${shape.id}", "${shape.name}: мышь тащит тело за угол мира",
            "Мир 30×20 со стенкой. Мышь берёт дальнюю клетку тела и плавно уводит курсор за правый нижний угол, на шесть единиц за рамку.",
            "Тело прижимается к углу и сминается, но не выходит за рамку, не входит в стенку и не рвётся; после отпускания расправляется и засыпает.",
            300 + settle(0.5), Limits.DRIVEN) {
            arena(30, 20)
            val b = body(shape, 22.0, 7.0)
            val cell = shape.extreme(-1.0, 1.0)
            val p = b.particle(cell)
            val sx = gameX(p); val sy = gameY(p)
            mousePath(b, cell, 10, 3, (0..80).map { doubleArrayOf(sx + (36.0 - sx) * it / 80.0, sy + (-6.0 - sy) * it / 80.0) })
            expectInside(30, 20, 300 + settle(0.5))
        }
    }
    return family.list
}
