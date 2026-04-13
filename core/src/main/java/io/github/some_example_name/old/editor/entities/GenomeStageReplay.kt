package io.github.some_example_name.old.editor.entities

import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.ParticleEntity
import it.unimi.dsi.fastutil.ints.IntArrayList

class ReplayEntity(
    startCapacity: Int,
    val particleEntity: ParticleEntity,
    val cellEntity: CellEntity
) {
    var capacity = startCapacity
    var size = 0

    var x = FloatArray(startCapacity)
    var y = FloatArray(startCapacity)
    var energy = FloatArray(startCapacity)
    var color = IntArray(startCapacity)
    var cellType = ByteArray(startCapacity)

    val replayCellsCounterInTick = IntArrayList(10)
    val tickStartIndices = IntArrayList(10)   // ← НОВОЕ: стартовые индексы для каждого тика

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity > capacity) {
            val newCapacity = minCapacity.coerceAtLeast(capacity * 2)
            x = x.copyOf(newCapacity)
            y = y.copyOf(newCapacity)
            energy = energy.copyOf(newCapacity)
            color = color.copyOf(newCapacity)
            cellType = cellType.copyOf(newCapacity)
            capacity = newCapacity
        }
    }

    fun copy() {
        val cellsAmount = particleEntity.aliveList.size
//        println("copy ${cellsAmount}")

        // Запоминаем количество клеток и стартовую позицию этого тика
        replayCellsCounterInTick.add(cellsAmount)
        tickStartIndices.add(size)                    // ← сохраняем начало текущего тика

        ensureCapacity(size + cellsAmount)

        System.arraycopy(particleEntity.x, 0, x, size, cellsAmount)
        System.arraycopy(particleEntity.y, 0, y, size, cellsAmount)
        System.arraycopy(cellEntity.energy, 0, energy, size, cellsAmount)
        System.arraycopy(particleEntity.color, 0, color, size, cellsAmount)
        System.arraycopy(cellEntity.cellType, 0, cellType, size, cellsAmount)

        size += cellsAmount
    }

    /** Обход ВСЕХ клеток ВСЕХ тиков (как было раньше) */
    inline fun forEach(action: (x: Float, y: Float, energy: Float, color: Int, cellType: Byte) -> Unit) {
        var pos = 0
        val numTicks = replayCellsCounterInTick.size
        for (tick in 0 until numTicks) {
            val count = replayCellsCounterInTick.getInt(tick)
            for (i in 0 until count) {
                action(x[pos], y[pos], energy[pos], color[pos], cellType[pos])
                pos++
            }
        }
    }

    /** Обход по тикам (диапазонами) — удобно для воспроизведения */
    inline fun forEachTick(action: (tick: Int, start: Int, count: Int) -> Unit) {
        var start = 0
        val numTicks = replayCellsCounterInTick.size
        for (tick in 0 until numTicks) {
            val count = replayCellsCounterInTick.getInt(tick)
            action(tick, start, count)
            start += count
        }
    }

    /**
     * НОВЫЙ МЕТОД: итерация ТОЛЬКО по клеткам ОДНОГО выбранного тика.
     * Максимально быстрый — прямой доступ к массивам без лишних вычислений.
     *
     * Пример использования:
     * replay.forEachInTick(currentTick) { x, y, energy, color, cellType ->
     *     // отрисовка или обработка одной клетки
     * }
     */
    inline fun forEachInTick(tick: Int, action: (x: Float, y: Float, energy: Float, color: Int, cellType: Byte) -> Unit) {
        if (tick < 0 || tick >= tickStartIndices.size) return

        val start = tickStartIndices.getInt(tick)
        val count = replayCellsCounterInTick.getInt(tick)
        val end = start + count
//        println("lol $count")

        for (i in start until end) {
            action(x[i], y[i], energy[i], color[i], cellType[i])
        }
    }

    /** Полезный геттер — сколько тиков уже сохранено */
    fun getTickCount(): Int = replayCellsCounterInTick.size
}
