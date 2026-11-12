package io.github.some_example_name.old.core.ui

import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.kotcrab.vis.ui.widget.VisTextField
import io.github.some_example_name.old.bug.BugOverlay

/**
 * Печатает ли сейчас игрок.
 *
 * Горячие клавиши экранов опрашивают клавиатуру напрямую (Gdx.input.isKeyPressed), мимо
 * событий scene2d, — поэтому текстовое поле их не «съедает»: набор описания бага нажимал
 * игровые клавиши. «в» в русской раскладке — это клавиша D, «н» — Y, пробел — клавиша контроллера. Экраны
 * пропускают опрос, пока здесь true.
 */
object KeyboardFocus {
    fun isTyping(stage: Stage?): Boolean =
        BugOverlay.isDialogOpen || stage?.keyboardFocus is TextField || stage?.keyboardFocus is VisTextField
}
