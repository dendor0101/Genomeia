package io.github.some_example_name.old.features.guide

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.scenes.scene2d.ui.Widget
import com.kotcrab.vis.ui.VisUI
import io.github.some_example_name.old.core.ui.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * График формулы нейрона на стенде гайда. Два вида:
 *  - [showCurve] — кривая y(x) по всему диапазону входа и отметка текущего входа: формула
 *    без памяти, у неё каждому x свой y;
 *  - [showHistory] — x и y во времени за последние секунды: формула с памятью или временем.
 *
 * Рисуется батчем сцены через белую точку скина: отдельный ShapeRenderer потребовал бы
 * закрыть батч посреди сцены. Данные передаются массивами, которые график только читает;
 * пересчитывает их экран, когда что-то поменялось.
 */
class FormulaGraph(private val font: BitmapFont) : Widget() {

    private val white: TextureRegion = VisUI.getSkin().getRegion("white")
    private val layout = GlyphLayout()

    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var count = 0
    private var overTime = false
    private var markerX = 0f
    private var markerY = 0f

    // Видимый диапазон значений.
    private var minX = -1f
    private var maxX = 1f
    private var minY = -1f
    private var maxY = 1f

    /** Кривая y(x): точки [xs] -> [ys], отметка на входе [x] с выходом [y]. */
    fun showCurve(xs: FloatArray, ys: FloatArray, x: Float, y: Float) {
        this.xs = xs
        this.ys = ys
        count = xs.size
        overTime = false
        markerX = x
        markerY = y
        minX = xs.first()
        maxX = xs.last()
        fitY(ys, count, y)
    }

    /** Подпись окна истории: секунд в [showHistory]. */
    private var historySeconds = 0

    /**
     * Сигнал во времени: [historyX] — вход, [historyY] — выход, [size] последних тиков из
     * [capacity]; [ticksPerSecond] — сколько тиков мира в секунде (подпись окна).
     */
    fun showHistory(historyX: FloatArray, historyY: FloatArray, size: Int, capacity: Int, ticksPerSecond: Int) {
        historySeconds = capacity / ticksPerSecond
        xs = historyX
        ys = historyY
        count = size
        overTime = true
        minX = 0f
        maxX = (capacity - 1).toFloat()
        markerX = (size - 1).coerceAtLeast(0).toFloat()
        markerY = if (size > 0) historyY[size - 1] else 0f
        fitY(historyY, size, if (size > 0) historyX[size - 1] else 0f, historyX)
    }

    /** Диапазон по Y — по видимым значениям с запасом; бесконечности и гигантские хвосты отсекаются. */
    private fun fitY(values: FloatArray, size: Int, extra: Float, also: FloatArray? = null) {
        var lo = min(0f, finite(extra))
        var hi = max(0f, finite(extra))
        for (i in 0 until size) {
            val v = finite(values[i])
            lo = min(lo, v); hi = max(hi, v)
            if (also != null) {
                val u = finite(also[i])
                lo = min(lo, u); hi = max(hi, u)
            }
        }
        if (hi - lo < 1e-3f) { lo -= 1f; hi += 1f }
        val margin = (hi - lo) * 0.1f
        minY = lo - margin
        maxY = hi + margin
    }

    private fun finite(v: Float) = if (v.isNaN()) 0f else v.coerceIn(-LIMIT, LIMIT)

    override fun getPrefWidth() = 480.dp()
    override fun getPrefHeight() = 280.dp()

    override fun draw(batch: Batch, parentAlpha: Float) {
        validate()
        val old = batch.color.cpy()
        val oldFont = font.color.cpy()

        rect(batch, x, y, width, height, BACKGROUND)

        // Оси: x = 0 и y = 0, если попадают в окно.
        if (!overTime && minX < 0f && maxX > 0f) line(batch, px(0f), y, px(0f), y + height, AXIS, 1.dp())
        if (minY < 0f && maxY > 0f) line(batch, x, py(0f), x + width, py(0f), AXIS, 1.dp())

        if (overTime) {
            series(batch, xs, INPUT, 2.dp())
            series(batch, ys, OUTPUT, 3.dp())
        } else {
            series(batch, ys, OUTPUT, 3.dp())
        }

        // Отметка текущей точки: пунктир к осям, квадрат и подписи значений.
        if (count > 0) {
            val mx = px(markerX)
            val my = py(finite(markerY))
            val baseY = py(min(max(0f, minY), maxY))
            val baseX = if (overTime) x else px(min(max(0f, minX), maxX))
            dashed(batch, mx, baseY, mx, my)
            dashed(batch, baseX, my, mx, my)
            val s = 8.dp()
            rect(batch, mx - s / 2, my - s / 2, s, s, MARKER)

            val xValue = if (overTime) xs[count - 1] else markerX
            text(batch, "x = ${format(xValue)}", min(mx + 6.dp(), x + width - 120.dp()), baseY + 18.dp(), INPUT)
            text(batch, "y = ${format(markerY)}", baseX + 6.dp(), my + 18.dp(), MARKER)
        }

        // Границы окна по Y — чтобы было видно масштаб.
        text(batch, format(maxY), x + 4.dp(), y + height - 4.dp(), LABEL)
        text(batch, format(minY), x + 4.dp(), y + 16.dp(), LABEL)
        // Диапазон по X — одной подписью в правом нижнем углу: у оси y = 0 подписи
        // налезали на границу по Y и на саму кривую.
        val range = if (overTime) "t: -$historySeconds s .. 0" else "x: ${format(minX)} .. ${format(maxX)}"
        layout.setText(font, range)
        text(batch, range, x + width - layout.width - 4.dp(), y + 16.dp(), LABEL)

        batch.color = old
        font.color = oldFont
    }

    private fun px(v: Float) = x + (v - minX) / (maxX - minX) * width
    private fun py(v: Float) = y + ((v - minY) / (maxY - minY)).coerceIn(-0.02f, 1.02f) * height

    private fun series(batch: Batch, values: FloatArray, color: Color, thickness: Float) {
        for (i in 1 until count) {
            val x0 = if (overTime) i - 1f else xs[i - 1]
            val x1 = if (overTime) i.toFloat() else xs[i]
            val a = values[i - 1]
            val b = values[i]
            // Разрыв (бесконечность, ступенька через всё окно) не соединяем вертикальной стеной.
            if (a.isNaN() || b.isNaN()) continue
            line(batch, px(x0), py(finite(a)), px(x1), py(finite(b)), color, thickness)
        }
    }

    private fun dashed(batch: Batch, x0: Float, y0: Float, x1: Float, y1: Float) {
        val dx = x1 - x0
        val dy = y1 - y0
        val length = sqrt(dx * dx + dy * dy)
        if (length < 1f) return
        val dash = 6.dp()
        var t = 0f
        while (t < length) {
            val t1 = min(t + dash, length)
            line(batch, x0 + dx * t / length, y0 + dy * t / length, x0 + dx * t1 / length, y0 + dy * t1 / length, MARKER_LINE, 1.dp())
            t += dash * 2
        }
    }

    private fun line(batch: Batch, x0: Float, y0: Float, x1: Float, y1: Float, color: Color, thickness: Float) {
        val dx = x1 - x0
        val dy = y1 - y0
        val length = sqrt(dx * dx + dy * dy)
        if (length < 0.01f) return
        batch.color = color
        batch.draw(
            white, x0, y0 - thickness / 2, 0f, thickness / 2, length, thickness, 1f, 1f,
            atan2(dy, dx) * MathUtils.radiansToDegrees
        )
    }

    private fun rect(batch: Batch, x: Float, y: Float, w: Float, h: Float, color: Color) {
        batch.color = color
        batch.draw(white, x, y, w, h)
    }

    private fun text(batch: Batch, text: String, x: Float, y: Float, color: Color) {
        font.color = color
        font.draw(batch, text, x, y)
    }

    private fun format(v: Float): String = when {
        v.isNaN() -> "NaN"
        abs(v) >= 1e4f -> String.format(Locale.ROOT, "%.2e", v)
        else -> String.format(Locale.ROOT, "%.2f", v)
    }

    private companion object {
        /** Дальше этого график не рисует: y = t или x^a убегают в бесконечность. */
        const val LIMIT = 1e6f

        val BACKGROUND = Color(0.10f, 0.12f, 0.14f, 1f)
        val AXIS = Color(0.45f, 0.45f, 0.42f, 1f)
        val INPUT = Color(0.45f, 0.75f, 0.95f, 1f)
        val OUTPUT = Color(0.95f, 0.80f, 0.35f, 1f)
        val MARKER = Color(0.95f, 0.45f, 0.35f, 1f)
        val MARKER_LINE = Color(0.95f, 0.45f, 0.35f, 0.7f)
        val LABEL = Color(0.65f, 0.62f, 0.56f, 1f)
    }
}
