package io.github.some_example_name.old.genome_editor.dialog

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.I18NBundle
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.color.ColorPickerAdapter
import io.github.some_example_name.old.genome.Action
import io.github.some_example_name.old.genome_editor.EditorCell
import io.github.some_example_name.old.genome_editor.FullReplayStructure
import io.github.some_example_name.old.genome_editor.dialog.color.ColorPicker
import io.github.some_example_name.old.screens.MyGame
import io.github.some_example_name.old.screens.setupTitleSize
import io.github.some_example_name.old.world_logic.cells.base.getCellColor
import io.github.some_example_name.old.world_logic.cells.base.isDirected
import io.github.some_example_name.old.world_logic.cells.base.isEye
import io.github.some_example_name.old.world_logic.cells.base.isNeural

class ChangeRemoveActionDialog(
    val clickedCell: EditorCell,
    var divide: Action,
    val onChange: (Action) -> Unit,
    val onRemove: () -> Unit,
    val game: MyGame,
    val bundle: I18NBundle
) : VisDialog("${bundle.get("button.cellId")} ${clickedCell.id}") {

    val scrollPane: ScrollPane
    val cellBaseAngle = clickedCell.angle

    init {
        isModal = true
        isMovable = true

        setupTitleSize(game)
        val scrollContentTable = VisTable()

        scrollPane = ScrollPane(scrollContentTable).apply {
            setFadeScrollBars(false)
            setScrollingDisabled(false, false)
            setForceScroll(false, true)
            setFlickScroll(true)
            setOverscroll(false, true)
        }

        // Добавляем ScrollPane в диалог с ограничением по высоте
        contentTable.add(scrollPane)
            .grow()  // растягиваем на доступное место
            .maxHeight(Gdx.graphics.height * 0.8f)

        contentTable.row()

        closeOnEscape()

        setupUI(scrollContentTable)
        pack()
        centerWindow()
    }

    private fun setupUI(scrollContentTable: VisTable) {
        val density = Gdx.graphics.density
        val cellType = divide.cellType ?: 0

        scrollContentTable.clear()
        val colorCircleWidget = divide.color ?: getCellColor(cellType)
        val circleWidgetDivide = CircleWidget(
            initialColor = colorCircleWidget.cpy(),
            smallCircleRadius = 2f,
            initialDirectedAngle = if (cellType.isDirected()) (divide.angleDirected ?: 0f) + cellBaseAngle else null
        )
        val previewTable = Table()
        previewTable.add(circleWidgetDivide).size(100f * density, 100f * density)
        circleWidgetDivide.setCircleColor(colorCircleWidget.cpy())

        scrollContentTable.add(previewTable).row()

        actionButton("Remove", game) {
            onRemove.invoke()
            fadeOut()
        }.also { scrollContentTable.add(it).padBottom(15f * density).size(200f * density, 35f * density).row() }

        val colorPicker = ColorPicker(
            game = game,
            title = bundle.get("button.chooseColorDialog"),
            listener = object : ColorPickerAdapter() {
                override fun changed(newColor: Color) {
                    val newColor = newColor.cpy()
                    circleWidgetDivide.setCircleColor(newColor)
                }

                override fun finished(newColor: Color?) {
                    super.finished(newColor)
                    if (newColor == null) return
                    val newColor = newColor.cpy()
                    divide.color = newColor
                    circleWidgetDivide.setCircleColor(newColor)
                }
            },
            colorInit = colorCircleWidget.cpy()
        )

        colorPicker(colorPicker, game, bundle).also { scrollContentTable.add(it).align(Align.left).padBottom(15f * density).row() }
        cellTypePicker(cellType, game = game) {
            setupDivide(cellType, it, circleWidgetDivide)
            divide.cellType = it
            val cellColor = getCellColor(it)
            divide.color = cellColor
            colorPicker.color = cellColor
            circleWidgetDivide.setCircleColor(cellColor)
            if (cellType.isDirected()) circleWidgetDivide.setAngle(0f)
            setupUI(scrollContentTable)
        }.also { scrollContentTable.add(it).align(Align.left).size(200f * density, 30f * density).padBottom(15f * density).row() }

        if (cellType.isDirected()) {
            angleDirected(divide, scrollPane, game, bundle) { angle ->
                divide = divide.copy(angleDirected = angle)
                circleWidgetDivide.setAngle(angle + cellBaseAngle)
            }.also { scrollContentTable.add(it).width(200f * density).row() }
        }
        if (cellType.isNeural()) {
            neuron(
                action = divide,
                game = game,
                bundle = bundle,
                onFuncChange = {
                    divide = divide.copy(funActivation = it)
                },
                onAChange = {
                    divide = divide.copy(a = it)
                },
                onBChange = {
                    divide = divide.copy(b = it)
                },
                onCChange = {
                    divide = divide.copy(c = it)
                },
                onIsSumChange = {
                    divide = divide.copy(isSum = it)
                }
            ).also { scrollContentTable.add(it).align(Align.left).padBottom(10f * density).row() }
        }

        if (cellType.isEye()) {
            eye(
                action = divide,
                scrollPane = scrollPane,
                game = game,
                bundle = bundle,
                onDistanceChange = {
                    divide = divide.copy(lengthDirected = it)
                },
                onColorChange = {
                    divide = divide.copy(colorRecognition = it)
                },
            ).also { scrollContentTable.add(it).row()}
        }

        actionButton("Change", game) {
            if (clickedCell.divide.hashCode() != divide.hashCode() && clickedCell.divide != divide) {
                onChange.invoke(divide)
            }
            fadeOut()
        }.also { scrollContentTable.add(it).size(200f * density, 35f * density).row() }

        pack()
        centerWindow()
    }

    private fun setupDivide(fromCellType: Int, toCellType: Int, circleWidgetDivide: CircleWidget) {
        when {
            fromCellType.isDirected() && !toCellType.isDirected() -> {
                divide = divide.copy(angleDirected = null)
                circleWidgetDivide.setAngle(null)
            }
            !fromCellType.isDirected() && toCellType.isDirected() -> {
                divide = divide.copy(angleDirected = 0f)
                circleWidgetDivide.setAngle(0f + cellBaseAngle)
            }
        }

        when {
            fromCellType.isNeural() && !toCellType.isNeural() -> {
                divide = divide.copy(
                    funActivation = null,
                    a = null,
                    b = null,
                    c = null,
                    isSum = null
                )
            }
            !fromCellType.isNeural() && toCellType.isNeural() -> {
                divide = divide.copy(
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
                divide = divide.copy(
                    colorRecognition = null,
                    lengthDirected = null
                )
            }
            !fromCellType.isEye() && toCellType.isEye() -> {
                divide = divide.copy(
                    colorRecognition = 7,
                    lengthDirected = 170f
                )
            }
        }
    }
}
