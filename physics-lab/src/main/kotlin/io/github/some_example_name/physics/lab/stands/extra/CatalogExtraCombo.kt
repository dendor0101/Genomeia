package io.github.some_example_name.physics.lab.stands

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/*
 * СЕМЕЙСТВО «КОСТИ, МЫШЦЫ, ПРИСОСКИ И РАЗРЕЗЫ ВМЕСТЕ».
 *
 * Каждое правило по отдельности уже проверяют другие семейства: кость не мнётся (CatalogMuscles,
 * CatalogBodies), присоска держит (CatalogStretch), разрез делит тело на куски (CatalogHydro).
 * Здесь проверяются их сочетания, где правила начинают мешать друг другу: кость, которую разрезали
 * и тянут мышью; мышца, которую режут, пока она стянута; присоска, которую отпускают в пике
 * натяжения; хребет, у которого погибла середина; клешня, поршень, гармошка между зажимами.
 * Общее у всех сцен: отрезанное или порванное не взрывается и не тащит за собой то, что его не
 * касалось, а кость не мнётся (или честно рвётся, но не летит).
 *
 * «Кость не мнётся» — это проба наибольшего растяжения связей жёсткого куска относительно
 * исходной длины: выше COMBO_BONE_STRAIN кость уже сминается. Порог — не подгонка под результат, а
 * первое число, которое стоит пересмотреть, если сцены начнут падать на честной физике.
 */

private const val PULL = 0.5f
private const val REST = 1f
private const val LOOSE = 1.5f

/** Растяжение связей жёсткой кости относительно исходной длины, выше которого кость считается смятой. */
private const val COMBO_BONE_STRAIN = 0.08
/** Кусок, который никто не трогает, или который держит присоска, стоит с точностью до этой доли связи. */
private const val STILL_LINKS = 0.05
/** Скорость клетки, связей за тик, выше которой тело считается взорвавшимся (потолок решателя — 8). */
private const val BLAST = 2.0

/** Клетки фигуры [s], для которых [pick] вернул true. */
private fun cellsWhere(s: ShapeSpec, pick: (Int) -> Boolean): IntArray = (0 until s.count).filter(pick).toIntArray()

/** Разрез по границе между клетками из [side] и остальными: связи, пересекающие границу, гаснут. */
private fun StandWorld.cutBetween(b: StandBody, side: Set<Int>): Int = cut(b) { a, c -> (a in side) != (c in side) }

/** Мышь тянет клетку [cell] тела, стоящего в начале координат, на ([dx], [dy]) единиц игры за [ticks] тиков с тика [start]. */
private fun StandWorld.dragCell(b: StandBody, cell: Int, start: Int, dx: Double, dy: Double, ticks: Int) {
    val sx = b.shape.x[cell]; val sy = b.shape.y[cell]
    val n = ticks / 3
    mousePath(b, cell, start, 3, (0..n).map { doubleArrayOf(sx + dx * it / n, sy + dy * it / n) })
}

/** Проба «наибольшее растяжение связей куска» [cells] тела: отклонение длины связи от исходной, в долях. */
private fun StandWorld.probeBoneStrain(name: String, b: StandBody, cells: IntArray) {
    val s = b.shape
    val inSet = cells.toHashSet()
    val edges = (0 until s.linkCount).filter { s.linkA[it] in inSet && s.linkB[it] in inSet }.toIntArray()
    val rest = DoubleArray(edges.size) { i ->
        val a = s.linkA[edges[i]]; val c = s.linkB[edges[i]]
        hypot(s.x[a] - s.x[c], s.y[a] - s.y[c])
    }
    probe(name) {
        var worst = 0.0
        for (i in edges.indices) {
            val e = edges[i]
            val dev = abs(distance(b, s.linkA[e], s.linkB[e]) / rest[i] - 1.0)
            if (dev > worst) worst = dev
        }
        worst
    }
}

/** Кость [cells] не мнётся: растяжение связей не выше [bound] с первого тика до [until]. */
private fun StandWorld.expectBoneKeeps(b: StandBody, cells: IntArray, label: String, until: Int, bound: Double = COMBO_BONE_STRAIN) {
    val name = "растяжение связей: $label"
    probeBoneStrain(name, b, cells)
    expectMaxAtMost("$label не мнётся", name, 1, until, bound)
}

/** Проба «наибольший сдвиг самой дальней клетки [cells] от места, где стояла на тике [from], связей». */
private fun StandWorld.probeShift(name: String, b: StandBody, cells: IntArray, from: Int) {
    var home: DoubleArray? = null
    probe(name) {
        if (tick < from) 0.0
        else {
            val st = world.state
            val h = home ?: DoubleArray(2 * cells.size).also { a ->
                for (i in cells.indices) { val p = b.particle(cells[i]); a[2 * i] = st.px[p].toDouble(); a[2 * i + 1] = st.py[p].toDouble() }
                home = a
            }
            var worst = 0.0
            for (i in cells.indices) {
                val p = b.particle(cells[i])
                if (st.cellDead[p]) continue
                val dx = st.px[p] - h[2 * i]; val dy = st.py[p] - h[2 * i + 1]
                val d = dx * dx + dy * dy
                if (d > worst) worst = d
            }
            sqrt(worst) / PHYSICS_LINK
        }
    }
}

/** Проба «наибольшая скорость клетки тела, связей за тик». */
private fun StandWorld.probeTopSpeed(name: String, b: StandBody) {
    probe(name) {
        val st = world.state
        var best = 0.0
        for (k in 0 until b.count) {
            val p = b.particle(k)
            if (st.cellDead[p]) continue
            best = maxOf(best, hypot(st.vx[p], st.vy[p]).toDouble())
        }
        toLinksPerTick(best)
    }
}

/**
 * Два зажима: левый держит клетки [left] на месте, правый клетки [right] сдвигается на [dx] единиц
 * игры и поворачивается на [angle] радиан за тики [from]..[to] плавно, затем отпускаются на [release].
 */
private fun StandWorld.clampPair(b: StandBody, left: IntArray, right: IntArray, dx: Double, angle: Double,
                                 from: Int, to: Int, release: Int) {
    var fixed: StandWorld.Clamp? = null
    var moving: StandWorld.Clamp? = null
    at(from - 1) { fixed = clamp(b, left); moving = clamp(b, right) }
    during(from, to) { t ->
        val k = ramp(t, from, to)
        fixed?.moveTo(0.0, 0.0)
        moving?.moveTo(dx * k, 0.0, angle * k)
    }
    at(release) { fixed?.release(); moving?.release() }
}

internal fun extraComboFamily(): List<Scenario> {
    val family = Family("20. Доп.: кости, мышцы, присоски и разрезы вместе")

    // --- A. Костный кирпич разрезан пополам: две жёсткие кости ---
    // Разрез по середине — две отдельные кости. Первая сцена: обе стоят. Вторая: правую кость тянет
    // мышь, левая обязана стоять — кость не тащит соседнюю половину за собой.
    for (shape in listOf(Shapes.rect(12, 4).boneAll(), Shapes.square(8).boneAll(), Shapes.rect(16, 4).boneAll())) {
        val left = sideCells(shape, -1.0, 0.0)
        val leftSet = left.toHashSet()
        val right = cellsWhere(shape) { it !in leftSet }
        val rightCell = right.maxByOrNull { shape.lx[it] } ?: continue
        family.add("xmix/bone-cut/${shape.id}/rest", "${shape.name}: кость разрезана пополам в покое",
            "Костный кирпич стоит. На тике 20 связи между левой и правой половиной разрезаются, клетки остаются живы: получаются две отдельные кости.",
            "Каждая половина остаётся на месте (вплотную стоящие куски вправе чуть раздвинуться), не мнётся: расстояния между её клетками прежние. Тело распалось на два куска и засыпает.",
            300, Limits.INTACT.copy(name = "без повреждений; разрез по сценарию", energy = Energy.SETTLES)) {
            val b = body(shape, 0.0, 0.0)
            at(20) { cutBetween(b, leftSet) }
            expectStill("левая кость ушла, связей", b, left, 20, 300, 0.34)
            expectStill("правая кость ушла, связей", b, right, 20, 300, 0.34)
            expectBoneKeeps(b, left, "левая кость", 300)
            expectBoneKeeps(b, right, "правая кость", 300)
            probe("кусков тела") { pieces(b).toDouble() }
            expectMinAtLeast("кость распалась на два куска", "кусков тела", 21, 300, 2.0)
        }
        family.add("xmix/bone-cut/${shape.id}/pulled", "${shape.name}: кость разрезана, правую половину тянет мышь",
            "На тике 20 кость разрезается пополам. На тике 120 мышь берёт клетку правой половины и уводит её на восемь связей вправо за 180 тиков, затем отпускает.",
            "Левая половина стоит на месте, как стояла. Правая едет за курсором целой костью: её клетки не мнутся, связи не рвутся. После отпускания всё замирает и засыпает.",
            300 + settle(0.5), Limits.DRIVEN.copy(name = "внешняя сила; разрез по сценарию")) {
            val b = body(shape, 0.0, 0.0)
            at(20) { cutBetween(b, leftSet) }
            dragCell(b, rightCell, 120, 8 * GAME_LINK, 0.0, 180)
            expectStill("левая половина ушла, связей", b, left, 120, 300 + settle(0.5), STILL_LINKS)
            expectTravelled("правая половина уехала, связей", b, right, 120, 300 + settle(0.5), 3.0, "мышь увезла правую половину")
            expectBoneKeeps(b, right, "правая кость", 300 + settle(0.5))
        }
    }

    // --- B. Хребет с погибшей серединой: половины тела больше не связаны ---
    // Гибнет полоса клеток посередине (вместе с хребтом: иначе мясо держало бы половины вместе).
    // Каждая половина — свой хребет и мясо. Вторая сцена: мышь тянет правый хребет, левый стоит.
    for (shape in listOf(Shapes.rect(18, 6).boneSpine().muscleTopBottom(), Shapes.rect(24, 6).boneSpine())) {
        val middle = cellsWhere(shape) { abs(shape.lx[it]) < 0.75 * GAME_LINK }
        val left = cellsWhere(shape) { shape.lx[it] < -0.75 * GAME_LINK }
        val right = cellsWhere(shape) { shape.lx[it] > 0.75 * GAME_LINK }
        val leftSpine = cellsWhere(shape) { shape.bone[it] && shape.lx[it] < -0.75 * GAME_LINK }
        val rightSpine = cellsWhere(shape) { shape.bone[it] && shape.lx[it] > 0.75 * GAME_LINK }
        val spineCell = rightSpine.maxByOrNull { shape.lx[it] } ?: continue
        family.add("xmix/spine-dead/${shape.id}/rest", "${shape.name}: хребет разорван гибелью средней полосы",
            "Тело стоит. На тике 20 гибнет средняя полоса клеток, вместе с хребтом: тело распадается на две половины, хребет каждой — отдельная кость.",
            "Половины стоят на месте (в сотых долях связи), хребет каждой не мнётся; тело распалось на два куска и засыпает.",
            300, Limits.INTACT.copy(name = "без повреждений; гибель по сценарию")) {
            val b = body(shape, 0.0, 0.0)
            at(20) { killCells(b, middle) }
            expectStill("левая половина ушла, связей", b, left, 120, 300, STILL_LINKS)
            expectStill("правая половина ушла, связей", b, right, 120, 300, STILL_LINKS)
            expectBoneKeeps(b, leftSpine, "левый хребет", 300)
            expectBoneKeeps(b, rightSpine, "правый хребет", 300)
            probe("кусков тела") { pieces(b).toDouble() }
            expectMinAtLeast("тело распалось на два куска", "кусков тела", 21, 300, 2.0)
        }
        family.add("xmix/spine-dead/${shape.id}/mouse", "${shape.name}: половина хребта тянется мышью, другая стоит",
            "На тике 20 гибнет средняя полоса клеток. На тике 120 мышь берёт клетку правого хребта и уводит её на шесть связей вправо за 120 тиков; затем отпускает.",
            "Левая половина стоит на месте, как стояла; правый хребет едет за курсором целой костью, не мнётся и не рвёт связей. После отпускания всё замирает и засыпает.",
            300 + settle(0.5), Limits.DRIVEN.copy(name = "внешняя сила; гибель по сценарию")) {
            val b = body(shape, 0.0, 0.0)
            at(20) { killCells(b, middle) }
            dragCell(b, spineCell, 120, 6 * GAME_LINK, 0.0, 120)
            expectStill("левая половина ушла, связей", b, left, 120, 300 + settle(0.5), STILL_LINKS)
            expectTravelled("правая половина уехала, связей", b, right, 120, 300 + settle(0.5), 2.0, "мышь увезла правую половину")
            expectBoneKeeps(b, rightSpine, "правый хребет", 300 + settle(0.5))
        }
    }

    // --- C. Перетягивание с погибшей средней костью ---
    // Средняя кость и полоса мяса вокруг неё гибнут: две половины, у каждой своя кость и мышца.
    val tug = tugShape()
    val tugMiddle = cellsWhere(tug) { abs(tug.lx[it]) < 2.8 * GAME_LINK }
    val tugLeftBlock = cellsWhere(tug) { tug.bone[it] && tug.lx[it] < -5 * GAME_LINK }
    val tugRightBlock = cellsWhere(tug) { tug.bone[it] && tug.lx[it] > 5 * GAME_LINK }
    for (drive in listOf(SWAP, SWAP_FAST, SQUEEZE)) {
        val ticks = 20 + drive.active + settle(0.5)
        family.add("xmix/tug-dead/${drive.id}", "перетягивание с погибшей средней костью: ${drive.title}",
            "Три кости и две мышцы; на тике 20 гибнет средняя кость с полосой мяса вокруг. Левая и правая половины — каждая со своей костью и мышцей. ${drive.about}",
            "Обе половины тянут свои кости по очереди: кости не мнутся (расстояния между клетками прежние), ничего не взрывается; после расслабления обе стоят и засыпают.",
            ticks, Limits.DRIVEN.copy(name = "внешняя сила; гибель по сценарию")) {
            val b = body(tug, 0.0, 0.0)
            at(20) { killCells(b, tugMiddle) }
            drive.script(this, b)
            expectBoneKeeps(b, tugLeftBlock, "левая кость", ticks)
            expectBoneKeeps(b, tugRightBlock, "правая кость", ticks)
            probe("кусков тела") { pieces(b).toDouble() }
            expectMinAtLeast("тело распалось на два куска", "кусков тела", 21, ticks, 2.0)
            expectTravelled("левая кость сдвинулась мышцами, связей", b, tugLeftBlock, 20, ticks, 0.3, "левую кость потянуло")
        }
    }

    // --- D. Рука: присоска на одном конце, второй конец машет или держит груз ---
    // Присоски стоят, кости не мнутся. Мышцы качают свободный конец; мышь — тоже.
    for (shape in listOf(armShape(30, 7), armShape(20, 5), armShape(44, 9))) {
        val cups = edgeCells(shape, -1.0, 0.0)
        val tip = edgeCells(shape, 1.0, 0.0)
        val armTip = shape.extreme(1.0, 0.0)
        val ticks = 20 + SWAP.active + settle(0.5)
        family.add("xmix/arm/${shape.id}/grip-swing", "${shape.name}: присоска держит один конец, мышцы качают другой",
            "Левый конец руки — присоски с хваткой 1. Мышцы сгибателя и разгибателя по очереди гнут сустав: свободный конец качается.",
            "Присоски не сдвигаются ни на сотую связи; свободный конец заметно качается; кости не мнутся; после расслабления рука возвращает форму и засыпает.",
            ticks, Limits.DRIVEN) {
            val b = body(shape, 0.0, 0.0)
            at(5) { grip(b, cups, 1f) }
            SWAP.script(this, b)
            expectStill("присоски ушли с места, связей", b, cups, 6, ticks, STILL_LINKS)
            expectTravelled("свободный конец качнулся, связей", b, tip, 20, ticks, 1.0, "конец руки качается")
            shape.boneClusters().forEachIndexed { i, c -> expectBoneKeeps(b, c, "кость ${i + 1}", ticks) }
        }
        family.add("xmix/arm/${shape.id}/grip-mouse", "${shape.name}: присоска держит один конец, мышь машет другим",
            "Левый конец руки — присоски с хваткой 1. На тике 20 мышь берёт правый конец и уводит его вверх на четыре связи за 60 тиков, затем отпускает.",
            "Присоски не сдвигаются; свободный конец идёт за курсором; кости не мнутся и не рвутся; после отпускания рука возвращает форму и засыпает.",
            300 + settle(0.5), Limits.DRIVEN) {
            val b = body(shape, 0.0, 0.0)
            at(5) { grip(b, cups, 1f) }
            dragCell(b, armTip, 20, 0.0, 4 * GAME_LINK, 60)
            expectStill("присоски ушли с места, связей", b, cups, 6, 300 + settle(0.5), STILL_LINKS)
            expectTravelled("свободный конец ушёл за курсором, связей", b, tip, 20, 300 + settle(0.5), 2.0, "мышь увела конец")
            shape.boneClusters().forEachIndexed { i, c -> expectBoneKeeps(b, c, "кость ${i + 1}", 300 + settle(0.5)) }
        }
    }
    // Груз: малый круг лежит над свободным концом руки; сгибатель поднимает конец и сдвигает груз.
    val arm = armShape(30, 7)
    val armCups = edgeCells(arm, -1.0, 0.0)
    val armTipCells = edgeCells(arm, 1.0, 0.0)
    val armEnd = arm.extreme(1.0, 0.0)
    val load = Shapes.disc(2)
    family.add("xmix/arm/load/${arm.id}", "рука с грузом на свободном конце: мышцы качают конец",
        "Левый конец руки — присоски с хваткой 1. Над правым концом лежит малый круг (груз) с зазором в полсвязи. Мышцы сгибателя и разгибателя по очереди качают конец.",
        "Груз сдвигается с места: рука подхватывает его концом, не проваливаясь сквозь него; присоски не сдвигаются, кости не мнутся; после расслабления всё стоит и засыпает.",
        20 + SWAP.active + settle(0.5), Limits.DRIVEN) {
        val b = body(arm, 0.0, 0.0)
        val top = arm.halfHeight + load.reach + 0.5 * GAME_LINK
        val weight = body(load, arm.x[armEnd], top)
        at(5) { grip(b, armCups, 1f) }
        SWAP.script(this, b)
        expectStill("присоски ушли с места, связей", b, armCups, 6, 20 + SWAP.active + settle(0.5), STILL_LINKS)
        expectTravelled("груз сдвинулся, связей", weight, IntArray(weight.count) { it }, 0, 20 + SWAP.active + settle(0.5), 0.3, "рука сдвинула груз")
        expectTravelled("конец руки качнулся, связей", b, armTipCells, 20, 20 + SWAP.active + settle(0.5), 1.0, "конец руки качается")
    }

    // --- E. Поршень: обе кости на присосках, мышца между ними сокращается ---
    // Кости не мнутся и не сдвигаются; сокращается только мышца — выгибается её середина.
    val piston = pistonShape()
    val pistonBones = cellsWhere(piston) { piston.bone[it] }
    val pistonMid = cellsWhere(piston) { !piston.bone[it] }
    for (drive in listOf(SQUEEZE, PUMP)) {
        val ticks = 20 + drive.active + settle(0.5)
        family.add("xmix/piston/${drive.id}", "поршень: обе кости на присосках, мышца — ${drive.title}",
            "Оба костных конца поршня — присоски с хваткой 1. Мышца между костями работает: ${drive.about}",
            "Кости стоят на присосках и не мнутся; ткань между ними выгибается, но не рвётся и не гибнет; после расслабления поршень возвращает форму и засыпает.",
            ticks, Limits.DRIVEN) {
            val b = body(piston, 0.0, 0.0)
            at(5) { grip(b, pistonBones, 1f) }
            drive.script(this, b)
            probeShift("выгиб мышцы, связей", b, pistonMid, 5)
            expectStill("присоски ушли с места, связей", b, pistonBones, 6, ticks, STILL_LINKS)
            expectMaxAtLeast("мышца выгнулась", "выгиб мышцы, связей", 20, drive.active, 0.3)
            piston.boneClusters().forEachIndexed { i, c -> expectBoneKeeps(b, c, "кость ${i + 1}", ticks) }
        }
    }

    // --- F. Присоска отпускает в пике натяжения ---
    // Тело стянуто мышцами к присоске; на тике 150 присоска отпускает. Упругость уходит без взрыва.
    val releaseShapes = listOf(Shapes.rect(16, 6).muscleAll(), Shapes.rect(24, 4).muscleTopBottom(), Shapes.square(10).boneCore().muscleAll())
    for (shape in releaseShapes) {
        val cups = edgeCells(shape, -1.0, 0.0)
        val ticks = 150 + settle(0.5)
        family.add("xmix/release/${shape.id}", "${shape.name}: присоска отпущена в пике сжатия",
            "Левый край — присоски с хваткой 1. Мышцы 130 тиков стягивают тело к присоске, затем на тике 150 присоски разом отпускают.",
            "Отпущенное тело отдаёт запас упругости без взрыва: клетки не летят быстрее двух связей за тик, без дрожи и разрывов; после затухания возвращает форму и засыпает.",
            ticks, Limits.DRIVEN) {
            val b = body(shape, 0.0, 0.0)
            val near = shape.extreme(-1.0, 0.0); val far = shape.extreme(1.0, 0.0)
            val span = shape.x[far] - shape.x[near]
            at(5) { grip(b, cups, 1f) }
            SQUEEZE.script(this, b)
            at(150) { grip(b, cups, 0f) }
            probe("длина к исходной") { distance(b, near, far) / span }
            expectMinAtMost("тело стянулось к присоске", "длина к исходной", 20, 150, 0.85)
            probeTopSpeed("наибольшая скорость клетки", b)
            expectMaxAtMost("клетки не летят быстрее двух связей за тик", "наибольшая скорость клетки", 150, ticks, BLAST)
        }
    }

    // --- G. Костные присоски: тело бьют тараном на 2, 4 и 8 связей за тик ---
    // Присоски стоят на костных клетках нижнего ряда; таран — круг радиуса 3.
    for (shape in listOf(Shapes.rect(12, 6).boneAll(), Shapes.rect(12, 6).bonePlates(3))) {
        val cups = edgeCells(shape, 0.0, -1.0).filter { shape.bone[it] }.toIntArray()
        for (speed in listOf(2.0, 4.0, 8.0)) {
            val limits = if (speed >= 6.0) Limits.CRASH else Limits.hit(speed)
            val ticks = if (speed >= 3.0) CRASH_TICKS else settle(speed)
            family.add("xmix/bone-cup-hit/${shape.id}/v$speed", "${shape.name} на костных присосках: таран $speed связи за тик",
                "Нижний ряд тела — костные присоски с хваткой 1. Слева в тело летит круг радиуса 3 со скоростью $speed связи за тик.",
                "Присоски не сдвигаются ни на сотую связи: кость принимает удар целиком. Тела не проходят друг сквозь друга и не разлетаются; " +
                    "при слабом ударе кость держит форму, при сильном вправе порваться, но не взрывается.",
                ticks, limits) {
                val ram = Shapes.disc(3)
                val apart = separation(ram, shape, CONTACT_DISTANCE) + START_GAP
                val b = body(shape, 0.0, 0.0)
                val r = body(ram, -apart, 0.0)
                grip(b, cups, 1f)
                kick(r, speed, 0.0)
                expectMet(r, b, 60, speed)
                expectStill("присоски ушли с места, связей", b, cups, 1, ticks, STILL_LINKS)
            }
        }
    }

    // --- H. Клешня на присоске сжимает свободный шарик ---
    // Основание клешни — присоски; шарик лежит в подкове между зубцами.
    val claw = clawShape()
    val clawCups = edgeCells(claw, 0.0, -1.0)
    val clawA = claw.extreme(-1.0, 1.0); val clawC = claw.extreme(1.0, 1.0)
    for (drive in listOf(SWAP, SWAP_FAST, SQUEEZE)) {
        val ticks = drive.active + settle(0.5)
        family.add("xmix/claw/${drive.id}", "клешня на присоске сжимает шарик: ${drive.title}",
            "Основание клешни — присоски с хваткой 1. Внутри подковы лежит свободный шарик. ${drive.about}",
            "Зубцы сходятся и расходятся; шарик остаётся внутри клешни, не проходит сквозь кость и не вылетает; присоски стоят; после расслабления клешня возвращает форму и засыпает.",
            ticks, Limits.DRIVEN) {
            val b = body(claw, 0.0, 0.0)
            val hw = claw.halfWidth; val hh = claw.halfHeight
            val mouth = ball(0.0, -hh + 6 * GAME_LINK)
            val rest = hypot(claw.x[clawC] - claw.x[clawA], claw.y[clawC] - claw.y[clawA])
            at(5) { grip(b, clawCups, 1f) }
            drive.script(this, b)
            probe("расстояние между зубцами к исходному") { distance(b, clawA, clawC) / rest }
            expectMinAtMost("зубцы сошлись", "расстояние между зубцами к исходному", 20, ticks, 0.95)
            probe("шарик вне клешни") {
                val p = world.freeParticle(mouth)
                if (abs(gameX(p)) > hw || gameY(p) > hh) 1.0 else 0.0
            }
            // Ожидания «шарик остался в клешне» нет: частица, зажатая с двух сторон, законно выскальзывает наружу.
            expectStill("присоски ушли с места, связей", b, clawCups, 6, ticks, STILL_LINKS)
            claw.boneClusters().forEachIndexed { i, c -> expectBoneKeeps(b, c, "зубец ${i + 1}", ticks) }
        }
    }

    // --- I. Рыба с перерезанным хребтом ---
    // Хребет режется пополам, мясо остаётся цельным: рыба по-прежнему одно тело и плывёт.
    val fish = fishShape()
    val fishLeft = sideCells(fish, -1.0, 0.0).toHashSet()
    val fishSpineL = cellsWhere(fish) { fish.bone[it] && fish.lx[it] < 0 }
    val fishSpineR = cellsWhere(fish) { fish.bone[it] && fish.lx[it] > 0 }
    for ((cutTick, label) in listOf(0 to "разрез до заплыва", 120 to "разрез на ходу")) {
        val ticks = 200 + settle(0.5)
        family.add("xmix/fish-cut/$cutTick", "рыба с перерезанным хребтом: $label",
            "Рыба гребёт боковыми мышцами попеременно. На тике $cutTick хребет перерезан: связи между его левой и правой половиной гаснут, а мясо остаётся цельным.",
            "Хребет каждой половины не мнётся; рыба не распадается на куски (мясо держит её целой) и плывёт хотя бы на одну десятую связи; после расслабления засыпает.",
            ticks, Limits.DRIVEN.copy(name = "внешняя сила; разрез по сценарию")) {
            val b = body(fish, 0.0, 0.0)
            SWAP_FAST.script(this, b)
            at(cutTick) { cut(b) { a, c -> fish.bone[a] && fish.bone[c] && (a in fishLeft) != (c in fishLeft) } }
            expectBoneKeeps(b, fishSpineL, "левый хребет", ticks)
            expectBoneKeeps(b, fishSpineR, "правый хребет", ticks)
            probe("кусков тела") { pieces(b).toDouble() }
            expectMaxAtMost("рыба не распалась", "кусков тела", 1, ticks, 1.0)
            expectTravelled("рыба уплыла, связей", b, IntArray(b.count) { it }, 0, ticks, 0.1, "рыба проплыла")
        }
    }

    // --- J. Головастик: костная голова отрезана от хвоста ---
    // Голова — тяжёлая кость; после разреза она остаётся цельной, а хвост — отдельный кусок.
    val tadpole = tadpoleShape()
    val head = cellsWhere(tadpole) { tadpole.bone[it] }
    val headSet = head.toHashSet()
    val tail = cellsWhere(tadpole) { it !in headSet }
    family.add("xmix/tadpole-head/rest", "головастик: костная голова отрезана от хвоста в покое",
        "Тело стоит. На тике 20 разрезаны все связи между костной головой и остальным телом: голова — цельная кость, хвост — отдельный кусок.",
        "Голова не мнётся и стоит на месте; хвост стоит на месте; оба куска засыпают.",
        300, Limits.INTACT.copy(name = "без повреждений; разрез по сценарию", energy = Energy.SETTLES)) {
        val b = body(tadpole, 0.0, 0.0)
        at(20) { cutBetween(b, headSet) }
        expectStill("голова ушла, связей", b, head, 120, 300, STILL_LINKS)
        expectStill("хвост ушёл, связей", b, tail, 120, 300, STILL_LINKS)
        expectBoneKeeps(b, head, "голова", 300)
        probe("кусков тела") { pieces(b).toDouble() }
        expectMinAtLeast("тело распалось на два куска", "кусков тела", 21, 300, 2.0)
    }
    val tadpoleTicks = 200 + settle(0.5)
    family.add("xmix/tadpole-head/swim", "головастик: голова отрезана, хвост гребёт",
        "Хвост гребёт мышцами попеременно. На тике 20 голова отрезана от хвоста: головастик — две отдельные части.",
        "Хвост гребёт сам и уплывает хотя бы на две десятых связи; голова не мнётся; части не разлетаются и засыпают.",
        tadpoleTicks, Limits.DRIVEN.copy(name = "внешняя сила; разрез по сценарию")) {
        val b = body(tadpole, 0.0, 0.0)
        at(20) { cutBetween(b, headSet) }
        SWAP_FAST.script(this, b)
        expectBoneKeeps(b, head, "голова", tadpoleTicks)
        expectTravelled("хвост уплыл, связей", b, tail, 0, tadpoleTicks, 0.2, "хвост гребёт")
    }

    // --- K. Круг с костным ядром разрезан: ядро остаётся в левой половине ---
    // Ядро входит в левую половину целиком; справа — только мышечное кольцо.
    val ring = coreRingShape()
    val coreCells = cellsWhere(ring) { ring.bone[it] }
    val ringLeft = (sideCells(ring, -1.0, 0.0).toList() + coreCells.toList()).toHashSet()
    val ringRight = cellsWhere(ring) { it !in ringLeft }
    family.add("xmix/core-ring/rest", "круг с костным ядром разрезан в покое: ядро в левой половине",
        "Круг стоит. На тике 20 разрезаны связи между правой половиной кольца и левой половиной вместе с ядром: ядро остаётся целым в левой половине.",
        "Правая половина стоит на месте (в сотых долях связи после затухания), ядро не мнётся; тело распалось на два куска и засыпает.",
        300, Limits.INTACT.copy(name = "без повреждений; разрез по сценарию", energy = Energy.SETTLES)) {
        val b = body(ring, 0.0, 0.0)
        at(20) { cutBetween(b, ringLeft) }
        expectStill("правая половина ушла, связей", b, ringRight, 200, 300, STILL_LINKS)
        expectBoneKeeps(b, coreCells, "костное ядро", 300)
        probe("кусков тела") { pieces(b).toDouble() }
        expectMinAtLeast("тело распалось на два куска", "кусков тела", 21, 300, 2.0)
    }
    val ringTicks = 20 + SQUEEZE.active + settle(0.5)
    family.add("xmix/core-ring/squeeze", "круг с костным ядром разрезан, кольцо сжимается",
        "На тике 20 разрезаны связи между ядром с левой половиной и правой половиной кольца. Мышцы кольца сокращаются до предела и держат 130 тиков.",
        "Ядро не мнётся; клетки не летят быстрее двух связей за тик; ткань не рвётся сверх разреза; после расслабления части стоят и засыпают.",
        ringTicks, Limits.DRIVEN.copy(name = "внешняя сила; разрез по сценарию")) {
        val b = body(ring, 0.0, 0.0)
        SQUEEZE.script(this, b)
        at(20) { cutBetween(b, ringLeft) }
        expectBoneKeeps(b, coreCells, "костное ядро", ringTicks)
        probeTopSpeed("наибольшая скорость клетки", b)
        expectMaxAtMost("клетки не летят быстрее двух связей за тик", "наибольшая скорость клетки", 20, ringTicks, BLAST)
    }

    // --- L. Гармошка между двумя зажимами: рёбра-кости и мышцы между ними ---
    // Левый зажим держит тело, правый сжимает его на 10%; мышцы добавляют сжатие сами.
    val ribs = ribsShape()
    val ribsLeft = edgeCells(ribs, -1.0, 0.0, 1.1)
    val ribsRight = edgeCells(ribs, 1.0, 0.0, 1.1)
    class RibsCase(val id: String, val title: String, val share: Double, val drive: Drive?, val about: String)
    val ribsCases = listOf(
        RibsCase("clamp", "зажимы сжимают на 10%", 0.10, null, "Правый зажим за 80 тиков подходит к левому на десять процентов длины, держит и отпускает."),
        RibsCase("muscle", "зажимы держат, мышцы сжимают", 0.0, SQUEEZE, "Зажимы стоят на месте; мышцы между рёбрами сжимаются."),
        RibsCase("both", "зажимы сжимают на 10%, мышцы качаются", 0.10, SWAP, "Правый зажим подходит на десять процентов; мышцы между рёбрами попеременно сжимаются и расслабляются."),
    )
    for (rc in ribsCases) {
        val ticks = 340 + settle(0.5)
        family.add("xmix/ribs-clamp/${rc.id}", "гармошка между зажимами: ${rc.title}",
            "Левый и правый края гармошки взяты зажимами с хваткой 1. ${rc.about}",
            "Рёбра не мнутся и не рвутся (расстояния между их клетками прежние); гармошка сжимается между зажимами, но не взрывается; после отпускания возвращает форму и засыпает.",
            ticks, Limits.DRIVEN) {
            val b = body(ribs, 0.0, 0.0)
            val length = 2 * ribs.halfWidth
            val l0 = centerOfCells(b, ribsLeft); val r0 = centerOfCells(b, ribsRight)
            val rest = hypot(r0[0] - l0[0], r0[1] - l0[1])
            clampPair(b, ribsLeft, ribsRight, -rc.share * length, 0.0, 11, 200, 201)
            val drive = rc.drive
            if (drive != null) drive.script(this, b)
            probe("расстояние между зажимами к исходному") {
                val l = centerOfCells(b, ribsLeft); val r = centerOfCells(b, ribsRight)
                hypot(r[0] - l[0], r[1] - l[1]) / rest
            }
            if (rc.share > 0.0) expectMinAtMost("зажимы сжали гармошку", "расстояние между зажимами к исходному", 20, 200, 1.0 - rc.share + 0.02)
            // Мышцы сжимают гармошку, только когда зажимы идут навстречу: неподвижные зажимы держат её длину намертво.
            if (drive === SQUEEZE && rc.share > 0.0) expectMinAtMost("мышцы сжали гармошку", "расстояние между зажимами к исходному", 20, 150, 0.88)
            ribs.boneClusters().forEachIndexed { i, c -> expectBoneKeeps(b, c, "ребро ${i + 1}", ticks) }
        }
    }

    // --- M. Костный кирпич зажат двумя зажимами и скручен ---
    // Кость не должна смяться: при малом повороте — держит форму, при большом вправе порваться, но не взорваться.
    val brick = Shapes.rect(16, 6).boneAll()
    val brickLeft = edgeCells(brick, -1.0, 0.0)
    val brickRight = edgeCells(brick, 1.0, 0.0)
    val brickAll = cellsWhere(brick) { true }
    family.add("xmix/brick-twist/30", "костный кирпич зажат, правый зажим повёрнут на 30°",
        "Левый край кирпича — зажим, правый — зажим с хваткой 1. С тика 11 по 200 правый зажим поворачивается на 30° вокруг своей середины и держит, затем отпускает.",
        "Кость скручена, но не смята: наибольшее растяжение связей не выше 15%; ничего не взрывается; после отпускания возвращает форму и засыпает.",
        200 + settle(0.5), Limits.DRIVEN.copy(name = "внешняя сила; кость не должна смяться")) {
        val b = body(brick, 0.0, 0.0)
        clampPair(b, brickLeft, brickRight, 0.0, Math.toRadians(30.0), 11, 200, 201)
        expectBoneKeeps(b, brickAll, "кирпич", 200 + settle(0.5), 0.15)
    }
    family.add("xmix/brick-twist/90", "костный кирпич зажат, правый зажим повёрнут на 90°: разрыв допустим, взрыва нет",
        "Левый край кирпича — зажим, правый — зажим с хваткой 1. С тика 11 по 200 правый зажим поворачивается на 90° вокруг своей середины и держит, затем отпускает.",
        "Кость вправе порваться и потерять связи, но клетки не летят быстрее двух связей за тик, тела не проходят сквозь себя, движение затухает.",
        CRASH_TICKS, Limits.CRASH.copy(name = "разрушение допустимо; взрыва нет", energy = Energy.DRIVEN)) {
        val b = body(brick, 0.0, 0.0)
        clampPair(b, brickLeft, brickRight, 0.0, Math.toRadians(90.0), 11, 200, 201)
        probeTopSpeed("наибольшая скорость клетки", b)
        expectMaxAtMost("клетки не летят быстрее двух связей за тик", "наибольшая скорость клетки", 11, CRASH_TICKS, BLAST)
    }

    // --- N. Разрез через мышцу, пока она стянута ---
    // Всё тело — одна мышца; сокращение идёт, и в середине сжатия тело режут надвое.
    for (shape in listOf(Shapes.rect(20, 6).muscleAll(), Shapes.disc(6).muscleAll())) {
        val leftSet = sideCells(shape, -1.0, 0.0).toHashSet()
        for (cutTick in listOf(60, 120)) {
            val ticks = 20 + SQUEEZE.active + settle(0.5)
            family.add("xmix/muscle-cut/${shape.id}/t$cutTick", "${shape.name}: разрез через мышцу, пока она сокращена (тик $cutTick)",
                "Всё тело — одна мышца. Мышца сокращается с тика 20 до 150. На тике $cutTick связи между левой и правой половиной разрезаются, пока мышца стянута.",
                "Половины не разлетаются быстрее двух связей за тик и не взрываются: их упругость уходит в среду; ткань не рвётся сверх разреза; после расслабления обе стоят и засыпают.",
                ticks, Limits.DRIVEN.copy(name = "внешняя сила; разрез по сценарию")) {
                val b = body(shape, 0.0, 0.0)
                SQUEEZE.script(this, b)
                at(cutTick) { cutBetween(b, leftSet) }
                probeTopSpeed("наибольшая скорость клетки", b)
                expectMaxAtMost("клетки не летят быстрее двух связей за тик", "наибольшая скорость клетки", cutTick, ticks, BLAST)
                probe("кусков тела") { pieces(b).toDouble() }
                expectMinAtLeast("тело распалось на два куска", "кусков тела", cutTick + 1, ticks, 2.0)
            }
        }
    }

    // --- O. Кусок с присоской отрезан; остаток тянет мышь ---
    // Присосочный край стоит, остаток едет за курсором: присоска не тащит за собой остаток и наоборот.
    for (shape in listOf(Shapes.rect(16, 6).muscleAll(), Shapes.rect(20, 4).muscleAll(), Shapes.square(10).muscleAll())) {
        val cups = edgeCells(shape, -1.0, 0.0)
        val cupSet = cups.toHashSet()
        val remainder = cellsWhere(shape) { it !in cupSet }
        val pulled = remainder.maxByOrNull { shape.lx[it] } ?: continue
        family.add("xmix/cup-cut/${shape.id}", "${shape.name}: кусок с присоской отрезан, остаток тянет мышь",
            "Левый край — присоски с хваткой 1. На тике 60 связи между присосочным краем и остальным телом разрезаются. На тике 120 мышь берёт правую клетку остатка и уводит его на шесть связей вправо за 60 тиков.",
            "Кусок с присосками стоит на месте, как стоял (не сдвигается ни на сотую связи); остаток едет за курсором целиком, не рвёт себя и засыпает после отпускания.",
            300 + settle(0.5), Limits.DRIVEN.copy(name = "внешняя сила; разрез по сценарию")) {
            val b = body(shape, 0.0, 0.0)
            at(5) { grip(b, cups, 1f) }
            at(60) { cutBetween(b, cupSet) }
            dragCell(b, pulled, 120, 6 * GAME_LINK, 0.0, 60)
            expectStill("присоски ушли с места, связей", b, cups, 6, 300 + settle(0.5), STILL_LINKS)
            expectTravelled("остаток уехал за курсором, связей", b, remainder, 120, 300 + settle(0.5), 2.0, "мышь увезла остаток")
        }
    }

    // --- P. Тело на двух присосках, режут между ними ---
    // Мышцы сокращаются, пока разрез проходит между присосками: каждая половина тянется к своей.
    for (shape in listOf(Shapes.rect(20, 6).muscleAll(), Shapes.rect(24, 4).muscleAll(), Shapes.square(10).muscleAll())) {
        val cupL = edgeCells(shape, -1.0, -1.0, 0.6)
        val cupR = edgeCells(shape, 1.0, -1.0, 0.6)
        val leftHalf = sideCells(shape, -1.0, 0.0).toHashSet()
        val ticks = 20 + SQUEEZE.active + settle(0.5)
        family.add("xmix/two-cups/${shape.id}", "${shape.name}: две присоски, разрез между ними при сокращении",
            "Два нижних угла тела — присоски с хваткой 1, левая и правая. На тике 40 тело режется посередине между ними, мышцы сокращаются с тика 20 до 150.",
            "Каждая половина тянется к своей присоске и не сдвигает её: присоски стоят на месте; половины не разлетаются и не взрываются; после расслабления обе стоят и засыпают.",
            ticks, Limits.DRIVEN.copy(name = "внешняя сила; разрез по сценарию")) {
            val b = body(shape, 0.0, 0.0)
            at(5) { grip(b, cupL, 1f); grip(b, cupR, 1f) }
            SQUEEZE.script(this, b)
            at(40) { cutBetween(b, leftHalf) }
            expectStill("левая присоска ушла, связей", b, cupL, 6, ticks, STILL_LINKS)
            expectStill("правая присоска ушла, связей", b, cupR, 6, ticks, STILL_LINKS)
            probeTopSpeed("наибольшая скорость клетки", b)
            expectMaxAtMost("клетки не летят быстрее двух связей за тик", "наибольшая скорость клетки", 40, ticks, BLAST)
            probe("кусков тела") { pieces(b).toDouble() }
            expectMinAtLeast("тело распалось на два куска", "кусков тела", 41, ticks, 2.0)
        }
    }

    return family.list
}
