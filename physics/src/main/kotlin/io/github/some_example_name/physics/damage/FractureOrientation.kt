package io.github.some_example_name.physics

/** Не даёт отломку родиться с внутренней стороны ткани при смерти соседней клетки или пробое связей.
 * Контакт может вывернуть её треугольник после прохода площадей. Если сначала
 * удалить клетку, треугольник исчезнет и ограничение его знака уже не сработает.
 * Поэтому обрабатываем исчезающие треугольники до удаления их связей.
 *
 * Список берётся из готовой смежности, без обхода всей ткани. Битовая маска
 * убирает дубли, список занятых слов позволяет очистить только задетое. Сортировка
 * слов и биты по возрастанию сохраняют порядок исходных треугольников независимо
 * от порядка заявок. Память — 12 байт на 64 треугольника: около 38 КБ при 100k
 * клеток/200k треугольников, около L1; при отдельном повреждении читаются лишь
 * несколько слов. Буферы выделяются при загрузке; маска очищается после прохода,
 * а длина списка локальна вызову и не переносится на следующую смерть.
 */
internal class FractureOrientation(private val body: BodyDefinition, private val topology: BakedTopology) {
    private val words = LongArray((body.triCount + 63) ushr 6)
    private val occupied = IntArray(words.size)

    fun solve(cells: IntArray, count: Int, x: DoubleArray, y: DoubleArray, w: DoubleArray) {
        var used = 0
        for (k in 0 until count) {
            val v = cells[k]
            for (s in topology.vertexTriStart[v] until topology.vertexTriStart[v + 1]) {
                val t = topology.vertexTriangles[s] / 3
                used = collect(t, used)
            }
        }
        project(used, x, y, w)
    }

    fun solvePierced(ct: BoundaryContacts, conOfEdge: IntArray, conDead: BooleanArray,
                     conCount: Int, x: DoubleArray, y: DoubleArray, w: DoubleArray) {
        var used = 0
        // Собираем весь пакет ДО первого удаления: два пробоя могут удалить
        // обе стороны одного треугольника, как у клетки 949 в записи 22.10.
        for (k in 0 until ct.pierceN) {
            val e = topology.edgeOf(ct.pierceA[k], ct.pierceB[k])
            if (e < 0 || topology.edgeDead[e]) continue
            val c = conOfEdge[e]
            if (c < 0 || c >= conCount || conDead[c]) continue
            for (s in topology.edgeTriStart[e] until topology.edgeTriStart[e + 1]) {
                used = collect(topology.edgeTris[s], used)
            }
        }
        project(used, x, y, w)
    }

    private fun collect(t: Int, used: Int): Int {
        if (!topology.triAlive[t]) return used
        val word = t ushr 6
        val fresh = words[word] == 0L
        if (fresh) occupied[used] = word
        words[word] = words[word] or (1L shl (t and 63))
        return if (fresh) used + 1 else used
    }

    private fun project(used: Int, x: DoubleArray, y: DoubleArray, w: DoubleArray) {
        java.util.Arrays.sort(occupied, 0, used)
        for (k in 0 until used) {
            val word = occupied[k]
            var bits = words[word]
            words[word] = 0L
            while (bits != 0L) {
                val t = (word shl 6) + java.lang.Long.numberOfTrailingZeros(bits)
                bits = bits and (bits - 1)
                val a = body.triA[t]; val b = body.triB[t]; val c = body.triC[t]
                val area = (x[b] - x[a]) * (y[c] - y[a]) - (y[b] - y[a]) * (x[c] - x[a])
                if (area < 0.0) projectBoundaryOrientation(a, b, c, area, x, y, w)
            }
        }
    }
}
