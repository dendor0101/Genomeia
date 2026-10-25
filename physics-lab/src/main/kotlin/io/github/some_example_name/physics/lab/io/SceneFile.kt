package io.github.some_example_name.physics.lab.io

import io.github.some_example_name.physics.BodyDefinition
import java.io.File

/**
 * СЦЕНА ИЗ РАЗНЫХ ТЕЛ: файл `*.scene`, по строке на тело.
 *
 *   <путь к телу> <копий>     тело из редактора генома (см. BodyFile)
 *   grid:<сторона> <копий>    мягкая сетка сторона x сторона, как у scaleBench
 *   free <число>              свободные частицы без связей
 *   width <мировых единиц>    ширина ряда раскладки (по умолчанию — под квадрат)
 *
 * Пути — от каталога запуска. Все тела берутся в ОДНОМ масштабе: файлы редактора
 * задают клетку радиусом 0.5, и общий множитель (по первому телу, как у одиночного
 * BodyFile.load) сохраняет настоящие размеры тел друг относительно друга. Масштаб по
 * каждому телу отдельно вписал бы кирпич из 182 клеток в ту же ширину, что тело из 947,
 * и клетки вышли бы разного размера.
 *
 * Раскладка — полками: копии слева направо с зазором, ряд переносится, когда не
 * помещается в ширину. Тела не перекрываются на старте, связей между ними нет.
 */
object SceneFile {
    fun isScene(path: String) = path.endsWith(".scene")

    private class Entry(val raw: BodyFile.RawBody, val copies: Int)

    fun load(path: String): BodyDefinition {
        val file = File(path)
        require(file.isFile) { "нет файла сцены: $path" }
        val entries = ArrayList<Entry>()
        var free = 0
        var rowWidth = -1f
        file.forEachLine { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEachLine
            val p = t.split(Regex("\\s+"))
            when {
                p[0] == "free" -> free += p[1].toInt()
                p[0] == "width" -> rowWidth = p[1].toFloat()
                p[0].startsWith("grid:") -> entries.add(Entry(grid(p[0].removePrefix("grid:").toInt()), p[1].toInt()))
                else -> entries.add(Entry(BodyFile.parse(p[0]), p[1].toInt()))
            }
        }
        require(entries.isNotEmpty()) { "в сцене нет тел: $path" }

        // Масштаб — как у одиночного тела BodyFile.load по первому телу сцены.
        val first = entries[0].raw
        val scale = 2.6f / maxOf(span(first.x), span(first.y) * 2.6f / 1.7f)
        val gap = 0.35f

        var total = free
        var area = 0f
        for (e in entries) {
            total += e.raw.n * e.copies
            area += (span(e.raw.x) * scale + gap) * (span(e.raw.y) * scale + gap) * e.copies
        }
        if (rowWidth <= 0f) rowWidth = kotlin.math.sqrt(area * 1.6f)

        val x = FloatArray(total); val y = FloatArray(total); val r = FloatArray(total)
        val isBone = BooleanArray(total); val isMuscle = BooleanArray(total)
        val la = ArrayList<Int>(); val lb = ArrayList<Int>()
        var cursorX = 0f; var cursorY = 0f; var rowHeight = 0f
        var off = 0
        for (e in entries) {
            val raw = e.raw
            val w = span(raw.x) * scale; val h = span(raw.y) * scale
            val minX = raw.x.min(); val minY = raw.y.min()
            repeat(e.copies) {
                if (cursorX > 0f && cursorX + w > rowWidth) { cursorX = 0f; cursorY -= rowHeight + gap; rowHeight = 0f }
                for (i in 0 until raw.n) {
                    x[off + i] = cursorX + (raw.x[i] - minX) * scale
                    y[off + i] = cursorY - h + (raw.y[i] - minY) * scale
                    r[off + i] = raw.r[i] * scale
                    isBone[off + i] = raw.bone[i]; isMuscle[off + i] = raw.muscle[i]
                }
                for (k in raw.linkA.indices) { la.add(raw.linkA[k] + off); lb.add(raw.linkB[k] + off) }
                off += raw.n
                cursorX += w + gap
                if (h > rowHeight) rowHeight = h
            }
        }
        // Свободные частицы — ряд под всеми телами, шаг в четыре радиуса.
        val rFree = 0.5f * scale
        val bottom = cursorY - rowHeight - gap - 6f * rFree
        for (k in 0 until free) {
            val i = off + k
            x[i] = (k * 4f * rFree) % rowWidth
            y[i] = bottom - (k * 4f * rFree / rowWidth).toInt() * 4f * rFree
            r[i] = rFree
        }
        return BodyFile.build(x, y, r, isBone, isMuscle, la, lb, scale)
    }

    /** Мягкая сетка как writeScaleGrid у scaleBench: шаг 0.6, радиус 0.5, диагональ. */
    private fun grid(side: Int): BodyFile.RawBody {
        val n = side * side
        val x = FloatArray(n) { (it % side) * 0.6f }
        val y = FloatArray(n) { (it / side) * 0.6f }
        val r = FloatArray(n) { 0.5f }
        val la = ArrayList<Int>(); val lb = ArrayList<Int>()
        for (gy in 0 until side) for (gx in 0 until side) {
            val i = gy * side + gx
            if (gx + 1 < side) { la.add(i); lb.add(i + 1) }
            if (gy + 1 < side) { la.add(i); lb.add(i + side) }
            if (gx + 1 < side && gy + 1 < side) { la.add(i); lb.add(i + side + 1) }
        }
        return BodyFile.RawBody(x, y, r, BooleanArray(n), BooleanArray(n), la.toIntArray(), lb.toIntArray())
    }

    private fun span(v: FloatArray): Float = maxOf(v.max() - v.min(), 1e-6f)
}
