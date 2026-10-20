package io.github.some_example_name.old.features.editor.dialog

import io.github.some_example_name.old.core.ui.hint
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.cellList
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.cellPalette
import io.github.some_example_name.old.editor.entities.EditorCell
import io.github.some_example_name.old.editor.system.ActionDialogSystem
import io.github.some_example_name.old.editor.undo_redo_commands.hasCellMutation
import io.github.some_example_name.old.features.editor.colorPickerDialog
import io.github.some_example_name.old.systems.genomics.genome.Action
import io.github.some_example_name.old.systems.physics.CollisionManager.Companion.PARTICLE_MAX_RADIUS
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.globalVisTable
import io.github.some_example_name.old.core.ui.h
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visSelectBox
import io.github.some_example_name.old.core.ui.visSlider
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextButton
import io.github.some_example_name.old.core.ui.w
import io.github.some_example_name.old.core.ui.setupTitleSize
import kotlin.math.round

enum class ActionDialogType {
    DIVIDE,
    MUTATION,
    CHANGE_REMOVE
}

class ActionDialog(
    val clickedCell: EditorCell,
    val actionDialogType: ActionDialogType,
    newDividedCellPosition: Pair<Float, Float>? = null,
    val onDivide: ((Action) -> Unit)? = null,
    val onChange: ((Action) -> Unit)? = null,
    val onRemove: (() -> Unit)? = null,
    val onMutate: ((Action) -> Unit)? = null,
    val onCancelMutation: (() -> Unit)? = null,
) : VisDialog("${bundle.get("button.cellId")} ${clickedCell.id}"), CellDialog {

    override val actionSystem = ActionDialogSystem(actionDialogType, newDividedCellPosition, clickedCell)

    /** Кружок «как будет выглядеть клетка»; секции крутят на нём стрелку направления. */
    override var circleWidget: CircleWidget? = null
        private set

    init {
        setupTitleSize(game)

        isModal = true
        isMovable = true
        val dialog = this

        val rootTable = globalVisTable {
            composeRoot(dialog)
        }

        contentTable.add(rootTable)
            .maxWidth(0.9f * w)
            .maxHeight(0.9f * h)

        closeOnEscape()

        pack()
        centerWindow()
    }

    private fun VisTable.composeRoot(dialog: ActionDialog) {
        var dynamicContent: VisTable? = null

        //Выбор типа клетки — из палитры редактора; свой тип клетки виден, даже если скрыт.
        val cellChoices = cellPalette.actionCellChoices(cellList, current = actionSystem.cellType)
        visSelectBox(
            items = cellChoices.map { it.name }.toTypedArray(),
            // Типа нет в списке — клетка от мода, которого нет; показывается первый.
            selectedIndex = cellChoices.indexOfFirst { it.key == actionSystem.cellType }.coerceAtLeast(0),
            onChange = { _, choice ->
                val fromCellType = actionSystem.cell
                val toCellType = cellChoices[choice]
                actionSystem.setUpDefaultActionData(fromCellType.key, toCellType.key)

                //Настройка угла и цвета в зависимости от типа клетки
                if (fromCellType.isDirected && !toCellType.isDirected) {
                    circleWidget?.setAngle(null)
                } else if (!fromCellType.isDirected && toCellType.isDirected) {
                    circleWidget?.setAngle(actionSystem.angleParent)
                }
                circleWidget?.setCircleColor(actionSystem.getCellColor())

                //Перерисовка UI
                dynamicContent?.clearChildren()
                dynamicContent?.composeDynamic()
                dynamicContent?.invalidateHierarchy()
                dialog.pack()
                dialog.centerWindow()
            }
        ) { left() }.hint(bundle.get("editor.hint.cellType"))
        row()

        dynamicContent = visTable { }
        dynamicContent.composeDynamic()
        row()

        if (actionDialogType == ActionDialogType.DIVIDE || actionDialogType == ActionDialogType.CHANGE_REMOVE) {
            //Настройка радиуса клетки при делении
            val defaultRadius = actionSystem.action.radius ?: PARTICLE_MAX_RADIUS
            val radiusLabel = visLabel("${bundle.get("button.radius")}: $defaultRadius", font = game.extraLargeFont).hint(bundle.get("editor.hint.radius"))
            row()
            visSlider(
                0.2f, 0.5f, 0.01f, value = defaultRadius,
                onValueChange = {
                    val radius = round(it * 100f) / 100f
                    actionSystem.action = actionSystem.action.copy(radius = radius)
                    radiusLabel.setText("${bundle.get("button.radius")}: $radius")
                }) { growX().expandX() }.hint(bundle.get("editor.hint.radius"))
            row()
        }

        visTable {
            if (actionDialogType == ActionDialogType.MUTATION) {
                //Как сейчас выглядит клетка в текущем тике
                val cellType = clickedCell.actual?.cellType ?: throw Exception("actual cellType can't be null")
                visCircleWidget(
                    initialColor = clickedCell.actual.color ?: throw Exception("color is null"),
                    initialDirectedAngle = if (cellList.byKey(cellType).isDirected) {
                        actionSystem.angleParent + (clickedCell.angleDirected ?: 0f)
                    } else null
                ).hint(bundle.get("editor.hint.previewNow"))

                visLabel("->", font = game.extraLargeFont)
            }

            //Как будет вылгядеть клетка после создания при divide
            circleWidget = visCircleWidget(
                initialColor = actionSystem.action.color ?: clickedCell.actual?.color ?: throw Exception("color is null"),
                initialDirectedAngle = if (actionSystem.isDirected) actionSystem.angleParent + actionSystem.angleDirected else null
            ).hint(bundle.get("editor.hint.preview"))
        }
        row()

        if (actionDialogType == ActionDialogType.MUTATION) {
            //Текст списка изменений в следствии mutation
            visLabel(text = actionSystem.makeMutateList(), font = game.largeFont) { left().padLeft(8.dp()) }.hint(bundle.get("editor.hint.mutateList"))
            row()
        }

        visTable {
            //Цвет клетки и кнопки action
            visTextButton(bundle.get("button.chooseCellColorDialog"), onClick = {
                stage.colorPickerDialog(
                    initColor = actionSystem.action.color ?: clickedCell.actual?.color ?: throw Exception("color is null"),
                    onFinished = {
                        actionSystem.action = actionSystem.action.copy(color = it)
                        circleWidget?.setCircleColor(it)
                    }
                )
            }).hint(bundle.get("editor.hint.cellColor"))

            visTextButton(
                text = when (actionDialogType) {
                    ActionDialogType.DIVIDE -> bundle.get("button.divide")
                    ActionDialogType.MUTATION -> bundle.get("button.mutate")
                    ActionDialogType.CHANGE_REMOVE -> bundle.get("button.change")
                },
                onClick = {
                    when (actionDialogType) {
                        ActionDialogType.DIVIDE -> { onDivide?.invoke(actionSystem.action) }
                        ActionDialogType.MUTATION -> {
                            if (clickedCell.mutate != actionSystem.action && actionSystem.action != Action()) {
                                println(actionSystem.action)
                                onMutate?.invoke(actionSystem.action)
                            }
                        }
                        ActionDialogType.CHANGE_REMOVE -> {
                            if (clickedCell.divide != actionSystem.action) {
                                onChange?.invoke(actionSystem.action)
                            }
                        }
                    }
                    fadeOut()
                }
            ).hint(
                bundle.get(
                    when (actionDialogType) {
                        ActionDialogType.DIVIDE -> "editor.hint.divide"
                        ActionDialogType.MUTATION -> "editor.hint.mutate"
                        ActionDialogType.CHANGE_REMOVE -> "editor.hint.change"
                    }
                )
            )

            if (actionDialogType == ActionDialogType.CHANGE_REMOVE) {
                visTextButton(
                    text = bundle.get("button.remove"),
                    onClick = {
                        onRemove?.invoke()
                        fadeOut()
                    }
                ).hint(bundle.get("editor.hint.remove"))
            }
        }

        //Отмена мутации — отдельной строкой под кнопками и только если на этой стадии
        //клетка действительно мутирует
        if (actionDialogType == ActionDialogType.MUTATION && clickedCell.mutate.hasCellMutation()) {
            row()
            visTextButton(
                text = bundle.get("button.cancelMutation"),
                onClick = {
                    onCancelMutation?.invoke()
                    fadeOut()
                }
            ) { center() }.hint(bundle.get("editor.hint.cancelMutation"))
        }
    }

    /** Свои настройки выбранного типа клетки — его секции сверху вниз (Cell.editorSections). */
    private fun VisTable.composeDynamic() {
        for (section in actionSystem.cell.editorSections()) {
            with(section) { compose(this@ActionDialog) }
        }
    }
}
