package io.github.some_example_name.physics.lab.stands

import io.github.some_example_name.physics.lab.stands.perf.PERF_SCENES
import io.github.some_example_name.physics.lab.stands.perf.setup
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/*
 * СЕМЕЙСТВА ИЗ ПЛАНА СТАБИЛЬНОСТИ (пункт «5. Стенды»), которых в каталоге не было (16.11).
 *
 *  28. Свободный полёт и законы сохранения — «20 секунд без сил; вращение без среды». Среда
 *      выключена (StandWorld.noMedium), и на тело не действует ничего внешнего: импульс, момент
 *      импульса и энергия обязаны сохраняться. Здесь судят сам решатель, а не то, как он тормозит.
 *  29. Сетка ударов — «таран по сетке углов, скоростей и сдвигов».
 *  30. Сжали и отпустили — «толпа в ёмкости, сжали и отпустили».
 *  31. Игровая логика — «ткань становится костью, массовая гибель, поедание, деление».
 *  32. Вырожденные тела — «нулевые связи, одна и две клетки».
 *  33. Масштаб — «тело 10k+, толпа 100k».
 *
 * Пороги в ожиданиях этих сцен — первые оценки: что считать «сохраняется», решает точность
 * решателя, и числа стоит ужать по первым прогонам (записано рядом с каждым).
 */

/** Без среды: тело не тормозится и не засыпает. Проникновения, числа и энергия судятся как всегда. */
private val VACUUM = Limits("пустота: среды нет; покоя и сна не ждём", calm = false, sleep = false)

/**
 * Величина [value] не уходит от своего значения при сборке сцены больше чем на долю [tolerance].
 * С первой погибшей клетки не судится: мёртвая клетка выходит из мира вместе со своей массой и
 * импульсом, и сохраняться сумме по живым после этого не с чего.
 */
private fun StandWorld.conserved(what: String, tolerance: Double, until: Int, scale: Double = 0.0, value: () -> Double) {
    val start = value()
    val unit = maxOf(abs(start), scale, 1e-12)
    probe("$what: отклонение от начального, доля") { if (anyDead()) Double.NaN else abs(value() - start) / unit }
    expectMaxAtMost("$what сохраняется", "$what: отклонение от начального, доля", 1, until, tolerance)
}

/** Погибла ли в сцене хоть одна клетка: с этого момента суммы по живым клеткам сохраняться не обязаны. */
private fun StandWorld.anyDead(): Boolean = world.state.deadCount > 0

/** Скорость центра масс всего мира, связей за тик. */
private fun StandWorld.worldSpeed(): Double { val m = momentum(); return if (m[3] > 0.0) hypot(m[0], m[1]) / m[3] else 0.0 }

internal fun freeFamily(): List<Scenario> {
    val family = Family("28. Свободный полёт и законы сохранения")
    class Flyer(val id: String, val shape: ShapeSpec)
    val flyers = listOf(
        Flyer("square8", Shapes.square(8)), Flyer("disc5", Shapes.disc(5)), Flyer("rect16x4", Shapes.rect(16, 4)),
        Flyer("ring", Shapes.ring(6, 3)), Flyer("rope12", Shapes.rope(12)), Flyer("blob", Shapes.blob(2, 6)),
        Flyer("brick", Shapes.square(8).boneAll()), Flyer("core", Shapes.square(8).boneCore()),
        Flyer("plates", Shapes.rect(18, 6).bonePlates()), Flyer("spine", Shapes.rect(18, 6).boneSpine()),
    )
    val long = 600   // «20 секунд без сил» при 30 тиках в секунду

    for (f in flyers) {
        val s = f.shape
        val all = IntArray(s.count) { it }
        family.add("free/rest/${f.id}", "${s.name}: покой в пустоте, 600 тиков",
            "Одно тело в позе покоя; среда выключена, сон выключен. Двадцать секунд ничего не происходит.",
            "Без среды гасить нечего — и нечему разгонять: ни одна клетка не уходит от места дальше двух сотых связи, тело не ползёт, не вращается и не дрожит.",
            long, VACUUM.copy(calm = true)) {
            noMedium(); noSleep()
            val b = body(s, 0.0, 0.0)
            idle("покой в пустоте: проверяется, что тело само не разгоняется")
            expectStill("клетки ушли от места, связей", b, all, 0, long, 0.02)
            probe("скорость тела") { speedOf(b) }
            expectMaxAtMost("тело не разгоняется само", "скорость тела", 1, long, 0.0005)
        }
        for (speed in listOf(0.6, 2.0, 6.0)) {
            family.add("free/flight/${f.id}/v$speed", "${s.name}: свободный полёт в пустоте, скорость $speed",
                "Тело получает скорость $speed связи за тик вправо; среда выключена. Двадцать секунд на него ничего не действует.",
                "Тело летит равномерно и прямо: импульс сохраняется до миллионной доли, центр идёт ровно со скоростью толчка, вбок не уходит, форма не меняется, энергия не растёт.",
                long, VACUUM) {
                noMedium(); noSleep()
                val b = body(s, 0.0, 0.0)
                kick(b, speed, 0.0)
                val start = centerOf(b)
                mustMove(b, speed * 100, "тело улетело от старта")
                conserved("импульс вдоль хода", 1e-6, long) { momentum()[0] }
                probe("увод вбок, связей") { abs(centerOf(b)[1] - start[1]) / GAME_LINK }
                expectMaxAtMost("тело не уводит вбок", "увод вбок, связей", 1, long, 0.01)
                probe("отставание от равномерного хода, связей") { abs((centerOf(b)[0] - start[0]) / GAME_LINK - speed * tick) }
                expectMaxAtMost("тело идёт равномерно", "отставание от равномерного хода, связей", 1, long, 0.01 * speed * long)
                probe("искажение формы, доля") { linkStrain(b) }
                expectMaxAtMost("форма в полёте не меняется", "искажение формы, доля", 1, long, 0.01)
            }
        }
        for (spin in listOf(0.02, 0.1)) {
            family.add("free/spin/${f.id}/w$spin", "${s.name}: вращение в пустоте, $spin радиана за тик",
                "Тело получает вращение $spin радиана за тик вокруг центра масс; среда выключена.",
                "Тело вращается с постоянной скоростью: момент импульса сохраняется до десятитысячной доли, центр масс стоит на месте, импульс остаётся нулевым, энергия не растёт.",
                long, VACUUM) {
                noMedium(); noSleep()
                val b = body(s, 0.0, 0.0)
                kick(b, 0.0, 0.0, spin = spin)
                val start = centerOf(b)
                mustTurn(b, 1.0, "тело повернулось хотя бы на радиан")
                conserved("момент импульса", 1e-4, long) { momentum()[2] }
                probe("центр масс ушёл, связей") { val c = centerOf(b); hypot(c[0] - start[0], c[1] - start[1]) / GAME_LINK }
                expectMaxAtMost("центр масс на месте", "центр масс ушёл, связей", 1, long, 0.02)
                probe("скорость центра масс") { worldSpeed() }
                expectMaxAtMost("импульс остаётся нулевым", "скорость центра масс", 1, long, 1e-5)
            }
        }
        family.add("free/both/${f.id}", "${s.name}: летит и вращается в пустоте",
            "Тело получает скорость 2 связи за тик вправо и вращение 0.05 радиана за тик; среда выключена.",
            "Полёт и вращение не мешают друг другу: импульс и момент импульса сохраняются, центр масс идёт прямо.",
            long, VACUUM) {
            noMedium(); noSleep()
            val b = body(s, 0.0, 0.0)
            kick(b, 2.0, 0.0, spin = 0.05)
            val start = centerOf(b)
            mustMove(b, 200.0, "тело улетело от старта")
            conserved("импульс вдоль хода", 1e-6, long) { momentum()[0] }
            // Момент — относительно центра масс: относительно начала координат он растёт вместе с уходом тела по Y, которого нет.
            conserved("момент импульса вокруг центра масс", 1e-4, long) {
                val m = momentum(); val c = centerOf(b)
                m[2] - (c[0] * m[1] - c[1] * m[0])
            }
            probe("увод вбок, связей") { abs(centerOf(b)[1] - start[1]) / GAME_LINK }
            expectMaxAtMost("тело не уводит вбок", "увод вбок, связей", 1, long, 0.05)
        }
    }

    // --- удар в пустоте: импульс и момент пары тел сохраняются, энергия не растёт ---
    class Pair2(val id: String, val a: ShapeSpec, val b: ShapeSpec)
    val pairs = listOf(
        Pair2("square8", Shapes.square(8), Shapes.square(8)), Pair2("disc5-rect16x4", Shapes.disc(5), Shapes.rect(16, 4)),
        Pair2("disc3-square12", Shapes.disc(3), Shapes.square(12)), Pair2("brick-square8", Shapes.square(8).boneAll(), Shapes.square(8)),
        Pair2("brick-brick", Shapes.square(8).boneAll(), Shapes.square(8).boneAll()), Pair2("core-core", Shapes.square(10).boneCore(), Shapes.square(10).boneCore()),
        Pair2("plates-plates", Shapes.rect(18, 6).bonePlates(), Shapes.rect(18, 6).bonePlates()),
    )
    for (p in pairs) for (hit in listOf(TAP, BUMP, HIT)) for (offset in listOf(0.0, 0.6)) {
        val tag = if (offset == 0.0) "" else "-glance"
        family.add("free/hit/${p.id}/${hit.id}$tag", "${p.a.name} в ${p.b.name} в пустоте: ${hit.title}${if (offset == 0.0) "" else ", вскользь"}",
            "Левое тело летит в стоящее со скоростью ${hit.speed} связи за тик${if (offset == 0.0) "" else ", сдвинутое на 0.6 полувысоты"}; среда выключена.",
            "Удар передаёт импульс от тела к телу, но общий импульс и общий момент импульса пары не меняются; энергия не растёт; проникновений нет.",
            400, hit.limits.copy(name = hit.limits.name + "; пустота", calm = false, sleep = false, shape = false)) {
            noMedium(); noSleep()
            val shift = offset * p.b.halfHeight
            val (a, b) = duel(p.a, p.b, hit.speed, shift)
            expectMet(a, b, 400, hit.speed)
            val size = p.a.reach + p.b.reach
            conserved("импульс пары вдоль хода", 1e-6, 400) { momentum()[0] }
            probe("импульс пары поперёк хода к начальному") { if (anyDead()) Double.NaN else abs(momentum()[1]) }
            val p0 = abs(momentum()[0])
            expectMaxAtMost("поперёк хода импульс не появляется", "импульс пары поперёк хода к начальному", 1, 400, 1e-6 * p0)
            // Масштаб момента — импульс на размер пары: при лобовом ударе сам момент нулевой, и делить на него нельзя.
            // Порог 5e-4 — по замеру 16.11: мягкие тела на ударе в 2 связи за тик уходят на 1.6–1.9e-4, костные — до 3.4e-4
            // по импульсу и на порядки дальше поперёк хода (костный кирпич о костный кирпич — 0.5% начального импульса).
            conserved("момент импульса пары", 5e-4, 400, scale = p0 * size) { momentum()[2] }
        }
    }

    // --- мышцы в пустоте: тело не может сдвинуть свой центр масс и раскрутиться ---
    for (shape in listOf(Shapes.rect(20, 4).muscleAll(), Shapes.rect(20, 4).muscleTopBottom(), Shapes.rect(24, 4).muscleSegments(6), Shapes.disc(6).muscleLeftRight(), fishShape(), tadpoleShape())) {
        for (drive in listOf(PUMP, SWAP_FAST, WAVE)) {
            val ticks = drive.active + 200
            family.add("free/muscle/${shape.id}/${drive.id}", "${shape.name} в пустоте: ${drive.title}",
                "Среда выключена. ${drive.about} Оттолкнуться телу не от чего.",
                "Мышцы меняют форму тела, но не двигают его центр масс и не дают ему импульса и момента импульса: в пустоте плыть нельзя.",
                ticks, Limits.DRIVEN.copy(name = "внешняя сила, без повреждений; пустота", calm = false, sleep = false, shape = false)) {
                noMedium(); noSleep()
                val b = body(shape, 0.0, 0.0)
                drive.script(this, b)
                mustDeform(b, 0.05, "мышцы изменили форму тела")
                val start = centerOf(b)
                probe("центр масс ушёл, связей") { if (anyDead()) Double.NaN else { val c = centerOf(b); hypot(c[0] - start[0], c[1] - start[1]) / GAME_LINK } }
                expectMaxAtMost("центр масс на месте", "центр масс ушёл, связей", 1, ticks, 0.05)
                probe("скорость центра масс") { if (anyDead()) Double.NaN else worldSpeed() }
                expectMaxAtMost("мышцы не дают телу импульса", "скорость центра масс", 1, ticks, 1e-4)
                // Замер 16.11: у целых тел без гибели клеток момент импульса гуляет до 1.4e-4 масштаба «масса на размер»; порог — вдвое выше.
                val unit = shape.count * shape.reach * 3e-4
                probe("момент импульса") { if (anyDead()) Double.NaN else abs(momentum()[2]) }
                expectMaxAtMost("мышцы не дают телу момента импульса", "момент импульса", 1, ticks, unit)
            }
        }
    }

    // --- рост в пустоте: тело не разгоняет само себя ---
    for (shape in listOf(Shapes.square(8), Shapes.disc(5), Shapes.rect(16, 4), Shapes.square(10).boneCore())) for (pace in listOf(SLOW, LAYERS)) {
        val center = shape.nearest(0.0, 0.0)
        val ticks = pace.ticks(shape, center) + 200
        family.add("free/grow/${shape.id}/${pace.id}", "${shape.name}: рост в пустоте, ${pace.name}",
            "Тело растёт из середины; среда выключена, гасить отдачу деления нечему.",
            "Тело вырастает целиком и после роста стоит: рост не даёт ему ни скорости, ни вращения.",
            ticks, GROW.copy(name = "рост без повреждений; пустота", calm = false, sleep = false)) {
            noMedium(); noSleep()
            val b = growing(shape, 0.0, 0.0, seed = center, period = pace.period, batch = pace.batch)
            probe("скорость тела после роста") { if (b.growth!!.done) speedOf(b) else 0.0 }
            expectMaxAtMost("рост не разгоняет тело", "скорость тела после роста", 1, ticks, 0.002)
        }
    }
    return family.list
}

internal fun gridFamily(): List<Scenario> {
    val family = Family("29. Сетка ударов: углы, сдвиги, скорости")
    class Pair2(val id: String, val a: ShapeSpec, val b: ShapeSpec)
    val pairs = listOf(
        Pair2("square8", Shapes.square(8), Shapes.square(8)), Pair2("disc5-rect16x4", Shapes.disc(5), Shapes.rect(16, 4)),
        Pair2("tri9-ring", Shapes.triangle(9), Shapes.ring(6, 3)), Pair2("brick-square10", Shapes.square(8).boneAll(), Shapes.square(10)),
    )
    for (p in pairs) for (turnA in listOf(0, 20, 45)) for (turnB in listOf(0, 30)) for (shift in listOf(0, 50, 90)) for (hit in listOf(BUMP, HIT, RAM)) {
        val ticks = if (hit.rough) CRASH_TICKS else settle(hit.speed)
        family.add("grid/${p.id}/a${turnA}b${turnB}o$shift/${hit.id}",
            "${p.a.name} в ${p.b.name}: ${hit.title}, повороты $turnA° и $turnB°, сдвиг $shift%",
            "Левое тело повёрнуто на $turnA°, правое — на $turnB°; левое сдвинуто вбок на $shift% полувысоты правого и летит в него со скоростью ${hit.speed} связи за тик.",
            "Тела встречаются углами, рёбрами или вскользь — как придётся. " + hit.expect,
            ticks, hit.limits) {
            val (a, b) = duel(p.a, p.b, hit.speed, shift / 100.0 * p.b.halfHeight, 0.0, Math.toRadians(turnA.toDouble()), Math.toRadians(turnB.toDouble()))
            expectMet(a, b, ticks, hit.speed)
        }
    }
    return family.list
}

internal fun pressFamily(): List<Scenario> {
    val family = Family("30. Сжали и отпустили")
    class Crowd(val id: String, val name: String, val shape: ShapeSpec)
    val crowds = listOf(
        Crowd("discs", "круги радиуса 2", Shapes.disc(2)), Crowd("squares", "квадраты 4", Shapes.square(4)),
        Crowd("bricks", "костные квадраты 4", Shapes.square(4).boneAll()), Crowd("cores", "квадраты 5 с костным ядром", Shapes.square(5).boneCore()),
        Crowd("worms", "мышечные бруски 6×3", Shapes.rect(6, 3).muscleAll()),
    )
    for (crowd in crowds) for ((tag, share, title) in listOf(Triple("p15", 0.15, "на 15%"), Triple("p30", 0.30, "на 30%"), Triple("p50", 0.50, "вдвое"))) {
        val hard = share >= 0.3
        val limits = if (hard) Limits.CRASH.copy(name = "сжатие за пределом: разрушение допустимо", energy = Energy.DRIVEN, sleep = false)
        else Limits.DRIVEN.copy(name = "внешняя сила, без повреждений; форма не судится", shape = false, sleep = false)
        family.add("press/${crowd.id}/$tag", "толпа в ёмкости (${crowd.name}): сжали $title и отпустили",
            "Замкнутая ёмкость из рельефа; в ней рядами стоит толпа тел, а справа — поршень: костный брусок во всю высоту ёмкости, " +
                "который ведут зажимы. За 120 тиков поршень уходит влево $title длины ёмкости, стоит 80 тиков и возвращается.",
            "Тела сжимаются и расправляются обратно. Никто не входит в рельеф и в поршень, никто не оказывается за поршнем, " +
                (if (hard) "ткань вправе порваться, но ничего не взрывается; " else "ничего не рвётся и не гибнет; ") + "после возврата поршня движение затухает.",
            340 + settle(0.5), limits) {
            val c = cavity(24, 10, jitter = false)
            val innerW = c.maxX - c.minX; val innerH = c.maxY - c.minY
            // Поршень: три столбца клеток, рядов — сколько помещается по высоте ёмкости с просветом меньше клетки.
            val contact = 0.5 * GAME_LINK * Variation.current.spread
            val rows = maxOf(3, floor((innerH - 2 * contact - 0.2) / (GAME_ROW * Variation.current.stretch)).toInt() + 1)
            val pistonShape = Shapes.rect(3, rows).boneAll()
            val pistonX = c.maxX - pistonShape.halfWidth - contact - 0.1
            val midY = (c.minY + c.maxY) / 2
            // Толпа — слева от поршня, рядами с просветом в две десятых.
            val s = crowd.shape
            val stepX = 2 * s.halfWidth + 2 * s.contact.max() + 0.2; val stepY = 2 * s.halfHeight + 2 * s.contact.max() + 0.2
            val freeW = pistonX - pistonShape.halfWidth - contact - c.minX - 0.3
            val cols = maxOf(1, floor(freeW / stepX).toInt()); val lines = maxOf(1, floor((innerH - 0.3) / stepY).toInt())
            val members = ArrayList<StandBody>()
            for (iy in 0 until lines) for (ix in 0 until cols)
                members.add(body(s, c.minX + 0.15 + s.halfWidth + s.contact.max() + ix * stepX, midY + (iy - (lines - 1) / 2.0) * stepY))
            val piston = body(pistonShape, pistonX, midY)
            val hold = clamp(piston, IntArray(piston.count) { it })
            val travel = share * innerW
            during(1, 340) { t ->
                val k = when { t <= 120 -> ramp(t, 1, 120); t <= 200 -> 1.0; else -> 1.0 - ramp(t, 200, 320) }
                hold.moveTo(-travel * k, 0.0)
            }
            if (s.muscleGroups > 0) {
                during(10, 300) { t -> for ((i, b) in members.withIndex()) muscles(b, if (((t + 17 * i) / 30) % 2 == 0) 0.5f else 1.5f) }
                at(301) { for (b in members) muscles(b, 1f) }
            }
            must("поршень дошёл до толпы") { members.any { touch.bodies(piston, it) } }
            probe("клеток толпы за поршнем") {
                val st = world.state
                var edge = Double.MAX_VALUE
                for (k in 0 until piston.count) edge = minOf(edge, gameX(piston.particle(k)))
                var beyond = 0
                for (b in members) for (k in 0 until b.count) { val q = b.particle(k); if (!st.cellDead[q] && gameX(q) > edge + 0.3) beyond++ }
                beyond.toDouble()
            }
            expectMaxAtMost("никто не оказался за поршнем", "клеток толпы за поршнем", 1, 340 + settle(0.5), 0.0)
        }
    }
    return family.list
}

internal fun logicFamily(): List<Scenario> {
    val family = Family("31. Игровая логика: кость из ткани, гибель, поедание, деление")
    val quiet = Limits.DRIVEN.copy(name = "событие по сценарию, без повреждений")

    // --- ткань становится костью ---
    val boned = listOf(Shapes.square(8).boneCore(), Shapes.square(8).boneAll(), Shapes.rect(18, 6).boneSpine(), Shapes.rect(18, 6).bonePlates(), Shapes.disc(6).boneShell())
    for (shape in boned) {
        family.add("logic/ossify/${shape.id}/rest", "${shape.name}: ткань становится костью в покое",
            "Тело стоит целиком мягким; на тике 40 его костные клетки становятся костью — как при мутации клетки в игре.",
            "Превращение проходит без рывка: тело не дёргается, не меняет форму и не получает энергии; кость включается целиком; затем всё засыпает.",
            40 + settle(0.2), quiet) {
            val b = body(shape, 0.0, 0.0, bonesOn = false)
            at(40) { ossify(b) }
            must("кость включилась целиком") { tick > 40 && ossified(b) }
            expectStill("клетки сдвинулись при превращении, связей", b, IntArray(b.count) { it }, 0, 40 + settle(0.2), 0.05)
        }
        family.add("logic/ossify/${shape.id}/bump", "${shape.name}: ткань стала костью, затем толчок",
            "Тело стоит мягким; на тике 40 его костные клетки становятся костью; на тике 140 в него влетает круг со скоростью 0.6 связи за тик.",
            "Свежая кость держит удар как обычная: не мнётся, ткань вокруг не рвётся, проникновений нет; к концу всё стоит и спит.",
            140 + settle(0.6), quiet) {
            val b = body(shape, 0.0, 0.0, bonesOn = false)
            val disc = Shapes.disc(3)
            val h = body(disc, -(separation(disc, shape, CONTACT_DISTANCE) + START_GAP), 0.0)
            at(40) { ossify(b) }
            at(140) { kick(h, 0.6, 0.0) }
            must("кость включилась до удара") { tick in 41..139 && ossified(b) }
            mustTouch(h, b, "круг долетел до тела")
        }
        family.add("logic/ossify/${shape.id}/bent", "${shape.name}: ткань становится костью у согнутого тела",
            "Мягкое тело перекошено зажимами (правый край поднят на четверть высоты); на тике 150, в перекошенной позе, костные клетки становятся костью; на тике 220 зажимы отпускают.",
            "Кость застывает в той позе, в какой её застало превращение: без рывка при превращении и без взрыва при отпускании; после отпускания тело успокаивается.",
            220 + settle(0.5), quiet.copy(name = "событие по сценарию; форма не судится", shape = false)) {
            val b = body(shape, 0.0, 0.0, bonesOn = false)
            val left = clamp(b, edgeCells(shape, -1.0, 0.0)); val right = clamp(b, edgeCells(shape, 1.0, 0.0))
            val lift = 0.25 * 2 * shape.halfHeight
            during(1, 219) { t -> left.moveTo(0.0, 0.0); right.moveTo(0.0, lift * ramp(t, 1, 120)) }
            at(150) { ossify(b) }
            at(220) { left.release(); right.release() }
            must("кость включилась в перекошенной позе") { tick in 151..219 && ossified(b) }
            probe("скорость тела") { speedOf(b) }
            expectMaxAtMost("отпущенное тело не выстреливает", "скорость тела", 221, 220 + settle(0.5), 0.3)
        }
    }

    // --- массовая гибель ---
    class Death(val id: String, val name: String, val pick: (ShapeSpec, Random) -> IntArray)
    val deaths = listOf(
        Death("scatter30", "гибнет случайная треть клеток") { s, rnd -> (0 until s.count).filter { rnd.nextDouble() < 0.3 }.toIntArray() },
        Death("scatter70", "гибнут две трети клеток, вразброс") { s, rnd -> (0 until s.count).filter { rnd.nextDouble() < 0.7 }.toIntArray() },
        Death("half", "гибнет левая половина") { s, _ -> sideCells(s, -1.0, 0.0) },
        Death("core", "гибнет середина") { s, _ -> (0 until s.count).filter { hypot(s.lx[it], s.ly[it]) < 0.45 * s.reach }.toIntArray() },
        Death("rim", "гибнет весь край") { s, _ -> (0 until s.count).filter { s.isOutline(it) }.toIntArray() },
    )
    for (shape in listOf(Shapes.square(10), Shapes.disc(6), Shapes.rect(18, 6).boneSpine(), Shapes.square(10).boneCore(), Shapes.rect(20, 4).muscleAll())) for (d in deaths) {
        family.add("logic/death/${shape.id}/${d.id}", "${shape.name}: ${d.name}",
            "Тело стоит; на тике 20 ${d.name} — разом, тем же вызовом, каким игра убивает клетку.",
            "Гибель клеток не толкает уцелевшие: остатки стоят на месте (не дальше трети связи), ничего не разлетается и не взрывается, энергия не появляется; остатки засыпают.",
            20 + settle(0.2), Limits.INTACT.copy(name = "без повреждений; гибель по сценарию")) {
            val b = body(shape, 0.0, 0.0)
            val victims = d.pick(shape, Random(31L + shape.count))
            val gone = victims.toHashSet()
            val left = (0 until shape.count).filter { it !in gone }.toIntArray()
            at(20) { killCells(b, victims) }
            must("клетки погибли по сценарию") { scriptedDead > 0 }
            if (left.isNotEmpty()) expectStill("уцелевшие клетки ушли, связей", b, left, 20, 20 + settle(0.2), 0.34)
        }
    }

    // --- поедание: костный едок вгрызается в мягкое тело, клетки перед ним гибнут ---
    for (prey in listOf(Shapes.square(12), Shapes.disc(7), Shapes.rect(20, 8), Shapes.square(12).boneCore())) {
        val eater = Shapes.square(6).boneAll()
        family.add("logic/eat/${prey.id}", "костный едок съедает ${prey.name}",
            "Костный квадрат подталкивают к телу (0.3 связи за тик каждые 30 тиков); каждые четыре тика гибнет клетка тела, ближайшая к едоку и не дальше единицы от него, — как при поедании в игре.",
            "Едок вгрызается в тело на пол его ширины. Ни одна его клетка не оказывается в чужой ткани, остатки тела не разлетаются и не взрываются.",
            900, Limits.DRIVEN.copy(name = "внешняя сила; гибель по сценарию; форма и сон не судятся", shape = false, sleep = false, calm = false)) {
            val b = body(prey, 0.0, 0.0)
            val e = body(eater, -(separation(eater, prey, CONTACT_DISTANCE) + 0.5), 0.0)
            val start = centerOf(e)[0]
            during(1, 800) { t ->
                if (t % 30 == 1) kick(e, 0.3, 0.0)
                if (t % 4 == 0) {
                    val st = world.state
                    val c = centerOf(e)
                    var best = -1; var bestD = Double.MAX_VALUE
                    for (k in 0 until b.count) {
                        val q = b.particle(k)
                        if (st.cellDead[q]) continue
                        val d = hypot(gameX(q) - c[0], gameY(q) - c[1])
                        if (d < bestD) { bestD = d; best = k }
                    }
                    if (best >= 0 && bestD < eater.reach + 1.0) killCells(b, intArrayOf(best))
                }
            }
            must("едок съел хотя бы десяток клеток") { scriptedDead >= 10 }
            probe("едок прошёл вглубь, единиц") { centerOf(e)[0] - start }
            expectMaxAtLeast("едок вгрызся в тело", "едок прошёл вглубь, единиц", 1, 900, prey.halfWidth)
        }
    }

    // --- деление: тело с работающими мышцами разрезают пополам и разводят половины ---
    for (shape in listOf(Shapes.rect(24, 4).muscleAll(), Shapes.rect(24, 4).muscleSegments(6), Shapes.rect(20, 6).muscleTopBottom(), Shapes.disc(6).muscleLeftRight())) {
        val left = sideCells(shape, -1.0, 0.0); val leftSet = left.toHashSet()
        val right = (0 until shape.count).filter { it !in leftSet }.toIntArray()
        family.add("logic/divide/${shape.id}", "${shape.name}: деление пополам при работающих мышцах",
            "Мышцы тела качают; на тике 90 тело делится: связи между левой и правой половиной гаснут, половины получают по 0.3 связи за тик в разные стороны. Мышцы обеих половин работают дальше.",
            "Половины расходятся и живут порознь: без рывка и взрыва в момент деления, мышцы не рвут ткань; после покоя обе половины останавливаются и засыпают.",
            PUMP.active + 90 + settle(0.5), Limits.DRIVEN.copy(name = "внешняя сила, без повреждений; разрез по сценарию", shape = false)) {
            val b = body(shape, 0.0, 0.0)
            during(10, 90 + PUMP.active) { t -> muscles(b, if ((t / 20) % 2 == 0) 0.5f else 1.5f) }
            at(91 + PUMP.active) { muscles(b, 1f) }
            at(90) {
                cut(b) { a, c -> (a in leftSet) != (c in leftSet) }
                kickCells(b, left, -0.3, 0.0); kickCells(b, right, 0.3, 0.0)
            }
            must("тело разделилось на две половины") { tick > 90 && pieces(b) >= 2 }
            probe("расстояние между половинами, связей") { (centerOfCells(b, right)[0] - centerOfCells(b, left)[0]) / GAME_LINK }
            expectMaxAtLeast("половины разошлись", "расстояние между половинами, связей", 91, PUMP.active + 90 + settle(0.5), 2 * shape.halfWidth / GAME_LINK / 2 + 2.0)
        }
    }
    return family.list
}

/** Вырожденное тело по точкам и связям, в единицах игры. Середина рамки точек ставится в ноль — как у фигур на решётке. */
private fun odd(id: String, name: String, points: List<Pair<Double, Double>>, links: List<Pair<Int, Int>>): ShapeSpec {
    val cx = (points.minOf { it.first } + points.maxOf { it.first }) / 2; val cy = (points.minOf { it.second } + points.maxOf { it.second }) / 2
    return ShapeSpec("odd-$id", name, DoubleArray(points.size) { points[it].first - cx }, DoubleArray(points.size) { points[it].second - cy },
        IntArray(links.size) { links[it].first }, IntArray(links.size) { links[it].second }, BooleanArray(points.size), IntArray(points.size) { -1 })
}

internal fun oddFamily(): List<Scenario> {
    val family = Family("32. Вырожденные тела")
    val l = GAME_LINK; val h = GAME_ROW
    val shapes = listOf(
        odd("cell1", "тело из одной клетки", listOf(0.0 to 0.0), emptyList()),
        odd("cell2", "тело из двух клеток", listOf(-l / 2 to 0.0, l / 2 to 0.0), listOf(0 to 1)),
        odd("line3", "три клетки в линию", listOf(-l to 0.0, 0.0 to 0.0, l to 0.0), listOf(0 to 1, 1 to 2)),
        odd("tri3", "треугольник из трёх клеток", listOf(-l / 2 to 0.0, l / 2 to 0.0, 0.0 to h), listOf(0 to 1, 1 to 2, 0 to 2)),
        odd("kite", "треугольник с хвостом в одну клетку", listOf(-l / 2 to 0.0, l / 2 to 0.0, 0.0 to h, 0.0 to h + l), listOf(0 to 1, 1 to 2, 0 to 2, 2 to 3)),
        odd("hub", "звезда: клетка и пять клеток на спицах, без треугольников", listOf(0.0 to 0.0) + (0 until 5).map { l * cos(2 * Math.PI * it / 5) to l * sin(2 * Math.PI * it / 5) },
            (1..5).map { 0 to it }),
        odd("zero", "треугольник с почти нулевой связью (0.02)", listOf(0.0 to 0.0, 0.02 to 0.0, 0.3 to 0.52), listOf(0 to 1, 0 to 2, 1 to 2)),
        odd("near", "четыре клетки, две почти в одной точке (0.05)", listOf(0.0 to 0.0, l to 0.0, l / 2 to h, l / 2 + 0.05 to h), listOf(0 to 1, 0 to 2, 1 to 2, 2 to 3, 1 to 3)),
        odd("long", "три клетки на связях длиной 2.4", listOf(-2.4 to 0.0, 0.0 to 0.0, 2.4 to 0.0), listOf(0 to 1, 1 to 2)),
        odd("longtri", "треугольник со связями 2.4", listOf(-1.2 to 0.0, 1.2 to 0.0, 0.0 to 2.08), listOf(0 to 1, 1 to 2, 0 to 2)),
        odd("sliver", "почти плоский треугольник", listOf(-l / 2 to 0.0, l / 2 to 0.0, 0.0 to 0.03), listOf(0 to 1, 1 to 2, 0 to 2)),
        odd("cross", "четыре клетки, связанные крест-накрест", listOf(-l / 2 to -l / 2, l / 2 to l / 2, -l / 2 to l / 2, l / 2 to -l / 2), listOf(0 to 1, 2 to 3)),
    )
    val one = Variation.ONLY_BASE
    for (s in shapes) {
        val slow = true
        family.add("odd/${s.id.removePrefix("odd-")}/rest", "${s.name}: покой", "Одно тело в позе покоя, без толчков.",
            "Ничего не происходит: тело стоит, не дрожит, не разлетается и засыпает; числа конечны.", 200, Limits.INTACT, one) {
            body(s, 0.0, 0.0)
        }
        for (hit in listOf(BUMP, HIT)) {
            family.add("odd/${s.id.removePrefix("odd-")}/${hit.id}", "${s.name}: в него летит круг, ${hit.title}",
                "Круг радиуса 3 летит в стоящее тело со скоростью ${hit.speed} связи за тик.",
                "Тело получает удар как любое другое: без взрыва и проникновений. " + hit.expect, settle(hit.speed, slow), hit.limits, one) {
                val disc = Shapes.disc(3)
                val (a, b) = duel(disc, s, hit.speed)
                expectMet(a, b, settle(hit.speed, slow), hit.speed)
            }
        }
        family.add("odd/${s.id.removePrefix("odd-")}/pair", "${s.name}: два таких тела сталкиваются",
            "Левое тело летит в стоящее такое же со скоростью 0.6 связи за тик.",
            "Тела сталкиваются и расходятся: без взрыва, проникновений, разрывов и гибели; к концу всё стоит и спит.", settle(0.6, slow), Limits.INTACT, one) {
            val (a, b) = duel(s, s, 0.6)
            expectMet(a, b, settle(0.6, slow), 0.6)
        }
        family.add("odd/${s.id.removePrefix("odd-")}/drag", "${s.name}: мышь ведёт тело по кругу",
            "Мышь берёт первую клетку тела и плавно ведёт её по кругу радиуса две единицы, затем отпускает.",
            "Тело идёт за курсором целиком, не рвётся и не выворачивается; после отпускания останавливается и засыпает.", 200 + settle(0.5, slow), Limits.DRIVEN, one) {
            val b = body(s, 0.0, 0.0)
            val sx = s.x[0]; val sy = s.y[0]
            mousePath(b, 0, 10, 4, circlePath(sx - 2.0, sy, 2.0, 40))
        }
        family.add("odd/${s.id.removePrefix("odd-")}/corner", "${s.name}: в угол рамки мира",
            "Мир 12×12 со стенкой. Тело летит наискось в угол со скоростью 2 связи за тик.",
            "Тело упирается в стенку и остаётся в мире: не выходит за рамку и не входит в частицы стенки; к концу стоит и спит.", settle(2.0, slow), Limits.hit(2.0), one) {
            arena(12, 12)
            // Длинное тело (связи 2.4) в точке (8.5, 8.5) стояло бы уже в стенке: место — от его размера.
            // Радиус касания клетки на длинной связи — половина связи (1.2): запас до стенки считается с ним.
            val at = minOf(8.5, 12.0 - 1.0 - s.reach - s.contact.max())
            val b = body(s, at, at)
            kick(b, 1.42, 1.42)
            mustTouchTerrain(b, "тело долетело до стенки")
            expectAllInside(0.0, 0.0, 12.0, 12.0, settle(2.0, slow))
        }
    }
    return family.list
}

internal fun hugeFamily(): List<Scenario> {
    val family = Family("33. Масштаб: сто тысяч частиц и тело в десять тысяч клеток")
    val one = Variation.ONLY_BASE
    val crowd = PERF_SCENES.first { it.id == "small" }
    family.add("huge/crowd100k", "толпа в сто тысяч частиц", crowd.about + " Размер — впятеро против замера: около ста пяти тысяч частиц в двух тысячах тел.",
        "Сцена идёт сто пятьдесят тиков и не обязана успокоиться. Числа конечны, клетки не оказываются в чужой ткани и не проходят сквозь неё, энергия не берётся из ничего.",
        150, Limits.CRASH.copy(name = "нагрузка: разрушение допустимо, покоя не ждём", energy = Energy.DRIVEN, calm = false, jitter = false), one) {
        crowd.setup(this, 5.0)
        mustCollide(0.25, "не меньше четверти тел толпы с чем-нибудь столкнулись")
    }
    val giant = Shapes.disc(57)
    family.add("huge/body10k/rest", "тело в десять тысяч клеток в покое", "Круг радиуса 57 связей — больше десяти тысяч клеток — в позе покоя.",
        "Ничего не происходит: тело не ползёт, не дрожит и засыпает.", 200, Limits.INTACT, one) {
        body(giant, 0.0, 0.0)
    }
    for (hit in listOf(BUMP, HIT, RAM)) {
        val ticks = if (hit.rough) CRASH_TICKS else settle(hit.speed)
        family.add("huge/body10k/${hit.id}", "в тело в десять тысяч клеток летит круг радиуса 8: ${hit.title}",
            "Круг радиуса 8 (около двухсот клеток) летит в стоящий круг радиуса 57 со скоростью ${hit.speed} связи за тик.",
            "Малое тело упирается в большое и отскакивает; волна удара расходится по большому телу и гаснет. " + hit.expect, ticks, hit.limits, one) {
            val (a, b) = duel(Shapes.disc(8), giant, hit.speed)
            expectMet(a, b, ticks, hit.speed)
        }
    }
    family.add("huge/body10k/drag", "тело в десять тысяч клеток: мышь ведёт край по кругу",
        "Мышь берёт крайнюю правую клетку круга радиуса 57 и плавно ведёт её по кругу радиуса шесть единиц.",
        "Тело тянется за курсором и не рвётся; после отпускания возвращает форму.", 200 + settle(0.5), Limits.DRIVEN.copy(name = "внешняя сила, без повреждений; сон не обязателен", sleep = false), one) {
        val b = body(giant, 0.0, 0.0)
        val cell = giant.extreme(1.0, 0.0)
        mousePath(b, cell, 10, 4, circlePath(giant.x[cell] - 6.0, giant.y[cell], 6.0, 40))
    }
    return family.list
}
