package io.github.some_example_name.physics.lab.stands

import java.util.Random
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * СЕМЕЙСТВА С ТЕЛАМИ НАСТОЯЩИХ ГЕНОМОВ.
 *
 * Фигуры каталога — квадраты и круги на ровной решётке. Игрок строит не их: у настоящего тела
 * клетки разного радиуса, связи разной длины, кости лежат где придётся, мышцы тянут под углом.
 * Здесь те же испытания, что у фигур, — покой, удары, мышь, рельеф, пули, мышцы — проходят
 * тела, выгруженные из геномов игры (StandGenomeExport в :lwjgl3: тот же импорт и то же
 * запекание, что в игре, и ровно то описание тела, которое мост отдаёт физике).
 *
 * И главное, ради чего они нужны, — рост: тело растёт из зиготы по стадиям своего генома, с
 * костями и без них. Если геном рвётся и при росте без костей — виноваты не только кости.
 *
 * Вариаций у этих сцен нет: длины связей и радиусы у тела генома свои.
 * Набор сцен зависит от размера тела: малые и средние проходят всё, у больших и огромных
 * убраны сцены, где два таких тела считались бы минутами, а у крошечных (меньше десяти
 * клеток: кусок еды, костяной шарик) оставлено главное — покой, удары, мышь, рост.
 */

/** Тело меньше стольких клеток — крошечное: ему ставится сокращённый набор сцен. */
private const val TINY = 10

private const val PULL = 0.5f
private const val REST = 1f
private const val LOOSE = 1.5f

private fun shapeOf(e: GenomeEntry): ShapeSpec = GenomeBodies.shape(e.slug)

private fun label(e: GenomeEntry) = "«${e.title}» (${e.cells} кл.)"

/** Что известно о теле по списку — одной фразой для описания сцены. */
private fun facts(e: GenomeEntry): String {
    val parts = ArrayList<String>()
    parts.add("${e.cells} клеток")
    if (e.bones > 0) parts.add("костных ${e.bones}")
    if (e.muscles > 0) parts.add("мышечных ${e.muscles}")
    if (e.cups > 0) parts.add("присосок ${e.cups}")
    return "Тело генома «${e.title}»: " + parts.joinToString(", ") + "."
}

private val ONE = Variation.ONLY_BASE

/** Малый круг на ровной решётке — снаряд для огромных тел: два огромных тела считались бы слишком долго. */
private fun pebble() = Shapes.disc(4)

internal fun genomeHitFamily(): List<Scenario> {
    val family = Family("22. Геномы: покой, удары, мышь")
    for (e in GenomeBodies.entries) {
        val id = "gen/${e.slug}"
        val name = label(e)
        val about = facts(e)
        val bones = if (e.bones > 0) " Кости не мнутся." else ""
        val tiny = e.cells < TINY
        val small = e.cells < 400 && !tiny
        val huge = e.cells >= 1500

        family.add("$id/rest", "$name: покой", "$about Тело стоит в позе покоя, без толчков.",
            "Ничего не происходит: тело не ползёт, не вращается, не дрожит и засыпает.$bones", 200, Limits.INTACT, ONE) {
            body(shapeOf(e), 0.0, 0.0)
        }
        if (small) {
            family.add("$id/near", "$name: два тела рядом", "$about Два таких тела стоят почти вплотную, не касаясь.",
                "Соседство без касания ничего не делает: оба тела стоят и засыпают.", 200, Limits.INTACT, ONE) {
                val s = shapeOf(e)
                val half = separation(s, s, 0.8) / 2
                val a = body(s, -half, 0.0); val b = body(s, half, 0.0)
                mustNotTouch(a, b, "тела, поставленные рядом, коснулись")
            }
            family.add("$id/touch", "$name: касание в покое", "$about Два таких тела поставлены с лёгким перекрытием касания.",
                "Тела мягко раздвигаются на расстояние касания и замирают: без разгона, дрожи, разрывов и гибели; затем засыпают.$bones", settle(0.1), TOUCH, ONE) {
                val s = shapeOf(e)
                val half = separation(s, s, 0.5) / 2
                body(s, -half, 0.0); body(s, half, 0.0)
            }
        }
        val hits = when { tiny -> listOf(BUMP, HIT, CRASH); small -> listOf(TAP, BUMP, HIT, RAM, CRASH, MAX_HIT); huge -> listOf(BUMP, HIT); else -> listOf(BUMP, HIT, RAM) }
        for (hit in hits) {
            val ticks = if (hit.rough) CRASH_TICKS else settle(hit.speed)
            if (huge) family.add("$id/${hit.id}", "$name: в него бьёт малый круг, ${hit.title}",
                "$about В стоящее тело летит круг радиуса 4 со скоростью ${hit.speed} связи за тик.",
                "Круг вминается в тело и отскакивает, не входя в его ткань. " + hit.expect + bones, ticks, hit.limits, ONE) {
                val (ram, target) = duel(pebble(), shapeOf(e), hit.speed)
                expectMet(ram, target, 80, hit.speed)
            }
            else family.add("$id/${hit.id}", "$name: ${hit.title}", "$about Левое тело летит в стоящее такое же со скоростью ${hit.speed} связи за тик.",
                hit.expect + bones, ticks, hit.limits, ONE) {
                val s = shapeOf(e)
                val (a, target) = duel(s, s, hit.speed)
                expectMet(a, target, 80, hit.speed)
            }
        }
        if (small) {
            family.add("$id/meet-max", "$name: встречный таран на потолке скорости", "$about Два таких тела летят друг в друга, каждое со скоростью ${SPEED_CAP.toInt()} связей за тик.",
                MAX_HIT.expect + bones, CRASH_TICKS, Limits.CRASH, ONE) {
                val s = shapeOf(e)
                duelBoth(s, s, SPEED_CAP)
            }
            family.add("$id/glance", "$name: скользящий удар с вращением", "$about Левое тело летит со скоростью 0.6 связи за тик, сдвинуто на полвысоты и вращается.",
                "Тела задевают друг друга краями и расходятся. Без разрывов, гибели и проникновений; к концу всё стоит и спит.$bones", settle(0.6), Limits.INTACT, ONE) {
                val s = shapeOf(e)
                duel(s, s, 0.6, offset = s.halfHeight, spin = 0.02)
            }
            family.add("$id/shove", "$name: мышь вдавливает тело в соседа", "$about Мышь берёт дальний край левого тела и медленно ведёт его сквозь место, где стоит правое.",
                "Левое тело упирается в правое и толкает его перед собой: клетки не входят в чужую ткань, ничего не рвётся; после отпускания оба успокаиваются и засыпают.$bones",
                260 + settle(0.5), Limits.DRIVEN, ONE) {
                val s = shapeOf(e)
                val (a, b) = duel(s, s, 0.0)
                val cell = s.extreme(-1.0, 0.0)
                val start = centerOf(a); val goal = centerOf(b)
                mousePath(a, cell, 10, 3, (0..80).map { doubleArrayOf(start[0] + s.x[cell] + (goal[0] - start[0] + 4.0) * it / 80.0, s.y[cell]) })
            }
        }
        family.add("$id/drag", "$name: мышь ведёт тело по кругу", "$about Мышь берёт крайнюю правую клетку и плавно ведёт её по кругу, затем отпускает.",
            "Тело послушно идёт за курсором, не рвётся и не теряет клеток; после отпускания останавливается, возвращает форму и засыпает.$bones",
            220 + settle(0.5), Limits.DRIVEN, ONE) {
            val s = shapeOf(e)
            val b = body(s, 0.0, 0.0)
            val cell = s.extreme(1.0, 0.0)
            val r = s.halfWidth + 3.0
            mousePath(b, cell, 10, 2, circlePath(0.0, 0.0, r, 100).map { doubleArrayOf(it[0] - r + s.x[cell], it[1] + s.y[cell]) })
        }
        if (!huge && !tiny) family.add("$id/yank", "$name: рывок мышью", "$about Мышь берёт крайнюю правую клетку, курсор разом уходит на 12 единиц в сторону и стоит там 60 тиков.",
            "Клетка летит к курсору и тащит тело. Ткань вправе порваться, но ничего не взрывается, оторванное не проходит сквозь тело, после отпускания движение затухает.$bones",
            CRASH_TICKS, YANK, ONE) {
            val s = shapeOf(e)
            val b = body(s, 0.0, 0.0)
            val cell = s.extreme(1.0, 0.0)
            at(10) { grab(b, cell); dragTo(s.x[cell] + 12.0, s.y[cell] + 5.0) }
            at(70) { release() }
        }
    }
    return family.list
}

internal fun genomeTerrainFamily(): List<Scenario> {
    val family = Family("23. Геномы: рельеф и частицы")
    val layouts = terrainLayouts().associateBy { it.id }
    for (e in GenomeBodies.entries) {
        if (e.cells >= 1500) continue
        val id = "gen-land/${e.slug}"
        val name = label(e)
        val about = facts(e)
        val tiny = e.cells < TINY
        for ((layoutId, hit) in listOf("wall" to BUMP, "wall" to HIT, "spikes" to HIT, "corridor" to BUMP, "pillar" to RAM, "rough" to CRASH)) {
            if (e.cells >= 400 && hit.rough) continue
            if (tiny && !(layoutId == "wall" && hit === HIT)) continue
            val layout = layouts.getValue(layoutId)
            val wedge = layoutId == "corridor"
            val ticks = if (hit.rough) CRASH_TICKS else settle(hit.speed)
            family.add("$id/$layoutId-${hit.id}", "$name в рельеф «${layout.name}»: ${hit.title}",
                "$about ${layout.about} Тело летит вправо со скоростью ${hit.speed} связи за тик.",
                "Тело упирается в рельеф и не входит в него: ни одна клетка не оказывается в толще или внутри частицы рельефа. " + hit.expect,
                ticks, if (wedge) hit.limits.copy(shape = false) else hit.limits, ONE) {
                val s = shapeOf(e)
                val gap = s.reach + START_GAP + TERRAIN_RADIUS + TERRAIN_JITTER / 2
                place(layout, gap)
                val shift = approach(s, 0.0, 0.0, 0.0, 1.0, 0.0)
                val b = body(s, shift, 0.0)
                kick(b, hit.speed, 0.0)
                expectReached(b, ticks)
                view = doubleArrayOf(minOf(-s.reach - 2.0, shift - s.reach - 2.0), -s.reach - 6.0, gap + 8.0, s.reach + 6.0)
            }
        }
        for ((speed, deg) in listOf(2.0 to 0, 7.5 to 0, 7.5 to 35)) {
            if (tiny && !(speed > 3 && deg == 0)) continue
            val fast = speed > 3
            family.add("$id/bullet-v$speed-a$deg", "$name: пуля, скорость $speed, угол $deg°",
                "$about Свободная частица летит в центр тела со скоростью $speed связи за тик под углом $deg°.",
                if (fast) "Пуля на пределе скорости: ткань вправе порваться, но сквозь целую ткань пуля не проходит и внутри живой ткани не оказывается; ничего не взрывается, движение затухает."
                else "Пуля отскакивает от мембраны: не входит в ткань и не проходит сквозь неё, ничего не рвётся; к концу всё стоит и спит.",
                if (fast) CRASH_TICKS else settle(speed, true), if (fast) Limits.CRASH else Limits.INTACT, ONE) {
                val s = shapeOf(e)
                body(s, 0.0, 0.0)
                val a = Math.toRadians(deg.toDouble())
                val d = s.reach + START_GAP + 1.0
                ball(-d * cos(a), -d * sin(a), speed * cos(a), speed * sin(a), GAME_RADIUS)
            }
        }
        if (e.cells >= 12) family.add("$id/sand", "$name: медленные частицы со всех сторон",
            "$about Свободные частицы по кругу (до сорока — сколько помещается вокруг тела) летят к телу со скоростью 0.3 связи за тик.",
            "Частицы облепляют тело и останавливаются. Ни одна не входит в ткань, ничего не рвётся; к концу всё стоит и спит.", settle(0.3, true) + 200, Limits.INTACT, ONE) {
            val s = shapeOf(e)
            val b = body(s, 0.0, 0.0)
            val r = s.reach + START_GAP + 2.0
            // Не больше, чем помещается вокруг тела вплотную: сорок частиц вокруг тела в дюжину клеток сходятся
            // и запирают друг друга кольцом, не дойдя до него (16.11: так «проходили» эту сцену 30 малых тел).
            val count = minOf(40, maxOf(6, (2 * Math.PI * (s.reach + GAME_LINK) / (1.2 * GAME_LINK)).toInt()))
            for (k in 0 until count) {
                val a = 2 * Math.PI * k / count
                ball(r * cos(a), r * sin(a), -0.3 * cos(a), -0.3 * sin(a), GAME_RADIUS)
            }
            mustTouchBalls(b, "частицы долетели до тела")
        }
    }
    return family.list
}

internal fun genomeMuscleFamily(): List<Scenario> {
    val family = Family("24. Геномы: мышцы и кости")
    for (e in GenomeBodies.entries) {
        if (e.muscles == 0 || e.cells < TINY) continue
        val id = "gen-muscle/${e.slug}"
        val name = label(e)
        val about = facts(e)
        val huge = e.cells >= 1500
        // «Тело изменило форму» проверяется только там, где мышц много, а костей мало: одна мышечная клетка из ста тело
        // не согнёт, а тело, на треть костяное (Megalodon: 2136 костных клеток из 5120), мышцам почти не поддаётся.
        val strong = e.muscles * 4 >= e.cells && e.bones * 5 < e.cells
        val bones = if (e.bones > 0) " Кости не мнутся и не складываются." else ""
        class Plan(val tag: String, val title: String, val about: String, val active: Int, val flicker: Boolean, val shape: (ShapeSpec) -> ShapeSpec, val script: StandWorld.(StandBody) -> Unit)
        val plans = ArrayList<Plan>()
        plans.add(Plan("squeeze", SQUEEZE.title, SQUEEZE.about, SQUEEZE.active, false, { it }) { b -> SQUEEZE.script(this, b) })
        plans.add(Plan("sides", "стороны попеременно", "Мышцы по одну сторону от длинной оси тела сокращаются, по другую — отпускают; через 150 тиков наоборот; затем покой.",
            SWAP.active, false, { it.muscleSides() }) { b -> SWAP.script(this, b) })
        if (!huge) {
            plans.add(Plan("pump", PUMP.title, PUMP.about, PUMP.active, false, { it }) { b -> PUMP.script(this, b) })
            plans.add(Plan("wave", "бегущая волна вдоль тела", "Мышцы четырёх участков вдоль длинной оси тела сокращаются по очереди, каждый на 20 тиков; волна проходит шесть раз.",
                WAVE.active, false, { it.muscleAlong(4) }) { b -> WAVE.script(this, b) })
            plans.add(Plan("flicker", FLICKER.title, FLICKER.about, FLICKER.active, true, { it }) { b -> FLICKER.script(this, b) })
            plans.add(Plan("loose", "все мышцы отпущены и удар", "Все мышцы отпущены до предела податливости; на тике 60 в тело летит малый круг со скоростью 2 связи за тик; на тике 260 мышцы возвращаются в покой.",
                260, false, { it }) { b ->
                at(10) { muscles(b, LOOSE) }
                at(260) { muscles(b, REST) }
            })
        }
        for (plan in plans) {
            val ticks = plan.active + settle(0.5)
            val hitToo = plan.tag == "loose"
            val limits = when {
                plan.flicker -> FLICKERING
                hitToo -> Limits.hit(2.0).copy(name = Limits.hit(2.0).name + "; мышцы отпущены", energy = Energy.DRIVEN)
                else -> Limits.DRIVEN
            }
            family.add("$id/${plan.tag}", "$name: ${plan.title}", "$about ${plan.about}",
                (if (hitToo) "Расслабленная ткань держит удар: круг не входит в неё; местный разрыв допустим (до 4% связей). "
                else "Мышцы не рвут свою ткань: ни одного разрыва и ни одной погибшей клетки, клетки не входят в свою ткань. ") +
                    "После расслабления тело возвращает исходную форму, останавливается и засыпает.$bones", ticks, limits, ONE) {
                val s = plan.shape(shapeOf(e))
                val b = body(s, 0.0, 0.0)
                plan.script(this, b)
                if (hitToo) {
                    val ram = pebble()
                    val d = separation(ram, s, CONTACT_DISTANCE) + START_GAP + 0.3 * s.halfWidth
                    val p = body(ram, -d, 0.0)
                    at(60) { kick(p, 2.0, 0.0) }
                    expectMet(p, b, 260, 2.0)
                } else if (strong && !plan.flicker) {
                    val tips = intArrayOf(s.extreme(-1.0, 0.0), s.extreme(1.0, 0.0), s.extreme(0.0, -1.0), s.extreme(0.0, 1.0))
                    val rest = DoubleArray(6); val pa = IntArray(6); val pb = IntArray(6)
                    var n = 0
                    for (i in 0..3) for (j in i + 1..3) if (tips[i] != tips[j]) {
                        pa[n] = tips[i]; pb[n] = tips[j]
                        rest[n] = Math.hypot(s.x[tips[i]] - s.x[tips[j]], s.y[tips[i]] - s.y[tips[j]]); n++
                    }
                    probe("изменение формы") { (0 until n).maxOfOrNull { Math.abs(distance(b, pa[it], pb[it]) / rest[it] - 1.0) } ?: 0.0 }
                    expectMaxAtLeast("мышцы изменили форму тела", "изменение формы", 20, plan.active, 0.01)
                }
            }
        }
    }
    return family.list
}

/** Темп роста тела генома: сколько тиков уходит на стадию. В игре это зависит от энергии клеток; здесь — условие сцены. */
private class StagePace(val id: String, val name: String, val period: Int)

private val STAGE_PACES = listOf(StagePace("p1", "стадия за тик", 1), StagePace("p4", "стадия за четыре тика", 4), StagePace("p12", "стадия за двенадцать тиков", 12))

/** Дольше этого рост в сцену не ставится: у генома на две тысячи стадий медленный темп — это час счёта. */
private const val GROWTH_TICKS_MAX = 5000

internal fun genomeGrowthFamily(): List<Scenario> {
    val family = Family("25. Геномы: рост из зиготы")
    val wall = terrainLayouts().first { it.id == "wall" }
    for (e in GenomeBodies.entries) {
        val id = "gen-grow/${e.slug}"
        val name = label(e)
        val about = facts(e)
        val grown = "Тело вырастает целиком и в задуманную форму (бледный контур на сцене): ни одного разрыва и ни одной погибшей клетки, ничего не взрывается. "
        val settled = "После роста тело останавливается и засыпает."
        for (noBones in listOf(false, true)) {
            if (noBones && e.bones == 0) continue
            val tag = if (noBones) "-nobone" else ""
            val what = if (noBones) " Кости заменены обычной тканью: если тело рвётся и так, виноваты не только кости." else ""
            for (pace in STAGE_PACES) {
                if (e.stages * pace.period > GROWTH_TICKS_MAX) continue
                if (e.cells < TINY && pace.period != 4) continue
                val ticks = e.stages * pace.period + settle(0.3) + if (e.cells >= 1500) 300 else 0
                family.add("$id/open$tag-${pace.id}", "$name: рост${if (noBones) " без костей" else ""}, ${pace.name}",
                    "$about Тело растёт из зиготы по стадиям своего генома (${e.stages} стадий), ${pace.name}.$what",
                    grown + (if (e.bones > 0 && !noBones) "Кости получаются той формы, что в геноме, а не той, в какой их застал рост. " else "") + settled, ticks, GROW, ONE) {
                    val full = shapeOf(e)
                    val s = if (noBones) full.withoutBones() else full
                    growing(s, 0.0, 0.0, seed = s.genome!!.zygote, period = pace.period, batch = 0)
                }
            }
        }
        // У стены: взрослая раскладка заходит в рельеф.
        if (e.cells in 8 until 1500 && e.stages * 4 <= GROWTH_TICKS_MAX) {
            family.add("$id/wall-p4", "$name: рост в стену, стадия за четыре тика",
                "$about Тело растёт из зиготы рядом со стеной рельефа: стена стоит ближе, чем дотянется взрослое тело, как бы его ни развернуло при росте.",
                "Новорождённая ткань не врастает в рельеф: тело упирается в стену и отодвигается от неё. Ни разрывов, ни гибели клеток; после роста всё замирает и засыпает.",
                e.stages * 4 + settle(0.5), GROW_TIGHT, ONE) {
                val s = shapeOf(e)
                val z = s.genome!!.zygote
                // ГДЕ СТЕНА. Прежде она стояла «на единицу ближе правого края взрослой раскладки». Но растущее тело
                // не лежит по раскладке: зигота не закреплена, тело при росте сдвигается и поворачивается, и у 15 тел
                // из 123 оно до стены так и не дотянулось — сцена была зелёной, ничего не проверив (замечание автора
                // игры 16.11). Теперь стена — на 0.4 наименьшего полуразмера тела от зиготы (но не ближе 0.9, чтобы
                // сама зигота её не касалась): тело упрётся в неё при любом сдвиге и повороте.
                val face = maxOf(0.9, 0.4 * minOf(s.halfWidth, s.halfHeight))
                place(wall, face)
                val g = growing(s, 0.0, 0.0, seed = z, period = 4, batch = 0)
                mustTouchTerrain(g, "растущее тело дотянулось до стены")
                view = doubleArrayOf(-s.halfWidth - s.x[z] - 3.0, -s.halfHeight - s.y[z] - 4.0, s.halfWidth - s.x[z] + 5.0, s.halfHeight - s.y[z] + 4.0)
            }
        }
        // Рост при работающих мышцах: команда — всем живым мышцам каждый тик.
        if (e.muscles > 0 && e.cells < 1500 && e.stages * 4 <= GROWTH_TICKS_MAX) {
            val grow = e.stages * 4
            family.add("$id/stroke-p4", "$name: рост при работающих мышцах",
                "$about Тело растёт из зиготы, стадия за четыре тика, а его мышцы всё это время сокращаются и расслабляются, по 20 тиков на ход.",
                "Мышцы не рвут растущую ткань и не губят новорождённые клетки. Тело вырастает целиком; после роста мышцы отпускают, тело возвращает форму и засыпает.",
                grow + 60 + settle(0.5), GROW, ONE) {
                val s = shapeOf(e)
                val b = growing(s, 0.0, 0.0, seed = s.genome!!.zygote, period = 4, batch = 0)
                during(0, grow + 40) { t -> muscles(b, if ((t / 20) % 2 == 0) PULL else LOOSE) }
                during(grow + 41, grow + 60) { muscles(b, REST) }
            }
        }
        // Два тела одного генома растут рядом и встречаются.
        if (e.cells in 20 until 400 && e.stages * 4 <= GROWTH_TICKS_MAX) {
            family.add("$id/twins-p4", "$name: два тела растут рядом и встречаются",
                "$about Две зиготы стоят ближе, чем наименьший полуразмер взрослого тела: тела обязаны встретиться, как бы их ни развернуло при росте; оба растут по стадиям, стадия за четыре тика.",
                "Тела упираются друг в друга и расходятся: ни одна клетка не оказывается в чужой ткани. Ни разрывов, ни гибели; после роста всё замирает и засыпает.",
                e.stages * 4 + settle(0.5), GROW_TIGHT, ONE) {
                val s = shapeOf(e)
                val z = s.genome!!.zygote
                // Зиготы — на 0.8 наименьшего полуразмера друг от друга (но не ближе 1.2: сами зиготы не касаются).
                // Прежде между центрами взрослых раскладок было полторы полуширины, и тела, которые при росте
                // сдвинуло или развернуло, расходились, не коснувшись (замечание автора игры 16.11).
                val step = maxOf(1.2, 0.8 * minOf(s.halfWidth, s.halfHeight))
                val a = growing(s, -step / 2, 0.0, seed = z, period = 4, batch = 0)
                val b = growing(s, step / 2, 0.0, seed = z, period = 4, batch = 0)
                mustTouch(a, b, "растущие тела встретились")
            }
        }
    }
    return family.list
}

internal fun genomeStageFamily(): List<Scenario> {
    val family = Family("26. Геномы: недорослые тела")
    for (e in GenomeBodies.entries) {
        if (e.cells < 30) continue
        val id = "gen-part/${e.slug}"
        val name = label(e)
        val huge = e.cells >= 1500
        for ((share, word) in listOf(0.35 to "на треть", 0.7 to "на две трети")) {
            val tag = "s${(share * 100).toInt()}"
            val about = "Тело генома «${e.title}», выросшее $word: клетки, родившиеся к этому времени, поставлены сразу такими."
            family.add("$id/$tag-rest", "$name, выросло $word: покой", "$about Тело стоит без толчков.",
                "Недорослое тело — такое же тело: стоит, не ползёт, не дрожит и засыпает.", 200, Limits.INTACT, ONE) {
                body(shapeOf(e).grownTo(share), 0.0, 0.0)
            }
            if (huge) continue
            family.add("$id/$tag-hit", "$name, выросло $word: сильный удар", "$about Левое тело летит в стоящее такое же со скоростью 2 связи за тик.",
                HIT.expect, settle(2.0), HIT.limits, ONE) {
                val s = shapeOf(e).grownTo(share)
                val (a, b) = duel(s, s, 2.0)
                expectMet(a, b, 80, 2.0)
            }
            family.add("$id/$tag-drag", "$name, выросло $word: мышь ведёт тело по кругу", "$about Мышь берёт крайнюю правую клетку и плавно ведёт её по кругу, затем отпускает.",
                "Тело идёт за курсором, не рвётся и не теряет клеток; после отпускания останавливается, возвращает форму и засыпает.", 220 + settle(0.5), Limits.DRIVEN, ONE) {
                val s = shapeOf(e).grownTo(share)
                val b = body(s, 0.0, 0.0)
                val cell = s.extreme(1.0, 0.0)
                val r = s.halfWidth + 3.0
                mousePath(b, cell, 10, 2, circlePath(0.0, 0.0, r, 100).map { doubleArrayOf(it[0] - r + s.x[cell], it[1] + s.y[cell]) })
            }
        }
    }
    return family.list
}

internal fun genomeZooFamily(): List<Scenario> {
    val family = Family("27. Геномы: присоски и зоопарк")
    val entries = GenomeBodies.entries

    // --- присоски настоящих геномов держатся за дно ---
    for (e in entries) {
        if (e.cups == 0 || e.cells >= 1500 || e.cells < TINY) continue
        val id = "gen-cup/${e.slug}"
        val name = label(e)
        val about = facts(e)
        family.add("$id/yank", "$name: присоски держат, мышь рвёт тело",
            "$about Все присоски тела держатся за дно хваткой 1. Мышь берёт самую дальнюю от них клетку, курсор разом уходит на десять единиц и стоит там 60 тиков.",
            "Присоски остаются на месте. Ткань вправе порваться, но ничего не взрывается; после отпускания движение затухает.", CRASH_TICKS, YANK, ONE) {
            val s = shapeOf(e)
            val b = body(s, 0.0, 0.0)
            val cups = s.cups()
            at(5) { grip(b, cups, 1f) }
            val c = centerOfCells(b, cups)
            var far = 0; var best = -1.0
            for (k in 0 until s.count) { val d = Math.hypot(s.x[k] - c[0], s.y[k] - c[1]); if (d > best) { best = d; far = k } }
            val len = best.coerceAtLeast(1e-6)
            at(20) { grab(b, far); dragTo(s.x[far] + 10.0 * (s.x[far] - c[0]) / len, s.y[far] + 10.0 * (s.y[far] - c[1]) / len) }
            at(80) { release() }
            expectStill("присоски ушли с места, связей", b, cups, 6, CRASH_TICKS, 0.02)
        }
        family.add("$id/hit", "$name на присосках: сильный удар",
            "$about Все присоски держатся за дно хваткой 1. Слева в тело летит малый круг со скоростью 2 связи за тик.",
            "Присоски остаются на месте, тело принимает удар целиком. Круг не входит в ткань; местный разрыв допустим (до 4% связей).",
            settle(2.0), Limits.hit(2.0).copy(energy = Energy.DRIVEN), ONE) {
            val s = shapeOf(e)
            val ram = pebble()
            val d = separation(ram, s, CONTACT_DISTANCE) + START_GAP
            val b = body(s, 0.0, 0.0)
            val p = body(ram, -d, 0.0)
            val cups = s.cups()
            grip(b, cups, 1f)
            kick(p, 2.0, 0.0)
            expectMet(p, b, 80, 2.0)
            expectStill("присоски ушли с места, связей", b, cups, 1, settle(2.0), 0.02)
        }
        if (e.muscles > 0) family.add("$id/pump", "$name на присосках: мышцы качают",
            "$about Все присоски держатся за дно хваткой 1. " + PUMP.about,
            "Мышцы тянут тело, приклеенное к дну, — и не рвут его: ни одного разрыва и ни одной погибшей клетки. Присоски не сдвигаются. После расслабления тело возвращает форму и засыпает.",
            PUMP.active + settle(0.5), Limits.DRIVEN, ONE) {
            val s = shapeOf(e)
            val b = body(s, 0.0, 0.0)
            val cups = s.cups()
            at(5) { grip(b, cups, 1f) }
            PUMP.script(this, b)
            expectStill("присоски ушли с места, связей", b, cups, 6, PUMP.active + settle(0.5), 0.02)
        }
    }

    // --- стая: шесть тел одного генома с работающими мышцами ---
    val movers = entries.filter { it.muscles > 0 && it.cells in 20 until 500 }
    for (e in movers) for (noBones in listOf(false, true)) {
        if (noBones && e.bones == 0) continue
        family.add("gen-zoo/school/${e.slug}${if (noBones) "-nobone" else ""}", "стая из шести тел ${label(e)}${if (noBones) ", без костей" else ""}",
            facts(e) + (if (noBones) " Кости заменены обычной тканью." else "") + " Шесть таких тел стоят тесной стаей; мышцы по сторонам от оси у каждого попеременно сокращаются, у соседей — со сдвигом по времени.",
            "Тела извиваются и задевают соседей. Мышцы не рвут ткань, клетки не входят ни в свою, ни в чужую ткань; после расслабления стая останавливается.",
            340 + settle(0.5) + 200, Limits.DRIVEN.copy(name = "внешняя сила, без повреждений; форма не судится", shape = false, sleep = false), ONE) {
            val s = shapeOf(e).let { if (noBones) it.withoutBones() else it }.muscleSides()
            // Соседи стоят в касании с самого начала (перекрытие кругов касания 0.05): при просвете 0.6 мышцы до соседей
            // не дотягивались, и у 17 стай из 49 никто никого не задел — сцена шла вхолостую.
            val stepX = 2 * s.halfWidth + 2 * s.contact.max() - 0.05; val stepY = 2 * s.halfHeight + 2 * s.contact.max() - 0.05
            val bodies = ArrayList<StandBody>()
            for (row in 0 until 2) for (col in 0 until 3) bodies.add(body(s, (col - 1) * stepX, (row - 0.5) * stepY))
            during(20, 320) { t ->
                for ((i, b) in bodies.withIndex()) {
                    val phase = ((t + i * 13) / 40) % 2
                    for (g in 0 until s.muscleGroups) muscle(b, g, if ((phase + g) % 2 == 0) PULL else LOOSE)
                }
            }
            at(321) { for (b in bodies) muscles(b, REST) }
            mustCollide(0.5, "не меньше половины тел стаи задели соседей")
        }
    }

    // --- зоопарк: много разных геномов в одной рамке мира ---
    //
    // Комбинации: состав по размерам тел, число тел (от 12 до 48), скорость разлёта (от 0.3 связи за
    // тик до потолка скорости, «каждое третье на потолке» и «буря» — новые толчки всем каждые 60 тиков),
    // набор геномов по сиду — и каждый такой зоопарк ещё раз из тех же тел, где кости заменены обычной
    // тканью (замечание автора игры 16.11: по паре видно, виноваты ли в поломке кости).
    //
    // Тела стоят плотно, рядами по своим размерам, с просветом меньше единицы: в прежней расстановке
    // по общей сетке малые тела стояли далеко друг от друга, и на малой скорости половина зоопарков
    // обходилась без единого столкновения — сцена шла вхолостую.
    class Mix(val id: String, val name: String, val small: Int, val medium: Int, val large: Int, val huge: Int, val seeds: Int)
    class Rush(val id: String, val name: String, val speed: Double, val thirdAtCap: Boolean = false, val storm: Boolean = false)
    val small = entries.filter { it.cells in 8 until 120 }
    val medium = entries.filter { it.cells in 120 until 500 }
    val large = entries.filter { it.cells in 500 until 1500 }
    val huge = entries.filter { it.cells >= 1500 }
    val mixes = listOf(
        Mix("small12", "двенадцать малых тел", 12, 0, 0, 0, 3), Mix("small24", "двадцать четыре малых тела", 24, 0, 0, 0, 3),
        Mix("small48", "сорок восемь малых тел", 48, 0, 0, 0, 2),
        Mix("mixed24", "шестнадцать малых и восемь средних тел", 16, 8, 0, 0, 3), Mix("mixed48", "тридцать два малых и шестнадцать средних тел", 32, 16, 0, 0, 2),
        Mix("big20", "четыре больших тела и шестнадцать малых", 16, 0, 4, 0, 2),
        Mix("huge22", "два огромных тела и двадцать малых", 20, 0, 0, 2, 2),
        Mix("all36", "все размеры: двадцать малых, десять средних, четыре больших и два огромных тела", 20, 10, 4, 2, 2),
    )
    val rushes = listOf(
        Rush("v0.3", "медленно, до 0.3 связи за тик", 0.3), Rush("v1.0", "до связи за тик", 1.0), Rush("v3.0", "до трёх связей за тик", 3.0),
        Rush("v6.0", "до шести связей за тик", 6.0), Rush("max", "до связи за тик, каждое третье тело — на потолке скорости", 1.0, thirdAtCap = true),
        Rush("storm", "буря: каждые 60 тиков все тела заново получают толчок до трёх связей за тик", 3.0, storm = true),
    )
    fun sample(from: List<GenomeEntry>, n: Int, seed: Long): List<GenomeEntry> =
        if (from.isEmpty() || n == 0) emptyList() else List(n) { from.shuffled(Random(seed + 977L * (it / from.size)))[it % from.size] }
    for (mix in mixes) for (seed in 1L..mix.seeds) {
        val pick = sample(small, mix.small, seed) + sample(medium, mix.medium, seed + 20) + sample(large, mix.large, seed + 40) + sample(huge, mix.huge, seed + 60)
        if (pick.isEmpty()) continue
        for (rush in rushes) for (noBones in listOf(false, true)) {
            if (noBones && pick.none { it.bones > 0 }) continue
            val rough = rush.thirdAtCap || rush.storm || rush.speed >= 3.0
            val base = if (rough) Limits.CRASH else Limits.hit(2 * rush.speed)
            val active = 400
            family.add("gen-zoo/${mix.id}-s$seed-${rush.id}${if (noBones) "-nobone" else ""}",
                "зоопарк${if (noBones) " без костей" else ""}: ${mix.name}, ${rush.name}, набор №$seed",
                "В рамке мира со стенкой ${pick.size} тел разных геномов стоят плотно и разлетаются в случайные стороны: ${rush.name}. " +
                    "Мышцы у тех, у кого они есть, сокращаются и расслабляются $active тиков, затем отпускают. " +
                    (if (noBones) "Кости у всех тел заменены обычной тканью. " else "") +
                    "Геномы: " + pick.map { it.title }.distinct().joinToString(", ") + ".",
                "Тела сталкиваются как придётся — не меньше половины из них обязаны с кем-нибудь столкнуться. Клетки не оказываются в чужой ткани и " +
                    "не проходят сквозь неё, никто не выходит за рамку, энергия не берётся из ничего. " +
                    (if (base.tornShare <= 0.0) "Ничего не рвётся и не гибнет. " else if (base.tornShare < 1.0) "Порваться может до ${Math.round(base.tornShare * 100)}% связей. " else "Ткань вправе рваться. ") +
                    "Движение затухает.", active + 900,
                base.copy(name = base.name + "; мышцы работают", energy = Energy.DRIVEN, sleep = false, shape = false), ONE) {
                val rnd = Random(seed * 131 + pick.size + rush.id.hashCode())
                val shapes = pick.map { e -> shapeOf(e).let { if (noBones && e.bones > 0) it.withoutBones() else it } }
                // Ряды по размерам: тела слева направо, ряд высотой в самое крупное своё тело, просвет 0.8.
                val gap = 0.8
                val order = shapes.indices.sortedByDescending { shapes[it].reach }
                val wide = maxOf(2 * shapes.maxOf { it.reach } + gap, 1.25 * sqrt(shapes.sumOf { (2 * it.reach + gap) * (2 * it.reach + gap) }))
                val px = DoubleArray(shapes.size); val py = DoubleArray(shapes.size)
                var x = 0.0; var y = 0.0; var row = 0.0; var widest = 0.0
                for (k in order) {
                    val d = 2 * shapes[k].reach
                    if (x > 0.0 && x + d > wide) { x = 0.0; y += row + gap; row = 0.0 }
                    px[k] = x + d / 2; py[k] = y + d / 2
                    x += d + gap; if (d > row) row = d; if (x > widest) widest = x
                }
                val margin = 2.0
                val width = ceil(widest - gap + 2 * margin).toInt().let { it + it % 2 }
                val height = ceil(y + row + 2 * margin).toInt().let { it + it % 2 }
                arena(width, height)
                val placed = shapes.indices.map { body(shapes[it], margin + px[it], margin + py[it], 2 * Math.PI * rnd.nextDouble()) }
                fun push(limit: Double) {
                    for ((i, b) in placed.withIndex()) {
                        val a = 2 * Math.PI * rnd.nextDouble()
                        val v = if (rush.thirdAtCap && i % 3 == 0) SPEED_CAP else limit * (0.4 + 0.6 * rnd.nextDouble())
                        kick(b, v * cos(a), v * sin(a))
                    }
                }
                push(rush.speed)
                if (rush.storm) during(60, active) { t -> if (t % 60 == 0) push(rush.speed) }
                val movers2 = placed.filter { it.shape.muscleGroups > 0 }
                if (movers2.isNotEmpty()) {
                    during(10, active) { t -> for ((i, b) in movers2.withIndex()) muscles(b, if (((t + 23 * i) / 35) % 2 == 0) PULL else LOOSE) }
                    at(active + 1) { for (b in movers2) muscles(b, REST) }
                }
                mustCollide(0.5, "не меньше половины тел зоопарка с кем-нибудь столкнулись")
                expectAllInside(0.0, 0.0, width.toDouble(), height.toDouble(), active + 900)
            }
        }
    }
    return family.list
}
