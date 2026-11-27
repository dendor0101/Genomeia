package io.github.some_example_name.old.features.levels.dialogs

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Align
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.ui.makeStyledButton
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.game.applyCustomFont
import io.github.some_example_name.old.game.applyCustomFontLarge
import kotlin.math.min

/**
 * Окно уровня из Lua (`popup`, `hint`). Пустой [title] — заголовок по умолчанию из бандла:
 * скрипт гайда зовёт `popup(text)` без заголовка, и раньше окно называлось «title».
 */
class PopUp(title: String, val text: String): VisDialog(title.ifEmpty { DIGameGlobalContainer.bundle.get("level.message") }) {
    init {
        setupTitleSize(DIGameGlobalContainer.game)
        val table = VisTable()

        val titleLabel = VisLabel(title)
        DIGameGlobalContainer.game.applyCustomFontLarge(titleLabel)
        //table.add(titleLabel).center().top().row()

        val textLabel = VisLabel(text)
        DIGameGlobalContainer.game.applyCustomFont(textLabel)

        val textWidth = textLabel.prefWidth

        textLabel.setWrap(true)
        textLabel.setAlignment(Align.center)
        table.add(textLabel).width(min(textWidth, Gdx.graphics.width * 0.8f)).pad(10f).center().top().row()

        val okBut = makeStyledButton(DIGameGlobalContainer.bundle.get("button.ok"), DIGameGlobalContainer.game, mutableListOf()).apply { addListener( object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                super.clicked(event, x, y)
                hide()
            }
        })}

        table.add(okBut).fillX()

        add(table)
    }

    override fun hide() {
        DIGameGlobalContainer.luaEnv.env.onPopupClosed()
        super.hide()
    }
}
