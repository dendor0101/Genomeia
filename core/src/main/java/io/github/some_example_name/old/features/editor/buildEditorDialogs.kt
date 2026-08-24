package io.github.some_example_name.old.features.editor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Stage
import com.kotcrab.vis.ui.widget.color.ColorPickerAdapter
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.commands.GoMenu
import io.github.some_example_name.old.commands.GoReplace
import io.github.some_example_name.old.commands.GoSimulation
import io.github.some_example_name.old.core.DIGameGlobalContainer.navigationCommandsManager
import io.github.some_example_name.old.core.color_picker.ColorPicker
import io.github.some_example_name.old.editor.system.logic.DivideDialog
import io.github.some_example_name.old.editor.system.logic.MutateDialog
import io.github.some_example_name.old.editor.system.logic.ShowChangeRemoveDialog
import io.github.some_example_name.old.editor.system.logic.ShowDivideDialog
import io.github.some_example_name.old.editor.system.logic.ShowMutateDialog
import io.github.some_example_name.old.editor.system.logic.ShowMutateOrDivideDialog
import io.github.some_example_name.old.editor.system.logic.TryToChange
import io.github.some_example_name.old.editor.system.logic.TryToDivide
import io.github.some_example_name.old.editor.system.logic.TryToMutate
import io.github.some_example_name.old.editor.system.logic.TryToRemove
import io.github.some_example_name.old.features.editor.dialog.ActionDialog
import io.github.some_example_name.old.features.editor.dialog.ActionDialogType
import io.github.some_example_name.old.features.editor.dialog.MutateOrDivideDialog
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer
import io.github.some_example_name.old.systems.genomics.genome.Genome

fun Stage.changeRemoveActionDialog(
    command: ShowChangeRemoveDialog,
    onRemove: (TryToRemove) -> Unit,
    onChange: (TryToChange) -> Unit,
) {
    val dialogDivide = ActionDialog(
        clickedCell = command.clickedCell,
        actionDialogType = ActionDialogType.CHANGE_REMOVE,
        onChange = { divide ->
            onChange(
                TryToChange(
                    clickedCellIndex = command.clickedCell.index,
                    divide = divide.copy()
                )
            )
        },
        onRemove = { onRemove(TryToRemove(clickedCellIndex = command.clickedCell.index)) },
    )
    dialogDivide.show(this)
}

fun Stage.mutateOrDivideDialog(
    command: ShowMutateOrDivideDialog,
    onMutate: (MutateDialog) -> Unit,
    onDivide: (DivideDialog) -> Unit
) {
    val dialogMutateOrDivide = MutateOrDivideDialog(
        clickedCell = command.clickedCell,
        onMutate = {
            onMutate.invoke(
                MutateDialog(
                    clickedCell = command.clickedCell,
                    parentCell = command.parentCell,
                    currentTick = command.currentTick
                )
            )
        },
        onDivide = {
            onDivide.invoke(
                DivideDialog(
                    clickedCell = command.clickedCell,
                    newDividedCellPosition = command.newDividedCellPosition
                )
            )
        }
    )
    dialogMutateOrDivide.show(this)
}

fun Stage.mutateActionDialog(
    command: ShowMutateDialog,
    onMutate: (TryToMutate) -> Unit
) {
    val dialogDivide = ActionDialog(
        clickedCell = command.clickedCell,
        actionDialogType = ActionDialogType.MUTATION,
        onMutate = { action ->
            onMutate.invoke(
                TryToMutate(
                    clickedCellIndex = command.clickedCell.index,
                    mutate = action.copy()
                )
            )
        }
    )
    dialogDivide.show(this)
}

fun Stage.divideActionDialog(
    command: ShowDivideDialog,
    onDivide: (TryToDivide) -> Unit
) {
    val dialogDivide = ActionDialog(
        clickedCell = command.clickedCell,
        newDividedCellPosition = command.newDividedCellPosition,
        actionDialogType = ActionDialogType.DIVIDE,
        onDivide = { action ->
            onDivide.invoke(
                TryToDivide(
                    clickedCellIndex = command.clickedCell.index,
                    newDividedCellPosition = command.newDividedCellPosition,
                    divide = action.copy()
                )
            )
        },
    )
    dialogDivide.show(this)
}

fun Stage.colorPickerDialog(
    initColor: Color,
    title: String = bundle.get("button.chooseColorDialog"),
    onChanged: (Color) -> Unit = {},
    onFinished: (Color) -> Unit = {}
) {
    val colorPicker = ColorPicker(
        title = title,
        listener = object : ColorPickerAdapter() {
            override fun changed(newColor: Color) {
                onChanged.invoke(newColor.cpy())
            }

            override fun finished(newColor: Color?) {
                super.finished(newColor)
                if (newColor == null) return
                onFinished.invoke(newColor.cpy())
            }
        },
        colorInit = initColor.cpy()
    )
    this.addActor(colorPicker)
    colorPicker.fadeIn()
}


fun Stage.saveDialog(
    isGoToMenu: Boolean,
    genome: Genome
) {
    SaveGenomeDialog(
        genome = genome,
        bake = { toSave ->
            // Тело в этот момент уже выращено в сущностях редактора, поэтому раскладка
            // считается прямо по ним. Организм в редакторе всегда один, с organIndex = 0.
            val layout = DIGenomeEditorContainer.rcmSort.bake()

            // Выгрузка топологии для стенда физики. Здесь же, потому что момент тот
            // самый: тело выращено целиком и отработало последний тик, значит координаты
            // это реальная поза покоя. Путь к файлу уходит в лог.
            DIGenomeEditorContainer.bodyExport.exportToFile()

            val cellEntity = DIGenomeEditorContainer.cellEntity
            toSave.copy(
                cellsAmount = cellEntity.aliveList.size,
                linksAmount = DIGenomeEditorContainer.linkEntity.aliveList.size,
                neuralLinksAmount = DIGenomeEditorContainer.neuralLinkEntity.aliveList.size,
                sortedGraph = layout
            )
        },
        // Оба перехода идут через менеджер, а не через game.screen = ...: только так они
        // попадают в журнал действий, и только так чистится стек прошлого маршрута.
        onSaveAndTest = { genomeNameForTest ->
            navigationCommandsManager.performCommand(
                GoReplace(GoSimulation(world = null, genomeName = genomeNameForTest))
            )
        },
        onGoMenu = {
            navigationCommandsManager.performCommand(GoReplace(GoMenu))
        },
        isGoToMenu = isGoToMenu
    ).show(this)
}
