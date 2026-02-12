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
import io.github.some_example_name.old.genome_editor.dialog.color.ColorPicker
import io.github.some_example_name.old.screens.MyGame
import io.github.some_example_name.old.screens.setupTitleSize
import io.github.some_example_name.old.world_logic.cells.base.getCellColor
import io.github.some_example_name.old.world_logic.cells.base.isDirected
import io.github.some_example_name.old.world_logic.cells.base.isEye
import io.github.some_example_name.old.world_logic.cells.base.isNeural
import kotlin.math.PI
import kotlin.math.atan2

class DivideActionDialog(
    val clickedCell: EditorCell,
    val newDividedCellPosition: Pair<Float, Float>,
    val onDivide: (Action) -> Unit,
    val game: MyGame,
    val bundle: I18NBundle
) : VisDialog("${bundle.get("button.cellId")} ${clickedCell.id}") {

    private var colorOfCell = getCellColor(0).cpy()
    private var cellType = 0
    private var divide = Action(
        cellType = cellType,
        color = colorOfCell
    )

    val scrollPane: ScrollPane
    private val baseAngle: Float

    init {
        setupTitleSize(game)
        val dx = clickedCell.x - newDividedCellPosition.first
        val dy = clickedCell.y - newDividedCellPosition.second
        val theta = atan2(dy, dx)
        baseAngle = (theta + PI).toFloat()

        isModal = true
        isMovable = true

        val scrollContentTable = VisTable()

        // Оборачиваем в ScrollPane
        // Wrap it in a ScrollPane
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

    private fun setupUI(scrollContentTable: VisTable) {
        val density = Gdx.graphics.density
        scrollContentTable.clear()
        val circleWidgetDivide = CircleWidget(
            initialColor = getCellColor(cellType),
            smallCircleRadius = 2f,
            initialDirectedAngle = if (cellType.isDirected()) baseAngle else null
        )
        val previewTable = Table()
        previewTable.add(circleWidgetDivide).size(100f * density, 100f * density).padBottom(8f * density)
        circleWidgetDivide.setCircleColor(colorOfCell.cpy())

        scrollContentTable.add(previewTable).row()

        val colorPicker = ColorPicker(
            game = game,
            title = bundle.get("button.chooseColorDialog"),
            listener = object : ColorPickerAdapter() {
                override fun changed(newColor: Color) {
                    colorOfCell = newColor.cpy()
                    circleWidgetDivide.setCircleColor(colorOfCell.cpy())
                }

                override fun finished(newColor: Color?) {
                    super.finished(newColor)
                    if (newColor == null) return
                    colorOfCell = newColor.cpy()
                    circleWidgetDivide.setCircleColor(colorOfCell.cpy())
                    divide = divide.copy(color = newColor.cpy())
                }
            },
            colorInit = colorOfCell.cpy()
        )

        colorPicker(colorPicker, game, bundle).also { scrollContentTable.add(it).align(Align.left).padBottom(15f * density).row() }
        cellTypePicker(cellType, game) {
            setupDivide(cellType, it, circleWidgetDivide)
            cellType = it
            divide = divide.copy(cellType = it, color = getCellColor(it))
            colorOfCell = getCellColor(cellType)
            colorPicker.color = colorOfCell
            circleWidgetDivide.setCircleColor(colorOfCell)
            if (cellType.isDirected()) circleWidgetDivide.setAngle(baseAngle)
            setupUI(scrollContentTable)
        }.also { scrollContentTable.add(it).align(Align.left).size(200f * density, 30f * density).padBottom(15f * density).row() }

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
            ).also { scrollContentTable.add(it).align(Align.left).padBottom(10f).row() }
        }

        if (cellType.isDirected()) {
            angleDirected(divide, scrollPane, game = game, bundle = bundle,) { angle ->
                divide = divide.copy(angleDirected = angle)
                circleWidgetDivide.setAngle(angle + baseAngle)
            }.also { scrollContentTable.add(it).width(200f).row() }
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
            ).also { scrollContentTable.add(it).row() }
        }

        actionButton(bundle.get("button.divide"), game) {
            if (clickedCell.divide.hashCode() != divide.hashCode() && clickedCell.divide != divide) {
                onDivide.invoke(divide)
            }
            fadeOut()
        }.also { scrollContentTable.add(it).size(200f * density, 35f * density).row() }

        pack()
        centerWindow()  // центрируем по экрану // center on the screen
    }

    private fun setupDivide(fromCellType: Int, toCellType: Int, circleWidgetDivide: CircleWidget) {
        when {
            fromCellType.isDirected() && !toCellType.isDirected() -> {
                divide = divide.copy(angleDirected = null)
                circleWidgetDivide.setAngle(null)
            }
            !fromCellType.isDirected() && toCellType.isDirected() -> {
                divide = divide.copy(angleDirected = 0f)
                circleWidgetDivide.setAngle(0f + baseAngle)
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
