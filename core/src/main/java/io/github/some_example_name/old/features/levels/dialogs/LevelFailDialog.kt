package io.github.some_example_name.old.features.levels.dialogs

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Align
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisImageButton
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.makeStyledButton
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.game.applyCustomFontMedium

/**
 * Уровень провален: причина от Lua и два выхода — повторить или уйти в меню.
 *
 * Раньше `loose()` просто делал GoBack: игрок оказывался в меню без объяснения и без
 * возможности попробовать ещё раз. Кнопку «Повторить» экран ведёт в обычный рестарт
 * (SimulationIntent.Restart), то есть тем же путём, что кнопка на панели.
 *
 * [reason] приходит уже текстом для игрока: ключи бандла по умолчанию (провал, лимит
 * времени) разворачивает экран, см. SimulationScreen.checkLevelFail.
 */
class LevelFailDialog(
    reason: String,
    private val onRetry: () -> Unit,
    private val onMenu: () -> Unit
) : VisDialog(bundle.get("level.failed")) {

    /** Девятипатчи кнопок: освобождаются вместе с диалогом. */
    private val textures = mutableListOf<Texture>()

    init {
        isModal = true
        setupTitleSize(game)
        // Крестик у провала не нужен: он закрыл бы окно, оставив игру на паузе, а второй
        // раз окно не покажется (failShown). Выход только через «Повторить» или «В меню».
        getTitleTable().children.filterIsInstance<VisImageButton>().forEach { it.remove() }

        val table = VisTable()

        val message = VisLabel(reason)
        message.setWrap(true)
        message.setAlignment(Align.center)
        game.applyCustomFontMedium(message)
        table.add(message).width(360.dp()).pad(20.dp()).row()

        val buttons = VisTable()
        buttons.add(retryButton()).pad(8.dp())
        buttons.add(menuButton()).pad(8.dp())
        table.add(buttons).padBottom(8.dp()).row()

        add(table)
        pack()
        centerWindow()
    }

    private fun retryButton() = makeStyledButton(bundle.get("level.retry"), game, textures).apply {
        addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                super.clicked(event, x, y)
                // Именно диалог, а не кнопка: голый remove() внутри apply — это
                // Actor.remove() самой кнопки, окно оставалось на экране без кнопок.
                this@LevelFailDialog.remove()
                onRetry()
            }
        })
    }

    private fun menuButton() = makeStyledButton(bundle.get("button.menu"), game, textures).apply {
        addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                super.clicked(event, x, y)
                this@LevelFailDialog.remove()
                onMenu()
            }
        })
    }

    override fun remove(): Boolean {
        textures.forEach { it.dispose() }
        textures.clear()
        return super.remove()
    }
}
