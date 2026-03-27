package io.github.some_example_name.old.commands


sealed interface PlayerCommand {
    data class SpawnCell(val x: Float, val y: Float) : PlayerCommand
    data class SpawnParticles(val x: Float, val y: Float) : PlayerCommand
    data class DragCell(val dx: Float, val dy: Float, val cellId: Int) : PlayerCommand
}
