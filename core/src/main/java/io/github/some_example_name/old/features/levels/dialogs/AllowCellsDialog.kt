package io.github.some_example_name.old.features.levels.dialogs

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.kotcrab.vis.ui.widget.VisCheckBox
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.cells.base.CellPalette
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.makeStyledButton
import io.github.some_example_name.old.core.ui.makeStyledNP
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.features.levels.editor.LevelEditorScreen

class AllowCellsDialog(val allowFunc: (String, Boolean) -> Unit, val allowCell: MutableMap<String, Boolean>): VisDialog("Allow cell dialog") {
    //var allowedCells = mutableMapOf<String, Boolean>()

    init {
        setupTitleSize(game)
        setupUI()
//        if (allowCell.isNotEmpty()) {
//            allowedCells = allowCell
//        }
    }



    fun setupUI() {
        val table = VisTable()
        table.setFillParent(true)

        val title = VisLabel("Allow cells to use in game") // TODO заменить на bundle
        table.add(title).center().pad(6.dp()).row()

        val cellsListTab = VisTable()
        cellsListTab.background = makeStyledNP(LevelEditorScreen.PANEL_BG, LevelEditorScreen.PANEL_BORDER, mutableListOf())
        cellsListTab.top()

        // Показываем все клетки, кроме выведенных из игры. Ключ клетки (cell.key) — это
        // то, что лежит в LevelInfo.allowedCells и в CellPalette; имя (cell.name) нужно
        // только для подписи. Раньше здесь брали name.lowercase(), и клетки с подчёркиванием
        // (touch_trigger, suction_cup, pheromone_emitter) не совпадали с ключами файла.
        for (cell in DISimulationContainer.cellList.filterNot { it.key in CellPalette.RETIRED_CELLS }) {
            val cellTab = VisTable()
            val cellName = VisLabel(cell.name)
            // Пустая карта в старых уровнях значила «разрешено всё» (см. NavigationCommandsManager).
            val allowed = allowCell.isEmpty() || (allowCell[cell.key] ?: false)
            val cellBool = makeStyledButton("Use", game, mutableListOf<Texture>(), true).apply {
                isChecked = allowed
                addListener(
                object: ClickListener() {
                    override fun clicked(event: InputEvent?, x: Float, y: Float) {
                        super.clicked(event, x, y)
                        changeUse(cell.key, isChecked)
                    }
                }
            )}

            cellTab.add(cellName).left().pad(16.dp())
            cellTab.add(cellBool).right().expandX().pad(8.dp())

            cellsListTab.add(cellTab).fillX().expandX().pad(8.dp()).row()
        }

        val cellsScroll = VisScrollPane(cellsListTab)

        cellsScroll.setScrollingDisabled(true, false)

        table.add(cellsScroll)
            .center()
            .width(400.dp())
            .height(300.dp())
            .expandY()
            .fill()
            .row()

        val btnClose = makeStyledButton("Close", game, mutableListOf(), false).also { button ->
            button.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    super.clicked(event, x, y)
                    hide()
                }
            })
        }

        table.add(btnClose).center().pad(10.dp())

        add(table)
    }

    fun changeUse(name: String, value: Boolean) {
        //allowedCells[name] = value
        allowFunc(name, value)
        println("${name} changed to ${value}")
    }

}




























//Нафиг этот ваш UI... TODO сделать visDSLDialog
