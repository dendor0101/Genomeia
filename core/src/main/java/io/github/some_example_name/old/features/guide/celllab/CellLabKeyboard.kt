package io.github.some_example_name.old.features.guide.celllab

import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.scenes.scene2d.Stage
import io.github.some_example_name.old.bug.BugReplay
import io.github.some_example_name.old.core.ui.KeyboardFocus
import io.github.some_example_name.old.journal.CellLabInput
import io.github.some_example_name.old.systems.simulation.ControllerKeys

/** Физическая клавиатура и экранная кнопка независимы; в журнал попадает снимок клавиш. */
internal class CellLabKeyboard(private val session: CellLabSession, private val stage: Stage) : InputAdapter() {
    override fun keyDown(keycode: Int) = change(keycode, true)
    override fun keyUp(keycode: Int) = change(keycode, false)

    private fun change(keycode: Int, pressed: Boolean): Boolean {
        val model = session.model ?: return false
        if (model.key != "controller" || BugReplay.isActive || KeyboardFocus.isTyping(stage)) return false
        val index = ControllerKeys.KEY_CODES.indexOf(keycode)
        if (index < 0) return false
        val current = mask()
        val bit = 1 shl index
        val next = if (pressed) current or bit else current and bit.inv()
        if (next != current) session.input(CellLabInput("keyboard", value = next.toFloat()))
        return true
    }

    private fun mask(): Int {
        var mask = 0
        session.model?.world?.simulationData?.controllerKeys?.forEachIndexed { i, pressed ->
            if (pressed) mask = mask or (1 shl i)
        }
        return mask
    }

    fun releaseWhileTyping() {
        if (!BugReplay.isActive && KeyboardFocus.isTyping(stage) && mask() != 0)
            session.input(CellLabInput("keyboard", value = 0f))
    }
}
