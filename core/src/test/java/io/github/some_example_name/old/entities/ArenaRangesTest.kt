package io.github.some_example_name.old.entities

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Свободные диапазоны сущности: арены умерших организмов выдаются заново. */
class ArenaRangesTest {

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
        organs.allocateArenas(first, layout = null, maxCells = 20, maxLinks = 40)
        val cellBase = organs.cellArenaBase[first]
        val particleBase = organs.particleArenaBase[first]
        val linkBase = organs.linkArenaBase[first]
        val cellTop = cells.lastId

        // Клетка родилась и умерла — организм пуст и удаляется в конце тика.
        val slot = organs.takeCellSlot(first, cellGenomeId = 0)
        cells.occupy(slot, first)
        particles.occupy(organs.particleIndexOfCell(first, slot), first)
        organs.cellAdded(first)
        organs.releaseCellSlot(first, slot)
        organs.cellRemoved(first)
        cells.free(slot)
        particles.free(organs.particleIndexOfCell(first, slot))
        organs.deleteEmptiedOrgans()
        assertTrue(!organs.hasArena(first))

        // Частица с владельцем-ареной не попала в общий стек.
        assertEquals(-1, particles.deadStack.indexOf(particleBase))

        val second = organs.addOrgan(genomeIndex = 0, genomeSize = 1)
        organs.allocateArenas(second, layout = null, maxCells = 20, maxLinks = 40)
        assertEquals(cellBase, organs.cellArenaBase[second])
        assertEquals(particleBase, organs.particleArenaBase[second])
        assertEquals(linkBase, organs.linkArenaBase[second])
        assertEquals(cellTop, cells.lastId)
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
