package io.github.some_example_name.old.features.editor.dialog

import com.kotcrab.vis.ui.util.FloatDigitsOnlyFilter
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.cells.base.formulaType
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.editor.system.ActionDialogSystem
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visSelectBox
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextField
import kotlin.text.toFloatOrNull


fun VisTable.neuralCompose(actionSystem: ActionDialogSystem) {
    visLabel(bundle.get("button.inputSignals"), font = game.extraLargeFont) { left() }
    row()

    visTable ({ left().padBottom(32.dp()) }) {
        visLabel("a:", font = game.extraLargeFont)
        val a = actionSystem.action.a ?: actionSystem.clickedCell.actual?.a ?: throw Exception("a is null")
        visTextField(
            text = a.toString(),
            textFieldFilter = FloatDigitsOnlyFilter(true),
            onTextChange = { text -> text.toFloatOrNull()?.let { actionSystem.action = actionSystem.action.copy(a = it) } }
        ) { padRight(16.dp()) }

        visLabel("b:", font = game.extraLargeFont)
        val b = actionSystem.action.b ?: actionSystem.clickedCell.actual?.b ?: throw Exception("b is null")
        visTextField(
            text = b.toString(),
            textFieldFilter = FloatDigitsOnlyFilter(true),
            onTextChange = { text -> text.toFloatOrNull()?.let { actionSystem.action = actionSystem.action.copy(b = it) } }
        ) { padRight(16.dp()) }

        visLabel("c:", font = game.extraLargeFont)
        val c = actionSystem.action.c ?: actionSystem.clickedCell.actual?.c ?: throw Exception("c is null")
        visTextField(
            text = c.toString(),
            textFieldFilter = FloatDigitsOnlyFilter(true),
            onTextChange = { text -> text.toFloatOrNull()?.let { actionSystem.action = actionSystem.action.copy(c = it) } }
        )
    }

    row()

    visTable {
        val funActivation = actionSystem.action.funActivation ?: actionSystem.clickedCell.actual?.funActivation ?: throw Exception("funActivation is null")
        visSelectBox(
            items = formulaType,
            selectedIndex = funActivation,
            onChange = { _, formulaIndex ->
                actionSystem.action = actionSystem.action.copy(funActivation = formulaIndex)
            }
        )

        val isSum = actionSystem.action.isSum ?: actionSystem.clickedCell.actual?.isSum ?: throw Exception("isSum is null")
        visSelectBox(
            items = arrayOf(bundle.get("button.addition"), bundle.get("button.multiplication")),
            selectedIndex = if (isSum) 0 else 1,
            onChange = { _, sumOrMultiply ->
                actionSystem.action = actionSystem.action.copy(isSum = sumOrMultiply == 0)
            }
        )
    }

    row()
}
