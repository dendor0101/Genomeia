package io.github.some_example_name.lwjgl3.mcp

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import io.github.some_example_name.old.bug.BugReplay
import io.github.some_example_name.old.cells.base.CellPalette
import io.github.some_example_name.old.commands.GoGenomes
import io.github.some_example_name.old.core.DIGameGlobalContainer as Game
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer as Editor
import io.github.some_example_name.old.features.editor.GenomeEditorScreen
import io.github.some_example_name.old.features.genomes.GenomesScreen
import io.github.some_example_name.old.features.genomes.GenomePreviewCache
import io.github.some_example_name.old.features.menu.MenuScreen
import io.github.some_example_name.old.systems.genomics.genome.*
import io.github.some_example_name.old.systems.genomics.library.GenomeCatalog
import io.github.some_example_name.old.systems.genomics.library.GenomeImport
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID

/** Состояние черновиков принадлежит окну игры; обращения сериализованы в потоке libGDX. */
internal object GenomeTools {
    /** [world] — итог роста в мире с энергией ([growInWorld]); считается раз на черновик. */
    private data class Draft(val source: Genome, val layout: BodyLayout, var grown: Genome? = null, var world: JsonObject? = null)

    private fun worldGrowth(d: Draft, g: Genome): JsonObject = d.world ?: growInWorld(g).also { d.world = it }
    private var lastGrowth: Draft? = null
    private var lastReplayGenome: Genome? = null
    private val drafts = linkedMapOf<String, Draft>()
    // Bundled assets are immutable for the life of the installation. Player files are always reloaded.
    private val references = linkedMapOf<String, Genome>()
    private val codec by lazy { Json { serializersModule = GenomeProto.serializersModule } }
    private val screen get() = Game.game.screen
    private val canBake get() = screen is MenuScreen || screen is GenomesScreen

    fun call(name: String, a: JsonObject): JsonObject {
        check(!BugReplay.isActive) { "MCP is unavailable during report replay" }
        if (name == "preview_organism") return image(StateReader.bodySnapshot(a))
        if (name == "preview_genome") return image(snapshot(a.string("id")))
        if (name == "preview_body_layout") {
            val layout = BodyLayouts.get(a.string("id"))
            val pose = when (a["view"]?.jsonPrimitive?.content ?: "placed") {
                "placed" -> layout.preview()
                "settled" -> checkNotNull(layout.settled) { "Run a successful layout trial first." }
                else -> error("Expected placed or settled")
            }
            val size = a["size"]?.jsonPrimitive?.int ?: 384
            require(size in 128..1024) { "Preview size must be 128..1024 pixels" }
            return image(pose, size).also { if (layout.tested != null) layout.reviewed = true }
        }
        val data = when (name) {
            "get_status" -> obj("gameVersion" to io.github.some_example_name.old.core.GameVersion.NAME, "apiVersion" to 2, "screen" to screen.javaClass.simpleName,
                "route" to Game.navigationCommandsManager.currentPath(), "canValidateAndSave" to canBake,
                "bodyLayouts" to BodyLayouts.ids, "drafts" to drafts.keys, "currentEditorTick" to if (screen is GenomeEditorScreen) Editor.currentTick else null)
            "read_body_layout" -> BodyLayouts.read(a)
            "create_body_layout" -> BodyLayouts.create(a)
            "test_body_layout" -> {
                val layout = BodyLayouts.get(a.string("id"))
                layout.reviewed = false
                layout.approved = false
                layout.tested = null
                layout.settled = null
                testBodyLayout(layout, a.number("ticks", 600, 60..1200)).also { layout.tested = it }
            }
            "approve_body_layout" -> BodyLayouts.approve(a.string("id"), a["positionTolerance"]?.jsonPrimitive?.double ?: 0.1)
            "start_motion_trial" -> MotionTrial.start(a)
            "read_motion_trial" -> MotionTrial.read(a)
            "get_catalog" -> catalog()
            "list_genomes" -> obj("player" to GenomeCatalog.playerNames(), "bundled" to GenomeCatalog.bundledNames())
            "read_state" -> StateReader.read(a)
            "measure_organism" -> StateReader.measure(a)
            "read_growth" -> {
                val id = a.string("id")
                if (id == "editor:") check(screen is GenomeEditorScreen) { "Open the genome editor first" }
                else grow(draft(id), needReplay = true)
                StateReader.growth(a)
            }
            "read_genome" -> readGenome(a)
            "create_genome" -> create(a)
            "validate_genome" -> {
                val d = draft(a.string("id"))
                val g = grow(d)
                val fit = d.layout.compare(g)
                val world = worldGrowth(d, g)
                val matches = fit.getValue("matchesApprovedLayout").jsonPrimitive.boolean
                val grownInWorld = world.getValue("grown").jsonPrimitive.boolean
                obj("layoutComparison" to fit, "valid" to (matches && grownInWorld), "growthSucceeded" to true, "worldGrowth" to world,
                    "stages" to g.stageInstruction.size, "cells" to g.cellsAmount,
                    "physicalLinks" to g.linksAmount, "neuralLinks" to g.neuralLinksAmount, "neuralLinksPeak" to g.neuralLinksPeak)
            }
            "save_genome" -> {
                val d = draft(a.string("id"))
                val g = grow(d)
                check(d.layout.approved && d.layout.compare(g).getValue("matchesApprovedLayout").jsonPrimitive.boolean) { "Adult differs from the approved layout. Inspect validate_genome.layoutComparison before saving." }
                check(worldGrowth(d, g).getValue("grown").jsonPrimitive.boolean) { "The genome does not grow in a world with energy. Inspect validate_genome.worldGrowth before saving." }
                check(canBake) { "Open the main menu or Genomes before saving" }
                if (screen is MenuScreen) Game.navigationCommandsManager.performCommand(GoGenomes)
                val saved = (screen as GenomesScreen).importBaked(g)
                obj("id" to saved, "fileName" to "$saved.genome", "cells" to g.cellsAmount)
            }
            else -> error("Unknown tool $name")
        }
        return result(data)
    }

    private fun catalog(): JsonObject {
        val palette = if (screen is GenomeEditorScreen) Editor.cellPalette else CellPalette.FULL
        val cells = palette.designCells()
        return obj("designWorkflow" to BodyLayouts.RULES, "maximumCellRadius" to io.github.some_example_name.old.editor.geometry.CellPlacement.CHILD_RADIUS_MAX, "cells" to cells.map { obj("key" to it.key, "description" to it.description) },
            "formulas" to palette.formulaChoices(null).map { obj("key" to it.key, "label" to it.label) },
            "rules" to "Root cell ID is 0. Source IDs must exist before their stage. Give each child a unique ID. Declare its physical links to already grown neighbours on division. Mutation can change neural wiring, but cannot add a physical edge between existing cells. Angles are relative radians. Link lengths use game units. In a neural link directedNeuronLink identifies the receiver. Inspect an existing genome for examples.",
            "exampleStages" to codec.encodeToJsonElement(getEmptyGenome().stageInstruction))
    }

    private fun create(a: JsonObject): JsonObject {
        val layout = BodyLayouts.get(a.string("layoutId"))
        require(layout.approved) { "Show the tested Debug preview and get user approval before creating growth stages." }
        val name = a.string("name")
        require(name.length in 1..80 && name == GenomeImport.fileBaseName("$name.genome") &&
            !Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?").matches(name)) { "Invalid library name" }
        val stages = codec.decodeFromJsonElement<List<GenomeStage>>(a.getValue("stages"))
        checkStages(stages, if (screen is GenomeEditorScreen) Editor.cellPalette else CellPalette.FULL)
        val id = "draft:" + UUID.randomUUID()
        if (drafts.size >= 8) drafts.remove(drafts.keys.first())
        drafts[id] = Draft(Genome(24, name, stages), layout)
        return obj("id" to id, "name" to name, "stages" to stages.size)
    }

    private fun draft(id: String) = drafts[id] ?: error("Unknown draft. Use get_status or create_genome.")

    private fun grow(d: Draft, needReplay: Boolean = false): Genome {
        d.grown?.let {
            if (!needReplay || (lastGrowth === d && lastReplayGenome === Editor.editorSimulationSystem.getGenome())) return it
        }
        check(canBake) { "Return to the main menu or Genomes to run the editor growth calculation." }
        lastGrowth = null
        lastReplayGenome = null
        val baked = Editor.genomeBaker.bake(d.source, d.source.name)
        check((baked.preview?.cellCount ?: 0) > 0) { "Growth produced no cells" }
        lastGrowth = d
        lastReplayGenome = Editor.editorSimulationSystem.getGenome()
        d.grown = baked
        return baked
    }

    private fun source(id: String): Genome {
        drafts[id]?.let { return it.source }
        if (id == "editor:") {
            check(screen is GenomeEditorScreen) { "Open the genome editor first" }
            return Editor.editorSimulationSystem.getGenome()
        }
        require(id in GenomeCatalog.all()) { "Unknown catalog ID. Use list_genomes." }
        if (!GenomeCatalog.isBundled(id)) return GenomeCatalog.load(id)
        references[id]?.let { return it }
        val loaded = GenomeCatalog.load(id)
        if (references.size >= 4) references.remove(references.keys.first())
        references[id] = loaded
        return loaded
    }

    private fun snapshot(id: String): GenomePreview {
        drafts[id]?.let { return checkNotNull(grow(it).preview) }
        if (id == "editor:") {
            check(screen is GenomeEditorScreen) { "Open the genome editor first" }
            return Editor.genomePreviewCapture.capture()
        }
        return source(id).preview ?: error("This library file has no baked preview")
    }

    private fun readGenome(a: JsonObject): JsonObject {
        val id = a.string("id")
        return when (a["view"]?.jsonPrimitive?.content ?: "stages") {
            "stages" -> {
                val g = source(id)
                obj("name" to g.name, "genomeVersion" to g.version, "stages" to page(a, g.stageInstruction.size) { i ->
                    codec.encodeToJsonElement(g.stageInstruction[i])
                })
            }
            "links" -> {
                val pairs = (drafts[id]?.let { grow(it) } ?: source(id)).sortedGraph?.linkPairsInSlotOrder ?: error("No baked topology available")
                obj("links" to page(a, pairs.size / 2) { i -> obj("a" to pairs[2*i], "b" to pairs[2*i+1]) })
            }
            "cells" -> {
                val p = snapshot(id)
                obj("cells" to page(a, p.cellCount) { i ->
                    obj("id" to p.cellGenomeId.getOrNull(i), "type" to p.cellTypeKeys[p.cellType[i]],
                        "x" to p.x[i], "y" to p.y[i], "radius" to p.radius[i],
                        "angleCos" to p.angleCos[i], "angleSin" to p.angleSin[i])
                })
            }
            else -> error("Unknown genome view")
        }
    }

    private fun image(p: GenomePreview, size: Int = 384): JsonObject {
        val cache = GenomePreviewCache(size)
        try {
            val fbo = cache.renderBody(p)
            try {
                fbo.begin()
                val pixels = try { Pixmap.createFromFrameBuffer(0, 0, fbo.width, fbo.height) } finally { fbo.end() }
                val writer = PixmapIO.PNG()
                val bytes = ByteArrayOutputStream()
                try { writer.write(bytes, pixels) } finally { writer.dispose(); pixels.dispose() }
                return obj("content" to listOf(obj("type" to "image", "mimeType" to "image/png",
                    "data" to Base64.getEncoder().encodeToString(bytes.toByteArray()))))
            } finally { fbo.dispose() }
        } finally { cache.dispose() }
    }
}
