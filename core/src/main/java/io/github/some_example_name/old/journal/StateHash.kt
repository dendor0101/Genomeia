package io.github.some_example_name.old.journal

import io.github.some_example_name.old.entities.Entity
import io.github.some_example_name.old.entities.EntityState
import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlin.reflect.KProperty0

/**
 * Хэш состояния мира — то, что делает повтор бага ПРОВЕРЯЕМЫМ.
 *
 * Одинаковые команды ещё не значат одинаковый мир: если в симуляции есть хоть одна
 * недетерминированная операция (порядок потоков, случайность мимо seed), повтор тихо
 * уйдёт в сторону, а разработчик будет чинить не тот баг. Поэтому раз в
 * SimulationSystem.STATE_HASH_PERIOD тиков мир сворачивается в хэши, запись хранит их, а
 * повтор считает свои и сверяет. Первое несовпадение — первый тик, где он разошёлся.
 *
 * ЧТО ВХОДИТ
 * ----------
 * ВСЁ состояние мира, причём тем же списком, что пишет сохранение мира: каждая сущность
 * сама перечисляет свои поля (Entity.persistFields) — частицы, клетки, связи, нейроны,
 * нейросвязи, феромоны, органы, свои данные клеток (глаза, хвосты…), субстанции. Добавили поле
 * в сохранение — оно попало и в хэш, отдельно помнить не надо. Плюс то, что живёт вне
 * сущностей, но решает следующие тики:
 *  - SimulationData: номер тика, время, выбранный геном, выбранная клетка;
 *  - ввод игрока: схваченная частица, точка касания, генератор случайностей команд;
 *  - порядок частиц в пространственной сетке: по нему идёт перебор столкновений.
 *
 * Значения — побитово (floatToRawIntBits), массивы — по всем слотам до lastId.
 *
 * ПО ЧАСТЯМ
 * ---------
 * Хэш считается отдельно для каждой части ([partNames]), общий — из них. При расхождении
 * повтор называет, ЧТО именно разошлось: «NeuralLinkEntity» сразу говорит, где искать.
 *
 * [VERSION] растёт, когда меняется сама формула: хэши старой формулы из старых .bug
 * не сверяются, иначе каждый такой повтор «расходился» бы на первом же тике.
 *
 * Стоимость — проход по всем массивам мира, однопоточно в конце тика; раз в секунду
 * игрового времени это незаметно.
 */
object StateHash {

    /**
     * 3 — в хэш вошли свои данные клеток (SpecialModDataEntity.specialModData).
     * 4 — EyeEntity, TailEntity, ProducerEntity и PheromoneEmitterEntity влились в них:
     *     частей хэша стало меньше.
     * 5 — формула remember держит память на нулевом входе (Formula.kt): формула хэша та же,
     *     но мир с этой формулой считается иначе, и старые хэши сверять нельзя.
     * 6 — у каждой сущности в хэш вошли свободные диапазоны (Entity.freeRanges): арены
     *     умерших организмов возвращаются и выдаются заново.
     * 7 — у организма в хэш вошла арена нейросвязей (OrganEntity.neuralArena*), а нейрофаза
     *     суммирует сигналы по аренам, а не по общему списку.
     * 8 — в часть SimulationData вошли параметры стресса (PlayerCommand.SetStress).
     */
    const val VERSION = 8

    class Result(val hash: Long, val parts: LongArray)

    /** Имена частей — в том порядке, в каком идут хэши в [Result.parts]. */
    fun partNames(entities: List<Entity>): List<String> =
        entities.map { it::class.simpleName ?: "Entity" } + listOf(SIMULATION, INPUT, GRID)

    fun compute(
        entities: List<Entity>,
        simulation: (Long) -> Long,
        input: (Long) -> Long,
        grid: (Long) -> Long
    ): Result {
        val parts = LongArray(entities.size + 3)
        entities.forEachIndexed { i, entity ->
            val state = HashingState(slots = entity.lastId + 1)
            entity.saveState(state)
            parts[i] = state.hash
        }
        parts[entities.size] = simulation(Fnv.OFFSET)
        parts[entities.size + 1] = input(Fnv.OFFSET)
        parts[entities.size + 2] = grid(Fnv.OFFSET)

        var total = Fnv.int(Fnv.OFFSET, VERSION)
        for (part in parts) total = Fnv.long(total, part)
        return Result(total, parts)
    }

    /** Имена разошедшихся частей. */
    fun diff(names: List<String>, expected: LongArray, actual: LongArray): List<String> =
        names.indices.filter { it < expected.size && it < actual.size && expected[it] != actual[it] }.map { names[it] }

    private const val SIMULATION = "SimulationData"
    private const val INPUT = "PlayerInput"
    private const val GRID = "Grid"

    /**
     * Сохранение мира наоборот: вместо записи поля в файл — в хэш. Имя поля входит в хэш,
     * чтобы два соседних массива, поменявшиеся местами, не дали тот же результат.
     */
    private class HashingState(private val slots: Int) : EntityState {
        var hash = Fnv.OFFSET
            private set

        override val isReading = false

        override fun ints(field: KProperty0<IntArray>, perSlot: Int) {
            val array = field.get()
            mixName(field.name)
            val count = minOf(array.size, slots * perSlot)
            for (i in 0 until count) hash = Fnv.word(hash, array[i])
        }

        override fun floats(field: KProperty0<FloatArray>) {
            val array = field.get()
            mixName(field.name)
            val count = minOf(array.size, slots)
            for (i in 0 until count) hash = Fnv.word(hash, java.lang.Float.floatToRawIntBits(array[i]))
        }

        override fun booleans(field: KProperty0<BooleanArray>) {
            val array = field.get()
            mixName(field.name)
            val count = minOf(array.size, slots)
            for (i in 0 until count) hash = Fnv.word(hash, if (array[i]) 1 else 0)
        }

        override fun bytes(field: KProperty0<ByteArray>) {
            val array = field.get()
            mixName(field.name)
            val count = minOf(array.size, slots)
            for (i in 0 until count) hash = Fnv.word(hash, array[i].toInt())
        }

        override fun intList(field: KProperty0<IntArrayList>) {
            val list = field.get()
            mixName(field.name)
            hash = Fnv.word(hash, list.size)
            for (i in 0 until list.size) hash = Fnv.word(hash, list.getInt(i))
        }

        override fun int(name: String, value: Int): Int {
            mixName(name)
            hash = Fnv.word(hash, value)
            return value
        }

        override fun packed(name: String, write: () -> IntArray, read: (IntArray) -> Unit) {
            val values = write()
            mixName(name)
            hash = Fnv.word(hash, values.size)
            for (v in values) hash = Fnv.word(hash, v)
        }

        private fun mixName(name: String) {
            for (c in name) hash = Fnv.word(hash, c.code)
        }
    }
}
