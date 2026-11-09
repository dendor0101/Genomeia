package io.github.some_example_name.old.features.simulation

import io.github.some_example_name.old.entities.CellEntity

/**
 * ОТМЕТКИ ИГРОКА на клетках — чтобы показать в отчёте о баге, о какой клетке речь
 * (SimulationIntent.MarkCell, средняя кнопка мыши; на паузе и на любой скорости).
 *
 * Мир они не меняют и в хэш не входят: команда лежит в журнале, и повтор отчёта ставит ту же
 * отметку в тот же тик. Позицию отмеченной клетки обновляет поток симуляции в конце тика
 * ([follow], из RenderBufferManager), рисует поток кадра (RenderSystem). Список меняется и
 * читается под замком; координаты — одиночные float, их запись и чтение из разных потоков
 * без замка дают в худшем случае позицию прошлого тика.
 */
class PlayerMarks {

    class Mark(val number: Int, val cell: Int, val generation: Int, x: Float, y: Float) {
        @Volatile var x = x
        @Volatile var y = y
        /** Клетка жива и та же (поколение): иначе отметка стоит на месте её гибели. */
        @Volatile var alive = cell >= 0
    }

    private val marks = ArrayList<Mark>()
    private var next = 1

    /** Новая отметка; возвращает её номер (по порядку с начала мира). */
    fun add(cell: Int, generation: Int, x: Float, y: Float): Int = synchronized(marks) {
        val number = next++
        marks.add(Mark(number, cell, generation, x, y))
        number
    }

    fun clear() = synchronized(marks) { marks.clear(); next = 1 }

    /** Поток симуляции, конец тика: отмеченные клетки — на их нынешние места. */
    fun follow(cellEntity: CellEntity) = synchronized(marks) {
        for (m in marks) {
            if (!m.alive) continue
            if (!cellEntity.isAlive[m.cell] || cellEntity.getGeneration(m.cell) != m.generation) { m.alive = false; continue }
            m.x = cellEntity.getX(m.cell); m.y = cellEntity.getY(m.cell)
        }
    }

    /** Поток кадра: обойти отметки (без копии списка — отметок единицы). */
    inline fun forEach(action: (Mark) -> Unit) = synchronized(marksLock()) { marksView().forEach(action) }

    @PublishedApi internal fun marksLock(): Any = marks
    @PublishedApi internal fun marksView(): List<Mark> = marks
}
