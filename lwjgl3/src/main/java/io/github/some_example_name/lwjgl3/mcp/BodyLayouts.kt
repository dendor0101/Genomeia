package io.github.some_example_name.lwjgl3.mcp

import io.github.some_example_name.old.cells.base.CellPalette
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.editor.geometry.CellPlacement
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.systems.genomics.genome.*
import java.util.UUID
import kotlin.math.*
import kotlinx.serialization.json.*

internal data class BodyCell(
    val type: String,
    val x: Float,
    val y: Float,
    val radius: Float,
    val angle: Float,
    val fineDetailReason: String?,
)

internal data class BodyLayout(
    val width: Float,
    val height: Float,
    val cells: List<BodyCell>,
    val links: List<Pair<Int, Int>>,
) {
    var settled: GenomePreview? = null
    var tested: JsonObject? = null
    var reviewed = false
    var approved = false
    var positionTolerance = 0.1

    fun preview(): GenomePreview {
        val keys = cells.map { it.type }.distinct()
        val types = DIGameGlobalContainer.cellTypes
        val colors =
            IntArray(cells.size) { i ->
                types[types.indexOfKey(cells[i].type)].defaultColor.toIntBits()
            }
        return GenomePreview(
            keys,
            cells.map { keys.indexOf(it.type) }.toIntArray(),
            cells.map { it.x - width / 2 }.toFloatArray(),
            cells.map { it.y - height / 2 }.toFloatArray(),
            cells.map { it.radius }.toFloatArray(),
            cells.map { cos(it.angle) }.toFloatArray(),
            cells.map { sin(it.angle) }.toFloatArray(),
            GenomePreview.colorBytesOf(colors),
            IntArray(cells.size) { it },
        )
    }

    /**
     * Compare adult IDs in a common frame. Translation and rotation do not change a body's shape.
     */
    fun compare(g: Genome): JsonObject {
        val p = checkNotNull(g.preview)
        val index = p.cellGenomeId.withIndex().associate { it.value to it.index }
        val common = cells.indices.filter(index::containsKey)
        val incorrect =
            common.filter {
                val i = index.getValue(it)
                p.cellTypeKeys[p.cellType[i]] != cells[it].type ||
                    abs(p.radius[i] - cells[it].radius) > 0.0001f
            }
        val actual =
            checkNotNull(g.sortedGraph)
                .linkPairsInSlotOrder
                .chunked(2)
                .map { minOf(it[0], it[1]) to maxOf(it[0], it[1]) }
                .toSet()
        val gaps =
            actual.filter { (a, b) ->
                val i = index[a]
                val j = index[b]
                i != null &&
                    j != null &&
                    hypot(p.x[i] - p.x[j], p.y[i] - p.y[j]) >= p.radius[i] + p.radius[j]
            }
        val missing = links.filter { it !in actual }
        var fitsScale = false
        val maxError =
            if (common.isEmpty()) null
            else {
                val cx = common.map { cells[it].x }.average()
                val cy = common.map { cells[it].y }.average()
                val px = common.map { p.x[index.getValue(it)] }.average()
                val py = common.map { p.y[index.getValue(it)] }.average()
                var dot = 0.0
                var cross = 0.0
                for (id in common) {
                    val i = index.getValue(id)
                    val x = cells[id].x - cx
                    val y = cells[id].y - cy
                    dot += x * (p.x[i] - px) + y * (p.y[i] - py)
                    cross += x * (p.y[i] - py) - y * (p.x[i] - px)
                }
                val a = atan2(cross, dot)
                val fitted =
                    common.map { id ->
                        val i = index.getValue(id)
                        val x = p.x[i] - px
                        val y = p.y[i] - py
                        Triple(cos(a) * x + sin(a) * y, -sin(a) * x + cos(a) * y, p.radius[i])
                    }
                fitsScale =
                    fitted.maxOf { it.first + it.third } - fitted.minOf { it.first - it.third } <=
                        width + 0.0001 &&
                        fitted.maxOf { it.second + it.third } -
                            fitted.minOf { it.second - it.third } <= height + 0.0001
                common.maxOf { id ->
                    val i = index.getValue(id)
                    val x = cells[id].x - cx
                    val y = cells[id].y - cy
                    hypot(
                        cos(a) * x - sin(a) * y - (p.x[i] - px),
                        sin(a) * x + cos(a) * y - (p.y[i] - py),
                    )
                }
            }
        val topologyMatches =
            (common.size == cells.size &&
                p.cellCount == cells.size &&
                incorrect.isEmpty() &&
                gaps.isEmpty() &&
                missing.isEmpty() &&
                actual.size == links.size)
        return obj(
            "matchesApprovedLayout" to
                (topologyMatches && fitsScale && maxError != null && maxError <= positionTolerance),
            "fitsScale" to fitsScale,
            "positionTolerance" to positionTolerance,
            "cellsAndLinksMatch" to topologyMatches,
            "maxPositionErrorWorldUnits" to maxError,
            "missingCellIds" to cells.indices.filterNot(index::containsKey),
            "unexpectedCellIds" to index.keys.filter { it !in cells.indices },
            "typeOrRadiusMismatch" to incorrect,
            "nonOverlappingLinks" to gaps.map { it.toList() },
            "missingLinks" to missing.map { it.toList() },
            "extraLinks" to (actual - links.toSet()).map { it.toList() },
        )
    }
}

internal object BodyLayouts {
    const val RULES =
        "Ask the user for maximum adult width and height in world cells before designing a new body (for example 4 x 4). Place cells directly in a body layout first. Use maximum cell radius except for thin detail explicitly requested by the user. Every physical link requires overlapping disks. Include Leaf for solar energy. Test the layout, show its Debug Original PNG and wait for the user's approval before writing growth stages. Compare the grown genome with the approved layout. For locomotion measure sustained progress on a fixed world-space axis over a specified distance."
    private val layouts = linkedMapOf<String, BodyLayout>()

    fun get(id: String): BodyLayout =
        layouts[id] ?: error("Unknown or expired body layout; create a new layout.")

    val ids: Set<String>
        get() = layouts.keys

    fun read(a: JsonObject): JsonObject {
        val body = get(a.string("id"))
        val entries =
            when (a["view"]?.jsonPrimitive?.content ?: "cells") {
                "cells" ->
                    page(a, body.cells.size) { id ->
                        val c = body.cells[id]
                        obj(
                            "id" to id,
                            "type" to c.type,
                            "x" to c.x,
                            "y" to c.y,
                            "angle" to c.angle,
                            "radius" to c.radius,
                            "fineDetailReason" to c.fineDetailReason,
                        )
                    }
                "links" ->
                    page(a, body.links.size) { i ->
                        obj("a" to body.links[i].first, "b" to body.links[i].second)
                    }
                else -> error("Expected cells or links")
            }
        return obj(
            "width" to body.width,
            "height" to body.height,
            "approved" to body.approved,
            "lastTrial" to body.tested,
            "positionTolerance" to body.positionTolerance,
            "page" to entries,
        )
    }

    fun create(a: JsonObject): JsonObject {
        val width = a.getValue("width").jsonPrimitive.float
        val height = a.getValue("height").jsonPrimitive.float
        require(width > 0 && width <= 128 && height > 0 && height <= 128) {
            "Supply the user's dimensions in (0,128] world cells."
        }
        val items = a.getValue("cells").jsonArray
        require(items.size in 1..MAX_DESIGN_CELLS) { "Use 1..$MAX_DESIGN_CELLS cells. Array indices are genome cell IDs." }
        val keys = CellPalette.FULL.designCells().map { it.key }.toSet()
        val cells =
            items.mapIndexed { id, item ->
                val c = item.jsonObject
                val type = c.string("type")
                require(type in keys) { "Cell $id: unknown or unavailable type $type" }
                val r = c["radius"]?.jsonPrimitive?.float ?: CellPlacement.CHILD_RADIUS_MAX
                require(r in CellPlacement.CHILD_RADIUS_MIN..CellPlacement.CHILD_RADIUS_MAX) {
                    "Cell $id: radius outside editor range"
                }
                require(
                    r == CellPlacement.CHILD_RADIUS_MAX ||
                        !c["fineDetailReason"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
                ) {
                    "Cell $id: smaller radius requires a user-requested thin detail"
                }
                val x = c.getValue("x").jsonPrimitive.float
                val y = c.getValue("y").jsonPrimitive.float
                require(x in r..width - r && y in r..height - r) {
                    "Cell $id including radius exceeds approved dimensions"
                }
                val angle = c["angle"]?.jsonPrimitive?.float ?: 0f
                require(angle.isFinite())
                BodyCell(type, x, y, r, angle, c["fineDetailReason"]?.jsonPrimitive?.contentOrNull)
            }
        require(cells.any { it.type == "leaf" }) {
            "Include at least one Leaf; check the energy budget in test_body_layout."
        }
        require(cells.map { it.x to it.y }.distinct().size == cells.size) {
            "Coincident cell centers"
        }
        val neighbours = Array(cells.size) { mutableSetOf<Int>() }
        val links =
            a.getValue("links").jsonArray.map { edge ->
                val pair = edge.jsonArray
                require(pair.size == 2) { "Each physical link needs two cell IDs" }
                val i = pair[0].jsonPrimitive.int
                val j = pair[1].jsonPrimitive.int
                require(
                    i in cells.indices &&
                        j in cells.indices &&
                        i != j &&
                        neighbours[i].add(j) &&
                        neighbours[j].add(i)
                ) {
                    "Invalid or duplicate link $i-$j"
                }
                val u = cells[i]
                val v = cells[j]
                require(hypot(u.x - v.x, u.y - v.y) < u.radius + v.radius) {
                    "Cells $i and $j must overlap, including at thin details."
                }
                minOf(i, j) to maxOf(i, j)
            }
        require(neighbours.all { it.size <= CellEntity.MAX_LINKS_PER_CELL }) {
            "Too many physical links per cell"
        }
        val seen = mutableSetOf(0)
        val queue = ArrayDeque(listOf(0))
        while (queue.isNotEmpty()) for (j in neighbours[queue.removeFirst()]) if (seen.add(j))
            queue.addLast(j)
        require(seen.size == cells.size) { "All cells must belong to one connected physical body" }
        val id = "layout:" + UUID.randomUUID()
        if (layouts.size >= 8) layouts.remove(layouts.keys.first())
        layouts[id] = BodyLayout(width, height, cells, links)
        return obj(
            "id" to id,
            "width" to width,
            "height" to height,
            "cells" to cells.size,
            "physicalLinks" to links.size,
            "shader" to "Debug Original",
        )
    }

    fun approve(id: String, tolerance: Double): JsonObject {
        val l = get(id)
        require(l.reviewed && l.tested?.get("survivedIntact")?.jsonPrimitive?.boolean == true) {
            "Test the layout, show its Debug PNG and obtain user approval first."
        }
        require(tolerance.isFinite() && tolerance >= 0 && tolerance <= max(l.width, l.height)) {
            "Invalid position tolerance"
        }
        l.positionTolerance = tolerance
        l.approved = true
        return obj("id" to id, "approved" to true)
    }
}
