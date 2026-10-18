package io.github.some_example_name.old.features.editor.dialog

import io.github.some_example_name.old.core.ui.hint
import com.kotcrab.vis.ui.util.FloatDigitsOnlyFilter
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.cells.base.Formulas
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visSelectBox
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextField
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.cellPalette
import io.github.some_example_name.old.systems.genomics.genome.Action
import kotlin.text.toFloatOrNull

/** Обычный нейрон: коэффициенты a, b, c, формула активации и способ сложения входов. */
object NeuralSection : CellEditorSection {

    override fun applyDefaults(action: Action) = action.copy(
        funActivation = cellPalette.defaultFormula().key,
        a = 1f,
        b = 0f,
        c = 0f,
        isSum = true
    )

    override fun clear(action: Action) = action.copy(
        funActivation = null,
        a = null,
        b = null,
        c = null,
        isSum = null
    )

    override fun VisTable.compose(dialog: CellDialog) {
        val actionSystem = dialog.actionSystem
        visLabel(bundle.get("button.inputSignals"), font = game.extraLargeFont) { left() }.hint(bundle.get("editor.hint.combineInputs"))
        row()

        visTable ({ left().padBottom(32.dp()) }) {
            visLabel("a:", font = game.extraLargeFont).hint(bundle.get("editor.hint.coefA"))
            val a = actionSystem.action.a ?: actionSystem.clickedCell.actual?.a ?: throw Exception("a is null")
            visTextField(
                text = a.toString(),
                textFieldFilter = FloatDigitsOnlyFilter(true),
                onTextChange = { text -> text.toFloatOrNull()?.let { actionSystem.action = actionSystem.action.copy(a = it) } }
            ) { padRight(16.dp()) }.hint(bundle.get("editor.hint.coefA"))

            visLabel("b:", font = game.extraLargeFont).hint(bundle.get("editor.hint.coefB"))
            val b = actionSystem.action.b ?: actionSystem.clickedCell.actual?.b ?: throw Exception("b is null")
            visTextField(
                text = b.toString(),
                textFieldFilter = FloatDigitsOnlyFilter(true),
                onTextChange = { text -> text.toFloatOrNull()?.let { actionSystem.action = actionSystem.action.copy(b = it) } }
            ) { padRight(16.dp()) }.hint(bundle.get("editor.hint.coefB"))

            visLabel("c:", font = game.extraLargeFont).hint(bundle.get("editor.hint.coefC"))
            val c = actionSystem.action.c ?: actionSystem.clickedCell.actual?.c ?: throw Exception("c is null")
            visTextField(
                text = c.toString(),
                textFieldFilter = FloatDigitsOnlyFilter(true),
                onTextChange = { text -> text.toFloatOrNull()?.let { actionSystem.action = actionSystem.action.copy(c = it) } }
            ).hint(bundle.get("editor.hint.coefC"))
        }

        row()

        visTable {
            val funActivation = actionSystem.action.funActivation ?: actionSystem.clickedCell.actual?.funActivation ?: throw Exception("funActivation is null")
            // Из палитры редактора; формула этой клетки видна, даже если скрыта.
            val formulas = cellPalette.formulaChoices(current = funActivation)
            visSelectBox(
                items = formulas.map { it.label }.toTypedArray(),
                // Формулы нет в списке — геном от мода, которого нет; показывается первая,
                // а в геноме остаётся прежняя, пока игрок не выберет другую.
                selectedIndex = formulas.indexOfFirst { it.key == funActivation }.coerceAtLeast(0),
                onChange = { _, choice ->
                    actionSystem.action = actionSystem.action.copy(funActivation = formulas[choice].key)
                }
            ).hint(bundle.get("editor.hint.formula"))

            val isSum = actionSystem.action.isSum ?: actionSystem.clickedCell.actual?.isSum ?: throw Exception("isSum is null")
            visSelectBox(
                items = arrayOf(bundle.get("button.addition"), bundle.get("button.multiplication")),
                selectedIndex = if (isSum) 0 else 1,
                onChange = { _, sumOrMultiply ->
                    actionSystem.action = actionSystem.action.copy(isSum = sumOrMultiply == 0)
                }
            ).hint(bundle.get("editor.hint.combineInputs"))
        }

        row()
    }

    override fun describeMutation(actual: Action, mutate: Action, out: StringBuilder) = with(mutate) {
        if (funActivation != null && funActivation != actual.funActivation) {
            val from = actual.funActivation?.let(::formulaLabel)
            out.append("Activation formula: $from -> ${formulaLabel(funActivation)}\n")
        }
        if (a != null && a != actual.a) out.append("a: ${actual.a} -> $a\n")
        if (b != null && b != actual.b) out.append("b: ${actual.b} -> $b\n")
        if (c != null && c != actual.c) out.append("c: ${actual.c} -> $c\n")
        if (isSum != null && isSum != actual.isSum) {
            val fromText = when (actual.isSum) {
                true -> "Addition"
                false -> "Multiplication"
                null -> "null"
            }
            val toText = if (isSum) "Addition\n" else "Multiplication\n"
            out.append("isSum: $fromText -> $toText")
        }
    }

    /** Подпись формулы; формулы нет в реестре (мод) — её ключ. */
    private fun formulaLabel(key: String): String =
        Formulas.indexOfKey(key).let { if (it == -1) key else Formulas.all()[it].label }
}
