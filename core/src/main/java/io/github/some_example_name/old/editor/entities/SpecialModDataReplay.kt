package io.github.some_example_name.old.editor.entities

import io.github.some_example_name.old.cells.base.SpecialModData
import io.github.some_example_name.old.entities.SpecialModDataEntity
import it.unimi.dsi.fastutil.ints.IntArrayList

/**
 * Свои данные клеток (SpecialModData) по тикам редактора — чтобы диалог клетки показывал,
 * какие они у клетки на выбранном тике: параметры глаза, клавишу контроллера
 * (ToEditorDataMapper).
 *
 * Копируются ссылки. Для данных из генома (неизменяемых) снимок тика верен и позже; у
 * состояния (TailState и т.п.) объект меняется на месте, и снимок покажет последнее
 * значение — редактору оно и не нужно.
 */
class SpecialModDataReplay(
    startCapacity: Int,
    val specialModDataEntity: SpecialModDataEntity
) : EditorReplay {

    private var data = arrayOfNulls<SpecialModData>(startCapacity)
    private var size = 0

    private val tickStartIndices = IntArrayList(10)
    private val slotsInTick = IntArrayList(10)

    // Только массив ссылок: сами объекты общие с миром редактора (см. выше).
    override fun byteSize(): Long = data.size.toLong() * 4

    override fun reset() {
        data.fill(null, 0, size)
        size = 0
        tickStartIndices.clear()
        slotsInTick.clear()
    }

    override fun copy() {
        // По lastId, а не по числу живых: слоты могут идти с дырками от удалённых.
        val slots = specialModDataEntity.lastId + 1
        tickStartIndices.add(size)
        slotsInTick.add(slots)

        if (size + slots > data.size) data = data.copyOf((size + slots).coerceAtLeast(data.size * 2))
        System.arraycopy(specialModDataEntity.specialModData, 0, data, size, slots)
        size += slots
    }

    /** Данные слота [slot] на тике [tick]; null — тика или слота нет. */
    fun get(tick: Int, slot: Int): SpecialModData? {
        if (tick < 0 || tick >= tickStartIndices.size) return null
        if (slot < 0 || slot >= slotsInTick.getInt(tick)) return null
        return data[tickStartIndices.getInt(tick) + slot]
    }
}
