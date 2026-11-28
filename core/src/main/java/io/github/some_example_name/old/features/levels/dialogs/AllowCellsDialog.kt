package io.github.some_example_name.old.features.levels.dialogs

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.makeStyledButton
import io.github.some_example_name.old.core.ui.makeStyledNP
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.features.levels.editor.LevelEditorScreen

/**
 * Какие клетки игрок может использовать в уровне. Только вёрстка: переключатель отдаёт
 * [onToggle] (команда LevelEditorIntent.SetCellAllowed), состояние — в модели редактора.
 *
 * @param cells ключ клетки (то, что лежит в LevelInfo.allowedCells и CellPalette) и имя для
 *   подписи. Раньше здесь брали name.lowercase(), и клетки с подчёркиванием (touch_trigger,
 *   suction_cup, pheromone_emitter) не совпадали с ключами файла.
 */
class AllowCellsDialog(
    cells: List<Pair<String, String>>,
    allowed: Map<String, Boolean>,
    private val onToggle: (key: String, allowed: Boolean) -> Unit
) : VisDialog(bundle.get("level.allowedCells.title")) {

    /** Девятипатчи кнопок: освобождаются вместе с диалогом, см. [remove]. */
    private val textures = mutableListOf<Texture>()

    init {
        isModal = true
        setupTitleSize(game)

        val table = VisTable()
        table.add(VisLabel(bundle.get("level.allowedCells.hint"))).center().pad(6.dp()).row()

        val list = VisTable()
        list.background = makeStyledNP(LevelEditorScreen.PANEL_BG, LevelEditorScreen.PANEL_BORDER, textures)
        list.top()
        for ((key, name) in cells) {
            val row = VisTable()
            val toggle = makeStyledButton(bundle.get("level.allowedCells.use"), game, textures, true).apply {
                isChecked = allowed[key] == true
                addListener(object : ClickListener() {
                    override fun clicked(event: InputEvent?, x: Float, y: Float) {
                        super.clicked(event, x, y)
                        onToggle(key, isChecked)
                    }
                })
            }
            row.add(VisLabel(name)).left().pad(16.dp())
            row.add(toggle).right().expandX().pad(8.dp())
            list.add(row).fillX().expandX().pad(8.dp()).row()
        }

        val scroll = VisScrollPane(list)
        scroll.setScrollingDisabled(true, false)
        table.add(scroll).center().width(400.dp()).height(300.dp()).expandY().fill().row()

        val close = makeStyledButton(bundle.get("button.close"), game, textures, false)
        close.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                super.clicked(event, x, y)
                this@AllowCellsDialog.remove()
            }
        })
        table.add(close).center().pad(10.dp())

        add(table)
        closeOnEscape()
        pack()
        centerWindow()
    }

    override fun remove(): Boolean {
        textures.forEach { it.dispose() }
        textures.clear()
        return super.remove()
    }
}
