package io.github.some_example_name.old.systems.maps

/**
 * Имя сохранённого мира — это имя его файла (`maps/<имя>.map`), отдельного поля в архиве
 * нет: файл можно переименовать в проводнике, и игра покажет новое имя.
 *
 * Поэтому имя обязано быть допустимым именем файла на всех платформах сразу: мир,
 * сохранённый на телефоне, приходит в .bug на ПК разработчика. Самые строгие правила —
 * у Windows, их и проверяем.
 */
object MapNames {

    /**
     * В символах. Кириллица в UTF-8 — два байта на символ, и с «.map.tmp» (временный файл
     * записи) имя остаётся далеко от предела в 255 байт на Android и iOS.
     */
    const val MAX_LENGTH = 48

    /** Запрещены в именах файлов Windows; `/` и `\` ещё и сделали бы из имени путь. */
    private const val FORBIDDEN = "<>:\"/\\|?*"

    /** Имена устройств Windows: файл «CON.map» там не создать. */
    private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") +
        (1..9).flatMap { listOf("COM$it", "LPT$it") }

    fun isValid(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_LENGTH) return false
        // Пробелы по краям Windows молча срезает, и файл получил бы другое имя, чем в команде.
        if (name != name.trim()) return false
        // Точка в начале — скрытый файл на Android/iOS («.» и «..» — вообще не имена),
        // точку в конце Windows срезает так же, как пробел.
        if (name.startsWith('.') || name.endsWith('.')) return false
        if (name.any { it < ' ' || it in FORBIDDEN }) return false
        return name.substringBefore('.').uppercase() !in RESERVED
    }

    /**
     * Имя из имени внешнего файла (импорт .map): запрещённые символы заменяются, лишнее
     * обрезается. null — разумного имени не вышло, пусть вызывающий возьмёт своё.
     */
    fun fromFileName(raw: String): String? {
        val replaced = raw.map { if (it < ' ' || it in FORBIDDEN) '_' else it }.joinToString("")
        val name = replaced.take(MAX_LENGTH).trim().trim('.').trim()
        return name.takeIf { isValid(it) }
    }

    /**
     * [base], а если занято — «base (2)», «base (3)»… [exists] — есть ли уже такой мир:
     * его отвечает файловая система, и на Windows «Мир» и «мир» — один файл.
     */
    fun unique(base: String, exists: (String) -> Boolean): String {
        require(isValid(base)) { "недопустимое имя мира: $base" }
        if (!exists(base)) return base
        var n = 2
        while (true) {
            val suffix = " ($n)"
            val name = base.take(MAX_LENGTH - suffix.length).trimEnd().trimEnd('.') + suffix
            if (!exists(name)) return name
            n++
        }
    }
}
