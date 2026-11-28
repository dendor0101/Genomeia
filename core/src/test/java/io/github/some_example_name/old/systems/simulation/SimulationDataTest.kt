package io.github.some_example_name.old.systems.simulation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimulationDataTest {

    /**
     * Пауза принадлежит экрану: диалог провала и окно уровня ставят её напрямую в
     * SimulationData, а уходя из симуляции её никто не снимает. Новый мир уровня собирается
     * через clear(), и он обязан начаться идущим — иначе следующий уровень из редактора
     * открывается стоящим на тике 0 (поток обновления при isPlay=false просто спит).
     */
    @Test
    fun `clear снимает паузу прошлого экрана в уровне`() {
        val data = SimulationData()
        data.isLevel = true
        data.isPlay = false

        data.clear()

        assertTrue(data.isPlay)
    }

    /**
     * Обычный мир — как до PR 38: рестарт на паузе остаётся на паузе, а BugReplay сам держит
     * мир (isPlay = false) и не должен получить его обратно идущим от сборки мира.
     */
    @Test
    fun `clear не трогает паузу обычного мира`() {
        val data = SimulationData()
        data.isPlay = false

        data.clear()

        assertFalse(data.isPlay)
    }
}
