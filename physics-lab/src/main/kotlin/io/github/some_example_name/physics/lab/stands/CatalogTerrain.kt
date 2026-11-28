package io.github.some_example_name.physics.lab.stands

import java.util.Random
import kotlin.math.abs

/*
 * СЕМЕЙСТВО «РЕЛЬЕФ». Тело летит слева в рельеф справа. Рельеф собран так же, как в игре:
 * клетки карты с шагом 2/3, частицы радиуса 0.5 со случайным сдвигом, в мир физики идёт
 * только кромка. Главное правило семейства — «ничто не входит в рельеф»: подвижная частица
 * не оказывается ни в толще, ни глубже половины радиуса в частице кромки, а частица рельефа —
 * внутри живой ткани.
 */

/** Раскладка рельефа: твёрдая ли клетка карты (столбец от лицевой стороны, ряд от середины). */
internal class Layout(val id: String, val name: String, val about: String, val cols: Int, val rows: Int,
                      /** Сколько столбцов раскладки лежит левее лицевой стороны (пол перед стеной, стенки кармана). */
                      val before: Int = 0,
                      val solid: (ix: Int, cy: Int) -> Boolean)

private const val T = TERRAIN_STEP

internal fun terrainLayouts(): List<Layout> {
    val rnd = Random(11)
    val bumps = IntArray(64) { rnd.nextInt(4) }
    return listOf(
        Layout("wall", "ровная стена", "Стена толщиной в шесть клеток карты.", 6, 40) { _, _ -> true },
        Layout("pillar", "столб", "Отдельный столб пять на пять клеток карты на пути тела.", 5, 40) { _, cy -> abs(cy) <= 2 },
        Layout("spikes", "зубчатая стена", "Стена с треугольными зубцами глубиной в четыре клетки карты.", 10, 40) { ix, cy ->
            ix >= abs(Math.floorMod(cy, 8) - 4)
        },
        Layout("rough", "неровная стена", "Стена со случайными выступами до трёх клеток карты.", 9, 40) { ix, cy ->
            ix >= bumps[Math.floorMod(cy, 64)]
        },
        Layout("ledge", "уступ", "Стена только в нижней половине: тело бьётся о неё нижним краем.", 6, 40) { _, cy -> cy < 0 },
        Layout("slit", "стена со щелью", "Стена со щелью высотой в одну клетку карты — уже диаметра клетки тела.", 6, 40) { _, cy -> cy != 0 },
        Layout("corner", "угол", "Стена и пол под ней: тело летит наискось в угол.", 36, 40, before = 30) { ix, cy ->
            ix >= 30 || cy < -8
        },
        Layout("corridor", "узкий коридор", "Две плиты с проходом в шесть клеток карты (4 единицы) — уже тела; в конце тупик.", 24, 40) { ix, cy ->
            abs(cy) > 3 || ix >= 18
        },
        Layout("funnel", "воронка", "Сходящиеся стенки сужаются до щели в две клетки карты.", 24, 40) { ix, cy ->
            abs(cy) > maxOf(1, 9 - ix / 2)
        },
        Layout("pocket", "карман", "Плита с прямоугольной выемкой чуть больше тела, открытой навстречу.", 24, 40) { ix, cy ->
            abs(cy) > 7 || ix >= 16
        },
    )
}

/**
 * Рельеф раскладки: лицевая сторона — центры крайних частиц первого твёрдого столбца стены — в
 * [faceX] игры, середина по Y — в нуле. Центр самого столбца клеток карты на TERRAIN_FACE глубже.
 */
internal fun StandWorld.place(layout: Layout, faceX: Double, jitter: Boolean = true): StandTerrain =
    terrain(faceX - layout.before * T + TERRAIN_FACE, -(layout.rows / 2) * T, layout.cols, layout.rows, jitter) { ix, iy ->
        layout.solid(ix, iy - layout.rows / 2)
    }

private class Runner(val shape: ShapeSpec, val angle: Double, val id: String, val name: String)

internal fun terrainFamily(): List<Scenario> {
    val family = Family("4. Рельеф")
    val runners = listOf(
        Runner(Shapes.square(8), 0.0, "square8", "квадрат 8"),
        Runner(Shapes.disc(4), 0.0, "disc4", "круг радиуса 4"),
        Runner(Shapes.rect(12, 4), 0.0, "rod-end", "брусок 12×4 торцом"),
        Runner(Shapes.square(8).boneCore(), 0.0, "square8-core", "квадрат 8 с костным ядром"),
    )
    val extra = listOf(
        Runner(Shapes.rect(12, 4), Math.PI / 2, "rod-flat", "брусок 12×4 плашмя"),
        Runner(Shapes.star(5, 7, 3), 0.0, "star", "звезда"),
        Runner(Shapes.ribbon(12), Math.PI / 2, "ribbon-flat", "лента 2×12 плашмя"),
        Runner(Shapes.square(8).boneAll(), 0.0, "brick", "костный кирпич"),
    )
    for (layout in terrainLayouts()) {
        val all = if (layout.id == "wall" || layout.id == "spikes") runners + extra else runners
        for (r in all) for (hit in HITS) {
            // Таран на пределе — только там, где есть во что врезаться всем телом.
            if (hit.rough && layout.id !in setOf("wall", "pillar", "spikes", "rough", "corner")) continue
            val ticks = if (hit.rough) CRASH_TICKS else settle(hit.speed)
            val diagonal = layout.id == "corner"
            // В проход уже себя тело вклинивается и остаётся сжатым: прежней формы от него требовать нельзя.
            val wedge = layout.id == "corridor" || layout.id == "funnel"
            family.add("land/${layout.id}/${r.id}/${hit.id}", "${layout.name}, ${r.name}: ${hit.title}",
                "${layout.about} ${r.name} летит ${if (diagonal) "наискось вниз" else "вправо"} со скоростью ${hit.speed} связи за тик.",
                "Тело упирается в рельеф и не входит в него: ни одна клетка не оказывается в толще или внутри частицы рельефа. " + hit.expect +
                    (if (wedge) " Тело вправе остаться зажатым в проходе, поэтому возврат к исходной форме здесь не проверяется." else ""),
                ticks, if (wedge) hit.limits.copy(shape = false) else hit.limits) {
                val reach = if (r.angle == 0.0) r.shape.halfWidth + GAME_RADIUS else r.shape.reach
                val gap = reach + START_GAP + TERRAIN_RADIUS + TERRAIN_JITTER / 2
                place(layout, gap)
                // В углу пол на 8 клеток карты ниже середины: тело ставится над ним с тем же зазором.
                val y0 = if (diagonal) -8 * T + gap else 0.0
                val ux = if (diagonal) 0.7071 else 1.0; val uy = if (diagonal) -0.7071 else 0.0
                // До первого касания с рельефом — ровно START_GAP по настоящим клеткам и частицам
                // (Placement.kt): иначе «лёгкое касание» гаснет в воде, не долетев до стены.
                val shift = approach(r.shape, 0.0, y0, r.angle, ux, uy)
                val x = shift * ux; val y = y0 + shift * uy
                val b = body(r.shape, x, y, r.angle)
                kick(b, hit.speed * ux, hit.speed * uy)
                expectReached(b, ticks)
                view = doubleArrayOf(minOf(-reach - 2.0, x - reach - 2.0), minOf(-reach - 2.0, y - reach - 6.0), gap + 8.0, maxOf(reach + 2.0, y0 + reach + 2.0))
            }
        }
    }

    // Мышь вдавливает тело в рельеф: курсор плавно уходит вглубь стены и стоит там.
    for (layout in terrainLayouts().filter { it.id in setOf("wall", "spikes", "pocket", "corridor") }) for (r in listOf(runners[0], extra[2])) {
        family.add("land/${layout.id}/${r.id}/mouse", "${layout.name}, ${r.name}: мышь вдавливает тело в рельеф",
            "${layout.about} Мышь берёт дальний край тела и плавно ведёт его на шесть единиц вглубь рельефа.",
            "Тело прижимается к рельефу и сминается, но не входит в него и не рвётся; после отпускания расправляется, останавливается и засыпает.",
            300 + settle(0.5), if (layout.id == "corridor") Limits.DRIVEN.copy(shape = false) else Limits.DRIVEN) {
            val reach = if (r.angle == 0.0) r.shape.halfWidth + GAME_RADIUS else r.shape.reach
            val gap = reach + START_GAP + TERRAIN_RADIUS + TERRAIN_JITTER / 2
            place(layout, gap)
            val shift = approach(r.shape, 0.0, 0.0, r.angle, 1.0, 0.0)
            val b = body(r.shape, shift, 0.0, r.angle)
            expectReached(b, 300)
            val cell = if (r.angle == 0.0) r.shape.extreme(-1.0, 0.0) else r.shape.extreme(0.0, 1.0)
            val p = b.particle(cell)
            val sx = gameX(p); val sy = gameY(p)
            // Курсор уходит на шесть единиц дальше места, где тело коснётся рельефа.
            mousePath(b, cell, 10, 3, (0..80).map { doubleArrayOf(sx + (START_GAP + 6.0) * it / 80.0, sy) })
            view = doubleArrayOf(minOf(-reach - 2.0, shift - reach - 2.0), -reach - 4.0, gap + 8.0, reach + 4.0)
        }
    }

    // Покой у рельефа: без случайного сдвига частиц, чтобы расстояние до стены было точным.
    val wall = terrainLayouts().first { it.id == "wall" }
    for (r in listOf(runners[0], runners[1], runners[3])) {
        family.add("land/wall/${r.id}/near", "ровная стена, ${r.name}: покой рядом со стеной",
            "Тело стоит у стены, не касаясь её: между ближайшими клетками и частицами стены 0.8.",
            "Ничего не происходит: тело стоит и засыпает, стена его не притягивает и не толкает.", 150, Limits.INTACT) {
            val reach = r.shape.halfWidth
            place(wall, reach + 0.8, jitter = false)
            mustNotTouchTerrain(body(r.shape, 0.0, 0.0), "тело, поставленное рядом со стеной, коснулось её")
            view = doubleArrayOf(-reach - 2.0, -reach - 3.0, reach + 5.0, reach + 3.0)
        }
        family.add("land/wall/${r.id}/touch", "ровная стена, ${r.name}: прижато к стене",
            "Тело поставлено к стене с лёгким перекрытием касания (0.5 при пороге 0.6).",
            "Тело мягко отходит на расстояние касания, замирает и засыпает; ничего не рвётся и не гибнет.", settle(0.1), TOUCH) {
            val reach = r.shape.halfWidth
            place(wall, reach + 0.5, jitter = false)
            body(r.shape, 0.0, 0.0)
            view = doubleArrayOf(-reach - 2.0, -reach - 3.0, reach + 5.0, reach + 3.0)
        }
        family.add("land/wall/${r.id}/lean", "ровная стена, ${r.name}: тело привалилось к стене и должно уснуть",
            "Тело медленно (0.15 связи за тик) подплывает к стене и остаётся у неё.",
            "Тело касается стены, останавливается и засыпает, как в чистом поле: соседство с рельефом сну не мешает.", settle(0.15) + 150, Limits.INTACT) {
            val reach = r.shape.halfWidth + GAME_RADIUS
            place(wall, reach + START_GAP, jitter = false)
            val b = body(r.shape, approach(r.shape, 0.0, 0.0, 0.0, 1.0, 0.0), 0.0)
            kick(b, 0.15, 0.0)
            expectReached(b, settle(0.15) + 150)
            view = doubleArrayOf(-reach - 2.0, -reach - 3.0, reach + 7.0, reach + 3.0)
        }
    }
    return family.list
}

/**
 * На сколько единиц игры подвинуть фигуру из ([x], [y]) вдоль ([ux], [uy]), чтобы до первого
 * касания с уже поставленным рельефом ей осталось лететь START_GAP. Ноль — если по этой прямой
 * она рельефа не встречает.
 */
internal fun StandWorld.approach(s: ShapeSpec, x: Double, y: Double, angle: Double, ux: Double, uy: Double): Double {
    val travel = travelToTerrain(s, x, y, angle, ux, uy)
    return if (travel.isFinite()) travel - START_GAP else 0.0
}
