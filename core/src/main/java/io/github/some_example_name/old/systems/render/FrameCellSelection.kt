package io.github.some_example_name.old.systems.render

import io.github.some_example_name.old.commands.ScreenCell
import io.github.some_example_name.render.pack.CellInstanceBuffer as Cells

private const val CENTER = Cells.OFFSET_CENTER / 4
private const val PREVIOUS = Cells.OFFSET_PREV_CENTER / 4

/** Читается только передний слот. При клике время и позиции берутся из уже нарисованного кадра. */
internal fun RenderSnapshot.cellAt(x: Float, y: Float, lag: Float): ScreenCell {
    var closest = -1
    var bestDistance = Float.POSITIVE_INFINITY
    val ranges = if (hasOptics) opticalCullRanges else null
    val pad = Cells.RADIUS_MIN + Cells.RADIUS_SPAN
    var range = 0
    var i = 0
    while (i < cellCount) {
        if (ranges != null && range < ranges.count && i == ranges.first(range)) {
            val end = ranges.end(range)
            if (!ranges.intersects(range++, x - pad, y - pad, x + pad, y + pad)) {
                i = end
                continue
            }
        }
        val o = i * Cells.STRUCT_INTS
        val cx = Float.fromBits(instances[o + CENTER])
        val cy = Float.fromBits(instances[o + CENTER + 1])
        val dx = cx + (Float.fromBits(instances[o + PREVIOUS]) - cx) * lag - x
        val dy = cy + (Float.fromBits(instances[o + PREVIOUS + 1]) - cy) * lag - y
        val distance = dx * dx + dy * dy
        val shape = instances[o + Cells.OFFSET_SHAPE / 4]
        val radius = Cells.RADIUS_MIN + ((shape ushr 16) and 255) / 255f * Cells.RADIUS_SPAN
        if (distance <= radius * radius && distance < bestDistance) {
            closest = i
            bestDistance = distance
        }
        i++
    }
    if (closest < 0) return ScreenCell(-1, 0, epoch)
    val key = cellKeys[closest]
    return ScreenCell((key ushr 32).toInt(), key.toInt(), epoch)
}

/** До снимка с применённой командой HUD уже показывает выбор, в том числе на паузе. */
internal class FrameCellSelection {
    private var target: ScreenCell? = null
    private var throughTick = -1
    private val info = RenderSpecificBufferData()

    fun clear() { target = null }

    fun preview(cell: ScreenCell, tick: Int, snapshot: RenderSnapshot): RenderSpecificBufferData {
        target = cell
        throughTick = tick
        updatePose(snapshot, cell)
        return info
    }

    fun forFrame(snapshot: RenderSnapshot, fresh: Boolean): RenderSpecificBufferData {
        val cell = target ?: return snapshot.spec
        if (snapshot.epoch != cell.epoch || snapshot.tick > throughTick) {
            clear()
            return snapshot.spec
        }
        if (fresh) updatePose(snapshot, cell)
        return info
    }

    private fun updatePose(snapshot: RenderSnapshot, cell: ScreenCell) {
        info.isCellSelected = false
        if (cell.index < 0) return
        val key = (cell.index.toLong() shl 32) or (cell.generation.toLong() and 0xffffffffL)
        for (i in 0 until snapshot.cellCount) {
            if (snapshot.cellKeys[i] != key) continue
            val o = i * Cells.STRUCT_INTS
            info.grabbedCellX = Float.fromBits(snapshot.instances[o + CENTER])
            info.grabbedCellY = Float.fromBits(snapshot.instances[o + CENTER + 1])
            info.grabbedPrevX = Float.fromBits(snapshot.instances[o + PREVIOUS])
            info.grabbedPrevY = Float.fromBits(snapshot.instances[o + PREVIOUS + 1])
            info.isCellSelected = true
            return
        }
    }
}
