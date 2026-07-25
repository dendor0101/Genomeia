package io.github.some_example_name.old.systems.simulation

import io.github.some_example_name.old.core.WorldResizable

interface Simulation: WorldResizable {

    fun start()
    fun updateTick()
    fun stop()
    fun dispose()

    companion object {
        const val DELTA_SIM_TICK_TIME = 0.016666666f
    }
}
