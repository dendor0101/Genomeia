package io.github.some_example_name.physics

/** Неизменяемые пары исходных связей, без Long-объектов и узлов HashSet.
 * Поиск нужен при загрузке/диагностике, не в проходе контактов. 8 байт на пару:
 * около 2,1 МиБ на 269k связей; последовательная загрузка и двоичный поиск.
 * Формат ключа сохранён ради прежних инструментов и побитового A/B.
 */
class LinkedPairs private constructor(private val keys: LongArray) {
    fun contains(key: Long): Boolean = java.util.Arrays.binarySearch(keys, key) >= 0

    companion object {
        fun from(a: IntArray, b: IntArray, count: Int): LinkedPairs {
            val keys = LongArray(count) { c ->
                minOf(a[c], b[c]).toLong() * 1000003L + maxOf(a[c], b[c]).toLong()
            }
            keys.sort()
            var size = 0
            for (k in keys.indices) if (size == 0 || keys[k] != keys[size - 1]) keys[size++] = keys[k]
            return LinkedPairs(if (size == keys.size) keys else keys.copyOf(size))
        }
    }
}
