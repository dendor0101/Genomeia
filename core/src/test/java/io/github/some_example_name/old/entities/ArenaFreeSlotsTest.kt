package io.github.some_example_name.old.entities

import io.github.some_example_name.old.systems.genomics.genome.BakedLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Слоты клеток и связей в арене — только по раскладке.
 *
 * Свободных списков и курсора сверх раскладки у клеток и связей больше нет: тело растёт
 * только по запечённому геному, и каждая клетка (связь) ложится в свой слот. Умершая и
 * отросшая получает тот же самый, посторонней в арене не бывает.
 */
class ArenaFreeSlotsTest {

    private class Slots : Entity(16) {
        fun occupy(index: Int, owner: Int) = addAt(index, owner)
        fun free(index: Int) = delete(index)
        override fun onCopy() {}
        override fun onPaste() {}
        override fun onClear(bound: Int) {}
        override fun onResize(oldMax: Int) {}
    }

    private val bakedA = 10
    private val bakedB = 11
    private val notBaked = 99

    private val layout = BakedLayout(
        cellGenomeIdsInSlotOrder = listOf(bakedA, bakedB),
        linkPairsInSlotOrder = listOf(bakedA, bakedB)
    )

    private fun world(): Triple<OrganEntity, Slots, Slots> {
        val organs = OrganEntity(4)
        val cells = Slots()
        val particles = Slots()
        val links = Slots()
        organs.bindEntities(cells, particles, links)
        organs.addOrgan(genomeIndex = 0, genomeSize = 1)
        organs.allocateArenas(organIndex = 0, layout = layout)
        return Triple(organs, cells, links)
    }

    @Test
    fun arenaIsExactlyTheLayout() {
        val (organs, _, _) = world()
        assertEquals(2, organs.cellArenaCapacity[0])
        assertEquals(1, organs.linkArenaCapacity[0])
        assertEquals(organs.cellArenaBase[0] + 2, organs.cellArenaEnd(0))
        assertEquals(organs.linkArenaBase[0] + 1, organs.linkArenaEnd(0))
    }

    @Test
    fun regrownCellAndRecreatedLinkTakeTheirOwnSlots() {
        val (organs, cells, links) = world()

        val cell = organs.takeCellSlot(0, bakedA)
        assertEquals(organs.cellArenaBase[0], cell)
        cells.occupy(cell, 0)
        cells.free(cell)
        assertEquals(cell, organs.takeCellSlot(0, bakedA))

        val link = organs.takeLinkSlot(0, bakedA, bakedB)
        links.occupy(link, 0)
        links.free(link)
        // С другого конца — тот же слот.
        assertEquals(link, organs.takeLinkSlot(0, bakedB, bakedA))
    }

    @Test
    fun cellOrLinkOutsideTheLayoutHasNoSlot() {
        val (organs, _, _) = world()
        assertEquals(-1, organs.takeCellSlot(0, notBaked))
        assertEquals(-1, organs.takeLinkSlot(0, bakedA, notBaked))
    }

    @Test
    fun unbakedGenomeGetsNoArena() {
        val organs = OrganEntity(4)
        organs.bindEntities(Slots(), Slots(), Slots())
        organs.addOrgan(genomeIndex = 0, genomeSize = 1)
        assertFailsWith<IllegalStateException> { organs.allocateArenas(organIndex = 0, layout = null) }
    }
}
