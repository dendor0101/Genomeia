package io.github.some_example_name.old.features.editor.dialog

import io.github.some_example_name.old.core.ui.hint
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visSelectBox
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.systems.genomics.genome.Action


/** Излучатель и датчик феромонов: тип феромона. */
object PheromoneSection : CellEditorSection {

    override fun applyDefaults(action: Action) = action.copy(pheromoneType = 0)

    override fun clear(action: Action) = action.copy(pheromoneType = null)

    override fun VisTable.compose(dialog: CellDialog) {
        val actionSystem = dialog.actionSystem
        visTable {
            visLabel(text = bundle.get("label.pheromoneType")) { padRight(16.dp())}.hint(bundle.get("editor.hint.pheromone"))

            val pheromoneType = actionSystem.action.pheromoneType ?: actionSystem.clickedCell.actual?.pheromoneType ?: throw Exception("pheromoneType is null")
            visSelectBox(
                items = Array(32) { i ->
                    when (i) {
                        0 -> "Food - p$i"
                        11 -> "Stem grow - p$i"
                        18 -> "Dead cell - p$i"
                        else -> "p$i"
                    }
                },
                selectedIndex = pheromoneType.coerceIn(0, 31),
                onChange = { _, pheromoneId ->
                    actionSystem.action = actionSystem.action.copy(pheromoneType = pheromoneId)
                }
            ).hint(bundle.get("editor.hint.pheromone"))
        }
        row()
    }

    override fun describeMutation(actual: Action, mutate: Action, out: StringBuilder) {
        if (mutate.pheromoneType != null && mutate.pheromoneType != actual.pheromoneType) {
            out.append("Pheromone type: ${actual.pheromoneType} -> ${mutate.pheromoneType}\n")
        }
    }
}
