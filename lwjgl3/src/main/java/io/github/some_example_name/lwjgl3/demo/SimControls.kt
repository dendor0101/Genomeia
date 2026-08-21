package io.github.some_example_name.lwjgl3.demo

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import kotlin.math.sqrt

/**
 * Диалог управления скоростью проигрывания. Вынесен из RealBodyDemo намеренно: к физике
 * он отношения не имеет, а класс демо и без него большой.
 *
 * ПОЧЕМУ РИСУЕТСЯ ВРУЧНУЮ, А НЕ ЧЕРЕЗ scene2d.ui. Готовым виджетам нужен Skin, то есть
 * атлас и шрифт в assets. Ради одного ползунка тащить в стенд зависимость от файлов
 * незачем — ShapeRenderer и BitmapFont уже есть, а ползунок это прямоугольник, кружок
 * и одно сравнение координат.
 *
 * ЧТО ТАКОЕ timeScale И ЧЕГО ОН НЕ ДЕЛАЕТ. Он умножает РЕАЛЬНОЕ время на входе в
 * аккумулятор, то есть меняет ЧИСЛО ТИКОВ в секунду. Сам шаг DT не трогается никогда:
 * от него зависит вся физика, и менять его ради замедления означало бы менять материал.
 * Отсюда важное следствие: воспроизведение на 0.5x даёт ПОБИТОВО те же состояния, что
 * и на 1x, просто показанные на большем числе кадров. Детерминизм цел.
 */
class SimControls {

    /** Множитель скорости проигрывания. 0 — стоп, 1 — реальное время. */
    var timeScale = 1.0
        private set

    var isOpen = false
        private set

    private var dragging = false

    companion object {
        const val MAX_SCALE = 10.0

        /**
         * Ползунок КВАДРАТИЧНЫЙ: value = MAX * t^2.
         *
         * При линейной шкале 0..10 единица оказалась бы на 10% дорожки, и выставить
         * что-то осмысленное между 0.2x и 2x — а это и есть рабочий диапазон, когда
         * разглядываешь столкновение или заворот — было бы невозможно. Квадрат отдаёт
         * медленной половине примерно две трети хода: x1 садится на 32%, x2.5 на 50%.
         */
        private fun toValue(t: Float): Double = MAX_SCALE * t * t
        private fun toTrack(v: Double): Float = sqrt(v / MAX_SCALE).toFloat()

        /** Отметки на дорожке — без них квадратичная шкала не читается. */
        private val TICKS = doubleArrayOf(0.0, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0)

        private val PANEL_BG = Color.valueOf("0E1219F2")
        private val PANEL_EDGE = Color.valueOf("3A4557FF")
        private val TRACK = Color.valueOf("2B3341FF")
        private val TRACK_FILL = Color.valueOf("4BE08AFF")
        private val KNOB = Color.valueOf("EAF0F8FF")
        private val TEXT = Color.valueOf("EAF0F8FF")
        private val MUTED = Color.valueOf("76818FFF")
        private val WARN = Color.valueOf("E8A33DFF")

        private const val W = 440f
        private const val H = 132f
        private const val PAD = 22f
    }

    fun toggle() { isOpen = !isOpen; dragging = false }
    fun close() { isOpen = false; dragging = false }

    /** Сбросить на реальное время. Отдельной клавишей: попасть точно в x1 мышью трудно. */
    fun reset() { timeScale = 1.0 }

    private fun panelX(screenW: Int) = (screenW - W) * 0.5f
    private fun panelY() = 60f
    private fun trackX(screenW: Int) = panelX(screenW) + PAD
    private fun trackW() = W - PAD * 2f
    private fun trackY() = panelY() + 44f

    /**
     * Обработка мыши. Возвращает true, если событие съедено диалогом — тогда демо не
     * должно трактовать этот же клик как перетаскивание вершины.
     */
    fun handleInput(screenW: Int, screenH: Int): Boolean {
        if (!isOpen) return false

        val mx = Gdx.input.x.toFloat()
        val my = screenH - Gdx.input.y.toFloat()   // экранный Y смотрит вниз, HUD — вверх
        val pressed = Gdx.input.isButtonPressed(Input.Buttons.LEFT)

        val tx = trackX(screenW)
        val tw = trackW()
        val ty = trackY()

        // Захват засчитывается и по дорожке, и рядом с ней по высоте: попадать пиксель
        // в пиксель по вертикали неудобно.
        val onTrack = mx >= tx - 12f && mx <= tx + tw + 12f && my >= ty - 16f && my <= ty + 16f
        if (pressed && !dragging && onTrack) dragging = true
        if (!pressed) dragging = false

        if (dragging) {
            val t = ((mx - tx) / tw).coerceIn(0f, 1f)
            timeScale = toValue(t)
            // Прилипание к единице: без него ровно реальное время мышью не поймать,
            // а это самое нужное значение.
            if (kotlin.math.abs(timeScale - 1.0) < 0.12) timeScale = 1.0
        }

        val inPanel = mx >= panelX(screenW) && mx <= panelX(screenW) + W &&
            my >= panelY() && my <= panelY() + H
        return dragging || (pressed && inPanel)
    }

    /**
     * Рисование. Батч и шейпы приходят снаружи и уже настроены на HUD-камеру —
     * заводить свои значило бы держать вторую копию тех же ресурсов.
     */
    fun render(shapes: ShapeRenderer, batch: SpriteBatch, font: BitmapFont, screenW: Int) {
        if (!isOpen) return

        val px = panelX(screenW)
        val py = panelY()
        val tx = trackX(screenW)
        val tw = trackW()
        val ty = trackY()
        val t = toTrack(timeScale)

        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.color = PANEL_BG
        shapes.rect(px, py, W, H)
        shapes.color = PANEL_EDGE
        shapes.rect(px, py, W, 2f)
        shapes.rect(px, py + H - 2f, W, 2f)
        shapes.rect(px, py, 2f, H)
        shapes.rect(px + W - 2f, py, 2f, H)

        shapes.color = TRACK
        shapes.rect(tx, ty - 3f, tw, 6f)
        shapes.color = TRACK_FILL
        shapes.rect(tx, ty - 3f, tw * t, 6f)

        // Отметки шкалы.
        shapes.color = PANEL_EDGE
        for (v in TICKS) {
            val x = tx + tw * toTrack(v)
            shapes.rect(x - 1f, ty - 11f, 2f, 8f)
        }

        shapes.color = KNOB
        shapes.circle(tx + tw * t, ty, 9f, 16)
        shapes.end()

        batch.begin()
        font.color = TEXT
        font.draw(batch, "SIMULATION SPEED", px + PAD, py + H - 14f)
        font.color = if (timeScale == 0.0) WARN else TEXT
        font.draw(batch, "x%.2f".format(timeScale), px + W - 82f, py + H - 14f)

        font.color = MUTED
        for (v in TICKS) {
            val x = tx + tw * toTrack(v)
            val label = if (v == 0.0) "0" else "%.4g".format(v).trimEnd('0').trimEnd('.')
            font.draw(batch, label, x - 6f, ty - 14f)
        }
        font.draw(batch, if (timeScale == 0.0)
            "остановлено — физика не считается, картинка живая"
        else "T закрыть   Y вернуть x1   шаг DT не меняется, меняется число тиков",
            px + PAD, py + 20f)
        batch.end()
    }
}
