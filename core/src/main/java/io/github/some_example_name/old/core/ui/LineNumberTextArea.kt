package io.github.some_example_name.old.core.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.utils.FloatArray
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.util.highlight.Highlight
import com.kotcrab.vis.ui.util.highlight.Highlighter
import com.kotcrab.vis.ui.widget.HighlightTextArea
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextField
import io.github.some_example_name.old.core.DIGameGlobalContainer.game

/**
 * Многострочное поле для кода: слева жёлоб с номерами строк, текущая строка чуть подсвечена.
 *
 * База — [HighlightTextArea], а не [com.kotcrab.vis.ui.widget.VisTextArea], по причине:
 * HighlightTextArea единственный в VisUI выставляет `softwrap = false` и рисует текст построчно.
 * Без отключённого переноса длинная строка делится на две визуальные — номера строк сразу разъезжаются.
 *
 * Отрисовка идёт в [drawText] (после фона поля и до глифов, поэтому подсветка ложится ПОД текст)
 * и в [draw] — только для пустого текста, когда базовый класс [drawText] не вызывает.
 * Слушатели не нужны: номера и текущая строка читаются из состояния каждый кадр.
 *
 * Осознанные ограничения первой версии:
 *  - жёлоб прокручивается вместе с текстом по горизонтали (не «прилипает» к левому краю);
 *  - текст красится только через [codeColor] (HighlightTextArea рисует текст исключительно highlighter'ом);
 *  - номера пересчитываются при изменении числа строк, а не каждой строки.
 */
class LineNumberTextArea(
    text: String = "",
    style: VisTextField.VisTextFieldStyle,
) : HighlightTextArea(text, style) {

    /** Цвет кода. Непустой текст подсвечиваем целиком, иначе HighlightTextArea рисует его белым. */
    var codeColor: Color = Color.WHITE
        private set

    var gutterBackground: Color = Color(0f, 0f, 0f, 0.28f)
    var gutterTextColor: Color = Color(0.62f, 0.62f, 0.66f, 1f)
    var currentLineNumberColor: Color = Color.WHITE

    /** Насколько «чуть-чуть» подсветить текущую строку. */
    var currentLineColor: Color = Color(1f, 1f, 1f, 0.07f)

    private val gutterBasePad = 10f.dp()
    private val gutterTextPad = 12f.dp()

    private val whiteDrawable: Drawable = VisUI.getSkin().getDrawable("white")
    private val measure = GlyphLayout()

    // Номера и их ширины держим между кадрами: без этого каждый кадр был бы toString + глиф-раскладка
    // на каждую строку. Пересобираем только когда меняется число строк.
    private val numberStrings = ArrayList<String>()
    private val numberWidths = FloatArray()
    private var cachedLineCount = -1
    private var cachedGutterWidth = 0f

    // true только после init. Супер-конструктор VisTextField зовёт getPrefWidth() ДО того,
    // как Kotlin выполнит инициализаторы полей наследника, поэтому до этого флага их трогать нельзя.
    private var numbersReady = false

    init {
        codeColor = style.fontColor?.cpy() ?: Color.WHITE
        applyCodeColor()
        numbersReady = true
    }

    fun setCodeColor(color: Color) {
        codeColor = color
        applyCodeColor()
    }

    private fun applyCodeColor() {
        setHighlighter(Highlighter().apply {
            addRule { area, highlights ->
                val len = area.text.length
                if (len > 0) highlights.add(Highlight(codeColor, 0, len))
            }
        })
    }

    override fun getPrefWidth(): Float {
        val base = super.getPrefWidth()
        if (!numbersReady) return base
        ensureNumbers(style.font, countLines())
        return base + cachedGutterWidth
    }

    override fun drawText(batch: Batch, font: BitmapFont, x: Float, y: Float) {
        ensureNumbers(font, countLines())
        drawGutter(batch, font, x, y)
        super.drawText(batch, font, x + cachedGutterWidth, y)
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        super.draw(batch, parentAlpha)
        // Базовый VisTextField.draw() при пустом тексте НЕ зовёт drawText (идёт ветка messageText),
        // поэтому жёлоб с номером «1» и подсветку первой строки дорисовываем отдельно.
        if (displayText.length == 0) {
            val font = style.font
            val bg = style.background
            val x = this.x + (bg?.leftWidth ?: 0f)
            val y = this.y + getTextY(font, bg) + (if (font.isFlipped) -textHeight else 0f)
            ensureNumbers(font, countLines())
            drawGutter(batch, font, x, y)
        }
    }

    /** Жёлоб: фон, подсветка текущей строки и номера. Текст рисует вызывающая сторона. */
    private fun drawGutter(batch: Batch, font: BitmapFont, x: Float, y: Float) {
        val lineCount = numberStrings.size
        val gutterW = cachedGutterWidth
        val lineH = font.lineHeight
        val curLine = cursorLineIndex()

        // Сохраняем цвета batch и шрифта: базовый drawText рисует чанки с alpha = font.color.a как parentAlpha.
        val bc = batch.color
        val br = bc.r; val bg = bc.g; val bb = bc.b; val ba = bc.a
        val fc = font.color
        val fr = fc.r; val fg = fc.g; val fb = fc.b; val fa = fc.a

        // Фон жёлоба на всю высоту виджета — поверх фона поля, чтобы номера читались.
        batch.setColor(gutterBackground.r, gutterBackground.g, gutterBackground.b, gutterBackground.a * ba)
        whiteDrawable.draw(batch, x, this.y, gutterW, height)

        // Подсветка текущей строки ДО текста → глифы остаются сверху.
        // Строки рисуются сверху вниз (font.draw получает верх строки), поэтому верх строки i = y - i*lineH,
        // а её прямоугольник лежит НИЖЕ верхней границы: [y - (i+1)*lineH, y - i*lineH].
        if (currentLineColor.a > 0f && lineCount > 0 && curLine < lineCount) {
            val rowY = y - (curLine + 1) * lineH
            batch.setColor(currentLineColor.r, currentLineColor.g, currentLineColor.b, currentLineColor.a * ba)
            whiteDrawable.draw(batch, x + gutterW, rowY, (width - gutterW).coerceAtLeast(0f), lineH)
            // В жёлобе полоску делаем заметнее, чтобы номер текущей строки было видно.
            batch.setColor(currentLineColor.r, currentLineColor.g, currentLineColor.b, (currentLineColor.a * 2.2f * ba).coerceAtMost(ba))
            whiteDrawable.draw(batch, x, rowY, gutterW, lineH)
        }

        for (i in 0 until lineCount) {
            val lineY = y - i * lineH
            if (i == curLine) {
                font.setColor(currentLineNumberColor.r, currentLineNumberColor.g, currentLineNumberColor.b, currentLineNumberColor.a * ba)
            } else {
                font.setColor(gutterTextColor.r, gutterTextColor.g, gutterTextColor.b, gutterTextColor.a * ba)
            }
            font.draw(batch, numberStrings[i], x + gutterW - gutterTextPad - numberWidths[i], lineY)
        }

        font.setColor(fr, fg, fb, fa)
        batch.setColor(br, bg, bb, ba)
    }

    // Текст сдвинут на ширину жёлоба, значит выделение и каретку сдвигаем туда же.
    override fun drawSelection(selection: Drawable, batch: Batch, font: BitmapFont, x: Float, y: Float) {
        ensureNumbers(font, countLines())
        super.drawSelection(selection, batch, font, x + cachedGutterWidth, y)
    }

    override fun drawCursor(cursorPatch: Drawable, batch: Batch, font: BitmapFont, x: Float, y: Float) {
        ensureNumbers(font, countLines())
        super.drawCursor(cursorPatch, batch, font, x + cachedGutterWidth, y)
    }

    private fun countLines(): Int {
        val t = text
        var n = 1
        for (i in t.indices) if (t[i] == '\n') n++
        return n
    }

    private fun cursorLineIndex(): Int {
        val t = text
        val c = cursorPosition.coerceIn(0, t.length)
        var line = 0
        for (i in 0 until c) if (t[i] == '\n') line++
        return line
    }

    private fun ensureNumbers(font: BitmapFont, lineCount: Int) {
        if (cachedLineCount == lineCount && numberStrings.size == lineCount) return
        numberStrings.clear()
        numberWidths.clear()
        var maxW = 0f
        for (i in 1..lineCount) {
            val s = i.toString()
            numberStrings.add(s)
            measure.setText(font, s)
            numberWidths.add(measure.width)
            if (measure.width > maxW) maxW = measure.width
        }
        cachedGutterWidth = maxW + gutterBasePad * 2f
        cachedLineCount = lineCount
    }
}

/**
 * Поле кода с номерами строк внутри DSL. Само оборачивает себя в правильную [ScrollPane]
 * (двухосевую, как требует [HighlightTextArea]) и добавляет её в таблицу.
 *
 * По умолчанию берётся проектный стиль поля из [makeStyledTextFieldStyle] (тёмный фон, бежевая рамка).
 */
fun VisTable.visLineNumberTextArea(
    text: String = "",
    style: VisTextField.VisTextFieldStyle = makeStyledTextFieldStyle(game, mutableListOf()),
    cellInit: (Cell<ScrollPane>.() -> Unit) = {}
): LineNumberTextArea {
    val area = LineNumberTextArea(text, style)
    val scroll = area.createCompatibleScrollPane()
    scroll.setFadeScrollBars(false)

    val cell = this.add(scroll) as Cell<ScrollPane>
    cellInit(cell)
    return area
}
