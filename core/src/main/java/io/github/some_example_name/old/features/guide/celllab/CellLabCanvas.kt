package io.github.some_example_name.old.features.guide.celllab

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.ui.Widget
import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.cells.eyeData
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.journal.CellLabInput
import io.github.some_example_name.old.systems.render.DebugCellPalette
import io.github.some_example_name.render.RenderFrame
import io.github.some_example_name.render.RenderMode
import io.github.some_example_name.render.pack.CellInstanceBuffer
import io.github.some_example_name.render.pack.OverlayInstanceBuffer
import kotlin.math.sqrt

/**
 * GL вызывается до stage.draw, а не внутри его Batch. Два поточных буфера по 128×40 байт
 * занимают около 10 КБ и помещаются в L1; это верхний запас для 32 клеток и 24 частиц еды.
 * Выходной FBO 512² RGBA8 — 1 МБ. Промежуточные FBO принадлежат общему WorldRenderer.
 */
internal class CellLabCanvas(private val session: CellLabSession) : Widget(), Disposable {
    private val cells = CellInstanceBuffer(128)
    private val overlay = OverlayInstanceBuffer(128)
    private val frame = RenderFrame().apply {
        mode = RenderMode.DEBUG
        cameraProjection = Matrix4().setToOrtho2D(0f, 0f, CellLabWorld.SIZE.toFloat(), CellLabWorld.SIZE.toFloat())
    }
    private var output: FrameBuffer? = null
    private var region: TextureRegion? = null
    private var pointer = -1
    private var renderedRevision = -1L
    private var palette: DebugCellPalette? = null
    private var paletteModel: CellLabModel? = null

    init {
        name = "cell-lab-canvas"
        addListener(object : InputListener() {
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                if (this@CellLabCanvas.pointer != -1 || (button != 0 && button != -1)) return false
                this@CellLabCanvas.pointer = pointer
                event.stop()
                session.input(CellLabInput("grab", x = x / width * CellLabWorld.SIZE.toFloat(), y = y / height * CellLabWorld.SIZE.toFloat()))
                return true
            }
            override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                if (pointer == this@CellLabCanvas.pointer) {
                    event.stop()
                    session.input(CellLabInput("drag", x = x / width * CellLabWorld.SIZE.toFloat(), y = y / height * CellLabWorld.SIZE.toFloat()))
                }
            }
            override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                if (pointer == this@CellLabCanvas.pointer) {
                    session.input(CellLabInput("release"))
                    this@CellLabCanvas.pointer = -1
                }
            }
        })
    }

    override fun getPrefWidth() = 480.dp()
    override fun getPrefHeight() = 480.dp()

    fun renderPreview() {
        val model = session.model ?: return
        if (renderedRevision == session.revision) return
        renderedRevision = session.revision
        if (paletteModel !== model) {
            palette = DebugCellPalette(model.world.cellList)
            paletteModel = model
        }
        val palette = palette!!
        val p = model.p; val c = model.c
        cells.begin(p.aliveList.size)
        overlay.begin()
        for (i in 0..p.lastId) if (p.isAlive[i]) {
            val cell = p.holderEntityIndex[i]
            val isCell = p.isCell[i]
            val type = if (isCell) c.cellType[cell].toInt() else model.world.cellList.emptyTextureLayer
            val signal = if (isCell) c.neuronImpulseOutput[cell] else 0f
            val cos = if (isCell) c.angleCos[cell] else 1f
            val sin = if (isCell) c.angleSin[cell] else 0f
            cells.putCell(p.x(i), p.y(i), if (isCell) palette.color(type, p.color[i]) else p.color[i],
                cos, sin, p.radius[i], if (isCell) c.energy[cell] else 0f, type, i,
                isCell = isCell, look = if (isCell) palette.look(type, signal) else 0)
            if (isCell && palette.isEye(type)) {
                val range = model.world.specialEntity.eyeData(cell).visibilityRange
                overlay.putRay(p.x(i) + cos * p.radius[i], p.y(i) + sin * p.radius[i],
                    p.x(i) + cos * range, p.y(i) + sin * range, palette.defaultColor(type), signal)
            } else if (isCell && palette.hasFlagellum(type)) {
                overlay.putFlagellum(p.x(i) + cos * p.radius[i], p.y(i) + sin * p.radius[i],
                    p.x(i) + cos * 1.7f, p.y(i) + sin * 1.7f, palette.defaultColor(type), signal, i)
            }
        }
        val links = model.world.linkEntity
        for (i in 0..links.lastId) if (links.isAlive[i]) {
            val a = links.links1[i]; val b = links.links2[i]
            val dx = c.getX(b) - c.getX(a); val dy = c.getY(b) - c.getY(a)
            val distance = sqrt(dx * dx + dy * dy)
            if (distance > c.getRadius(a) + c.getRadius(b)) {
                val ra = c.getRadius(a) / distance; val rb = c.getRadius(b) / distance
                overlay.putRay(c.getX(a) + dx * ra, c.getY(a) + dy * ra,
                    c.getX(b) - dx * rb, c.getY(b) - dy * rb, 0xffa0a0a0.toInt())
            }
        }
        val neural = model.world.neuralLinkEntity
        for (i in 0..neural.lastId) if (neural.isAlive[i]) {
            val a = if (neural.isLink1NeuralDirected[i]) neural.links2[i] else neural.links1[i]
            val b = if (neural.isLink1NeuralDirected[i]) neural.links1[i] else neural.links2[i]
            overlay.putNeuralLink(c.getX(a), c.getY(a), c.getX(b), c.getY(b),
                Color.CYAN.toIntBits(), c.neuronImpulseOutput[a], i)
        }
        // Контур области феромона — фактический радиус поля, не декоративная анимация.
        val pheromones = model.world.pheromoneEntity
        for (i in 0..pheromones.lastId) if (pheromones.isAlive[i] && pheromones.radiusSquared[i] > 0f) {
            overlay.putRing(pheromones.x[i], pheromones.y[i], sqrt(pheromones.radiusSquared[i]), pheromones.color[i])
        }
        if (model.primaryAlive) overlay.putRing(c.getX(model.primary), c.getY(model.primary), 0.67f, Color.GOLD.toIntBits())
        if (model.grabbed >= 0 && p.isAlive[model.grabbed]) {
            overlay.putRay(p.x(model.grabbed), p.y(model.grabbed), model.targetX, model.targetY, Color.WHITE.toIntBits())
        }
        frame.cells = cells.end()
        frame.overlay = overlay.end()
        frame.time = model.tick / 60f
        if (output == null) {
            output = FrameBuffer(Pixmap.Format.RGBA8888, 512, 512, false)
            output!!.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            region = TextureRegion(output!!.colorBufferTexture).apply { flip(false, true) }
        }
        DIGameGlobalContainer.worldRenderer.renderOffscreen(frame, output!!)
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val image = region ?: return
        val color = batch.packedColor
        batch.setColor(1f, 1f, 1f, parentAlpha)
        batch.draw(image, x, y, width, height)
        batch.packedColor = color
    }

    override fun dispose() {
        output?.dispose()
        output = null
        renderedRevision = -1L
        region = null
        paletteModel = null
        palette = null
    }
}
