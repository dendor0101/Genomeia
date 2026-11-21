package io.github.some_example_name.physics.lab.stands.viewer

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.math.Matrix4
import io.github.some_example_name.render.demo.DemoRenderer
import io.github.some_example_name.render.demo.TextCanvas

/** Цвета окна стендов. Ткань разных тел — разных оттенков, чтобы два одинаковых тела не сливались при касании. */
internal object Palette {
    val BACKGROUND: Color = Color.valueOf("0B0F16FF")
    val PANEL: Color = Color.valueOf("111722FF")
    val PANEL_EDGE: Color = Color.valueOf("2A3444FF")
    val ROW_SELECTED: Color = Color.valueOf("244468FF")
    val ROW_HOVER: Color = Color.valueOf("1A2433FF")
    val FAMILY_ROW: Color = Color.valueOf("19202CFF")
    val TEXT: Color = Color.valueOf("E6ECF5FF")
    val DIM: Color = Color.valueOf("8D99AAFF")
    val HEAD: Color = Color.valueOf("9FC3F0FF")
    val OK: Color = Color.valueOf("4BE08AFF")
    val FAIL: Color = Color.valueOf("FF5D5DFF")
    val WAIT: Color = Color.valueOf("6B7686FF")
    val WARN: Color = Color.valueOf("FFC14DFF")

    val SOFT: Array<Color> = arrayOf(
        Color.valueOf("3FA7A0FF"), Color.valueOf("4F86D9FF"), Color.valueOf("63B45CFF"), Color.valueOf("9C7BD6FF"),
        Color.valueOf("3F9CC2FF"), Color.valueOf("7FA84AFF"), Color.valueOf("5C7FE0FF"), Color.valueOf("47B58CFF"),
    )
    val BONE: Color = Color.valueOf("EDE6D2FF")
    /** Мышечные группы: покой; при сокращении цвет уходит к MUSCLE_TIGHT, при расслаблении — к MUSCLE_LOOSE. */
    val MUSCLE: Array<Color> = arrayOf(
        Color.valueOf("D9605AFF"), Color.valueOf("E08A3CFF"), Color.valueOf("D65C9EFF"), Color.valueOf("C9B23AFF"),
        Color.valueOf("E0705AFF"), Color.valueOf("B56AD9FF"), Color.valueOf("E0A050FF"), Color.valueOf("D9507AFF"),
    )
    val MUSCLE_TIGHT: Color = Color.valueOf("FFF1A8FF")
    val MUSCLE_LOOSE: Color = Color.valueOf("5A2A3AFF")
    val BALL: Color = Color.valueOf("FFD54AFF")
    val ROCK: Color = Color.valueOf("6C7688FF")
    val BULK: Color = Color.valueOf("262C37FF")
    val LINK: Color = Color.valueOf("0B0F1670")
    val MOUSE: Color = Color.valueOf("FFE27AFF")
    val GRID: Color = Color.valueOf("FFFFFF10")
}

/**
 * СЛОЙ РИСОВАНИЯ ОКНА: панели, кружки, линии и текст в пикселях экрана (ноль внизу слева).
 *
 * Фигуры и текст рисуются разными пакетами libGDX, и чередовать их в одном проходе нельзя.
 * Поэтому панели не рисуют сразу, а складывают заказы в списки; [flush] выводит сначала все
 * фигуры, потом весь текст. Десятки короткоживущих объектов на кадр — для окна разработчика
 * не цена, а код панелей остаётся прямым: «прямоугольник, на нём строка».
 */
internal class Ui(private val renderer: DemoRenderer, private val small: TextCanvas, private val big: TextCanvas) {
    private class Rect(val x: Float, val y: Float, val w: Float, val h: Float, val c: Color)
    private class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val width: Float, val c: Color)
    private class Disc(val x: Float, val y: Float, val r: Float, val c: Color)
    private class Tri(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val c: Color)
    private class Text(val canvas: TextCanvas, val s: String, val x: Float, val y: Float, val c: Color)

    private val rects = ArrayList<Rect>()
    private val lines = ArrayList<Line>()
    private val discs = ArrayList<Disc>()
    private val tris = ArrayList<Tri>()
    private val texts = ArrayList<Text>()

    val lineHeight: Float get() = small.lineHeight
    val bigLineHeight: Float get() = big.lineHeight

    fun rect(x: Float, y: Float, w: Float, h: Float, c: Color) { rects.add(Rect(x, y, w, h, c)) }
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, c: Color) { lines.add(Line(x0, y0, x1, y1, width, c)) }
    fun disc(x: Float, y: Float, r: Float, c: Color) { discs.add(Disc(x, y, r, c)) }
    fun triangle(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, c: Color) { tris.add(Tri(x0, y0, x1, y1, x2, y2, c)) }

    /** Строка; [y] — верх заглавных букв. Возвращает ширину строки. */
    fun text(s: String, x: Float, y: Float, c: Color, large: Boolean = false): Float {
        val canvas = if (large) big else small
        texts.add(Text(canvas, s, x, y, c))
        return canvas.width(s)
    }

    fun width(s: String, large: Boolean = false): Float = (if (large) big else small).width(s)

    /** Строка, обрезанная до ширины [max] с многоточием. */
    fun fit(s: String, max: Float, large: Boolean = false): String {
        if (width(s, large) <= max) return s
        var lo = 0; var hi = s.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (width(s.substring(0, mid) + "…", large) <= max) lo = mid else hi = mid - 1
        }
        return s.substring(0, lo) + "…"
    }

    /** Разбивает текст на строки не шире [max] по словам. */
    fun wrap(s: String, max: Float, large: Boolean = false): List<String> {
        val out = ArrayList<String>()
        for (paragraph in s.split('\n')) {
            var line = ""
            for (word in paragraph.split(' ')) {
                if (word.isEmpty()) continue
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && width(candidate, large) > max) { out.add(line); line = word } else line = candidate
            }
            out.add(line)
        }
        return out
    }

    /** Абзац с переносом по словам от верха [top]; возвращает координату под абзацем. */
    fun paragraph(s: String, x: Float, top: Float, max: Float, c: Color, large: Boolean = false): Float {
        val step = (if (large) bigLineHeight else lineHeight) + 2f
        var y = top
        for (line in wrap(s, max, large)) { text(line, x, y, c, large); y -= step }
        return y
    }

    fun flush(projection: Matrix4) {
        val canvas = renderer.canvas
        if (rects.isNotEmpty() || discs.isNotEmpty() || lines.isNotEmpty() || tris.isNotEmpty()) {
            canvas.begin(true)
            for (r in rects) { canvas.color = r.c; canvas.rect(r.x, r.y, r.w, r.h) }
            for (l in lines) { canvas.color = l.c; canvas.rectLine(l.x0, l.y0, l.x1, l.y1, l.width) }
            for (t in tris) { canvas.color = t.c; canvas.triangle(t.x0, t.y0, t.x1, t.y1, t.x2, t.y2) }
            for (d in discs) { canvas.color = d.c; canvas.circle(d.x, d.y, d.r, 14) }
            canvas.end()
        }
        if (texts.isNotEmpty()) {
            // Один пакет спрайтов на оба шрифта: begin у холста только выставляет проекцию и открывает пакет.
            small.begin(projection)
            for (t in texts) { t.canvas.color = t.c; t.canvas.text(t.s, t.x, t.y) }
            small.end()
        }
        rects.clear(); lines.clear(); discs.clear(); tris.clear(); texts.clear()
    }

    companion object {
        /**
         * Шрифт с кириллицей из assets/fonts (тот же Roboto, что у игры). Набор знаков — латиница,
         * русский алфавит и всё, что встречается в текстах сцен: знак, которого нет в наборе,
         * libGDX молча пропускает, и в описании сцены появлялась бы дыра.
         */
        fun font(size: Int, extra: String): BitmapFont {
            val generator = FreeTypeFontGenerator(Gdx.files.internal("fonts/Roboto-Regular.ttf"))
            try {
                val base = FreeTypeFontGenerator.DEFAULT_CHARS +
                    "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя№«»—–…×°±≤≥·"
                val chars = (base + extra).toSet().filter { it >= ' ' }.joinToString("")
                val p = FreeTypeFontGenerator.FreeTypeFontParameter()
                p.size = size
                p.characters = chars
                p.minFilter = Texture.TextureFilter.Linear
                p.magFilter = Texture.TextureFilter.Linear
                return generator.generateFont(p)
            } finally { generator.dispose() }
        }
    }
}
