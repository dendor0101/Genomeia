package io.github.some_example_name.lwjgl3.mcp

import io.github.some_example_name.old.core.SingleThreadWorld
import io.github.some_example_name.old.systems.genomics.OrganismSpawner
import io.github.some_example_name.old.systems.genomics.genome.Genome
import kotlinx.serialization.json.JsonObject
import kotlin.math.ceil

/**
 * Рост из зиготы в отдельном мире, как в игре: с энергией и солнцем.
 *
 * ЗАЧЕМ. Расчёт роста редактора (GenomeBaker) энергию не считает: каждое деление проходит
 * сразу. В мире деление стоит энергии (CellSystem: energyNecessaryToDivide), и геном,
 * у которого первые клетки не добывают энергию (кости и нейроны головы до первых листьев),
 * в мире встаёт. Так застревала рыба-змея на 18 клетках из 360, а проверка MCP её пропускала.
 *
 * Мир отдельный (SingleThreadWorld, как у test_body_layout): мир игрока не трогается.
 * Цена: считанные секунды на геном (медуза 968 клеток растёт ~800 тиков). Поток моста ждёт
 * ответ 30 с, поэтому прогон ограничен и временем ([TIME_LIMIT_NS]) — время здесь влияет
 * только на то, когда остановить замер, а не на сам мир.
 */
private const val MAX_TICKS = 12_000
/** Стадия не сменилась столько тиков — рост встал. Самая долгая стадия у рыбы и медузы — десятки тиков. */
private const val STALL_TICKS = 1_500
private const val TIME_LIMIT_NS = 20_000_000_000L

internal fun growInWorld(genome: Genome): JsonObject {
    val half = genome.preview?.halfExtent() ?: 24f
    val size = maxOf(64, ceil(half * 2).toInt() + 40)
    val world = SingleThreadWorld(size, interactive = false)
    try {
        val c = world.cellEntity
        val organs = world.organEntity
        val gi = world.genomeManager.addSavedGenome(genome)
        val zygote = OrganismSpawner(organs, c, world.genomeManager, world.zygote)
            .spawn(gi, size / 2f, size / 2f, world.zygote.defaultColor.toIntBits(), 0.5f, 1f, 0f)
        val organ = c.organIndex[zygote]
        val stages = genome.stageInstruction.size
        val started = System.nanoTime()
        var tick = 0
        var lastStage = -1
        var stageSince = 0
        var outcome = "grown"
        while (!organs.alreadyGrownUp[organ]) {
            if (tick >= MAX_TICKS) { outcome = "tooSlow"; break }
            if (System.nanoTime() - started > TIME_LIMIT_NS) { outcome = "timeLimit"; break }
            world.simulationSystem.updateTick()
            tick++
            if (countCells(world, organ) == 0) { outcome = "died"; break }
            val stage = organs.stage[organ]
            if (stage != lastStage) { lastStage = stage; stageSince = tick }
            if (tick - stageSince > STALL_TICKS) { outcome = "stalled"; break }
        }
        val alive = countCells(world, organ)
        var energy = 0.0
        var low = 0
        val list = c.aliveList
        for (k in 0 until list.size) {
            val i = list.getInt(k)
            if (c.organIndex[i] != organ) continue
            energy += c.energy[i]
            // Меньше, чем нужно на деление (CellSystem ставит 3) — такая клетка не делится.
            if (c.energy[i] < 3f) low++
        }
        val grown = outcome == "grown" && alive == genome.cellsAmount
        return obj(
            "grown" to grown,
            "outcome" to if (outcome == "grown" && !grown) "grownWithLosses" else outcome,
            "ticks" to tick,
            "cells" to alive,
            "expectedCells" to genome.cellsAmount,
            "stage" to organs.stage[organ],
            "stages" to stages,
            "totalEnergy" to energy,
            "cellsBelowDivisionEnergy" to low,
            "hint" to when {
                grown -> null
                outcome == "stalled" || outcome == "tooSlow" ->
                    "Growth stopped in a world with energy. Division costs energy: make the first cells near the zygote leaves (they can mutate into bones or neurons at the last stage) and keep leaves close to every growing front."
                outcome == "died" -> "The organism died while growing. Check energy: cells with negative energy die."
                outcome == "timeLimit" -> "World growth did not finish in the time limit. This is a limit of the check, not a growth failure; try again from the main menu."
                else -> "Some cells died during growth in the world."
            },
            "conditions" to "Zygote in an empty world under current solar settings, the same growth rules and energy costs as in the game.",
        )
    } finally {
        world.dispose()
    }
}

private fun countCells(world: SingleThreadWorld, organ: Int): Int {
    val c = world.cellEntity
    val list = c.aliveList
    var n = 0
    for (k in 0 until list.size) if (c.organIndex[list.getInt(k)] == organ) n++
    return n
}
