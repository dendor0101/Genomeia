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

    /** Сцена одним описанием — склейка тел по порядку (BodyDefinition.concat). */
    fun load(path: String): BodyDefinition = loadBodies(path).reduce { a, b -> a.concat(b) }

    /**
     * Тела сцены по отдельности, уже расставленные в мире, — для мира, который
     * добавляет их по одному (PhysicsWorld.addBody). Свободные частицы — последним
     * описанием (каждая частица — свой организм).
     */
    fun loadBodies(path: String): List<BodyDefinition> {
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

        var area = 0f
        for (e in entries) area += (span(e.raw.x) * scale + gap) * (span(e.raw.y) * scale + gap) * e.copies
        if (rowWidth <= 0f) rowWidth = kotlin.math.sqrt(area * 1.6f)

        val bodies = ArrayList<BodyDefinition>()
        var cursorX = 0f; var cursorY = 0f; var rowHeight = 0f
        for (e in entries) {
            val raw = e.raw
            val w = span(raw.x) * scale; val h = span(raw.y) * scale
            val minX = raw.x.min(); val minY = raw.y.min()
            repeat(e.copies) {
                if (cursorX > 0f && cursorX + w > rowWidth) { cursorX = 0f; cursorY -= rowHeight + gap; rowHeight = 0f }
                val x = FloatArray(raw.n) { cursorX + (raw.x[it] - minX) * scale }
                val y = FloatArray(raw.n) { cursorY - h + (raw.y[it] - minY) * scale }
                val r = FloatArray(raw.n) { raw.r[it] * scale }
                bodies.add(BodyFile.build(x, y, r, raw.bone.copyOf(), raw.muscle.copyOf(),
                    raw.linkA.toList(), raw.linkB.toList(), scale))
                cursorX += w + gap
                if (h > rowHeight) rowHeight = h
            }
        }
        // Свободные частицы — ряд под всеми телами, шаг в четыре радиуса.
        if (free > 0) {
            val rFree = 0.5f * scale
            val bottom = cursorY - rowHeight - gap - 6f * rFree
            val x = FloatArray(free) { k -> (k * 4f * rFree) % rowWidth }
            val y = FloatArray(free) { k -> bottom - (k * 4f * rFree / rowWidth).toInt() * 4f * rFree }
            bodies.add(BodyFile.build(x, y, FloatArray(free) { rFree }, BooleanArray(free), BooleanArray(free),
                emptyList(), emptyList(), scale))
        }
        return bodies
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
