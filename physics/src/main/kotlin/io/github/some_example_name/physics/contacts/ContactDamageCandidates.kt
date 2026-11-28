package io.github.some_example_name.physics

/**
 * Клетки, уже глубоко перекрытые в начале подшага. После открытия контура
 * это может оказаться единственным свидетельством пробоя: к prepare клетка
 * успевает выйти из радиуса с внутренней стороны ткани.
 *
 * До updateBones ещё неизвестно, кого защищает жёсткая кость. Здесь только
 * собираем уникальные ID, а правило смерти применяется в solveContacts.
 * Тот же список хранит итоговые заявки: линейный поиск дублей в killList
 * превращал массовое повреждение в O(k²). Здесь вставка O(1) в прежнем порядке.
 * 5 байт/частицу на список (два списка — ~1 МБ на 100k, больше L1,
 * около L2 ПК; на телефоне может быть больше L2).
 * Записи только по задетым клеткам; очистка O(число заявок), без прохода по миру
 * и без выделений памяти при массовом разрушении.
 */
internal class ContactDamageCandidates(capacity: Int) {
    val ids = IntArray(capacity)
    private val marked = BooleanArray(capacity)
    var count = 0
        private set

    fun add(v: Int) {
        if (marked[v]) return
        marked[v] = true
        ids[count++] = v
    }

    fun clear() {
        for (k in 0 until count) marked[ids[k]] = false
        count = 0
    }
}
