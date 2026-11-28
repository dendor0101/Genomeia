package io.github.some_example_name.old.features.editor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Stage
import com.kotcrab.vis.ui.widget.color.ColorPickerAdapter
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.commands.GoBack
import io.github.some_example_name.old.commands.GoMenu
import io.github.some_example_name.old.commands.GoReplace
import io.github.some_example_name.old.commands.GoSimulation
import io.github.some_example_name.old.core.DIGameGlobalContainer.navigationCommandsManager
import io.github.some_example_name.old.core.color_picker.ColorPicker
import io.github.some_example_name.old.editor.system.logic.DivideDialog
import io.github.some_example_name.old.editor.system.logic.EditorLogicSystem
import io.github.some_example_name.old.editor.system.logic.ExportGenome
import io.github.some_example_name.old.editor.system.logic.MutateDialog
import io.github.some_example_name.old.editor.system.logic.SaveGenome
import io.github.some_example_name.old.editor.system.logic.ShowChangeRemoveDialog
import io.github.some_example_name.old.editor.system.logic.ShowDivideDialog
import io.github.some_example_name.old.editor.system.logic.ShowMutateDialog
import io.github.some_example_name.old.editor.system.logic.ShowMutateOrDivideDialog
import io.github.some_example_name.old.editor.system.logic.TryToCancelMutation
import io.github.some_example_name.old.editor.system.logic.TryToChange
import io.github.some_example_name.old.editor.system.logic.TryToDivide
import io.github.some_example_name.old.editor.system.logic.TryToMutate
import io.github.some_example_name.old.editor.system.logic.TryToRemove
import io.github.some_example_name.old.features.editor.dialog.ActionDialog
import io.github.some_example_name.old.features.editor.dialog.ActionDialogType
import io.github.some_example_name.old.features.editor.dialog.MutateOrDivideDialog
import io.github.some_example_name.old.systems.physics.CollisionManager.Companion.PARTICLE_MAX_RADIUS

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
                    newDividedCellPosition = command.newDividedCellPosition,
                    places = command.places
                )
            )
        }
    )
    dialogMutateOrDivide.show(this)
}

fun Stage.mutateActionDialog(
    command: ShowMutateDialog,
    onMutate: (TryToMutate) -> Unit,
    onCancelMutation: (TryToCancelMutation) -> Unit
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
        },
        onCancelMutation = {
            onCancelMutation.invoke(TryToCancelMutation(clickedCellIndex = command.clickedCell.index))
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
        maxChildRadius = command.places.maxRadius,
        actionDialogType = ActionDialogType.DIVIDE,
        onDivide = { action ->
            // Место — под выбранный в диалоге радиус; у команд из старых журналов таблицы
            // нет, и место одно.
            val position = command.places.positionFor(action.radius ?: PARTICLE_MAX_RADIUS)
                ?: command.newDividedCellPosition
            onDivide.invoke(
                TryToDivide(
                    clickedCellIndex = command.clickedCell.index,
                    newDividedCellPosition = position,
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


/**
 * Диалог сохранения генома. Каждая его кнопка — одна или две команды подряд: сохранение
 * идёт командой редактора (SaveGenome), переходы — командами навигации.
 */
fun Stage.saveDialog(
    isGoToMenu: Boolean,
    genomeName: String,
    editorLogicSystem: EditorLogicSystem
) {
    // Редактор открыт из мира (кнопкой «Создать новый геном»): мир стоит под ним на паузе,
    // и все выходы ведут обратно в него, а не в меню и не в тест генома.
    val previous = navigationCommandsManager.previousCommand()
    val fromWorld = previous is GoSimulation && previous.genomeName == null
    SaveGenomeDialog(
        genomeName = genomeName,
        onSave = { name -> editorLogicSystem.putUiCommand(SaveGenome(name)) },
        // Оба перехода идут через менеджер, а не через game.screen = ...: только так они
        // попадают в журнал действий, и только так чистится стек прошлого маршрута.
        onSaveAndTest = { name ->
            editorLogicSystem.putUiCommand(SaveGenome(name))
            if (fromWorld) {
                navigationCommandsManager.performCommand(GoBack)
                // Отдельной командой уже мира: при повторе её подаст запись, на экран мира.
                (game.screen as? GenomeEditorCaller)?.onGenomeSaved(name)
            } else {
                navigationCommandsManager.performCommand(
                    GoReplace(GoSimulation(world = null, genomeName = name))
                )
            }
        },
        onSaveAndExport = { name ->
            editorLogicSystem.putUiCommand(SaveGenome(name))
            editorLogicSystem.putUiCommand(ExportGenome(name))
        },
        onGoMenu = {
            navigationCommandsManager.performCommand(if (fromWorld) GoBack else GoReplace(GoMenu))
        },
        goMenuLabel = bundle.get(if (fromWorld) "button.backToWorld" else "button.menu"),
        isGoToMenu = isGoToMenu
    ).show(this)
}

/**
 * Экран, из которого открыли редактор генома и в который он возвращается после
 * «Сохранить и протестировать» (мир симуляции).
 */
interface GenomeEditorCaller {
    /** Геном [fileName] только что сохранён — сделать его выбранным. */
    fun onGenomeSaved(fileName: String)
}
