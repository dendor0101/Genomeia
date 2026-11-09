package io.github.some_example_name.old.features.guide

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.systems.render.DebugCellPalette
import io.github.some_example_name.render.RenderFrame
import io.github.some_example_name.render.RenderMode
import io.github.some_example_name.render.pack.CellInstanceBuffer

/** Иконка рисуется один раз: 256² × 4 = 256 КБ на тип, без проходов шейдера каждый кадр. */
internal class GuideCellPreviews(private val cellList: List<Cell>) : Disposable {
    private val palette = DebugCellPalette(cellList)
    private val buffers = ArrayList<FrameBuffer>()
    private val icons = HashMap<String, TextureRegionDrawable>()
    private val cells = CellInstanceBuffer(1)
    private val frame = RenderFrame().apply {
        mode = RenderMode.DEBUG
        // Запас вокруг клетки оставляет место ресничкам и шипам.
        cameraProjection = Matrix4().setToOrtho2D(-0.7f, -0.7f, 1.4f, 1.4f)
    }

    fun drawable(cell: Cell): TextureRegionDrawable = icons.getOrPut(cell.key) {
        val type = cellList.indexOf(cell)
        cells.begin(1)
        cells.putCell(
            x = 0f, y = 0f, color = palette.defaultColor(type),
            angleCos = 1f, angleSin = 0f, radius = 0.5f, energy = 0f,
            cellType = type, noiseSeed = 0, look = palette.look(type, action = 0f)
        )
        frame.cells = cells.end()
        val output = FrameBuffer(Pixmap.Format.RGBA8888, 256, 256, false)
        buffers.add(output)
        output.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        DIGameGlobalContainer.worldRenderer.renderOffscreen(frame, output)
        TextureRegionDrawable(TextureRegion(output.colorBufferTexture).apply { flip(false, true) })
    }

    override fun dispose() {
        buffers.forEach { it.dispose() }
        buffers.clear()
        icons.clear()
    }
}
