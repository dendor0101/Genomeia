package io.github.some_example_name.physics.lab.stands

/*
 * СЕМЕЙСТВО «РОСТ». Тело растёт так же, как организм игры: раскладка заведена целиком,
 * рождена одна клетка, остальные рождаются делением живой соседки, кость включается, когда
 * клетка доросла. Растут те же фигуры, что проходят удары и мышцы, — с разных сторон и в
 * разном темпе.
 *
 * Что проверяется сверх общих правил: родились все клетки, тело выросло в задуманную форму
 * (длины связей и расстояния между дальними клетками — как в описании), кости получились той
 * формы, что в описании, а новорождённая ткань не вросла в рельеф и в соседей.
 */

/** Рост без повреждений: энергию вносит сам рост, рваться и гибнуть нечему. */
internal val GROW = Limits.DRIVEN.copy(name = "рост без повреждений")

/** Рост в тесноте: телу негде принять свою форму, она не судится. Рваться и гибнуть по-прежнему нельзя. */
internal val GROW_TIGHT = GROW.copy(name = "рост в тесноте: форма не судится", shape = false)

/** Рост взаперти, где взрослое тело не помещается: ткань вправе порваться, но не взорваться и не войти в рельеф. */
internal val GROW_JAM = YANK.copy(name = "рост взаперти: разрыв допустим")

/** Темп роста: по сколько клеток и как часто. */
internal class Pace(val id: String, val name: String, val period: Int, val batch: Int) {
    /** Сколько тиков займёт рост фигуры от клетки [seed]. */
    fun ticks(shape: ShapeSpec, seed: Int): Int =
        if (batch == 0) (shape.growthFrom(seed).depth.max() + 1) * period else shape.count * period / batch + period
}

internal val SLOW = Pace("slow", "по клетке раз в три тика", 3, 1)
internal val FAST = Pace("fast", "по две клетки за тик", 1, 2)
internal val LAYERS = Pace("layers", "слоями: все клетки очередного слоя разом, раз в 12 тиков", 12, 0)

/** Откуда растёт тело. */
internal class Seed(val id: String, val name: String, val pick: (ShapeSpec) -> Int)

internal val SEEDS = listOf(
    Seed("center", "из середины") { it.nearest(0.0, 0.0) },
    Seed("edge", "с левого края") { it.extreme(-1.0, 0.0) },
    Seed("corner", "из нижнего левого угла") { it.extreme(-1.0, -1.0) },
)

private const val GROWN = "Тело вырастает целиком и в задуманную форму: ни одного разрыва и ни одной погибшей клетки, ничего не взрывается. "
private const val SETTLED = "После роста тело останавливается и засыпает."

private fun bonesNote(shape: ShapeSpec) = if (shape.boneCount > 0) "Кости получаются той формы, что в описании, а не той, в какой их застал рост. " else ""

internal fun growthFamily(): List<Scenario> {
    val family = Family("8. Рост")
    val shapes = listOf(
        Shapes.square(8), Shapes.disc(5), Shapes.rect(16, 4), Shapes.ring(6, 3), Shapes.uShape(12, 9, 3), Shapes.star(5, 8, 3),
        Shapes.blob(2, 6), Shapes.ribbon(14), Shapes.rope(12),
        Shapes.square(10).boneCore(), Shapes.rect(18, 6).boneSpine(), Shapes.rect(18, 6).bonePlates(3),
        Shapes.square(8).boneAll(), Shapes.disc(6).boneShell(), armShape(30, 7),
    )

    // --- рост в чистом поле ---
    for (shape in shapes) for (seed in SEEDS) for (pace in listOf(SLOW, LAYERS, FAST)) {
        if (pace === FAST && seed.id != "center") continue
        val cell = seed.pick(shape)
        val ticks = pace.ticks(shape, cell) + settle(0.3, !shape.hasTissue)
        family.add("grow/open/${shape.id}/${seed.id}-${pace.id}", "${shape.name}: рост ${seed.name}, ${pace.name}",
            "Тело растёт из одной клетки в пустом мире — ${seed.name}, ${pace.name}.",
            GROWN + bonesNote(shape) + SETTLED, ticks, GROW) {
            growing(shape, 0.0, 0.0, seed = cell, period = pace.period, batch = pace.batch)
        }
    }

    // --- рост у стены ---
    val wall = terrainLayouts().first { it.id == "wall" }
    val nearWall = listOf(Shapes.square(8), Shapes.disc(5), Shapes.rect(16, 4), Shapes.star(5, 8, 3), Shapes.square(10).boneCore(), Shapes.rope(12))
    for (shape in nearWall) for (pace in listOf(SLOW, LAYERS)) {
        val center = shape.nearest(0.0, 0.0)
        val ticks = pace.ticks(shape, center) + settle(0.3, !shape.hasTissue)
        family.add("grow/wall/${shape.id}/into-${pace.id}", "${shape.name}: рост в стену, ${pace.name}",
            "Тело растёт из середины рядом со стеной рельефа: взрослая раскладка заходит в стену на две связи.",
            "Новорождённая ткань не врастает в рельеф: тело упирается в стену и отодвигается от неё. " + GROWN + bonesNote(shape) + SETTLED, ticks, GROW) {
            // Правый край взрослой раскладки — на halfWidth правее центра фигуры; стена — на две связи ближе.
            val face = shape.halfWidth - shape.x[center] - 2 * GAME_LINK
            place(wall, face + TERRAIN_RADIUS + TERRAIN_JITTER / 2)
            growing(shape, 0.0, 0.0, seed = center, period = pace.period, batch = pace.batch)
            // Стена высотой в сорок клеток карты; в кадре — взрослое тело и кусок стены рядом с ним.
            view = doubleArrayOf(-shape.halfWidth - 3.0, -shape.halfHeight - 4.0, shape.halfWidth + 5.0, shape.halfHeight + 4.0)
        }
        val right = shape.extreme(1.0, 0.0)
        val ticks2 = pace.ticks(shape, right) + settle(0.3, !shape.hasTissue)
        family.add("grow/wall/${shape.id}/from-${pace.id}", "${shape.name}: зигота у самой стены, ${pace.name}",
            "Зигота — крайняя правая клетка тела — стоит в одной единице от стены; тело растёт от стены.",
            "Клетки, рождающиеся у стены, не оказываются в рельефе. " + GROWN + bonesNote(shape) + SETTLED, ticks2, GROW) {
            place(wall, 1.0 + TERRAIN_JITTER / 2)
            growing(shape, 0.0, 0.0, seed = right, period = pace.period, batch = pace.batch)
            view = doubleArrayOf(-2 * shape.halfWidth - 3.0, -shape.halfHeight - 4.0, 6.0, shape.halfHeight + 4.0)
        }
    }

    // --- рост в коридоре уже тела ---
    val corridor = terrainLayouts().first { it.id == "corridor" }
    for (shape in listOf(Shapes.square(8), Shapes.disc(5), Shapes.star(5, 8, 3), Shapes.square(10).boneCore())) for (pace in listOf(SLOW, LAYERS)) {
        val center = shape.nearest(0.0, 0.0)
        family.add("grow/corridor/${shape.id}/${pace.id}", "${shape.name}: рост в коридоре уже тела, ${pace.name}",
            "Тело растёт из середины внутри коридора рельефа шириной около четырёх единиц — уже взрослого тела.",
            "Тело заполняет коридор и растёт вдоль него, сжимаясь поперёк. Ткань не входит в рельеф, не рвётся и не гибнет; после роста всё замирает и засыпает.",
            pace.ticks(shape, center) + settle(0.5), GROW_TIGHT) {
            place(corridor, -6.0)
            growing(shape, 0.0, 0.0, seed = center, period = pace.period, batch = pace.batch)
            view = doubleArrayOf(-9.0, -7.0, 9.0, 7.0)
        }
    }

    // --- рост взаперти: взрослое тело не помещается ---
    for (shape in listOf(Shapes.disc(5), Shapes.square(10), Shapes.square(10).boneCore(), Shapes.star(5, 8, 3))) {
        val center = shape.nearest(0.0, 0.0)
        family.add("grow/jam/${shape.id}", "${shape.name}: рост в замкнутой полости меньше тела",
            "Тело растёт из середины замкнутой полости шесть на шесть единиц — взрослое тело в неё не помещается.",
            "Ткани тесно: она вправе порваться и потерять клетки. Но ничего не взрывается, ткань не входит в рельеф и не выходит из полости; движение затухает.",
            LAYERS.ticks(shape, center) + CRASH_TICKS, GROW_JAM) {
            val c = cavity(9, 9, jitter = false)
            growing(shape, (c.minX + c.maxX) / 2, (c.minY + c.maxY) / 2, seed = center, period = LAYERS.period, batch = LAYERS.batch)
        }
    }

    // --- рост между соседями ---
    val block = Shapes.square(8)
    for (shape in listOf(Shapes.disc(5), Shapes.rect(16, 4), Shapes.square(10).boneCore())) for (pace in listOf(SLOW, LAYERS)) {
        val center = shape.nearest(0.0, 0.0)
        family.add("grow/between/${shape.id}/${pace.id}", "${shape.name}: рост между двумя телами, ${pace.name}",
            "Два квадрата стоят с просветом в три единицы; между ними из середины растёт тело шире просвета.",
            "Растущее тело расталкивает соседей: его клетки не оказываются в чужой ткани, а чужие — в его. " + GROWN + bonesNote(shape) + SETTLED,
            pace.ticks(shape, center) + settle(0.5), GROW) {
            body(block, -(block.halfWidth + 1.5), 0.0)
            body(block, block.halfWidth + 1.5, 0.0)
            growing(shape, 0.0, 0.0, seed = center, period = pace.period, batch = pace.batch)
        }
    }

    // --- два тела растут рядом и встречаются ---
    for (pace in listOf(SLOW, LAYERS)) {
        val disc = Shapes.disc(5)
        val center = disc.nearest(0.0, 0.0)
        family.add("grow/twins/${pace.id}", "два круга растут рядом и встречаются, ${pace.name}",
            "Две зиготы стоят в пяти единицах друг от друга; взрослые круги перекрылись бы на две единицы.",
            "Тела упираются друг в друга и расходятся: ни одна клетка не оказывается в чужой ткани. " + GROWN + SETTLED,
            pace.ticks(disc, center) + settle(0.5), GROW) {
            growing(disc, -2.5, 0.0, seed = center, period = pace.period, batch = pace.batch)
            growing(disc, 2.5, 0.0, seed = center, period = pace.period, batch = pace.batch)
        }
    }

    // --- рост при работающих мышцах ---
    val movers = listOf(Shapes.rect(20, 4).muscleAll(), Shapes.rect(20, 4).muscleTopBottom(), Shapes.disc(6).muscleAll(), Shapes.star(5, 8, 3).muscleArms(5, 3.0))
    for (shape in movers) for (seed in listOf(SEEDS[0], SEEDS[1])) {
        val cell = seed.pick(shape)
        val grow = SLOW.ticks(shape, cell)
        family.add("grow/stroke/${shape.id}/${seed.id}", "${shape.name}: рост ${seed.name} при работающих мышцах",
            "Тело растёт ${seed.name}, по клетке раз в три тика, а его мышцы всё это время сокращаются и расслабляются, по 20 тиков на ход.",
            "Мышцы не рвут растущую ткань и не губят новорождённые клетки. " + GROWN + "После роста мышцы отпускают, тело возвращает форму и засыпает.",
            grow + 60 + settle(0.5), GROW) {
            val b = growing(shape, 0.0, 0.0, seed = cell, period = SLOW.period, batch = SLOW.batch)
            // Команда — всем живым мышцам каждый тик: только что рождённая клетка получает её сразу, как в игре.
            during(0, grow + 40) { t ->
                val n = shape.muscleGroups
                for (g in 0 until n) muscle(b, g, if ((t / 20 + g) % 2 == 0) 0.5f else 1.5f)
            }
            during(grow + 41, grow + 60) { muscles(b, 1f) }
        }
    }
    return family.list
}
