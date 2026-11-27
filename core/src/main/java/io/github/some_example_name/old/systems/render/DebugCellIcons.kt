package io.github.some_example_name.old.systems.render

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.render.RenderFrame
import io.github.some_example_name.render.RenderMode
import io.github.some_example_name.render.pack.CellInstanceBuffer

/**
 * Значки типов клеток — клетка так, как её рисует отладочный шейдер мира (DebugCellPalette:
 * цвет типа и рисунок CellLook), одна в кадре. Ими клетки показывают справочник и панель
 * настроек мира: прежние картинки ui/cell-icons были у части типов и на мир не походили —
 * они убраны (27.11).
 *
 * Значок рисуется один раз, при первом запросе: [sizePx]² × 4 байта на тип (256² — 256 КБ,
 * 64² — 16 КБ), без проходов шейдера каждый кадр. Звать из потока GL и не внутри
 * batch.begin/end: рисует в свой FrameBuffer. Отрисовка мира после этого заливает свои клетки
 * заново (WorldRenderer: чужой буфер клеток заливается всегда).
 *
 * [transparent] = false — значок с фоном мира, квадратом (справочник: карточка клетки и так
 * тёмная). true — без фона (RenderFrame.transparentBackground), для кнопок интерфейса: снимок
 * читается обратно в память, цвет делится на покрытие (буфер премножен, а пакет интерфейса
 * смешивает непремноженный) и заливается обычной текстурой с MIP-уровнями — значок ужимают с
 * 64 пикселей до ~34 без ряби. Чтение обратно ждёт GPU: ~25 значков по 64² — доли миллисекунды
 * на ПК, один раз при открытии вкладки.
 *
 * На Android содержимое буферов и текстур из памяти после потери контекста пропадает, поэтому
 * держать значки стоит не дольше окна, которое их показывает: окна пересобираются на resize, а
 * он приходит и после пересоздания контекста.
 */
class DebugCellIcons(
    private val cellList: List<Cell>,
    private val sizePx: Int = 256,
    private val transparent: Boolean = false
) : Disposable {
    private val palette = DebugCellPalette(cellList)
    private val buffers = ArrayList<FrameBuffer>()
    private val textures = ArrayList<Texture>()
    private val icons = HashMap<String, TextureRegionDrawable>()
    private val cells = CellInstanceBuffer(1)
    private val frame = RenderFrame().apply {
        mode = RenderMode.DEBUG
        transparentBackground = transparent
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
        val output = FrameBuffer(Pixmap.Format.RGBA8888, sizePx, sizePx, false)
        DIGameGlobalContainer.worldRenderer.renderOffscreen(frame, output)
        if (transparent) {
            val texture = readStraightAlpha(output)
            output.dispose()
            textures.add(texture)
            TextureRegionDrawable(TextureRegion(texture))
        } else {
            buffers.add(output)
            output.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            TextureRegionDrawable(TextureRegion(output.colorBufferTexture).apply { flip(false, true) })
        }
    }

    /**
     * Снимок без фона — в текстуру с обычной (непремноженной) альфой. GL читает строки снизу
     * вверх, поэтому строки здесь же переворачиваются.
     */
    private fun readStraightAlpha(output: FrameBuffer): Texture {
        val n = sizePx
        output.begin()
        val raw = Pixmap.createFromFrameBuffer(0, 0, n, n)
        output.end()
        val image = Pixmap(n, n, Pixmap.Format.RGBA8888)
        val src = raw.pixels
        val dst = image.pixels
        val row = n * 4
        for (y in 0 until n) {
            val from = (n - 1 - y) * row
            val to = y * row
            for (x in 0 until row step 4) {
                val a = src.get(from + x + 3).toInt() and 0xFF
                for (c in 0 until 3) {
                    val v = src.get(from + x + c).toInt() and 0xFF
                    dst.put(to + x + c, if (a == 0) 0 else minOf(255, (v * 255 + a / 2) / a).toByte())
                }
                dst.put(to + x + 3, a.toByte())
            }
        }
        raw.dispose()
        val texture = Texture(image, true)
        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear)
        image.dispose()
        return texture
    }

    override fun dispose() {
        buffers.forEach { it.dispose() }
        buffers.clear()
        textures.forEach { it.dispose() }
        textures.clear()
        icons.clear()
    }
}
