package io.github.some_example_name.old.commands


sealed interface PlayerCommand {
    data class TouchDown(val x: Float, val y: Float, val isLeftButton: Boolean): PlayerCommand
    data class Drag(val x: Float, val y: Float, val dx: Float, val dy: Float): PlayerCommand
    class Tap(val x: Float, val y: Float, val isLeftButton: Boolean): PlayerCommand
    object StopDrag: PlayerCommand
}
