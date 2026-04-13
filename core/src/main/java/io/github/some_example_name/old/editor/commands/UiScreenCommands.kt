package io.github.some_example_name.old.editor.commands

import io.github.some_example_name.old.genome_editor_deprecated.EditorCell

sealed interface UiScreenCommands

class ShowDivideDialog(
    clickedCell: EditorCell,
    clickedIndex: Int,
    newDividedCellPosition: Pair<Float, Float>
): UiScreenCommands

class ShowMutateDialog(
    clickedCell: EditorCell,
    clickedIndex: Int
): UiScreenCommands
