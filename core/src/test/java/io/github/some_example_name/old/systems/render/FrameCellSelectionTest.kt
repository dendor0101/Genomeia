package io.github.some_example_name.old.systems.render

import io.github.some_example_name.old.commands.ScreenCell
import io.github.some_example_name.render.pack.CellInstanceBuffer as Cells
import kotlin.test.*

class FrameCellSelectionTest {
    private fun frame(tick: Int, generation: Int = 8) = RenderSnapshot(2, 1).apply {
        epoch = 3
        this.tick = tick
        instanceCount = 2
        cellCount = 1
        reserveCellKeys(1)[0] = (19L shl 32) or generation.toLong()
        Cells.write(instances, 0, 9f, 1f, 0, Cells.shape(Cells.radiusByte(.3f), 0),
            0, 0, 0, prevX = 1f, prevY = 1f, prevDir = 0)
    }

    @Test fun hitFollowsDrawnCenterAndPackedRadius() {
        val frame = frame(12)
        val expected = ScreenCell(19, 8, 3)
        for (lag in listOf(0f, .25f, .5f, .75f, 1f)) {
            val x = 9f - 8f * lag
            assertEquals(expected, frame.cellAt(x + .29f, 1f, lag))
            assertEquals(-1, frame.cellAt(x + .31f, 1f, lag).index)
        }
        assertEquals(-1, frame.cellAt(9f, 1f, .5f).index)
        assertEquals(-1, frame.cellAt(1f, 1f, .5f).index)
    }

    @Test fun pausedFramesKeepLatestClickUntilSimulationResponds() {
        val frame = frame(12)
        val selection = FrameCellSelection()
        val target = frame.cellAt(5f, 1f, .5f)
        val selected = selection.preview(target, 12, frame)
        selected.cellName = "Bone"
        assertSame(selected, selection.forFrame(frame, false))
        assertEquals("Bone", selected.cellName)
        assertEquals(9f, selected.grabbedCellX)
        assertEquals(1f, selected.grabbedPrevX)
        assertTrue(selected.isCellSelected)

        selection.preview(frame.cellAt(-10f, -10f, 0f), 12, frame)
        assertFalse(selection.forFrame(frame, false).isCellSelected)
        selection.preview(target, 12, frame)
        assertFalse(selection.forFrame(frame(12, generation = 9), true).isCellSelected)
        val next = frame(13)
        assertSame(next.spec, selection.forFrame(next, true))
    }
}
