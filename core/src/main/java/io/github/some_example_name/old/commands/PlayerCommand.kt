package io.github.some_example_name.old.commands

import com.badlogic.gdx.math.MathUtils
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.DISimulationContainer.simulationData


sealed interface PlayerCommand {
    data class TouchDown(val x: Float, val y: Float, val isLeftButton: Boolean): PlayerCommand
    data class Drag(val x: Float, val y: Float, val dx: Float, val dy: Float): PlayerCommand
    class Tap(val x: Float, val y: Float, val isLeftButton: Boolean = true, val genomeIndex: Int = simulationData.currentGenomeIndex): PlayerCommand//MathUtils.random(0f, MathUtils.PI2), val genomeIndex: Int = simulationData.currentGenomeIndex): PlayerCommand //добавил параметр, ну я просто не знаю как отличать replay userCommand от обычных :(
    object StopDrag: PlayerCommand
}
