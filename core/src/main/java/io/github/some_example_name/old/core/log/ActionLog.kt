package io.github.some_example_name.old.core.log

/**
 * Кольцевой журнал действий игрока.
 *
 * Смысл ровно один: когда прилетел краш, стектрейс говорит ГДЕ сломалось, а журнал —
 * ЧТО игрок для этого сделал. Поэтому сюда пишутся намерения ("SetDayNight(17)"),
 * а не состояние: по намерениям сценарий воспроизводится, по состоянию — нет.
 *
 * Подряд идущие записи одного вида схлопываются в одну со счётчиком. Без этого один
 * протяг слайдера или один мазок кистью выдавливают из буфера всю предысторию: события
 * там идут на каждое движение мыши, то есть десятками в секунду.
 *
 * Стоимость записи — одно сравнение с последней записью в горячем случае (повтор),
 * поэтому журнал можно оставлять включённым и в релизе.
 */
object ActionLog {

    class Entry(
        val source: String,
        val kind: String,
        var detail: String,
        var repeats: Int,
        var millis: Long
    ) {
        override fun toString(): String = buildString {
            append(source).append(' ').append(kind)
            if (detail.isNotEmpty()) append('(').append(detail).append(')')
            if (repeats > 1) append(" x").append(repeats)
        }
    }

    /** Сколько записей держим. Больше — дольше предыстория, но и дамп краша толще. */
    private const val CAPACITY = 256

    private val entries = ArrayDeque<Entry>()
    private val lock = Any()
    private val startMillis = System.currentTimeMillis()

    var isEnabled = true

    /**
     * Куда дублировать записи по мере появления — консоль, файл, телеметрия.
     * Вызывается только на НОВЫХ записях: повторы схлопываются в уже отданную запись,
     * поэтому счётчик у неё вырастет уже после того, как sink её увидел.
     */
    var sink: ((Entry) -> Unit)? = null

    /**
     * @param coalesce false для событий, у которых важен каждый экземпляр — например
     *   навигация: два GoBack подряд это два разных перехода, а не повтор одного.
     */
    fun record(source: String, kind: String, detail: String = "", coalesce: Boolean = true) {
        if (!isEnabled) return

        synchronized(lock) {
            val last = entries.lastOrNull()
            if (coalesce && last != null && last.source == source && last.kind == kind) {
                last.repeats++
                last.detail = detail
                last.millis = System.currentTimeMillis()
                return
            }

            val entry = Entry(source, kind, detail, 1, System.currentTimeMillis())
            entries.addLast(entry)
            while (entries.size > CAPACITY) entries.removeFirst()
            sink?.invoke(entry)
        }
    }

    fun tail(count: Int = CAPACITY): List<Entry> = synchronized(lock) {
        entries.takeLast(count.coerceAtLeast(0))
    }

    fun clear() = synchronized(lock) { entries.clear() }

    /** Готовый кусок текста для краш-репорта. */
    fun dump(count: Int = CAPACITY): String = buildString {
        val items = tail(count)
        if (items.isEmpty()) {
            append("(журнал действий пуст)")
            return@buildString
        }
        items.forEach { entry ->
            append(timestamp(entry.millis)).append(' ').append(entry).append('\n')
        }
    }

    /**
     * Секунды от старта, а не время суток: сессию читают целиком и сверху вниз,
     * и «через 12 секунд после запуска» полезнее, чем «14:52:31».
     */
    private fun timestamp(millis: Long): String {
        val elapsed = millis - startMillis
        val seconds = elapsed / 1000
        val fraction = (elapsed % 1000).toInt()
        return buildString {
            append('[')
            append(seconds)
            append('.')
            if (fraction < 100) append('0')
            if (fraction < 10) append('0')
            append(fraction)
            append(']')
        }
    }
}
