package io.github.some_example_name.physics.lab.stands

/*
 * СЕМЕЙСТВА «ФИГУРА × РЕЖИМ»: простые фигуры, фигуры с вогнутостями, фигуры с костями.
 * Режимы одни на все три семейства (bodyRegimes): покой, покой рядом, касание в покое, удары
 * четырёх сил, скользящий удар, мышь мягко, рывок мышью, тело вдавливают мышью в соседа, куча.
 */

/** Рывок мышью рвёт ткань законно: игрок так отрывает куски. Взрыва и проникновений быть не должно. */
internal val YANK = Limits.CRASH.copy(name = "рывок: разрыв допустим", energy = Energy.DRIVEN)

/** Тела поставлены с лёгким перекрытием касания: они разойдутся, поэтому энергия не «закрытая». */
internal val TOUCH = Limits.INTACT.copy(name = "касание в покое", energy = Energy.SETTLES)

/** Все режимы для одной фигуры. [prefix] — начало номера сцены, [family] — куда складывать. */
internal fun bodyRegimes(family: Family, prefix: String, shape: ShapeSpec, pile: Boolean = true) {
    val name = shape.name
    val id = "$prefix/${shape.id}"
    val bones = if (shape.boneCount > 0) " Кости не мнутся." else ""
    val slow = !shape.hasTissue

    family.add("$id/rest", "$name: покой", "Одно тело в позе покоя, без толчков.",
        "Ничего не происходит: тело не ползёт, не вращается, не дрожит и засыпает.$bones", 150, Limits.INTACT) {
        body(shape, 0.0, 0.0)
    }

    family.add("$id/near", "$name: два тела рядом", "Два одинаковых тела стоят почти вплотную, не касаясь (между ближайшими клетками 0.8).",
        "Соседство без касания ничего не делает: оба тела стоят и засыпают.", 150, Limits.INTACT) {
        val half = separation(shape, shape, 0.8) / 2
        val a = body(shape, -half, 0.0); val b = body(shape, half, 0.0)
        mustNotTouch(a, b, "тела, поставленные рядом, коснулись")
    }

    family.add("$id/touch", "$name: касание в покое", "Два одинаковых тела поставлены с лёгким перекрытием касания (между ближайшими клетками 0.5 при пороге 0.6).",
        "Тела мягко раздвигаются на расстояние касания и замирают: без разгона, дрожи, разрывов и гибели; затем засыпают.$bones",
        settle(0.1, slow), TOUCH) {
        val half = separation(shape, shape, 0.5) / 2
        body(shape, -half, 0.0); body(shape, half, 0.0)
    }

    for (hit in HITS) {
        val ticks = if (hit.rough) CRASH_TICKS else settle(hit.speed, slow)
        family.add("$id/${hit.id}", "$name: ${hit.title}", "Левое тело летит в стоящее такое же со скоростью ${hit.speed} связи за тик.",
            hit.expect + bones, ticks, hit.limits) {
            val (_, target) = duel(shape, shape, hit.speed)
            expectPushed(target, hit.speed, ticks)
        }
    }

    family.add("$id/glance", "$name: скользящий удар с вращением", "Левое тело летит со скоростью 0.6 связи за тик, сдвинуто на полвысоты и вращается.",
        "Тела задевают друг друга краями и расходятся, закручиваясь. Без разрывов, гибели и проникновений; к концу всё стоит и спит.$bones",
        settle(0.6, slow), Limits.INTACT) {
        duel(shape, shape, 0.6, offset = shape.halfHeight, spin = 0.02)
    }

    family.add("$id/drag", "$name: мышь ведёт тело по кругу", "Мышь берёт крайнюю правую клетку и плавно ведёт её по кругу, затем отпускает.",
        "Тело послушно идёт за курсором, не рвётся и не теряет клеток; после отпускания останавливается, возвращает форму и засыпает.$bones",
        220 + settle(0.5, slow), Limits.DRIVEN) {
        val b = body(shape, 0.0, 0.0)
        val cell = shape.extreme(1.0, 0.0)
        val r = shape.halfWidth + 3.0
        mousePath(b, cell, 10, 2, circlePath(0.0, 0.0, r, 100).map { doubleArrayOf(it[0] - r + shape.x[cell], it[1] + shape.y[cell]) })
    }

    family.add("$id/yank", "$name: рывок мышью", "Мышь берёт крайнюю правую клетку, курсор разом уходит на 12 единиц в сторону и стоит там 60 тиков.",
        "Клетка летит к курсору и тащит тело. Ткань вправе порваться, но ничего не взрывается, оторванное не проходит сквозь тело, после отпускания движение затухает.$bones",
        CRASH_TICKS, YANK) {
        val b = body(shape, 0.0, 0.0)
        val cell = shape.extreme(1.0, 0.0)
        at(10) { grab(b, cell); dragTo(shape.x[cell] + 12.0, shape.y[cell] + 5.0) }
        at(70) { release() }
    }

    family.add("$id/shove", "$name: мышь вдавливает тело в соседа", "Мышь берёт дальний край левого тела и медленно ведёт его сквозь место, где стоит правое.",
        "Левое тело упирается в правое и толкает его перед собой: клетки не входят в чужую ткань, ничего не рвётся; после отпускания оба тела успокаиваются и засыпают.$bones",
        260 + settle(0.5, slow), Limits.DRIVEN) {
        val (a, b) = duel(shape, shape, 0.0)
        val cell = shape.extreme(-1.0, 0.0)
        val start = centerOf(a)
        val goal = centerOf(b)
        val path = (0..80).map { doubleArrayOf(start[0] + shape.x[cell] + (goal[0] - start[0] + 4.0) * it / 80.0, shape.y[cell]) }
        mousePath(a, cell, 10, 3, path)
    }

    if (pile) family.add("$id/pile", "$name: четыре тела сходятся в кучу", "Четыре одинаковых тела летят к общему центру со скоростью 0.3 связи за тик.",
        "Тела сталкиваются, сминаются и расталкиваются. Без разрывов, гибели и проникновений; куча успокаивается и засыпает.$bones",
        settle(0.3, slow) + 100, Limits.INTACT) {
        val d = shape.reach + START_GAP
        val spots = arrayOf(doubleArrayOf(-d, -d), doubleArrayOf(d, -d), doubleArrayOf(d, d), doubleArrayOf(-d, d))
        for (s in spots) {
            val b = body(shape, s[0], s[1])
            val len = Math.hypot(s[0], s[1])
            kick(b, -0.3 * s[0] / len, -0.3 * s[1] / len)
        }
    }
}

internal fun primitiveFamily(): List<Scenario> {
    val family = Family("1. Простые фигуры")
    val shapes = listOf(
        Shapes.square(6), Shapes.square(12), Shapes.disc(3), Shapes.disc(6), Shapes.rect(16, 4),
        Shapes.triangle(9), Shapes.ellipse(8, 4), Shapes.ring(6, 3), Shapes.ribbon(14), Shapes.rope(12),
    )
    for (shape in shapes) bodyRegimes(family, "prim", shape)

    // Разные фигуры друг о друга: до сих пор тело сталкивалось только со своей копией.
    val pairs = listOf(
        Shapes.disc(3) to Shapes.square(12), Shapes.square(12) to Shapes.disc(3), Shapes.triangle(9) to Shapes.ring(6, 3),
        Shapes.rect(16, 4) to Shapes.ribbon(14), Shapes.rope(12) to Shapes.disc(6), Shapes.disc(6) to Shapes.rope(12),
    )
    for ((a, b) in pairs) for (hit in HITS) {
        val slow = !a.hasTissue || !b.hasTissue
        val ticks = if (hit.rough) CRASH_TICKS else settle(hit.speed, slow)
        family.add("prim/${a.id}-vs-${b.id}/${hit.id}", "${a.name} в ${b.name}: ${hit.title}",
            "${a.name} летит в стоящее тело «${b.name}» со скоростью ${hit.speed} связи за тик.", hit.expect, ticks, hit.limits) {
            val (_, target) = duel(a, b, hit.speed)
            expectPushed(target, hit.speed, ticks, share = 0.05, hitterCells = a.count)
        }
    }
    return family.list
}

internal fun complexShapes(): List<ShapeSpec> = listOf(
    Shapes.lShape(10, 4), Shapes.uShape(12, 9, 3), Shapes.cShape(7, 4), Shapes.cross(6, 4), Shapes.star(5, 8, 3),
    Shapes.comb(4, 2, 5, 3), Shapes.dumbbell(3, 6, 3), Shapes.crescent(7), Shapes.frame(11, 3), Shapes.gear(5, 6),
    Shapes.hourglass(10, 12, 4), Shapes.snake(22, 3, 3), Shapes.blob(1, 5), Shapes.blob(2, 6), Shapes.blob(3, 5),
    Shapes.coral(1, 5), Shapes.coral(2, 6), Shapes.coral(3, 7),
)

internal fun complexFamily(): List<Scenario> {
    val family = Family("2. Вогнутости и рваный контур")
    for (shape in complexShapes()) bodyRegimes(family, "cave", shape, pile = false)

    // Фигуры, которые входят друг в друга: выступ во впадину — самое трудное место для касаний.
    val u = Shapes.uShape(12, 9, 3)
    val rod = Shapes.rect(4, 10)
    for (hit in HITS) {
        val ticks = if (hit.rough) CRASH_TICKS else settle(hit.speed)
        family.add("cave/key-in-u/${hit.id}", "брусок входит в подкову: ${hit.title}",
            "Узкий брусок летит сверху вниз прямо в вырез подковы со скоростью ${hit.speed} связи за тик.",
            "Брусок входит в вырез и упирается в дно, не проходя сквозь стенки. " + hit.expect, ticks, hit.limits) {
            val cup = body(u, 0.0, 0.0)
            // Брусок ставится над вырезом так, чтобы до первого касания со стенкой или дном было START_GAP.
            val far = u.reach + rod.reach + 4.0
            val travel = travelToContact(rod, 0.0, far, 0.0, u, 0.0, 0.0, 0.0, 0.0, -1.0)
            val key = body(rod, 0.0, far - (travel - START_GAP), 0.0)
            kick(key, 0.0, -hit.speed)
            expectPushed(cup, hit.speed, ticks, share = 0.05, hitterCells = rod.count)
        }
    }
    val comb = Shapes.comb(4, 2, 5, 3)
    for (hit in SOFT_HITS) {
        val ticks = settle(hit.speed)
        family.add("cave/combs/${hit.id}", "две гребёнки зубцами навстречу: ${hit.title}",
            "Верхняя гребёнка перевёрнута и сдвинута на зубец; летит вниз на нижнюю со скоростью ${hit.speed} связи за тик.",
            "Зубцы входят между зубцами и упираются. Клетки одной гребёнки не оказываются внутри ткани другой. " + hit.expect, ticks, hit.limits) {
            body(comb, 0.0, 0.0)
            val far = 2 * comb.reach + 4.0
            val travel = travelToContact(comb, 2 * GAME_LINK, far, Math.PI, comb, 0.0, 0.0, 0.0, 0.0, -1.0)
            val top = body(comb, 2 * GAME_LINK, far - (travel - START_GAP), Math.PI)
            kick(top, 0.0, -hit.speed)
        }
    }
    val ring = Shapes.ring(8, 5)
    val ball = Shapes.disc(2)
    for (speed in listOf(0.6, 2.0, 6.0)) {
        val crash = speed > 3
        family.add("cave/disc-in-ring/v$speed", "круг внутри кольца, скорость $speed",
            "Маленький круг стоит в дыре кольца и получает скорость $speed связи за тик вправо.",
            "Круг бьётся о стенку кольца изнутри и остаётся внутри: сквозь целую стенку он пройти не может." +
                (if (crash) " На такой скорости стенка вправе порваться." else " Ничего не рвётся; к концу всё стоит и спит."),
            if (crash) CRASH_TICKS else settle(speed), if (crash) Limits.CRASH else Limits.INTACT) {
            val outer = body(ring, 0.0, 0.0)
            val inner = body(ball, 0.0, 0.0)
            kick(inner, speed, 0.0)
            if (!crash) {
                // Пока кольцо цело, круг не дальше его внутреннего радиуса от центра кольца.
                probe("круг от центра кольца") {
                    val a = centerOf(inner); val b = centerOf(outer)
                    Math.hypot(a[0] - b[0], a[1] - b[1]) / GAME_LINK
                }
                // Дыра кольца растёт вместе с решёткой вариации: порог в единицах игры — с той же поправкой.
                expectMaxAtMost("круг остался внутри кольца", "круг от центра кольца", 1, settle(speed), 5.0 * Variation.current.spread)
            }
        }
    }
    val hook = Shapes.cShape(7, 4)
    for (hit in SOFT_HITS) {
        val ticks = settle(hit.speed)
        family.add("cave/hooks/${hit.id}", "два разомкнутых кольца сцепляются: ${hit.title}",
            "Правое кольцо развёрнуто разрезом влево; левое летит разрезом вперёд со скоростью ${hit.speed} связи за тик.",
            "Кольца входят друг в друга через разрезы или отскакивают — но только через разрез, не сквозь ткань. " + hit.expect, ticks, hit.limits) {
            val lift = hook.halfHeight * 0.6
            val apart = separation(hook, hook, CONTACT_DISTANCE, lift, 0.0, Math.PI) + START_GAP
            val right = body(hook, apart / 2, 0.0, Math.PI)
            val left = body(hook, -apart / 2, lift)
            kick(left, hit.speed, 0.0)
            expectPushed(right, hit.speed, ticks, share = 0.02)
        }
    }
    return family.list
}

internal fun boneShapes(): List<ShapeSpec> = listOf(
    Shapes.square(10).boneCore(), Shapes.square(10).boneShell(), Shapes.square(10).boneLeft(),
    Shapes.square(10).boneScattered(1, 3, 6), Shapes.square(8).boneAll(),
    Shapes.disc(6).boneCore(), Shapes.disc(6).boneShell(),
    Shapes.rect(18, 6).boneSpine(), Shapes.rect(18, 6).bonePlates(3), Shapes.rect(18, 6).boneScattered(2, 4, 5),
    Shapes.rect(12, 4).boneAll(),
    Shapes.uShape(12, 9, 3).boneShell(), Shapes.star(5, 8, 3).boneCore(0.9), Shapes.cross(6, 4).boneCore(0.8),
    Shapes.ring(6, 3).boneLeft(), Shapes.blob(2, 6).boneScattered(3, 4, 6),
)

internal fun boneFamily(): List<Scenario> {
    val family = Family("3. Тела с костями")
    for (shape in boneShapes()) {
        bodyRegimes(family, "bone", shape, pile = false)
        val name = shape.name
        val id = "bone/${shape.id}"
        // Тяга за костную клетку: кость тянется целиком, а не одной клеткой.
        val cluster = shape.boneClusters().maxByOrNull { it.size } ?: continue
        val cell = cluster.maxByOrNull { shape.x[it] } ?: continue
        family.add("$id/bonedrag", "$name: мышь ведёт кость по кругу", "Мышь берёт крайнюю клетку самой большой кости и плавно ведёт её по кругу, затем отпускает.",
            "Кость идёт за курсором целиком и не мнётся, тело следует за ней без разрывов; после отпускания всё останавливается и засыпает.",
            220 + settle(0.5), Limits.DRIVEN) {
            val b = body(shape, 0.0, 0.0)
            val r = shape.halfWidth + 3.0
            mousePath(b, cell, 10, 2, circlePath(0.0, 0.0, r, 100).map { doubleArrayOf(it[0] - r + shape.x[cell], it[1] + shape.y[cell]) })
        }
        family.add("$id/boneyank", "$name: рывок за кость", "Мышь берёт клетку кости, курсор разом уходит на 12 единиц в сторону и стоит там 60 тиков.",
            "Кость летит к курсору целиком, не разлетаясь и не превышая потолка скорости; мягкая ткань вправе порваться, но ничего не взрывается.",
            CRASH_TICKS, YANK.copy(capHits = false)) {
            val b = body(shape, 0.0, 0.0)
            at(10) { grab(b, cell); dragTo(shape.x[cell] + 12.0, shape.y[cell] + 5.0) }
            at(70) { release() }
        }
    }

    // Кость о мягкое и мягкое о кость: одна сторона уступает целиком.
    val brick = Shapes.square(8).boneAll()
    val soft = Shapes.square(10)
    for (hit in HITS) {
        val ticks = if (hit.rough) CRASH_TICKS else settle(hit.speed)
        family.add("bone/brick-into-soft/${hit.id}", "костный кирпич в мягкий квадрат: ${hit.title}",
            "Целиком костное тело летит в мягкий квадрат со скоростью ${hit.speed} связи за тик.",
            "Кирпич вминается в мягкую ткань, но не входит в неё и не мнётся сам. " + hit.expect, ticks, hit.limits) {
            val (_, target) = duel(brick, soft, hit.speed)
            expectPushed(target, hit.speed, ticks)
        }
        family.add("bone/soft-into-brick/${hit.id}", "мягкий квадрат в костный кирпич: ${hit.title}",
            "Мягкий квадрат летит в стоящее костное тело со скоростью ${hit.speed} связи за тик.",
            "Мягкая ткань сминается о кирпич и не входит в него; кирпич не мнётся. " + hit.expect, ticks, hit.limits) {
            val (_, target) = duel(soft, brick, hit.speed)
            expectPushed(target, hit.speed, ticks)
        }
    }
    return family.list
}
