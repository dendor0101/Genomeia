package io.github.some_example_name.old.good_one.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.good_one.cells.base.Directed
import io.github.some_example_name.old.good_one.cells.base.Neural
import io.github.some_example_name.old.good_one.cells.cellsTypeFormula
import io.github.some_example_name.old.good_one.utils.addCell
import io.github.some_example_name.old.good_one.utils.cellsType


fun drawDialog(
    stage: Stage,
    skin: Skin,
    uiState: UiState,
    clickDivide: (UiResult) -> Unit,
    clickMutate: (UiResult) -> Unit,
    addedChanges: (UiResult) -> Unit,
    onRotate: (Boolean, Float) -> Unit,
    indexCounter: Int
) {
    stage.clear()
    // Создаем таблицу
    val table = Table()
    table.setSize(170f, Gdx.graphics.height.toFloat()) // Устанавливаем размер таблицы
    table.setPosition(Gdx.graphics.width - 170f, 0f) // Устанавливаем позицию таблицы
    table.top()

    // Создаем Drawable с цветом фона
    val backgroundDrawable: Drawable = skin.newDrawable("white", Color.GRAY)
    table.background = backgroundDrawable // Устанавливаем фон таблицы

    drawCheckBoxes(table, skin)


    if (uiState is Pause.Selected) {
        drawText(table, skin, uiState.cell.second.debugCurrentNeuronImpulseImport.toString())

        val cellExample = addCell(uiState.cell.second.cellTypeId)
        drawMutateBlock(
            table = table,
            skin = skin,
            cellExample = cellExample,
            uiState = uiState,
            clickMutate = clickMutate,
            onRotate = onRotate
        )

        if (!uiState.cell.second.isAlreadyDividedInGenomeStage) {
            drawDivideBlock(
                table = table,
                skin = skin,
                uiState = uiState,
                clickDivide = clickDivide,
                onChange = addedChanges,
                onRotate = onRotate,
                indexCounter = indexCounter
            )
        }
    }

    stage.addActor(table)
}

fun drawMutateBlock(
    table: Table,
    skin: Skin,
    uiState: Pause.Selected,
    clickMutate: (UiResult) -> Unit,
    cellExample: Cell,
    onRotate: (Boolean, Float) -> Unit
) {
    val blockName = if (!uiState.cell.second.isAdded) {
        "Mutate"
    } else {
        "Edit"
    }
    drawText(table, skin, blockName)

    drawText(table, skin, "id: ${uiState.cell.first}", 1f)

    drawCellTypeDropDownList(
        table,
        skin,
        uiState.cell.second.cellTypeId,
        isDisabled = false
    ) { value ->
        clickMutate.invoke(
            UiResult(
                cellType = value
            )
        )
    }

    if (cellExample is Directed) {
        createAngleSlider(
            table,
            skin,
            defaultValue = uiState.cell.second.angleDirected ?: 0f,
            onRotate = {
                onRotate.invoke(false, it)
            }
        ) { value ->
            clickMutate.invoke(
                UiResult(
                    angle = value
                )
            )
        }
        drawLinkList(table, skin, uiState.cell.second.physicalLink.keys.toList())
    }

    if (cellExample is Neural) {
        drawDropDownListFormula(table, skin, uiState.cell.second.activationFuncType ?: 0) { index ->
            clickMutate.invoke(
                UiResult(
                    funActivation = cellsTypeFormula.indexOf(index)
                )
            )
        }
    }

    if (cellExample is Neural) {
        drawFormulaOdds(
            table, skin, uiState,
            defaultA = uiState.cell.second.a ?: 1f,
            defaultB = uiState.cell.second.b ?: 0f,
            defaultC = uiState.cell.second.c ?: 0f
        ) {
            clickMutate.invoke(
                UiResult(
                    a = it.first.toString().toFloatOrNull(),
                    b = it.second.toString().toFloatOrNull(),
                    c = it.third.toString().toFloatOrNull()
                )
            )
        }
    }

}

fun drawDivideBlock(
    table: Table,
    skin: Skin,
    uiState: Pause.Selected,
    clickDivide: (UiResult) -> Unit,
    onChange: (UiResult) -> Unit,
    onRotate: (Boolean, Float) -> Unit,
    indexCounter: Int
) {
    val cellExample = addCell(uiState.cell.second.addedCellSettings?.cellType ?: 0)
    drawText(table, skin, "Divide")

    val idEditTextDivide =
        drawIdEditText(table, skin, uiState.cell.second.addedCellSettings?.id ?: (indexCounter + 1).toString())

    val cellTypeDropDownListDivide =
        drawCellTypeDropDownList(table, skin, uiState.cell.second.addedCellSettings?.cellType ?: 0) { value ->
            onChange.invoke(
                UiResult(
                    cellType = value
                )
            )
        }

    val slider = if (cellExample is Directed) {
        createAngleSlider(
            table,
            skin,
            defaultValue = uiState.cell.second.addedCellSettings?.angle ?: 0f,
            onRotate = {
                onRotate.invoke(true, it)
            }
        ) { value ->
            onChange.invoke(
                UiResult(
                    angle = value
                )
            )
        }
    } else null

    val dropDownListFormula = if (cellExample is Neural) {
        drawDropDownListFormula(table, skin, uiState.cell.second.addedCellSettings?.funActivation ?: 0) { index ->
            onChange.invoke(
                UiResult(
                    funActivation = cellsTypeFormula.indexOf(index)
                )
            )
        }
    } else null

    val formulaOddsDivide = if (cellExample is Neural) {
        drawFormulaOdds(
            table, skin, uiState,
            defaultA = uiState.cell.second.addedCellSettings?.a ?: 1f,
            defaultB = uiState.cell.second.addedCellSettings?.b ?: 0f,
            defaultC = uiState.cell.second.addedCellSettings?.c ?: 0f
        ) {
            onChange.invoke(
                UiResult(
                    a = it.first.toString().toFloatOrNull(),
                    b = it.second.toString().toFloatOrNull(),
                    c = it.third.toString().toFloatOrNull()
                )
            )
        }
    } else null

    drawResultButton(table, skin, "Divide") {
        clickDivide.invoke(
            UiResult(
                id = idEditTextDivide.text,
                cellType = cellTypeDropDownListDivide.let { cellsType.indexOf(it.selected) },
                funActivation = dropDownListFormula?.let { cellsTypeFormula.indexOf(it.selected) },
                a = formulaOddsDivide?.first?.text.toString().toFloatOrNull(),
                b = formulaOddsDivide?.second?.text.toString().toFloatOrNull(),
                c = formulaOddsDivide?.third?.text.toString().toFloatOrNull(),
                angle = slider?.value
            )
        )
    }
}

