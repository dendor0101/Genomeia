package io.github.some_example_name.physics

/** Множители необязательного warm start/legacy: только примитивные массивы.
 * Здесь нет обхода значений, поэтому размещение ключей не задаёт порядок физики.
 * 17 байт на слот, загрузка до 1/2: 1024 слота занимают 17 КиБ (порядка L1),
 * размер зависит от контактов, а не всех связей мира. В обычном режиме пусто.
 */
internal class ContactLambdaCache {
    private var keys = LongArray(16)
    private var values = DoubleArray(16)
    private var used = BooleanArray(16)
    private var mask = 15
    private var size = 0

    private fun home(key: Long): Int = ((key * -7046029254386353131L) ushr 32).toInt() and mask

    fun getOrDefault(key: Long, fallback: Double): Double {
        var s = home(key)
        while (used[s]) {
            if (keys[s] == key) return values[s]
            s = (s + 1) and mask
        }
        return fallback
    }

    operator fun set(key: Long, value: Double) { put(key, value) }

    fun put(key: Long, value: Double) {
        var s = home(key)
        while (used[s]) {
            if (keys[s] == key) { values[s] = value; return }
            s = (s + 1) and mask
        }
        if (size * 2 >= keys.size) {
            grow()
            s = home(key)
            while (used[s]) s = (s + 1) and mask
        }
        used[s] = true; keys[s] = key; values[s] = value; size++
    }

    fun remove(key: Long) {
        var hole = home(key)
        while (used[hole] && keys[hole] != key) hole = (hole + 1) and mask
        if (!used[hole]) return
        size--
        var next = (hole + 1) and mask
        while (used[next]) {
            // Сдвигаем только ключи, чей путь поиска пересекает освободившийся слот.
            val first = home(keys[next])
            if (((next - first) and mask) >= ((next - hole) and mask)) {
                keys[hole] = keys[next]; values[hole] = values[next]
                hole = next
            }
            next = (next + 1) and mask
        }
        used[hole] = false
    }

    fun clear() {
        if (size == 0) return
        used.fill(false); size = 0
    }

    private fun grow() {
        val oldKeys = keys; val oldValues = values; val oldUsed = used
        keys = LongArray(keys.size * 2); values = DoubleArray(keys.size)
        used = BooleanArray(keys.size); mask = keys.lastIndex; size = 0
        for (i in oldKeys.indices) if (oldUsed[i]) put(oldKeys[i], oldValues[i])
    }
}
