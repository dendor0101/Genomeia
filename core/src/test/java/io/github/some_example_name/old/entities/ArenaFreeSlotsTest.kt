package io.github.some_example_name.old.entities

import io.github.some_example_name.old.systems.genomics.genome.BakedLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Свободные списки арены не должны раздавать запечённые слоты.
 *
 * Запечённый слот принадлежит конкретной клетке (связи) по раскладке генома и выдаётся ей
 * напрямую, мимо списка. Попади он в список — его получит и посторонняя клетка, и тогда
 * либо посторонняя ляжет на место живой запечённой (addAt: «арена выдала занятый индекс»),
 * либо запечённая, вернувшись, найдёт свой слот занятым.
 */
class ArenaFreeSlotsTest {

    /** Минимальная сущность: только выдача и возврат индексов, без своих полей. */
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
        // Ёмкость на одну клетку и одну связь больше раскладки: им достанется bump-хвост.
        organs.allocateArenas(organIndex = 0, layout = layout, maxCells = 3, maxLinks = 2)
        return Triple(organs, cells, links)
    }

    @Test
    fun regrownBakedCellKeepsItsSlotFromStrangers() {
        val (organs, cells, _) = world()

        val baked = organs.takeCellSlot(0, bakedA)
        cells.occupy(baked, 0)

        // Запечённая клетка умерла и выросла снова — тот же слот, по раскладке.
        organs.releaseCellSlot(0, baked)
        cells.free(baked)
        assertEquals(baked, organs.takeCellSlot(0, bakedA))
        cells.occupy(baked, 0)

        // Посторонняя клетка не должна получить слот живой запечённой.
        val stranger = organs.takeCellSlot(0, notBaked)
        assertNotEquals(baked, stranger)
        cells.occupy(stranger, 0)
    }

    @Test
    fun deadBakedCellSlotWaitsForItsOwner() {
        val (organs, cells, _) = world()

        val baked = organs.takeCellSlot(0, bakedA)
        cells.occupy(baked, 0)
        organs.releaseCellSlot(0, baked)
        cells.free(baked)

        // Пока запечённая мертва, посторонняя берёт хвост, а не её слот.
        val stranger = organs.takeCellSlot(0, notBaked)
        assertNotEquals(baked, stranger)
        cells.occupy(stranger, 0)

        assertEquals(baked, organs.takeCellSlot(0, bakedA))
        cells.occupy(baked, 0)
    }

    @Test
    fun recreatedBakedLinkKeepsItsSlotFromStrangers() {
        val (organs, _, links) = world()

        val baked = organs.takeLinkSlot(0, bakedA, bakedB)
        links.occupy(baked, 0)
        organs.releaseLinkSlot(0, baked)
        links.free(baked)

        // Связь пересоздана с другого конца — слот тот же.
        assertEquals(baked, organs.takeLinkSlot(0, bakedB, bakedA))
        links.occupy(baked, 0)

        val stranger = organs.takeLinkSlot(0, bakedA, notBaked)
        assertNotEquals(baked, stranger)
        links.occupy(stranger, 0)
    }

    @Test
    fun tailSlotsAreStillReused() {
        val (organs, cells, _) = world()

        val tail = organs.takeCellSlot(0, notBaked)
        cells.occupy(tail, 0)
        organs.releaseCellSlot(0, tail)
        cells.free(tail)

        // Хвост за раскладкой — обычный свободный слот: без возврата ёмкость кончилась бы.
        assertEquals(tail, organs.takeCellSlot(0, notBaked))
    }
}
