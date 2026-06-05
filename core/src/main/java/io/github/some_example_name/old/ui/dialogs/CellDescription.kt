package io.github.some_example_name.old.ui.dialogs

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.I18NBundle
import com.badlogic.gdx.utils.Scaling
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisImage
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.ui.screens.MyGame
import io.github.some_example_name.old.ui.screens.applyCustomFontMedium
import java.util.MissingResourceException

fun I18NBundle.getSafe(key: String, defaultValue: String): String {
    return try {
        this.get(key)
    } catch (e: MissingResourceException) {
        defaultValue
    }
}

class CellDescription(
    val game: MyGame,
    val bundle: I18NBundle,
    cell: String,
    icon: String
) : VisDialog("Cell description") {

    init {
        val density = Gdx.graphics.density
        setupTitleSize(game)

        val maxDialogWidth = 400f * density

        contentTable.defaults().left().pad(6f * density)

        val texture = Texture(Gdx.files.internal(icon))
        val cellImage = VisImage(texture)
        cellImage.setScaling(Scaling.fit)

        contentTable.add(cellImage).width(256*density).height(256*density).center().pad(10f).row()

        val cellName = cell.replaceFirstChar { it.lowercase() }
        val cellText = bundle.getSafe("tutorial.${cellName}", "No tutorial exist, ask author or check update")

        val cellDescription = VisLabel(cellText)
        game.applyCustomFontMedium(cellDescription)

        cellDescription.setWrap(true)
        cellDescription.setAlignment(Align.center)

        contentTable.add(cellDescription)
            .width(maxDialogWidth - 60f * density)
            .center()
            .fillX()
            .row()

        val exitButton = VisTextButton(bundle.get("button.exit"))

        exitButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                this@CellDescription.hide()
            }
        })

        contentTable.add(exitButton).center().height(30*density).row()

        closeOnEscape()
        pack()
    }
}
