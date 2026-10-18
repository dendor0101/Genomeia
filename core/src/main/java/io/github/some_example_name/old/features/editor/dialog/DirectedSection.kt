package io.github.some_example_name.old.features.editor.dialog

import io.github.some_example_name.old.core.ui.hint
import com.kotcrab.vis.ui.widget.VisSlider
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visSlider
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextButton
import io.github.some_example_name.old.systems.genomics.genome.Action
import kotlin.math.PI
import kotlin.math.roundToInt

/** Направленная клетка: угол относительно родителя, стрелка на кружке превью. */
object DirectedSection : CellEditorSection {

    override fun applyDefaults(action: Action) = action.copy(angleDirected = 0f)

    override fun clear(action: Action) = action.copy(angleDirected = null)

    override fun VisTable.compose(dialog: CellDialog) {
        val actionSystem = dialog.actionSystem
        val angleAction = (actionSystem.angleDirected * (180 / PI.toFloat()) * 10).roundToInt() / 10.0f

        val angleLabel = visLabel(text = "${bundle.get("button.angle")}: $angleAction", font = game.extraLargeFont).hint(bundle.get("editor.hint.angle"))
        row()

        visTable({ growX().expandX() }) {

            var angleSlider: VisSlider? = null

            fun changeAngle(delta: Float) {
                angleSlider?.let {
                    val newValue = (it.value + delta).coerceIn(-180f, 180f)
                    it.value = newValue
                }
            }

            visTextButton("<<", onClick = { changeAngle(-1f) }).hint(bundle.get("editor.hint.angleStep"))
            visTextButton("<", onClick = { changeAngle(-0.1f) }).hint(bundle.get("editor.hint.angleFine"))

            angleSlider = visSlider(
                min = -180f,
                max = 180f,
                step = 0.1f,
                value = angleAction,
                onValueChange = { newValue ->
                    val angle = (newValue * 10).roundToInt() / 10f
                    angleLabel.setText("${bundle.get("button.angle")}: $angle")

                    val radianAngle = angle * (Math.PI.toFloat() / 180f)
                    actionSystem.action = actionSystem.action.copy(angleDirected = radianAngle)
                    dialog.circleWidget?.setAngle(radianAngle + actionSystem.angleParent)
                }
            ) {
                growX().expandX()
            }.hint(bundle.get("editor.hint.angle"))

            visTextButton(">", onClick = { changeAngle(0.1f) }).hint(bundle.get("editor.hint.angleFine"))
            visTextButton(">>", onClick = { changeAngle(1f) }).hint(bundle.get("editor.hint.angleStep"))
        }
        row()
    }
}
