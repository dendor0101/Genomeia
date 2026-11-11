package io.github.some_example_name.old.bug

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.makeStyledButton
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.core.ui.w

internal class BugMessageDialog(titleKey: String, message: String, private val dismissed: () -> Unit) :
    VisDialog(bundle.get(titleKey)) {
    private val messageLabel = VisLabel(message, Label.LabelStyle(game.mediumFont, Color.WHITE))
    private val messageCell = contentTable.add(messageLabel)
    private val textures = mutableListOf<Texture>()

    init {
        setupTitleSize(game)
        titleLabel.style = Label.LabelStyle(game.largeFont, Color.WHITE)
        titleLabel.setEllipsis(false)
        titleLabel.setWrap(true)
        titleTable.getCell(titleLabel).minWidth(0f).growX()
        isModal = true
        messageLabel.setWrap(true)
        messageCell.pad(8.dp()).growX()
        contentTable.row()
        closeOnEscape()
        fitToViewport()
    }

    fun choice(key: String, action: () -> Unit = {}) {
        val button = makeStyledButton(bundle.get(key), game, textures)
        button.label.setWrap(true)
        button.labelCell.minWidth(0f).growX()
        button.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                close()
                action()
            }
        })
        contentTable.add(button).growX().pad(4.dp()).minHeight(40.dp()).row()
    }

    fun fitToViewport() {
        val bodyWidth = minOf(440.dp(), w - padLeft - padRight - 24.dp()).coerceAtLeast(1f)
        messageCell.width(bodyWidth)
        val closeWidth = titleTable.getCell(titleTable.children.peek()).prefWidth
        titleLabel.width = (bodyWidth - closeWidth - titleTable.padLeft - titleTable.padRight).coerceAtLeast(1f)
        titleTable.invalidateHierarchy()
        padTop(titleTable.prefHeight)
        pack()
        centerWindow()
    }

    public override fun close() {
        hide(null)
        textures.forEach { it.dispose() }
        textures.clear()
        dismissed()
    }
}
