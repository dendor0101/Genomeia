package io.github.some_example_name.old.genome_editor.dialog

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.I18NBundle
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.VisDialog
import io.github.some_example_name.old.genome_editor.EditorCell
import io.github.some_example_name.old.screens.MyGame
import io.github.some_example_name.old.screens.applyCustomFont
import io.github.some_example_name.old.screens.setupTitleSize

class MutateOrDivideDialog(
    val clickedCell: EditorCell,
    val onDivide: () -> Unit,
    val onMutate: () -> Unit,
    val game: MyGame,
    val bundle: I18NBundle
) : VisDialog("${bundle.get("button.cellId")} ${clickedCell.id}") {

    init {
        val density = Gdx.graphics.density
        setupTitleSize(game)

        actionButton(bundle.get("button.divide"), game = game) {
            onDivide.invoke()
            fadeOut()
        }.also {
            // Добавляем внутренние отступы вокруг текста кнопки
            // Adding padding around the button text
            it.labelCell.pad(5f * density).padLeft(25f * density).padRight(25f * density)
            contentTable.add(it).padBottom(8f * density).fillX().row()
        }

        actionButton(bundle.get("button.mutate"), game = game) {
            onMutate.invoke()
            fadeOut()
        }.also {
            it.labelCell.pad(5f * density).padLeft(25f * density).padRight(25f * density)
            contentTable.add(it).fillX().row()
        }

        closeOnEscape()
    }

}
