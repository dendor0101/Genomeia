package io.github.some_example_name.old.entities

import io.github.some_example_name.old.cells.ControllerData
import io.github.some_example_name.old.cells.DelayData
import io.github.some_example_name.old.cells.DelayState
import io.github.some_example_name.old.cells.EyeData
import io.github.some_example_name.old.cells.PheromoneEmitterState
import io.github.some_example_name.old.cells.ProducerState
import io.github.some_example_name.old.cells.SynapseState
import io.github.some_example_name.old.cells.TailState
import io.github.some_example_name.old.systems.maps.decodeState
import io.github.some_example_name.old.systems.maps.encodeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Свои данные клеток в сохранении мира: слоты и объекты на своих местах, дырки остаются дырками. */
class SpecialModDataEntityTest {

    @Test
    fun `данные клеток переживают сохранение мира`() {
        val saved = SpecialModDataEntity(4)
        val w = saved.addModData(ControllerData("W"))
        val removed = saved.addModData(ControllerData("1"))
        val space = saved.addModData(ControllerData("SPACE"))
        saved.deleteModData(removed)

        val loaded = SpecialModDataEntity(1)
        loaded.decodeState(saved.encodeState())

        assertEquals(ControllerData("W"), loaded.specialModData[w])
        assertNull(loaded.specialModData[removed])
        assertEquals(ControllerData("SPACE"), loaded.specialModData[space])
        assertEquals(14, (loaded.specialModData[space] as ControllerData).keyIndex)
        assertEquals(saved.aliveList.size, loaded.aliveList.size)
    }

    @Test
    fun `все виды данных клеток переживают сохранение, включая изменяемое состояние`() {
        val saved = SpecialModDataEntity(2)
        val all = listOf(
            EyeData(3, 2.5f), TailState(speed = 0.37f), ProducerState(reproductionRestriction = 42),
            PheromoneEmitterState(lastImpulse = 1f), ControllerData("UP"),
            SynapseState(weight = 0.3f, inputTick = 17, teacherTick = 21, miswired = true, savedColor = 0x7f00ff00),
            DelayData(ticks = 12, binary = true), DelayState.of(DelayData(ticks = 5)).apply { push(0.5f); push(2f) }
        )
        val slots = all.map(saved::addModData)
        // Состояние меняется на месте — в сохранение идёт текущее значение.
        (saved.specialModData[slots[1]] as TailState).speed = 0.5f

        val loaded = SpecialModDataEntity(1)
        loaded.decodeState(saved.encodeState())

        assertEquals(TailState(speed = 0.5f), loaded.specialModData[slots[1]])
        for (i in all.indices) assertEquals(saved.specialModData[slots[i]], loaded.specialModData[slots[i]])
    }
}
