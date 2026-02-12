package io.github.some_example_name.old.world_logic.commands


sealed interface GameCommand {
    data class Spawn(val x: Float, val y: Float) : GameCommand
    data class MovePlayer(val dx: Float, val dy: Float, val cellId: Int) : GameCommand
}
