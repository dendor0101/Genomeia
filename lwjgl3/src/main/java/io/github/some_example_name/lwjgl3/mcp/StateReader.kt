package io.github.some_example_name.lwjgl3.mcp

import io.github.some_example_name.old.core.DIContext
import io.github.some_example_name.old.core.DISimulationContainer as World
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer as Editor
import io.github.some_example_name.old.features.editor.GenomeEditorScreen
import io.github.some_example_name.old.features.simulation.SimulationScreen
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.cells.base.Formulas
import io.github.some_example_name.old.editor.baking.GenomePreviewCapture
import io.github.some_example_name.old.systems.genomics.genome.GenomePreview
import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlinx.serialization.json.*

internal fun page(a: JsonObject, total: Int, entry: (Int) -> JsonElement): JsonObject {
    val offset = a.number("offset", 0, 0..10_000_000)
    val end = minOf(total, offset + a.number("limit", 30, 1..100))
    return obj("offset" to offset, "total" to total, "nextOffset" to end.takeIf { it < total },
        "items" to (offset until end).map(entry))
}

internal object StateReader {
    fun read(a: JsonObject): JsonObject = when (game.screen) {
        is SimulationScreen -> World.simulationSystem.betweenTicks {
            obj("source" to "world", "tick" to World.simulationData.tickCounter,
                "playing" to World.simulationData.isPlay, "page" to rows(World, a))
        }
        is GenomeEditorScreen -> obj("source" to "editor-grown-body", "selectedEditorTick" to Editor.currentTick,
            "editorLastTick" to Editor.lastTick, "page" to rows(Editor, a))
        else -> error("Open the simulation or genome editor to read live state. For library or draft data use read_genome.")
    }

    fun growth(a: JsonObject): JsonObject {
        val replay = Editor.cellReplay
        val last = replay.getTickCount() - 1
        check(last >= 0) { "The editor has no growth frames" }
        val tick = a.number("tick", if (game.screen is GenomeEditorScreen) Editor.currentTick else last, 0..last)
        val first = replay.tickStartIndices.getInt(tick)
        return obj("tick" to tick, "lastTick" to last, "cells" to page(a, replay.replayCellsCounterInTick.getInt(tick)) { offset ->
            val i = first + offset
            val p = replay.index[i]
            obj("id" to replay.cellGenomeId[i], "type" to Editor.cellList[replay.cellType[i].toInt()].key,
                "x" to Editor.particleEntity.x(p), "y" to Editor.particleEntity.y(p), "radius" to Editor.particleEntity.radius[p],
                "angleCos" to replay.angleCos[i], "angleSin" to replay.angleSin[i])
        })
    }

    fun bodySnapshot(a: JsonObject): GenomePreview {
        check(game.screen is SimulationScreen) { "Open the simulation first" }
        return World.simulationSystem.betweenTicks {
            val organs = World.organEntity
            val slot = a.number("organism", -1, organs.isAlive.indices)
            require(organs.isAlive[slot] && organs.getGeneration(slot) == a.getValue("generation").jsonPrimitive.int) { "Organism died or slot was reused" }
            GenomePreviewCapture(World.cellEntity, World.particleEntity).capture(slot)
        }
    }

    fun measure(a: JsonObject): JsonObject {
        check(game.screen is SimulationScreen) { "Open a world, then list organisms using read_state" }
        return World.simulationSystem.betweenTicks {
            val o = World.organEntity
            val id = a.number("organism", -1, 0 until o.isAlive.size)
            require(id in o.isAlive.indices && o.isAlive[id]) { "Organism is no longer alive" }
            val generation = o.getGeneration(id)
            require(a["generation"] == null || a.getValue("generation").jsonPrimitive.int == generation) { "Organism slot was reused" }
            var count = 0
            var x = 0.0
            var y = 0.0
            var vx = 0.0
            var vy = 0.0
            var energy = 0.0
            var physicalEnds = 0
            val types = mutableMapOf<String, Int>()
            val c = World.cellEntity
            val base = o.cellArenaBase[id]
            require(base >= 0) { "Organism has no cell arena" }
            for (i in base until base + o.cellArenaCapacity[id]) {
                if (!c.isAlive[i] || c.organIndex[i] != id) continue
                count++
                val key = c.cellList[c.cellType[i].toInt()].key
                types[key] = (types[key] ?: 0) + 1
                x += c.getX(i); y += c.getY(i)
                vx += c.getVx(i); vy += c.getVy(i)
                energy += c.energy[i]
                physicalEnds += c.linkAmount[i]
            }
            fun mean(sum: Double) = if (count > 0) sum / count else null
            obj("organism" to id, "generation" to generation, "tick" to World.simulationData.tickCounter,
                "cells" to count, "cellTypes" to JsonObject(types.mapValues { JsonPrimitive(it.value) }),
                "stage" to o.stage[id], "adult" to o.alreadyGrownUp[id], "totalEnergy" to energy,
                "centerX" to mean(x), "centerY" to mean(y), "meanVx" to mean(vx), "meanVy" to mean(vy),
                "physicalLinkEndpoints" to physicalEnds)
        }
    }

    private fun rows(d: DIContext, a: JsonObject): JsonObject {
        val cells = d.cellEntity
        val org = d.organEntity
        fun read(list: IntArrayList, entry: (Int) -> JsonElement) = page(a, list.size) { entry(list.getInt(it)) }
        return when (a["view"]?.jsonPrimitive?.content ?: "cells") {
            "cells" -> read(cells.aliveList) { i ->
                obj("slot" to i, "generation" to cells.getGeneration(i), "organism" to cells.organIndex[i],
                    "genomeCellId" to cells.cellGenomeId[i], "type" to cells.cellList[cells.cellType[i].toInt()].key,
                    "x" to cells.getX(i), "y" to cells.getY(i), "vx" to cells.getVx(i), "vy" to cells.getVy(i),
                    "radius" to cells.getRadius(i), "energy" to cells.energy[i], "maxEnergy" to cells.maxEnergy[i],
                    "signalIn" to cells.neuronImpulseInput[i], "signalOut" to cells.neuronImpulseOutput[i],
                    "shortening" to cells.degreeOfShortening[i],
                    "formula" to if (cells.isNeural[i]) Formulas.all()[cells.getActivationFuncType(i)].key else null)
            }
            "organisms" -> read(org.aliveList) { i ->
                obj("slot" to i, "generation" to org.getGeneration(i), "stage" to org.stage[i],
                    "adult" to org.alreadyGrownUp[i], "genome" to d.genomeManager.genomes.getOrNull(org.genomeIndex[i])?.name)
            }
            "links" -> with(d.linkEntity) {
                read(aliveList) { i -> obj("a" to links1[i], "b" to links2[i], "restLength" to linksNaturalLength[i]) }
            }
            "neuralLinks" -> with(d.neuralLinkEntity) {
                read(aliveList) { i ->
                    val forward = !isLink1NeuralDirected[i]
                    obj("source" to if (forward) links1[i] else links2[i], "target" to if (forward) links2[i] else links1[i])
                }
            }
            else -> error("Unknown state view")
        }
    }
}
