package io.github.some_example_name.physics

/** Порядок независимых связей внутри небольшого участка исходного списка.
 * Для КАЖДОЙ частицы порядок её связей остаётся прежним: уровень связи больше
 * уровней всех предыдущих связей обоих концов. Меняется только взаимный порядок
 * операций над непересекающимися частицами, поэтому округление не меняется.
 *
 * Скалярный проход без временных координат даёт процессору независимые цепочки
 * арифметики. Порядок и оба конца занимают 12 байт/связь (~3.12 МБ для 100k
 * частиц с 2.6 связями на частицу); участок из 1024 записей — 12 КБ. Полный план
 * на 100k больше L2 у многих процессоров, но три массива читаются потоком. Концы продублированы
 * ради последовательного доступа; изменяемые длины/мышцы/смерти читаются из
 * исходных массивов по forward. Между участками перестановок
 * нет, чтобы не разбрасывать случайные чтения частиц по всему миру.
 *
 * Храним номера ограничений без промежуточного чтения conOfEdge. Это допустимо,
 * пока размер равен исходному: упаковка только удаляет элементы, сохраняя порядок,
 * а reset заполняет исходный список заново. Немедленную смерть учитывает conDead; после
 * упаковки решатель использует обычный плотный список, чтобы не обходить весь
 * исходный граф ради небольшого остатка тела. Reset снова включает полный план.
 * Ни разрыв, ни reset не пересчитывают его.
 * Это производная неизменяемой топологии, а не дополнительное численное состояние.
 */
internal class DistanceSweepOrder private constructor(
    val forward: IntArray,
    val particleA: IntArray,
    val particleB: IntArray,
) {
    companion object {
        fun create(state: BodyState): DistanceSweepOrder {
            val order = build(state)
            return DistanceSweepOrder(order,
                IntArray(order.size) { state.conA[order[it]] },
                IntArray(order.size) { state.conB[order[it]] })
        }

        private fun build(state: BodyState): IntArray {
            val count = state.conCount
            val last = IntArray(state.n)
            val level = IntArray(count)
            val sizes = IntArray(count + 1)
            var height = 0
            var base = 0
            for (c in 0 until count) {
                if (c % 1024 == 0) base = height
                val i = state.conA[c]; val j = state.conB[c]
                val l = maxOf(base, last[i], last[j])
                level[c] = l
                last[i] = l + 1; last[j] = l + 1
                sizes[l]++
                height = maxOf(height, l + 1)
            }
            val cursor = IntArray(height + 1)
            for (l in 0 until height) cursor[l + 1] = cursor[l] + sizes[l]
            val constraints = IntArray(count)
            for (c in 0 until count) {
                constraints[cursor[level[c]]++] = c
            }
            return constraints
        }
    }
}
