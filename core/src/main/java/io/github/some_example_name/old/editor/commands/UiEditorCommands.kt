package io.github.some_example_name.old.editor.commands

sealed interface UiEditorCommands

object PrevStageButtonTap: UiEditorCommands
object NextStageButtonTap: UiEditorCommands
object PrevTickButtonTap: UiEditorCommands
class NextTickButtonClamped(val isFinish: Boolean): UiEditorCommands
object NextTickButtonTap: UiEditorCommands
object CtrlZ: UiEditorCommands
object CtrlY: UiEditorCommands

class PanScreen(
    val x: Float,
    val y: Float,
    val deltaX: Float,
    val deltaY: Float
): UiEditorCommands

object FlingScreen: UiEditorCommands

class TapScreen(
    val x: Float,
    val y: Float,
    val isLeft: Boolean
): UiEditorCommands

class TimeSlider(
    val value: Int,
    val isDragging: Boolean
): UiEditorCommands

object GoToEndOfTimeLine: UiEditorCommands

//TODO dialog commands
