package io.github.some_example_name.lwjgl3.verification

import io.github.some_example_name.old.physics.bridge.GAME_WORLD_SCALE
import io.github.some_example_name.old.physics.bridge.bodyDefinitionOf
import io.github.some_example_name.old.systems.genomics.genome.BakedLayout
import io.github.some_example_name.old.systems.genomics.genome.Genome
import io.github.some_example_name.old.systems.genomics.genome.decodeGenome
import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.GAIT_DUTY
import io.github.some_example_name.physics.GAIT_PERIOD
import io.github.some_example_name.physics.BIRTH_GROW_TICKS
import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.MUSCLE_CONTRACTION
import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.PhysicsWorld
import io.github.some_example_name.physics.WorldScale
import java.io.File
import java.util.Locale
import kotlin.math.hypot
import kotlin.system.exitProcess

/**
 * ТЕЛО СТЕНДА В МИРЕ ИГРЫ. Одно взрослое тело (по умолчанию A-11: слоты, связи и треугольники —
 * из раскладки генома, поза, кости и мышцы — из выгрузки тела стенда, та же геометрия редактора)
 * идёт в нескольких мирах с одними и теми же входами:
 *  - «стенд» — мир по правилам DemoSession: без сна (RB_SLEEP не задан), постоянные мира по
 *    самому телу (WorldScale.of), тело — единственное, без рамки;
 *  - «игра» — мир по правилам PhysicsBridge/WorldPhysics: постоянные мира игры
 *    (GAME_WORLD_SCALE), первым телом пул свободных частиц, тело дописано рождённым целиком,
 *    рамка мира 128×128, сон по умолчанию;
 *  - варианты игры, где по одному отличию убрано, — чтобы назвать, какое ломает биты.
 *
 * Сценарии: покой; толчок (все клетки получают скорость — сопротивление среды, «трение»);
 * гребок мышцами клеток (как в игре: длина покоя мышечной клетки — degreeOfShortening).
 * Побитово сравниваются позиции клеток тела каждый тик. Последний раздел — не побитовый:
 * гребок мышцами-кластерами стенда (DemoSession, клавиша G) против мышц клеток игры (цель
 * длины — как велит нейросеть, темп и сильнейшую мышцу концов даёт библиотека).
 *
 * Запуск: gradlew :lwjgl3:standParityCheck [-Pgenome=… -Pbody=…]
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val genome = decodeGenome(File(args.getOrElse(0) { "docs/physics/genomes/A-11.genome" }).readBytes())
    val pose = readPose(args.getOrElse(1) { "docs/physics/repros/detached-fragments-2026-10-21/body.txt" })
    val base = genome.sortedGraph ?: error("геном не запечён")
    val ids = base.cellGenomeIdsInSlotOrder
    require(ids.all { it in pose }) { "не у всех клеток раскладки есть поза в выгрузке тела" }
    val layout = base.copy(
        restX = ids.map { pose.getValue(it).x }, restY = ids.map { pose.getValue(it).y },
        restRadius = ids.map { pose.getValue(it).r }, restBone = ids.map { pose.getValue(it).bone }
    ).withTrianglesFromLinks()     // в файле A-11 треугольники прежнего перебора (TRIANGLES_VERSION)
    val muscle = BooleanArray(ids.size) { pose.getValue(ids[it]).muscle }
    val def = bodyDefinitionOf(layout, 0, layout.restX[0], layout.restY[0])
    println("тело ${genome.name}: клеток ${def.count}, связей ${def.linkCount}, треугольников ${def.triCount}, " +
        "костей ${def.isBone.count { it }} в ${def.boneClusters.size} кусках, мышечных клеток ${muscle.count { it }}, " +
        "средняя связь %.4f (мир игры %.4f)".format(def.meanLinkLength, GAME_WORLD_SCALE.meanLinkLength))

    val variants = listOf(
        Variant("стенд") { standWorld(def) },
        Variant("игра") { gameWorld(def, GAME_WORLD_SCALE, sleep = null, bounds = true) },
        Variant("игра без сна") { gameWorld(def, GAME_WORLD_SCALE, sleep = false, bounds = true) },
        Variant("игра без сна и рамки") { gameWorld(def, GAME_WORLD_SCALE, sleep = false, bounds = false) },
        Variant("игра без сна и рамки, постоянные по телу") { gameWorld(def, WorldScale.of(def), sleep = false, bounds = false) },
    )
    val ticks = 240
    for (scenario in listOf(Scenario.REST, Scenario.KICK, Scenario.GAIT)) {
        println("\n== ${scenario.title}, $ticks тиков")
        val runs = variants.map { it.run(scenario, ticks, muscle) }
        val ref = runs[0]
        for (r in runs) {
            val diverged = if (r === ref) "—" else r.firstDivergence(ref)?.let { "разошлись на тике $it" } ?: "побитово"
            println("  %-44s центр сдвинулся на %7.3f связи, порвано %4d, погибло %3d, %s".format(
                r.name, r.comShift / def.meanLinkLength, r.torn, r.dead, diverged))
        }
    }

    println("\n== гребок, 600 тиков: мышцы-кластеры стенда против мышц клеток игры (не побитово)")
    val clusters = def.withMuscleClusters(muscle)
    val stand = standWorld(clusters)
    val game = gameWorld(def, GAME_WORLD_SCALE, sleep = null, bounds = true)
    val c0 = listOf(stand, game).map { com(it.first, it.second, def.count) }
    for (t in 0 until 600) {
        stand.first.setAllMuscleTargets(if (gaitOn(t)) 1.0 else 0.0)
        gaitCells(game.first, game.second, muscle, t)
        for (w in listOf(stand, game)) w.first.step()
    }
    for ((name, w, c) in listOf(Triple("стенд, мышцы-кластеры", stand, c0[0]), Triple("игра, мышцы клеток", game, c0[1]))) {
        val c1 = com(w.first, w.second, def.count)
        println("  %-36s проплыл %7.3f связи, порвано %4d, погибло %3d".format(
            name, hypot(c1[0] - c[0], c1[1] - c[1]) / def.meanLinkLength, w.first.state.tornTotal, w.first.state.deadCount))
    }

    growthSection(genome, layout, def, muscle)
    exitProcess(0)
}


/**
 * РОСТ С НУЛЯ В МИРЕ ИГРЫ. Порядок рождения — стадии генома (на стадии родитель делится,
 * номер дочери — действие divide), как растёт тело в игре; время между стадиями в игре
 * зависит от энергии клеток, здесь оно — условие роста. Выросшее тело сравнивается со
 * взрослым, заведённым сразу целиком: целость, напряжение связей, толчок и гребок.
 */
private fun growthSection(genome: Genome, layout: BakedLayout, def: BodyDefinition, muscle: BooleanArray) {
    val stages = growthStages(genome, layout)
    val zygote = layout.slotByCellGenomeId.get(0)
    val boneSlots = stages.sumOf { it.bones.size }
    println("\n== рост с нуля в мире игры: ${stages.size} стадий, зигота — слот $zygote, костей по мутации $boneSlots " +
        "из ${def.isBone.count { it }} костных клеток описания")
    val adultKick = afterGrowth(gameWorld(def, GAME_WORLD_SCALE, sleep = null, bounds = true), def, muscle)
    println("  %-40s".format("взрослое (сразу целиком)") + adultKick)
    val whole = PhysicsWorld(PhysicsConfig(), workers = null, scale = GAME_WORLD_SCALE).also { it.state.quiet = true; it.addBody(def) }
    println("      контур взрослого: расхождений с топологией ${whole.boundaryMismatches()}, рёбер в списке ${whole.boundaryEdgeCount}")
    val conditions = listOf(
        Growth("1 тик на стадию", 1, muscles = false, kicks = false),
        Growth("10 тиков на стадию", 10, muscles = false, kicks = false),
        Growth("1 тик, гребок", 1, muscles = true, kicks = false),
        Growth("5 тиков, гребок", 5, muscles = true, kicks = false),
        Growth("20 тиков, гребок", 20, muscles = true, kicks = false),
        Growth("5 тиков, толчки", 5, muscles = false, kicks = true),
        Growth("5 тиков, гребок, кости с рождения", 5, muscles = true, kicks = false, bonesAtBirth = true),
        Growth("1 тик на стадию, без костей", 1, muscles = false, kicks = false, noBones = true),
        Growth("5 тиков, гребок, без костей", 5, muscles = true, kicks = false, noBones = true),
    )
    for (g in conditions) {
        val w = PhysicsWorld(PhysicsConfig(), workers = null, scale = GAME_WORLD_SCALE)
        w.state.quiet = true
        w.setBounds(0.0, 0.0, 128 * 0.048, 128 * 0.048)
        val body = w.addBody(def, BooleanArray(def.count) { it == zygote },
            boneOn = if (g.bonesAtBirth) null else BooleanArray(def.count))
        val first = w.bodyFirst(body)
        var tick = 0
        val bornAt = IntArray(def.count) { Int.MIN_VALUE / 2 }
        val tears = TearStats()
        // Кость, которую библиотека пока не включила (новорождённая выходит из родителя), — вызов
        // повторяется каждый тик, как в игре (WorldPhysics.syncOrganism).
        val wantBone = ArrayList<Int>()
        for (stage in stages) {
            for ((k, slot) in stage.births.withIndex()) if (w.isUnborn(first + slot)) {
                // Деление, как в игре (WorldPhysics.syncOrganism): почти в родителе, если он жив.
                val p = stage.parents[k]
                if (p >= 0 && !w.isUnborn(first + p) && !w.state.cellDead[first + p]) w.activateFromParent(first + slot, first + p)
                else w.activate(first + slot)
                bornAt[slot] = tick
            }
            if (!g.bonesAtBirth && !g.noBones) for (slot in stage.bones) if (!w.isUnborn(first + slot)) wantBone.add(slot)
            repeat(g.ticksPerStage) {
                wantBone.removeAll { w.state.cellDead[first + it] || w.setBone(first + it) }
                if (g.muscles) gaitCells(w, first, muscle, tick)
                if (g.kicks && tick % 40 == 0) {
                    val v = (if ((tick / 40) % 2 == 0) 1.0 else -1.0) * w.meanLinkLength / DT
                    for (s in 0 until def.count) if (!w.isUnborn(first + s)) { w.setVelocity(first + s, v, 0.5 * v); w.wake(first + s) }
                }
                w.step(); tick++
                tears.count(w, first, def, muscle, bornAt, tick)
            }
        }
        val mismatches = w.cellScaleMismatches()
        val boundary = w.boundaryMismatches()
        if (g.muscles) for (s in 0 until def.count) if (muscle[s]) w.setCellScale(first + s, 1f)
        repeat(120) { wantBone.removeAll { w.state.cellDead[first + it] || w.setBone(first + it) }; w.step() }
        val unborn = (0 until def.count).count { w.isUnborn(first + it) }
        println("  %-40s".format(g.name + " ($tick тиков)") + afterGrowth(w to first, def, muscle) +
            if (unborn > 0) ", не родилось $unborn" else "")
        if (tears.edges + tears.cells > 0) println("      во время роста: $tears")
        if (mismatches > 0) println("      МНОЖИТЕЛИ ПО МЕСТУ РАЗОШЛИСЬ С ПОЛНЫМ ПЕРЕСЧЁТОМ: $mismatches")
        println("      контур после роста: расхождений с топологией $boundary, рёбер в списке ${w.boundaryEdgeCount}")
    }
}

private class Growth(val name: String, val ticksPerStage: Int, val muscles: Boolean, val kicks: Boolean,
                     val bonesAtBirth: Boolean = false, val noBones: Boolean = false)

/** Стадия генома: кто родился делением и кто стал костью (мутация или деление с типом «кость»). */
internal class Stage(val births: IntArray, val parents: IntArray, val bones: IntArray)

/**
 * Разбор гибели во время роста: связь молодая (конец младше BIRTH_GROW_TICKS — она ещё
 * дорастает), у мышцы, у кости; клетка молодая, мышечная, костная.
 */
private class TearStats {
    var edges = 0; var youngEdges = 0; var muscleEdges = 0; var boneEdges = 0
    var cells = 0; var youngCells = 0; var muscleCells = 0; var boneCells = 0
    fun count(w: PhysicsWorld, first: Int, def: BodyDefinition, muscle: BooleanArray, bornAt: IntArray, tick: Int) {
        for (k in 0 until w.edgeDeathCount) {
            val a = w.edgeDeathA(k) - first; val b = w.edgeDeathB(k) - first
            if (a !in 0 until def.count || b !in 0 until def.count) continue
            if (w.state.cellDead[first + a] || w.state.cellDead[first + b]) continue   // гибель клетки — ниже
            edges++
            if (tick - bornAt[a] <= BIRTH_GROW_TICKS || tick - bornAt[b] <= BIRTH_GROW_TICKS) youngEdges++
            if (muscle[a] || muscle[b]) muscleEdges++
            if (def.isBone[a] || def.isBone[b]) boneEdges++
        }
        for (k in 0 until w.cellDeathCount) {
            val v = w.cellDeath(k) - first
            if (v !in 0 until def.count) continue
            cells++
            if (tick - bornAt[v] <= BIRTH_GROW_TICKS) youngCells++
            if (muscle[v]) muscleCells++
            if (def.isBone[v]) boneCells++
        }
    }
    override fun toString() = "разрывов $edges (молодых $youngEdges, у мышцы $muscleEdges, у кости $boneEdges), " +
        "погибло клеток $cells (молодых $youngCells, мышечных $muscleCells, костных $boneCells)"
}

/** Стадии генома с делениями или мутациями в кость: рождённые слоты и ставшие костью, в порядке действий. */
internal fun growthStages(genome: Genome, layout: BakedLayout): List<Stage> {
    val born = BooleanArray(layout.cellsInLayout)
    born[layout.slotByCellGenomeId.get(0)] = true
    val result = ArrayList<Stage>()
    for (stage in genome.stageInstruction) {
        val births = ArrayList<Int>(); val parents = ArrayList<Int>(); val bones = ArrayList<Int>()
        for ((cellId, action) in stage.cellActions) {
            action.divide?.let { d ->
                val slot = layout.slotByCellGenomeId.get(d.id)
                if (slot >= 0 && !born[slot]) { born[slot] = true; births.add(slot); parents.add(layout.slotByCellGenomeId.get(cellId)) }
                if (slot >= 0 && d.cellType == "bone") bones.add(slot)
            }
            if (action.mutate?.cellType == "bone") {
                val slot = layout.slotByCellGenomeId.get(cellId)
                if (slot >= 0) bones.add(slot)
            }
        }
        if (births.isNotEmpty() || bones.isNotEmpty()) result.add(Stage(births.toIntArray(), parents.toIntArray(), bones.toIntArray()))
    }
    return result
}

/** Фаза гребка стенда (DemoSession, клавиша G): GAIT_PERIOD тиков, рабочая доля GAIT_DUTY. */
private fun gaitOn(tick: Int) = tick % GAIT_PERIOD < (GAIT_PERIOD * GAIT_DUTY).toInt().coerceAtLeast(1)

/**
 * Гребок мышцами клеток, как в игре: мышечной клетке — цель длины (Muscle.kt —
 * degreeOfShortening), темп и сильнейшую мышцу концов даёт библиотека (setCellScale).
 */
private fun gaitCells(w: PhysicsWorld, first: Int, muscle: BooleanArray, tick: Int) {
    val scale = scaleOf(if (gaitOn(tick)) 1.0 else 0.0)
    for (s in muscle.indices) if (muscle[s] && !w.isUnborn(first + s)) w.setCellScale(first + s, scale)
}

/**
 * Состояние тела и два опыта на нём: целость (порвано, погибло, среднее |длина/покой − 1| по
 * связям между живыми клетками), затем толчок (2 связи/тик, 120 тиков) и гребок мышцами клеток
 * (240 тиков) — на копиях не сделать, поэтому опыты идут подряд: толчок, затем гребок.
 */
private fun afterGrowth(wf: Pair<PhysicsWorld, Int>, def: BodyDefinition, muscle: BooleanArray): String {
    val (w, first) = wf
    val torn0 = w.state.tornTotal; val dead0 = w.state.deadCount
    var strain = 0.0; var links = 0
    for (k in 0 until def.linkCount) {
        val a = first + def.linkA[k]; val b = first + def.linkB[k]
        if (w.state.cellDead[a] || w.state.cellDead[b]) continue
        val rest = hypot((def.x[def.linkA[k]] - def.x[def.linkB[k]]).toDouble(), (def.y[def.linkA[k]] - def.y[def.linkB[k]]).toDouble())
        val e = hypot(w.x[a] - w.x[b], w.y[a] - w.y[b]) / rest - 1.0
        if (kotlin.math.abs(e) < 0.5) { strain += kotlin.math.abs(e); links++ }
    }
    val c0 = com(w, first, def.count)
    val v = 2.0 * w.meanLinkLength / DT
    for (s in 0 until def.count) if (!w.state.cellDead[first + s]) { w.setVelocity(first + s, v, 0.0); w.wake(first + s) }
    repeat(120) { w.step() }
    val c1 = com(w, first, def.count)
    for (t in 0 until 240) { gaitCells(w, first, muscle, t); w.step() }
    val c2 = com(w, first, def.count)
    return "порвано %3d, погибло %3d, напряжение %.4f; толчок %6.2f связи, гребок %6.2f связи (порвано за опыты %d)".format(
        torn0, dead0, strain / maxOf(1, links), hypot(c1[0] - c0[0], c1[1] - c0[1]) / def.meanLinkLength,
        hypot(c2[0] - c1[0], c2[1] - c1[1]) / def.meanLinkLength, w.state.tornTotal - torn0)
}

private enum class Scenario(val title: String) { REST("покой"), KICK("толчок: все клетки 2 связи/тик вправо"), GAIT("гребок мышцами клеток (как в игре)") }

private class Variant(val name: String, val build: () -> Pair<PhysicsWorld, Int>) {
    fun run(scenario: Scenario, ticks: Int, muscle: BooleanArray): Run {
        val (w, first) = build()
        val n = muscle.size
        val trace = Array(ticks) { LongArray(2 * n) }
        val c0 = com(w, first, n)
        if (scenario == Scenario.KICK) {
            val v = 2.0 * w.meanLinkLength / DT
            for (s in 0 until n) { w.setVelocity(first + s, v, 0.0); w.wake(first + s) }
        }
        for (t in 0 until ticks) {
            if (scenario == Scenario.GAIT) gaitCells(w, first, muscle, t)
            w.step()
            for (s in 0 until n) { trace[t][2 * s] = w.x[first + s].toRawBits(); trace[t][2 * s + 1] = w.y[first + s].toRawBits() }
        }
        val c1 = com(w, first, n)
        return Run(name, trace, hypot(c1[0] - c0[0], c1[1] - c0[1]), w.state.tornTotal, w.state.deadCount)
    }
}

private class Run(val name: String, val trace: Array<LongArray>, val comShift: Double, val torn: Int, val dead: Int) {
    fun firstDivergence(other: Run): Int? = trace.indices.firstOrNull { !trace[it].contentEquals(other.trace[it]) }
}

/** Мир по правилам стенда (DemoSession): постоянные по телу, без сна, тело — единственное. */
private fun standWorld(def: BodyDefinition): Pair<PhysicsWorld, Int> {
    val w = PhysicsWorld(PhysicsConfig(), workers = null, scale = WorldScale.of(def))
    w.state.quiet = true
    w.sleepOn = false
    w.addBody(def)          // пустой мир: загрузка тела целиком и сброс в позу покоя, как стенд
    return w to 0
}

/**
 * Мир по правилам игры (PhysicsBridge.spawnOrganism): первым телом пул свободных частиц, тело
 * дописано рождённым целиком. [sleep] null — сон по умолчанию библиотеки, как в игре.
 */
private fun gameWorld(def: BodyDefinition, scale: WorldScale, sleep: Boolean?, bounds: Boolean): Pair<PhysicsWorld, Int> {
    val w = PhysicsWorld(PhysicsConfig(), workers = null, scale = scale)
    w.state.quiet = true
    if (sleep != null) w.sleepOn = sleep
    if (bounds) w.setBounds(0.0, 0.0, 128 * 0.048, 128 * 0.048)
    val body = w.addBody(def, BooleanArray(def.count) { true })
    return w to w.bodyFirst(body)
}

/** Длина покоя мышечной клетки при активации [a]: как у мышцы стенда (MuscleDriver). */
private fun scaleOf(a: Double): Float = (1.0 - a * (1.0 - MUSCLE_CONTRACTION)).toFloat()

private fun com(w: PhysicsWorld, first: Int, n: Int): DoubleArray {
    var x = 0.0; var y = 0.0; var k = 0
    for (s in 0 until n) { if (w.state.cellDead[first + s]) continue; x += w.x[first + s]; y += w.y[first + s]; k++ }
    return doubleArrayOf(x / k, y / k)
}

/** То же тело с мышцами-кластерами стенда: связные куски мышечных клеток (BodyFile.components). */
private fun BodyDefinition.withMuscleClusters(muscle: BooleanArray): BodyDefinition {
    val adj = Array(count) { ArrayList<Int>() }
    for (k in linkA.indices) { adj[linkA[k]].add(linkB[k]); adj[linkB[k]].add(linkA[k]) }
    val seen = BooleanArray(count)
    val clusters = ArrayList<IntArray>()
    for (s in 0 until count) {
        if (!muscle[s] || seen[s]) continue
        val group = ArrayList<Int>(); val queue = ArrayDeque<Int>()
        seen[s] = true; queue.addLast(s)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst(); group.add(v)
            for (u in adj[v]) if (muscle[u] && !seen[u]) { seen[u] = true; queue.addLast(u) }
        }
        clusters.add(group.toIntArray())
    }
    return BodyDefinition(x, y, radius, isBone, muscle.copyOf(), linkA, linkB, triA, triB, triC, triRestArea2,
        boneClusters, clusters.toTypedArray(), scale)
}

private class Pose(val x: Float, val y: Float, val r: Float, val bone: Boolean, val muscle: Boolean)

/** Выгрузка тела (BodyExport): P <genomeId> <x> <y> <radius> <cellType> <bone> <muscle>. */
private fun readPose(path: String): Map<Int, Pose> {
    val result = HashMap<Int, Pose>()
    File(path).forEachLine { line ->
        val p = line.trim().split(Regex("\\s+"))
        if (p.size >= 8 && p[0] == "P") {
            result[p[1].toInt()] = Pose(p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[6] == "1", p[7] == "1")
        }
    }
    return result
}
