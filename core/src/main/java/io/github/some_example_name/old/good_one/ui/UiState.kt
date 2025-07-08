package io.github.some_example_name.old.good_one.ui

import io.github.some_example_name.old.good_one.editor.CellCopy

sealed interface UiState

data object Play: UiState
sealed interface Pause: UiState {
    data object Unselected: Pause
    class Selected(val cell: Pair<String, CellCopy>): Pause
}


