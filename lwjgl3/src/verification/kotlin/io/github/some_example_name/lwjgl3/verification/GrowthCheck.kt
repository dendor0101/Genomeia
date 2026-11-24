package io.github.some_example_name.lwjgl3.verification

import io.github.some_example_name.old.physics.bridge.GAME_WORLD_SCALE
import io.github.some_example_name.old.physics.bridge.PHYSICS_PER_GAME
import io.github.some_example_name.old.physics.bridge.bodyDefinitionOf
import io.github.some_example_name.old.systems.genomics.genome.decodeGenome
import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.PhysicsWorld
import java.io.File
import java.util.Locale
import kotlin.math.hypot
import kotlin.system.exitProcess

/**
 * РОСТ ТЕЛА С НУЛЯ В МИРЕ ИГРЫ — С РАЗБОРОМ КАЖДОГО РАЗРЫВА. Тело — раскладка и поза покоя
 * генома (как мост, LayoutBodies), мир — постоянные мира игры и рамка 128×128, рождение —
 * стадии генома (StandParityCheck.growthStages) через activateFromParent/activate, как
 * WorldPhysics.syncOrganism, кость — по мутации. Без мышц, жгутиков и мыши: только рост.
 *
 * По каждому разрыву: тик, стадия, слоты и возраст концов (тиков от рождения), длина к длине
 * позы и причина из журнала физики (dbgTearLog: растяжение, щель, сжатие, пробой). Погибшие
 * клетки — отдельно (гибель снимает все их рёбра, такие рёбра не печатаются).
 *
 * Запуск: gradlew :lwjgl3:growthCheck [-Pgenome=путь] [-Pticks=1,5,20] — тиков на стадию.
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val genome = decodeGenome(File(args.getOrElse(0) { "assets/genomes/Burdock leaf.genome" }).readBytes())
    val layout = (genome.sortedGraph ?: error("геном не запечён")).withTrianglesFromLinks()
    require(layout.hasRestPose) { "у раскладки нет позы покоя" }
    val zygote = layout.slotByCellGenomeId.get(0)
    val def = bodyDefinitionOf(layout, zygote, 64f, 64f)
    val stages = growthStages(genome, layout)
    println("тело ${genome.name}: клеток ${def.count}, связей ${def.linkCount}, треугольников ${def.triCount}, " +
        "стадий с рождениями ${stages.size}, зигота — слот $zygote")
    for (ticks in args.getOrElse(1) { "1,5,20" }.split(',').map { it.trim().toInt() }) grow(def, stages, zygote, ticks)
    exitProcess(0)
}

private const val MAX_LINES = 80

private fun grow(def: BodyDefinition, stages: List<Stage>, zygote: Int, ticksPerStage: Int) {
    println("\n== $ticksPerStage тиков на стадию")
    val w = PhysicsWorld(PhysicsConfig(), workers = null, scale = GAME_WORLD_SCALE)
    w.state.quiet = true
    w.setBounds(0.0f, 0.0f, 128 * PHYSICS_PER_GAME, 128 * PHYSICS_PER_GAME)
    val body = w.addBody(def, BooleanArray(def.count) { it == zygote }, boneOn = BooleanArray(def.count))
    val first = w.bodyFirst(body)
    val bornAt = IntArray(def.count) { -1 }
    bornAt[zygote] = 0
    val log = StringBuilder()
    w.state.dbgTearLog = log
    var tick = 0
    var lines = 0
    var tears = 0
    var deaths = 0
    // Кость, которую библиотека пока не включила (новорождённая выходит из родителя), — вызов
    // повторяется каждый тик, как в игре (WorldPhysics.syncOrganism).
    val wantBone = ArrayList<Int>()
    for ((stageIndex, stage) in stages.withIndex()) {
        for ((k, slot) in stage.births.withIndex()) if (w.isUnborn(first + slot)) {
            val p = stage.parents[k]
            if (p >= 0 && !w.isUnborn(first + p) && !w.state.cellDead[first + p]) w.activateFromParent(first + slot, first + p)
            else w.activate(first + slot)
            bornAt[slot] = tick
        }
        for (slot in stage.bones) if (!w.isUnborn(first + slot)) wantBone.add(slot)
        repeat(ticksPerStage) {
            wantBone.removeAll { w.state.cellDead[first + it] || w.setBone(first + it) }
            w.state.playTick = tick
            w.step(); tick++
            for (k in 0 until w.edgeDeathCount) {
                val a = w.edgeDeathA(k) - first; val b = w.edgeDeathB(k) - first
                if (a !in 0 until def.count || b !in 0 until def.count) continue
                if (w.state.cellDead[first + a] || w.state.cellDead[first + b]) continue
                tears++
                if (lines++ < MAX_LINES) println("  тик %d, стадия %d: разрыв %d-%d, возраст %d/%d, длина/поза %.2f%s".format(
                    tick, stageIndex, a, b, tick - bornAt[a], tick - bornAt[b], lengthToPose(w, first, def, a, b),
                    bornNear(def, stage, a, b)))
            }
            for (k in 0 until w.cellDeathCount) {
                val v = w.cellDeath(k) - first
                if (v !in 0 until def.count) continue
                deaths++
                if (lines++ < MAX_LINES) println("  тик %d, стадия %d: погибла клетка %d, возраст %d".format(
                    tick, stageIndex, v, tick - bornAt[v]))
            }
            if (log.isNotEmpty()) {
                if (lines < MAX_LINES) print(log)
                log.setLength(0)
            }
        }
    }
    repeat(120) { wantBone.removeAll { w.state.cellDead[first + it] || w.setBone(first + it) }; w.state.playTick = tick; w.step(); tick++ }
    if (log.isNotEmpty() && lines < MAX_LINES) print(log)
    val unborn = (0 until def.count).count { w.isUnborn(first + it) }
    println("  итог за $tick тиков: разрывов во время роста $tears, погибло клеток $deaths; всего порвано " +
        "${w.state.tornTotal}, погибло ${w.state.deadCount}" + if (unborn > 0) ", не родилось $unborn" else "")
    boneReport(w, first, def)
}

private fun lengthToPose(w: PhysicsWorld, first: Int, def: BodyDefinition, a: Int, b: Int): Double {
    val pose = hypot((def.x[a] - def.x[b]).toDouble(), (def.y[a] - def.y[b]).toDouble())
    return hypot(w.x[first + a] - w.x[first + b], w.y[first + a] - w.y[first + b]) / pose
}

/** Кто из рождённых этой стадией — сосед конца разорванной связи (по связям тела), и от кого родился. */
private fun bornNear(def: BodyDefinition, stage: Stage, a: Int, b: Int): String {
    val out = StringBuilder()
    for ((k, s) in stage.births.withIndex()) {
        var near = s == a || s == b
        for (l in 0 until def.linkCount) {
            val u = def.linkA[l]; val v = def.linkB[l]
            if ((u == s && (v == a || v == b)) || (v == s && (u == a || u == b))) near = true
        }
        if (near) out.append(" $s(от ${stage.parents[k]})")
    }
    return if (out.isEmpty()) "" else ", рядом родились:$out"
}

/**
 * Кости после роста: сколько живых клеток описания-кости включено, и длина связей между включёнными
 * клетками одной кости к длине позы генома (кость держит позу, снятую при включении).
 */
private fun boneReport(w: PhysicsWorld, first: Int, def: BodyDefinition) {
    val boneCells = def.isBone.count { it }
    if (boneCells == 0) return
    val dead = w.state.cellDead
    val alive = (0 until def.count).count { def.isBone[it] && !dead[first + it] }
    val on = (0 until def.count).count { def.isBone[it] && !dead[first + it] && w.isBone(first + it) }
    var n = 0; var sum = 0.0; var min = Double.MAX_VALUE; var max = 0.0
    for (l in 0 until def.linkCount) {
        val a = def.linkA[l]; val b = def.linkB[l]
        if (dead[first + a] || dead[first + b]) continue
        if (!w.isBone(first + a) || !w.isBone(first + b) || w.state.boneOf[first + a] != w.state.boneOf[first + b]) continue
        val r = lengthToPose(w, first, def, a, b)
        n++; sum += r; if (r < min) min = r; if (r > max) max = r
    }
    println("  кости: включено $on из $alive живых (в описании $boneCells); связей внутри костей $n" +
        if (n > 0) ", длина/поза мин %.2f, средн %.2f, макс %.2f".format(min, sum / n, max) else "")
}
