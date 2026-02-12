package io.github.some_example_name.old.genome_editor.dialog

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.*
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.I18NBundle
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.color.ColorPickerAdapter
import io.github.some_example_name.old.genome.Action
import io.github.some_example_name.old.genome_editor.EditorCell
import io.github.some_example_name.old.genome_editor.FullReplayStructure
import io.github.some_example_name.old.genome_editor.dialog.color.ColorPicker
import io.github.some_example_name.old.good_one.getColorFromBits
import io.github.some_example_name.old.screens.MyGame
import io.github.some_example_name.old.screens.applyCustomFontMedium
import io.github.some_example_name.old.screens.setupTitleSize
import io.github.some_example_name.old.world_logic.cells.base.cellsType
import io.github.some_example_name.old.world_logic.cells.base.getCellColor
import io.github.some_example_name.old.world_logic.cells.base.isDirected
import io.github.some_example_name.old.world_logic.cells.base.isEye
import io.github.some_example_name.old.world_logic.cells.base.isNeural
import io.github.some_example_name.old.world_logic.cells.formulaType


class MutateActionDialog(
    val clickedCell: EditorCell,
    val cellFullReplay: FullReplayStructure,
    val clickedIndex: Int,
    val onMutate: (Action) -> Unit,
    val game: MyGame,
    val bundle: I18NBundle
) : VisDialog("${bundle.get("button.cellId")} ${clickedCell.id}") {
    private val colorOfCellFrom = Color(
        cellFullReplay.colorR[clickedIndex],
        cellFullReplay.colorG[clickedIndex],
        cellFullReplay.colorB[clickedIndex],
        1f
    )

    private var colorOfCellTo = colorOfCellFrom
    private var cellType = clickedCell.mutate?.cellType ?: cellFullReplay.cellType[clickedIndex]

    private var mutation: Action? = clickedCell.mutate?.copy()


    val scrollPane: ScrollPane
    init {
        VisUI.getSizes().scaleFactor = Gdx.graphics.density
        setupTitleSize(game)
        isModal = true
        isMovable = true

        val scrollContentTable = VisTable()

        // Оборачиваем в ScrollPane // Wrap it in a ScrollPane
        scrollPane = ScrollPane(scrollContentTable).apply {
            setFadeScrollBars(false)      // полоска прокрутки всегда видна // the scroll bar is always visible
            setScrollingDisabled(false, false)
            setForceScroll(false, true)
            setFlickScroll(true)
            setOverscroll(false, true)
        }

        // Добавляем ScrollPane в диалог с ограничением по высоте
        // Adding a ScrollPane to a Dialog with a Height Limit
        contentTable.add(scrollPane)
            .grow()  // растягиваем на доступное место // we stretch it to an accessible place
            .maxHeight(Gdx.graphics.height * 0.8f)

        contentTable.row()

        closeOnEscape()

        setupUI(scrollContentTable)
        pack()
        centerWindow()
    }

    private fun makeMutateList(text: StringBuilder) {
        clickedCell.mutate?.apply {
            cellType?.let {
                val fullReplayCellType = cellFullReplay.cellType[clickedIndex]
                if (it != fullReplayCellType)
                    text.append("Cell type: ${cellsType[fullReplayCellType]} -> ${cellsType[it]}\n")
            }
            funActivation?.let {
                if (it != cellFullReplay.activationFuncType[clickedIndex])
                    text.append("Activation formula:\n${formulaType[cellFullReplay.activationFuncType[clickedIndex]]} -> ${formulaType[it]}\n")
            }
            a?.let {
                if (it != cellFullReplay.a[clickedIndex])
                    text.append("a: ${cellFullReplay.a[clickedIndex]} -> $it\n")
            }
            b?.let {
                if (it != cellFullReplay.b[clickedIndex])
                    text.append("b: ${cellFullReplay.b[clickedIndex]} -> $it\n")
            }
            c?.let {
                if (it != cellFullReplay.c[clickedIndex])
                    text.append("c: ${cellFullReplay.c[clickedIndex]} -> $it\n")
            }
            isSum?.let {
                if (it != cellFullReplay.isSum[clickedIndex])
                    text.append(
                        "isSum: ${
                            if (cellFullReplay.isSum[clickedIndex]) "Addition" else "Multiplication"
                        } -> ${
                            if (it) "Addition\n" else "Multiplication\n"
                        }"
                    )
            }
            colorRecognition?.let {
                val colorFrom = getColorFromBits(cellFullReplay.colorDifferentiation[clickedIndex])
                val colorTo = getColorFromBits(it)
                if (it != cellFullReplay.colorDifferentiation[clickedIndex])
                    text.append(
                        "Eye color recognition: (r:${if (colorFrom.r > 0) 1 else 0}, g:${if (colorFrom.g > 0) 1 else 0}, b${if (colorFrom.b > 0) 1 else 0}) -> (r:${if (colorTo.r > 0) 1 else 0}, g:${if (colorTo.g > 0) 1 else 0}, b${if (colorTo.b > 0) 1 else 0})\n"
                    )
            }
            lengthDirected?.let {
                if (it != cellFullReplay.visibilityRange[clickedIndex])
                    text.append("Eye distance: ${cellFullReplay.visibilityRange[clickedIndex]} -> $it\n")
            }
        }
    }

    private fun setupUI(scrollContentTable: VisTable) {
        val density = Gdx.graphics.density
        scrollContentTable.clear()
        val circleWidgetFrom = CircleWidget(
            initialColor = colorOfCellFrom,
            smallCircleRadius = clickedCell.energy,
            initialDirectedAngle = if (cellFullReplay.cellType[clickedIndex].isDirected()) {
                cellFullReplay.angle[clickedIndex] + cellFullReplay.angleDiff[clickedIndex]
            } else null
        )
        var mutableCircleWidget = circleWidgetFrom
        val previewTable = Table()
        previewTable.add(circleWidgetFrom).size(100f * density, 100f * density)
        circleWidgetFrom.setCircleColor(colorOfCellFrom)

        val text = StringBuilder()
        if (mutation == null) {
            text.append(cellsType[cellType])
        } else {
            val volumeLabel = VisLabel("->")
            previewTable.add(volumeLabel).align(Align.center)
            mutation?.color?.let {
                colorOfCellTo = it
            }
            val circleWidgetTo = CircleWidget(
                initialColor = colorOfCellTo,
                smallCircleRadius = clickedCell.energy,
                initialDirectedAngle = if (cellType.isDirected()) {
                    cellFullReplay.angle[clickedIndex] + (mutation?.angleDirected ?: cellFullReplay.angleDiff[clickedIndex])
                } else null
            )
            previewTable.add(circleWidgetTo).size(100f * density, 100f * density)
            circleWidgetTo.setCircleColor(colorOfCellTo)
            mutableCircleWidget = circleWidgetTo
            makeMutateList(text)
        }

        scrollContentTable.add(previewTable).row()

        val description = VisLabel(text)
        game.applyCustomFontMedium(description)
        scrollContentTable.add(description).align(Align.left).row()

        val colorPicker = ColorPicker(
            game = game,
            title = bundle.get("button.chooseColorDialog"),
            listener = object : ColorPickerAdapter() {
                override fun changed(newColor: Color) {
                    colorOfCellTo = newColor.cpy()
                    mutableCircleWidget.setCircleColor(colorOfCellTo)
                }

                override fun finished(newColor: Color?) {
                    super.finished(newColor)
                    if (newColor == null) return
                    colorOfCellTo = newColor.cpy()
                    mutableCircleWidget.setCircleColor(colorOfCellTo)
                    if (mutation == null) mutation = Action()
                    mutation = mutation?.copy(color = newColor.cpy())
                }
            },
            colorInit = colorOfCellTo.cpy()
        )

        colorPicker(colorPicker, game, bundle).also { scrollContentTable.add(it).align(Align.left).padBottom(15f * density).row() }
        cellTypePicker(cellType, game) {
            if (mutation == null) mutation = Action()
            setupMutation(cellType, it, mutableCircleWidget)
            cellType = it
            mutation = mutation?.copy(cellType = it, color = getCellColor(it))
            colorOfCellTo = getCellColor(it)
            colorPicker.color = colorOfCellTo
            mutableCircleWidget.setCircleColor(colorOfCellTo)
            setupUI(scrollContentTable)
        }.also { scrollContentTable.add(it).align(Align.left).size(200f * density, 30f * density).padBottom(15f * density).row() }

        if (cellType.isNeural()) {
            neuron(
                action = mutation ?: Action(
                    funActivation = cellFullReplay.activationFuncType[clickedIndex],
                    a = cellFullReplay.a[clickedIndex],
                    b = cellFullReplay.b[clickedIndex],
                    c = cellFullReplay.c[clickedIndex],
                    isSum = cellFullReplay.isSum[clickedIndex]
                ),
                game = game,
                bundle = bundle,
                onFuncChange = {
                    if (mutation == null) mutation = Action()
                    mutation = mutation?.copy(funActivation = it)
                },
                onAChange = {
                    if (mutation == null) mutation = Action()
                    mutation = mutation?.copy(a = it)
                },
                onBChange = {
                    if (mutation == null) mutation = Action()
                    mutation = mutation?.copy(b = it)
                },
                onCChange = {
                    if (mutation == null) mutation = Action()
                    mutation = mutation?.copy(c = it)
                },
                onIsSumChange = {
                    if (mutation == null) mutation = Action()
                    mutation = mutation?.copy(isSum = it)
                }
            ).also { scrollContentTable.add(it).align(Align.left).padBottom(10f * density).row() }
        }

        if (cellType.isDirected()) {
            angleDirected(mutation ?: Action(
                angleDirected = cellFullReplay.angleDiff[clickedIndex]),
                scrollPane = scrollPane,
                game = game,
                bundle = bundle
            ) { angle ->
                if (mutation == null) mutation = Action()
                mutation = mutation?.copy(angleDirected = angle)
                mutableCircleWidget.setAngle(angle + cellFullReplay.angle[clickedIndex])
            }.also { scrollContentTable.add(it).width(200f * density).row() }
        }
        if (cellType.isEye()) {
            eye(
                action = mutation ?: Action(
                    lengthDirected = cellFullReplay.visibilityRange[clickedIndex],
                    colorRecognition = cellFullReplay.colorDifferentiation[clickedIndex]
                ),
                scrollPane = scrollPane,
                game = game,
                bundle = bundle,
                onDistanceChange = {
                    if (mutation == null) mutation = Action()
                    mutation = mutation?.copy(lengthDirected = it)
                },
                onColorChange = {
                    if (mutation == null) mutation = Action()
                    mutation = mutation?.copy(colorRecognition = it)
                },
            ).also { scrollContentTable.add(it).row()}
        }

        actionButton(bundle.get("button.mutate"), game) {
            if (clickedCell.mutate.hashCode() != mutation.hashCode() && clickedCell.mutate != mutation) {
                mutation?.let { onMutate.invoke(it) }
            }
            fadeOut()
        }.also { scrollContentTable.add(it).size(200f * density, 35f * density).row() }

        pack()
        centerWindow()  // центрируем по экрану // center on the screen
    }

    private fun setupMutation(fromCellType: Int, toCellType: Int, mutableCircleWidget: CircleWidget) {
        when {
            fromCellType.isDirected() && !toCellType.isDirected() -> {
                mutation = mutation?.copy(angleDirected = null)
                mutableCircleWidget.setAngle(null)
            }
            !fromCellType.isDirected() && toCellType.isDirected() -> {
                mutation = mutation?.copy(angleDirected = 0f)
                mutableCircleWidget.setAngle(0f + cellFullReplay.angle[clickedIndex])
            }
        }

        when {
            fromCellType.isNeural() && !toCellType.isNeural() -> {
                mutation = mutation?.copy(
                    funActivation = null,
                    a = null,
                    b = null,
                    c = null,
                    isSum = null
                )
            }
            !fromCellType.isNeural() && toCellType.isNeural() -> {
                mutation = mutation?.copy(
                    funActivation = 0,
                    a = 1f,
                    b = 0f,
                    c = 0f,
                    isSum = true
                )
            }
        }

        when {
            fromCellType.isEye() && !toCellType.isEye() -> {
                mutation = mutation?.copy(
                    colorRecognition = null,
                    lengthDirected = null
                )
            }
            !fromCellType.isEye() && toCellType.isEye() -> {
                mutation = mutation?.copy(
                    colorRecognition = 7,
                    lengthDirected = 170f
                )
            }
        }
    }
}
