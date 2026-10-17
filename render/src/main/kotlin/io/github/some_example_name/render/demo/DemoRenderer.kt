package io.github.some_example_name.render.demo

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.ScreenUtils

/** Принимает только геометрические примитивы и текст. Ни состояния физики, ни её
 * типов здесь нет. Примитивы отправляются сразу: дополнительного массива команд
 * на сотни тысяч элементов и второго копирования координат за кадр не требуется.
 */
class DemoRenderer : Disposable {
    private val shapes = ShapeRenderer(60000)
    private val batch = SpriteBatch()
    private val font = BitmapFont()
    val canvas = PrimitiveCanvas(shapes)
    val text = TextCanvas(batch, font)

    /** Снимок стенда для проверки после переноса рисования; вызывается только явно. */
    fun saveScreenshot(path: String) {
        val image = com.badlogic.gdx.graphics.Pixmap.createFromFrameBuffer(
            0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
        try {
            com.badlogic.gdx.graphics.PixmapIO.writePNG(Gdx.files.absolute(path), image, -1, true)
        } finally { image.dispose() }
    }

    fun beginFrame(background: Color, projection: Matrix4) {
        ScreenUtils.clear(background)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.projectionMatrix = projection
    }

    fun drawControls(controls: SimControls, projection: Matrix4, width: Int) {
        shapes.projectionMatrix = projection
        batch.projectionMatrix = projection
        controls.render(shapes, batch, font, width)
    }

    override fun dispose() {
        shapes.dispose()
        batch.dispose()
        font.dispose()
    }
}

class PrimitiveCanvas internal constructor(private val shapes: ShapeRenderer) {
    var color: Color
        get() = shapes.color
        set(value) { shapes.color = value }

    fun begin(filled: Boolean) = shapes.begin(if (filled) ShapeRenderer.ShapeType.Filled else ShapeRenderer.ShapeType.Line)
    fun end() = shapes.end()
    fun triangle(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float) =
        shapes.triangle(x0, y0, x1, y1, x2, y2)
    fun circle(x: Float, y: Float, radius: Float, segments: Int) = shapes.circle(x, y, radius, segments)
    fun rectLine(x0: Float, y0: Float, x1: Float, y1: Float, width: Float) = shapes.rectLine(x0, y0, x1, y1, width)
}

class TextCanvas internal constructor(private val batch: SpriteBatch, private val font: BitmapFont) {
    var color: Color
        get() = font.color
        set(value) { font.color = value }

    fun begin(projection: Matrix4) { batch.projectionMatrix = projection; batch.begin() }
    fun text(value: String, x: Float, y: Float) { font.draw(batch, value, x, y) }
    fun end() = batch.end()
}
