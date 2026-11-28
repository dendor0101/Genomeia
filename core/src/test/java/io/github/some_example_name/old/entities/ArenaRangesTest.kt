package io.github.some_example_name.old.entities

import io.github.some_example_name.old.systems.genomics.genome.BakedLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Свободные диапазоны сущности: арены умерших организмов выдаются заново. */
class ArenaRangesTest {

    /** Раскладка из [cells] клеток с номерами 0..cells-1 и [links] связей 0–k. */
    private fun layoutOf(cells: Int, links: Int) = BakedLayout(
        cellGenomeIdsInSlotOrder = List(cells) { it },
        linkPairsInSlotOrder = (1..links).flatMap { listOf(0, it) }
    )

    private class Slots : Entity(16) {
        fun occupy(index: Int, owner: Int = -1) = addAt(index, owner)
        fun free(index: Int) = delete(index)
        fun plain() = add()
        override fun onCopy() {}
        override fun onPaste() {}
        override fun onClear(bound: Int) {}
        override fun onResize(oldMax: Int) {}
    }

    @Test
    fun releasedRangeIsReusedInsteadOfGrowing() {
        val slots = Slots()
        val a = slots.reserveRange(10)
        val b = slots.reserveRange(10)
        val top = slots.lastId

        slots.releaseRange(a, 10)
        assertEquals(a, slots.reserveRange(10))
        assertEquals(top, slots.lastId)
        assertEquals(10, b - a)
    }

    @Test
    fun smallestFittingRangeWinsAndTiesGoToLowerAddress() {
        val slots = Slots()
        val big = slots.reserveRange(30)
        slots.reserveRange(1) // разделитель, чтобы куски не склеились
        val small1 = slots.reserveRange(12)
        slots.reserveRange(1)
        val small2 = slots.reserveRange(12)
        slots.reserveRange(1)

        slots.releaseRange(big, 30)
        slots.releaseRange(small2, 12)
        slots.releaseRange(small1, 12)

        // 10 помещается во все три — берётся меньший, из равных меньший по адресу.
        assertEquals(small1, slots.reserveRange(10))
        assertEquals(small2, slots.reserveRange(12))
        assertEquals(big, slots.reserveRange(30))
    }

    @Test
    fun neighbouringRangesMerge() {
        val slots = Slots()
        val a = slots.reserveRange(5)
        val b = slots.reserveRange(5)
        val c = slots.reserveRange(5)
        slots.reserveRange(1)

        slots.releaseRange(a, 5)
        slots.releaseRange(c, 5)
        slots.releaseRange(b, 5)

        assertEquals(15, slots.freeRangeSlots())
        assertEquals(a, slots.reserveRange(15))
        assertEquals(0, slots.freeRangeSlots())
    }

    @Test
    fun liveSquatterStaysOutOfFreeList() {
        val slots = Slots()
        val base = slots.reserveRange(10)
        // Чужой жилец посреди арены (старое сохранение: субстанция в слоте частицы клетки).
        slots.occupy(base + 4)

        slots.releaseRange(base, 10)
        assertEquals(9, slots.freeRangeSlots())

        // Ни один из выданных потом слотов не совпадает с жильцом.
        val first = slots.reserveRange(4)
        val second = slots.reserveRange(5)
        assertEquals(base, first)
        assertEquals(base + 5, second)
    }

    @Test
    fun deadOrganismGivesItsArenasBack() {
        val organs = OrganEntity(4)
        val cells = Slots()
        val particles = Slots()
        val links = Slots()
        organs.bindEntities(cells, particles, links)

        val first = organs.addOrgan(genomeIndex = 0, genomeSize = 1)
        organs.allocateArenas(first, layout = layoutOf(20, 40))
        val cellBase = organs.cellArenaBase[first]
        val particleBase = organs.particleArenaBase[first]
        val linkBase = organs.linkArenaBase[first]
        val cellTop = cells.lastId

        // Клетка родилась и умерла — организм пуст и удаляется в конце тика.
        val slot = organs.takeCellSlot(first, cellGenomeId = 0)
        cells.occupy(slot, first)
        particles.occupy(organs.particleIndexOfCell(first, slot), first)
        organs.cellAdded(first)
        organs.cellRemoved(first)
        cells.free(slot)
        particles.free(organs.particleIndexOfCell(first, slot))
        organs.deleteEmptiedOrgans()
        assertTrue(!organs.hasArena(first))

        // Частица с владельцем-ареной не попала в общий стек.
        assertEquals(-1, particles.deadStack.indexOf(particleBase))

        val second = organs.addOrgan(genomeIndex = 0, genomeSize = 1)
        organs.allocateArenas(second, layout = layoutOf(20, 40))
        assertEquals(cellBase, organs.cellArenaBase[second])
        assertEquals(particleBase, organs.particleArenaBase[second])
        assertEquals(linkBase, organs.linkArenaBase[second])
        assertEquals(cellTop, cells.lastId)
    }

    @Test
    fun neuralArenaReusesFreedSlotsAndReturnsOnDeath() {
        val organs = OrganEntity(4)
        val cells = Slots()
        val particles = Slots()
        val links = Slots()
        val neural = Slots()
        organs.bindEntities(cells, particles, links, neural)

        val organ = organs.addOrgan(genomeIndex = 0, genomeSize = 1)
        organs.allocateArenas(organ, layout = layoutOf(4, 4), maxNeuralLinks = 3)
        assertTrue(organs.hasNeuralArena(organ))
        val base = organs.neuralArenaBase[organ]

        // Пик — три одновременно: четвёртой места нет, это путь в общий пул.
        val a = organs.takeNeuralSlot(organ)
        val b = organs.takeNeuralSlot(organ)
        val c = organs.takeNeuralSlot(organ)
        assertEquals(listOf(base, base + 1, base + 2), listOf(a, b, c))
        assertEquals(-1, organs.takeNeuralSlot(organ))

        // Перевязка: удалённая уступает слот следующей того же организма.
        organs.releaseNeuralSlot(organ, b)
        assertEquals(b, organs.takeNeuralSlot(organ))

        // Организм без генома с пиком — арены нет.
        val old = organs.addOrgan(genomeIndex = 0, genomeSize = 1)
        organs.allocateArenas(old, layout = layoutOf(4, 4))
        assertTrue(!organs.hasNeuralArena(old))

        // Смерть: арена нейросвязей возвращается и выдаётся следующему.
        organs.cellAdded(organ)
        organs.cellRemoved(organ)
        organs.deleteEmptiedOrgans()
        val next = organs.addOrgan(genomeIndex = 0, genomeSize = 1)
        organs.allocateArenas(next, layout = layoutOf(4, 4), maxNeuralLinks = 3)
        assertEquals(base, organs.neuralArenaBase[next])
    }

    /** Сущность с пулом вне арен — как частицы субстанций рядом с частицами клеток. */
    private class Pooled : Entity(16) {
        override val poolBlockSize: Int get() = 4
        fun plain() = add()
        fun free(index: Int) = delete(index)
        override fun onCopy() {}
        override fun onPaste() {}
        override fun onClear(bound: Int) {}
        override fun onResize(oldMax: Int) {}
    }

    @Test
    fun poolTakesWholeBlocksAndDoesNotInterleaveArenas() {
        val pooled = Pooled()
        val first = pooled.plain()
        val arena = pooled.reserveRange(10)
        // Блок пула забронирован целиком: остальные три его индекса не достаются арене.
        assertEquals(first + 4, arena)
        assertEquals(listOf(first + 1, first + 2, first + 3), List(3) { pooled.plain() })
        // Блок кончился — следующий за ареной, а не между аренами.
        assertEquals(arena + 10, pooled.plain())
        // Умерший из пула переиспользуется раньше нового блока.
        pooled.free(first + 1)
        assertEquals(first + 1, pooled.plain())
    }

    @Test
    fun plainAddDoesNotLandInsideFreeRange() {
        val slots = Slots()
        val base = slots.reserveRange(10)
        slots.reserveRange(1)
        slots.releaseRange(base, 10)

        // Общий аллокатор (субстанции) свободных кусков не трогает: идёт выше.
        assertTrue(slots.plain() > base + 9)
        assertEquals(base, slots.reserveRange(10))
    }
}
