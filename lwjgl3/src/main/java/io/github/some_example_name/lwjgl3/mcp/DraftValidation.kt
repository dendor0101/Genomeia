package io.github.some_example_name.lwjgl3.mcp

import io.github.some_example_name.old.cells.base.CellPalette
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.editor.geometry.CellPlacement
import io.github.some_example_name.old.systems.genomics.genome.GenomeStage

internal const val MAX_DESIGN_CELLS = 1024
private const val MAX_GROWTH_STAGES = 512
private const val MAX_GROWTH_ACTIONS = MAX_DESIGN_CELLS * 8

/** Датчик продольной скорости доступен в свободной лаборатории MCP для обучения походки. */
internal fun CellPalette.designCells() = actionCellChoices(
    DIGameGlobalContainer.cellTypes.toList(), if (this == CellPalette.FULL) "speed_sensor" else null
)

/** Отсекаем недостижимые стадии до запуска редакторного моделирования. */
internal fun checkStages(stages: List<GenomeStage>, palette: CellPalette) {
    require(stages.size in 1..MAX_GROWTH_STAGES) { "Use 1..$MAX_GROWTH_STAGES growth stages" }
    val cells = palette.designCells().map { it.key }.toSet()
    val formulas = palette.formulaChoices(null).map { it.key }.toSet()
    val alive = mutableSetOf(0)
    var totalActions = 0
    for ((index, stage) in stages.withIndex()) {
        require(stage.cellActions.isNotEmpty()) { "Stage $index has no actions" }
        val next = alive.toMutableSet()
        for ((parent, actions) in stage.cellActions) {
            require(parent in alive) { "Stage $index: source cell $parent does not exist yet" }
            val divide = actions.divide ?: continue
            require(divide.id in 1..4096 && next.add(divide.id)) { "Stage $index: duplicate or invalid child ID ${divide.id}" }
            require(divide.angle != null && divide.cellType != null) { "Stage $index, cell $parent: division requires angle and cellType" }
        }
        require(next.size <= MAX_DESIGN_CELLS) { "A draft supports up to $MAX_DESIGN_CELLS cells" }
        for ((parent, actions) in stage.cellActions) {
            val ops = listOfNotNull(actions.divide, actions.mutate)
            require(ops.isNotEmpty()) { "Stage $index, cell $parent: supply divide or mutate" }
            totalActions += ops.size
            require(totalActions <= MAX_GROWTH_ACTIONS) { "Use at most $MAX_GROWTH_ACTIONS growth actions" }
            for (op in ops) {
                val at = "Stage $index, cell $parent"
                require(op.cellType == null || op.cellType in cells) { "$at: unavailable cell type ${op.cellType}" }
                require(op.funActivation == null || op.funActivation in formulas) { "$at: unknown formula ${op.funActivation}" }
                require(op.radius == null || op.radius!! in CellPlacement.CHILD_RADIUS_MIN..CellPlacement.CHILD_RADIUS_MAX) { "$at: invalid radius" }
                require(op.pheromoneType == null || op.pheromoneType!! in -1..31) { "$at: pheromoneType must be -1..31" }
                require(op.physicalLink.size <= 24) { "$at: too many links" }
                val owner = if (op === actions.divide) op.id else parent
                for ((other, link) in op.physicalLink) {
                    require(other in next && other != owner) { "$at: invalid link to $other" }
                    if (link != null) {
                        require(link.length == null || link.length!! in 0.001f..8f) { "$at: invalid link length" }
                        if (link.isNeuronal) require(link.directedNeuronLink == owner || link.directedNeuronLink == other) {
                            "$at: directedNeuronLink must identify the receiving endpoint"
                        }
                    }
                }
            }
        }
        alive.addAll(next)
    }
}
