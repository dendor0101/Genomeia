package io.github.some_example_name.old.features.levels.dialogs

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
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
import kotlin.math.min

/**
 * Окно уровня из Lua (`popup`, `hint`). Пустой [title] — заголовок по умолчанию из бандла:
 * скрипт гайда зовёт `popup(text)` без заголовка, и раньше окно называлось «title».
 */
class PopUp(title: String, val text: String) : VisDialog(title.ifEmpty { DIGameGlobalContainer.bundle.get("level.message") }) {

    /** Девятипатчи кнопки: освобождаются вместе с окном, см. [remove]. */
    private val textures = mutableListOf<Texture>()

    init {
        setupTitleSize(DIGameGlobalContainer.game)
        val table = VisTable()

        val textLabel = VisLabel(text)
        DIGameGlobalContainer.game.applyCustomFont(textLabel)
        val textWidth = textLabel.prefWidth
        textLabel.setWrap(true)
        textLabel.setAlignment(Align.center)
        table.add(textLabel).width(min(textWidth, Gdx.graphics.width * 0.8f)).pad(10f).center().top().row()

        val ok = makeStyledButton(DIGameGlobalContainer.bundle.get("button.ok"), DIGameGlobalContainer.game, textures)
        ok.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                super.clicked(event, x, y)
                hide()
            }
        })
        table.add(ok).fillX()

        add(table)
    }

    override fun hide() {
        DIGameGlobalContainer.luaEnv.env.onPopupClosed()
        super.hide()
    }

    override fun remove(): Boolean {
        textures.forEach { it.dispose() }
        textures.clear()
        return super.remove()
    }
}
