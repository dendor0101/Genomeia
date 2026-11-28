package io.github.some_example_name.old.editor.system

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.cellList
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.cellPalette
import io.github.some_example_name.old.editor.entities.EditorCell
import io.github.some_example_name.old.features.editor.dialog.ActionDialogType
import io.github.some_example_name.old.systems.genomics.genome.Action
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Состояние диалога клетки: черновик действия [action] и клетка, по которой кликнули.
 *
 * О конкретных типах клеток здесь ничего нет: что показать и какие поля сбросить при
 * смене типа, решают секции клетки (Cell.editorSections, см. CellEditorSection).
 */
class ActionDialogSystem(
    actionDialogType: ActionDialogType,
    newDividedCellPosition: Pair<Float, Float>?,
    val clickedCell: EditorCell,
) {

    var action = when(actionDialogType) {
        // Первая клетка палитры: обычно лист, но уровень может его и не разрешать.
        ActionDialogType.DIVIDE -> newCellAction(cellPalette.actionCellChoices(cellList, current = null).first())
        ActionDialogType.MUTATION -> clickedCell.mutate?.copy() ?: Action()
        ActionDialogType.CHANGE_REMOVE -> clickedCell.divide?.copy() ?: throw Exception("clickedCell.divide is null")
    }

    val angleParent = when(actionDialogType) {
        ActionDialogType.DIVIDE -> {
            if (newDividedCellPosition != null) {
                val dx = newDividedCellPosition.first - clickedCell.x
                val dy = newDividedCellPosition.second - clickedCell.y

                val len = sqrt(dx * dx + dy * dy)

                val angleCos = dx / len
                val angleSin = dy / len
                atan2(angleSin, angleCos)
            } else throw Exception("newDividedCellPosition is null")
        }
        ActionDialogType.MUTATION -> clickedCell.angleToParent
        ActionDialogType.CHANGE_REMOVE -> clickedCell.angleToParent
    }

    val angleDirected get() = action.angleDirected ?: clickedCell.angleDirected ?: throw Exception("angleDirected is null")

    /** Ключ типа клетки: выбранный в диалоге или текущий. */
    val cellType: String get() = action.cellType ?: clickedCell.actual?.cellType ?: throw Exception("cellType is null")

    /** Тип клетки, выбранный в диалоге. */
    val cell: Cell get() = cellList.byKey(cellType)

    val isDirected: Boolean get() = cell.isDirected

    fun getCellColor(): Color = cell.defaultColor.cpy()

    /**
     * Тип клетки сменили в диалоге: цвет — по умолчанию нового типа, поля секций, которых у
     * нового типа нет, сбрасываются, а секции нового типа ставят свои значения по умолчанию.
     * Секции, общие для обоих типов (нейрон -> глаз), свои поля сохраняют.
     */
    fun setUpDefaultActionData(fromCellType: String, toCellType: String) {
        val from = cellList.byKey(fromCellType)
        val to = cellList.byKey(toCellType)

        action = action.copy(
            cellType = toCellType,
            color = to.defaultColor.cpy()
        )

        val fromSections = from.editorSections()
        val toSections = to.editorSections()
        for (section in fromSections) if (section !in toSections) action = section.clear(action)
        for (section in toSections) if (section !in fromSections) action = section.applyDefaults(action)

        // Свои данные клетки в геноме (Action.special) — по их типу, а не по секциям: у
        // клетки мода данные могут быть и без своей секции в диалоге.
        if (from.defaultGenomeData?.javaClass != to.defaultGenomeData?.javaClass) {
            action = action.copy(special = to.defaultGenomeData)
        }
    }

    /** Формирование списка изменений при мутации*/
    fun makeMutateList(): String {
        val text = StringBuilder()
        val actual = clickedCell.actual ?: return ""
        val mutate = clickedCell.mutate ?: return ""

        // Мутация без смены типа хранит cellType = null — тип остаётся текущим.
        val actualCellType = actual.cellType
        val resultCellType = mutate.cellType ?: actualCellType
        if (resultCellType != null && actualCellType != null && resultCellType != actualCellType) {
            text.append("Cell type: ${displayName(actualCellType)} -> ${displayName(resultCellType)}\n")
        }

        // Строки своих полей — у секций итогового типа: синапс подписывает a, b, c по-своему,
        // а функция активации ему ни к чему.
        val resultCell = resultCellType?.takeIf(cellList::containsKey)?.let(cellList::byKey) ?: return text.toString()
        for (section in resultCell.editorSections()) section.describeMutation(actual, mutate, text)

        return text.toString()
    }

    /** Имя типа для людей; тип от мода, которого нет, — его ключ. */
    private fun displayName(key: String) = if (cellList.containsKey(key)) cellList.byKey(key).name else key

    companion object {
        /** Действие деления в клетку [cell]: её цвет и значения по умолчанию всех её секций. */
        fun newCellAction(cell: Cell): Action {
            var action = Action(cellType = cell.key, color = cell.defaultColor.cpy(), special = cell.defaultGenomeData)
            for (section in cell.editorSections()) action = section.applyDefaults(action)
            return action
        }
    }
}
