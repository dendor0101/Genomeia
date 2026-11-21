package io.github.some_example_name.lwjgl3.mcp

import io.github.some_example_name.old.editor.baking.GenomePreviewCapture
import io.github.some_example_name.old.cells.Eye
import io.github.some_example_name.old.cells.eyeData
import io.github.some_example_name.old.core.SingleThreadWorld
import io.github.some_example_name.old.systems.genomics.genome.*
import kotlin.math.*
import kotlinx.serialization.json.JsonObject

/** Spawn the proposed adult directly. The empty runtime genome only carries the arena topology. */
internal fun testBodyLayout(body: BodyLayout, ticks: Int): JsonObject {
    val size = maxOf(48, ceil(max(body.width, body.height)).toInt() + 12)
    val world = SingleThreadWorld(size, interactive = false)
    try {
        val cells = body.cells
        val pose = body.preview()
        val layout =
            BakedLayout(
                    cellGenomeIdsInSlotOrder = cells.indices.toList(),
                    linkPairsInSlotOrder = body.links.flatMap { it.toList() },
                    restX = pose.x.toList(),
                    restY = pose.y.toList(),
                    restRadius = pose.radius.toList(),
                    restBone = cells.map { it.type == "bone" },
                )
                .withTrianglesFromLinks()
        val genome =
            Genome(
                24,
                "MCP layout",
                listOf(GenomeStage()),
                sortedGraph = layout,
                neuralLinksPeak = 0,
            )
        val gi = world.genomeManager.addSavedGenome(genome)
        val organ = world.organEntity.addOrgan(gi, 1)
        world.organEntity.allocateArenas(organ, layout, 0)
        val c = world.cellEntity
        val slots = IntArray(cells.size)
        for ((id, cell) in cells.withIndex()) {
            val type = world.cellList[world.cellList.indexOfKey(cell.type)]
            val slot =
                c.addCell(
                    x = size / 2f + pose.x[id],
                    y = size / 2f + pose.y[id],
                    color = pose.color(id),
                    radius = cell.radius,
                    cellGenomeId = id,
                    cellType = type.cellTypeId,
                    organIndex = organ,
                    angleCos = pose.angleCos[id],
                    angleSin = pose.angleSin[id],
                )
            slots[id] = slot
            c.energy[slot] = c.maxEnergy[slot] / 2
            type.onStart(slot, 0, gi)
        }
        world.organEntity.alreadyGrownUp[organ] = true
        for ((i, j) in body.links) {
            val d = hypot(pose.x[i] - pose.x[j], pose.y[i] - pose.y[j])
            check(world.linkEntity.addLink(slots[i], slots[j], d) >= 0) {
                "Could not attach cells $i-$j"
            }
        }
        world.worldPhysics.placeAdultAtRest(organ)
        val initialPositionError = slots.indices.maxOf { id ->
            hypot(c.getX(slots[id]) - (size/2f + pose.x[id]), c.getY(slots[id]) - (size/2f + pose.y[id]))
        }
        check(initialPositionError < 0.0001f) { "Physics initialization displaced cells by $initialPositionError world units" }
        world.gridManager.rebuild(
            world.particleEntity.isAlive,
            world.particleEntity.gridId,
            world.particleEntity.isInGrid,
        )
        val initialEnergy = slots.sumOf { c.energy[it].toDouble() }
        val generations = slots.map(c::getGeneration)
        var lateEnergy = initialEnergy
        val lateTick = ticks * 3 / 4
        var elapsed = 0
        fun live(id: Int) = c.isAlive[slots[id]] && c.getGeneration(slots[id]) == generations[id]
        while (
            elapsed < ticks &&
                slots.indices.all(::live) &&
                world.linkEntity.aliveList.size == body.links.size
        ) {
            world.simulationSystem.updateTick()
            elapsed++
            if (elapsed == lateTick)
                lateEnergy = slots.indices.filter(::live).sumOf { c.energy[slots[it]].toDouble() }
        }
        val alive = slots.indices.filter(::live)
        val remainingLinks = world.linkEntity.aliveList.size
        val intact =
            alive.size == slots.size && remainingLinks == body.links.size && elapsed == ticks
        // Frame the adult itself, including its cell radii, even if it has drifted during the trial.
        val settled = if (intact) GenomePreviewCapture(c, world.particleEntity).capture(organ) else null
        val finalEnergy = alive.sumOf { c.energy[slots[it]].toDouble() }
        val finalGaps =
            if (intact)
                body.links
                    .filter { (a, b) ->
                        hypot(c.getX(slots[a]) - c.getX(slots[b]), c.getY(slots[a]) - c.getY(slots[b])) >=
                            c.getRadius(slots[a]) + c.getRadius(slots[b])
                    }
                    .map { it.toList() }
            else null
        body.settled = settled
        return obj(
            "ticks" to elapsed,
            "requestedTicks" to ticks,
            "survivedIntact" to intact,
            "initialCells" to cells.size,
            "maxInitialPositionError" to initialPositionError,
            "livingOriginalCells" to alive.size,
            "remainingLinks" to remainingLinks,
            "leafCount" to alive.count { cells[it].type == "leaf" },
            "initialEnergy" to initialEnergy,
            "finalEnergy" to finalEnergy,
            "lastQuarterEnergyChange" to (finalEnergy - lateEnergy).takeIf { elapsed == ticks },
            "nonOverlappingLinksAfterTrial" to finalGaps,
            "eyeProbes" to if (intact) probeLayoutEyes(world, body, slots) else null,
            "conditions" to
                "Isolated adult in an empty world under current solar settings, half-full energy, default cell behaviour, no programmed growth or neural wiring.",
        )
    } finally {
        world.dispose()
    }
}

/** Проверяем обзор каждой клетки отдельной мишенью в конце луча, сохраняя позу последнего тика. */
private fun probeLayoutEyes(world: SingleThreadWorld, body: BodyLayout, slots: IntArray): List<JsonObject> {
    val cells = world.cellEntity
    val particles = world.particleEntity
    val eye = world.cellList[world.cellList.indexOfKey("eye")] as Eye
    val targetRadius = 0.15f
    return body.cells.indices.filter { body.cells[it].type == "eye" }.map { id ->
        val slot = slots[id]
        val range = world.specialEntity.eyeData(slot).visibilityRange
        val x = cells.getX(slot) + cells.angleCos[slot] * range
        val y = cells.getY(slot) + cells.angleSin[slot] * range
        val inWorld = x in targetRadius..world.gridWidth - targetRadius && y in targetRadius..world.gridHeight - targetRadius
        val outsideBody = slots.none { hypot(x - cells.getX(it), y - cells.getY(it)) <= targetRadius + cells.getRadius(it) }
        var seesTarget = false
        var blocker: Int? = null
        if (inWorld) {
            val target = particles.addParticle(x, y, targetRadius, -1, isCell = false, isSub = false, holderEntityIndex = -1)
            world.gridManager.rebuild(particles.isAlive, particles.gridId, particles.isInGrid)
            val hit = eye.firstVisibleParticle(slot, range, 0)
            seesTarget = hit == target
            if (hit >= 0 && particles.isCell[hit]) blocker = cells.cellGenomeId[particles.holderEntityIndex[hit]]
            particles.deleteParticle(target)
            // Удаляем старую мишень из сетки до повторной выдачи её слота следующему глазу.
            world.gridManager.rebuild(particles.isAlive, particles.gridId, particles.isInGrid)
        }
        obj(
            "eyeId" to id, "angle" to atan2(cells.angleSin[slot], cells.angleCos[slot]),
            "targetDistance" to range, "targetInWorld" to inWorld, "targetOutsideBody" to outsideBody,
            "seesExternalTarget" to (seesTarget && outsideBody), "blockingCellId" to blocker
        )
    }
}
