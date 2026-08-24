package io.github.some_example_name.old.features.worldeditor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.ui.Widget
import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.core.ui.dp

/**
 * Холст редактора мира как обычный scene2d-актор.
 *
 * Раньше карта рисовалась собственным SpriteBatch поверх сцены, её прямоугольник
 * пересчитывался руками в resize(), а попадание мыши в UI отсекалось через stage.hit().
 * Теперь холст — участник вёрстки: место ему выделяет ячейка таблицы, попадания
 * разруливает сама сцена (кнопка сверху сама съедает клик), а поддержание пропорций
 * карты сводится к одному вписыванию в границы актора.
 */
class WorldCanvasActor(
    private val viewModel: WorldEditorViewModel,
    private val wallColor: Color,
    private val emptyColor: Color
) : Widget(), Disposable {

    private val pixmap = Pixmap(viewModel.gridWidth, viewModel.gridHeight, Pixmap.Format.RGBA8888)
    private val texture = Texture(pixmap).apply {
        setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
    }
    /**
     * Рамка рисуется собственным белым пикселем, а не skin-drawable "white": тот лежит
     * в атласе VisUI, и при растягивании в тонкую полоску к нему по краям подмешиваются
     * соседние пиксели атласа — граница получается пунктирной.
     */
    private val borderPixel = createPixelTexture()

    private var uploadedVersion = -1

    /** Прямоугольник, в который реально вписана карта: смещение внутри актора и размер. */
    private var mapOffsetX = 0f
    private var mapOffsetY = 0f
    private var mapWidth = 0f
    private var mapHeight = 0f

    init {
        addListener(object : InputListener() {
            override fun touchDown(
                event: InputEvent?,
                x: Float,
                y: Float,
                pointer: Int,
                button: Int
            ): Boolean {
                paintAt(x, y)
                return true
            }

            override fun touchDragged(event: InputEvent?, x: Float, y: Float, pointer: Int) {
                paintAt(x, y)
            }
        })
    }

    override fun getPrefWidth(): Float = viewModel.gridWidth.dp()

    override fun getPrefHeight(): Float = viewModel.gridHeight.dp()

    override fun layout() {
        val aspect = viewModel.gridWidth.toFloat() / viewModel.gridHeight.toFloat()

        mapWidth = width
        mapHeight = width / aspect
        if (mapHeight > height) {
            mapHeight = height
            mapWidth = height * aspect
        }

        mapOffsetX = (width - mapWidth) / 2f
        mapOffsetY = (height - mapHeight) / 2f
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        validate()
        syncTexture()

        val mapX = x + mapOffsetX
        val mapY = y + mapOffsetY

        batch.setColor(color.r, color.g, color.b, color.a * parentAlpha)
        batch.draw(texture, mapX, mapY, mapWidth, mapHeight)
        drawBorder(batch, mapX, mapY)
    }

    /** Локальные координаты актора -> клетка карты. Промах мимо карты просто игнорируется. */
    private fun paintAt(localX: Float, localY: Float) {
        validate()
        if (mapWidth <= 0f || mapHeight <= 0f) return

        val insideX = localX - mapOffsetX
        val insideY = localY - mapOffsetY
        if (insideX < 0f || insideY < 0f || insideX > mapWidth || insideY > mapHeight) return

        val gridX = (insideX / mapWidth * viewModel.gridWidth).toInt()
            .coerceIn(0, viewModel.gridWidth - 1)
        val gridY = (insideY / mapHeight * viewModel.gridHeight).toInt()
            .coerceIn(0, viewModel.gridHeight - 1)

        viewModel.handle(WorldEditorIntent.Paint(gridX, gridY))
    }

    private fun drawBorder(batch: Batch, mapX: Float, mapY: Float) {
        val thickness = BORDER_THICKNESS.dp()
        batch.setColor(BORDER_COLOR)
        batch.draw(borderPixel, mapX - thickness, mapY - thickness, mapWidth + thickness * 2f, thickness)
        batch.draw(borderPixel, mapX - thickness, mapY + mapHeight, mapWidth + thickness * 2f, thickness)
        batch.draw(borderPixel, mapX - thickness, mapY - thickness, thickness, mapHeight + thickness * 2f)
        batch.draw(borderPixel, mapX + mapWidth, mapY - thickness, thickness, mapHeight + thickness * 2f)
        batch.setColor(Color.WHITE)
    }

    /**
     * Заливка идёт целиком, но только когда карта действительно менялась: у сетки 192x192
     * это 36 тысяч пикселей, то есть доли миллисекунды, а вот пересоздавать Texture на
     * каждый мазок (как было раньше) — это аллокация и новый GL-объект каждый кадр.
     */
    private fun syncTexture() {
        if (uploadedVersion == viewModel.mapVersion) return

        val map = viewModel.map
        for (y in 0 until viewModel.gridHeight) {
            val row = map[y]
            // Пиксмап растёт сверху вниз, карта — снизу вверх.
            val pixelY = viewModel.gridHeight - 1 - y
            for (x in 0 until viewModel.gridWidth) {
                pixmap.setColor(if (row[x]) wallColor else emptyColor)
                pixmap.drawPixel(x, pixelY)
            }
        }
        texture.draw(pixmap, 0, 0)
        uploadedVersion = viewModel.mapVersion
    }

    override fun dispose() {
        texture.dispose()
        pixmap.dispose()
        borderPixel.dispose()
    }

    private companion object {
        const val BORDER_THICKNESS = 2f
        val BORDER_COLOR: Color = Color(0.35f, 0.35f, 0.40f, 1f)

        fun createPixelTexture(): Texture {
            val pixel = Pixmap(1, 1, Pixmap.Format.RGBA8888).apply {
                setColor(Color.WHITE)
                fill()
            }
            return Texture(pixel).also { pixel.dispose() }
        }
    }
}
