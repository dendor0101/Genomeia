package io.github.some_example_name.old.bug

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Align
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.h
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.core.ui.w

/** Чтение crash.txt не закрывает отчёт и не завершает игру после фонового краша. */
internal class CrashLogDialog(text: String, private val onClosed: () -> Unit) :
    VisDialog(bundle.get("bug.crashLog")) {

    init {
        setupTitleSize(game)
        isModal = true
        isMovable = true

        val width = minOf(w * 0.92f, 1100f.dp())
        // BitmapFont не рисует табуляцию стектрейса: заменяем её отступом только для показа.
        val label = VisLabel(text.replace("\t", "    "), Label.LabelStyle(game.mediumFont, Color.WHITE)).apply {
            setWrap(true)
            setAlignment(Align.topLeft)
        }
        // Фиксируем ширину текста: длинные строки стектрейса переносятся внутри окна.
        val content = VisTable().apply {
            top().left()
            add(label).width(width - 32f.dp()).pad(8f.dp()).top().left()
        }
        val scroll = ScrollPane(content).apply {
            setScrollingDisabled(true, false)
            setFadeScrollBars(false)
            setOverscroll(false, false)
        }
        contentTable.add(scroll).width(width).height(h * 0.72f)

        val closeButton = VisTextButton(bundle.get("bug.close")).apply {
            style = VisTextButton.VisTextButtonStyle(style as VisTextButton.VisTextButtonStyle).apply {
                font = game.mediumFont
            }
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) = close()
            })
        }
        contentTable.row()
        contentTable.add(closeButton).pad(8f.dp()).minWidth(120f.dp())
        closeOnEscape()
        pack()
        centerWindow()
    }

    public override fun close() {
        onClosed()
        super.close()
    }
}
