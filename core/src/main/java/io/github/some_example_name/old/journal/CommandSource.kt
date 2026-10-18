package io.github.some_example_name.old.journal

/**
 * Откуда пришла команда — кто её обработчик. В журнале это один байт: [code] стабилен,
 * новые источники добавляются в конец и никогда не переиспользуют чужой код.
 */
enum class CommandSource(val code: Int, val label: String) {
    NAVIGATION(0, "Navigation"),
    MENU(1, "Menu"),
    SETTINGS(2, "Settings"),
    SUPPORT(3, "Support"),
    WORLD_EDITOR(4, "WorldEditor"),
    SIMULATION(5, "Simulation"),
    PLAYER(6, "Player"),
    EDITOR(7, "Editor"),

    /** Сама игра, а не игрок: начало сессии симуляции, снимок клавиш, хэш состояния. */
    SYSTEM(8, "System"),

    /** Экран «Геномы»: свои и встроенные геномы, импорт, удаление, переименование. */
    GENOMES(9, "Genomes"),

    /** Гайд игрока: обучение редактору генома, описание клеток, стенд формул. */
    GUIDE(10, "Guide");

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun of(code: Int): CommandSource =
            byCode[code] ?: throw IllegalArgumentException("неизвестный источник команды: $code")
    }
}

/**
 * Канал записи.
 *
 * [UI] — команды, которые выполняет поток рендера: экраны, меню, редакторы. При повторе
 * их подаёт BugReplay по одной, в записанном порядке.
 *
 * [SIM] — то, что применяет поток симуляции в своём тике: касания мира, клавиши, начало
 * и рестарт сессии, хэши состояния. Пишутся с точным номером тика и при повторе
 * подаются в тот же тик.
 */
enum class Channel(val code: Int) {
    UI(0),
    SIM(1)
}
