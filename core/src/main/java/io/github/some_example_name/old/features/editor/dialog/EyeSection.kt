package io.github.some_example_name.old.features.editor.dialog

import io.github.some_example_name.old.core.ui.hint
import com.badlogic.gdx.graphics.Color
import com.kotcrab.vis.ui.widget.VisSlider
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visSlider
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextButton
import io.github.some_example_name.old.core.ui.visToggleButton
import io.github.some_example_name.old.systems.genomics.genome.Action


/** Глаз: дальность взгляда и какие цвета он различает. */
object EyeSection : CellEditorSection {

    override fun applyDefaults(action: Action) = action.copy(
        colorRecognition = 7,
        lengthDirected = 4.25f
    )

    override fun clear(action: Action) = action.copy(
        colorRecognition = null,
        lengthDirected = null
    )

    override fun VisTable.compose(dialog: CellDialog) {
        val actionSystem = dialog.actionSystem
        val distanceAction = (actionSystem.action.lengthDirected ?: actionSystem.clickedCell.actual?.lengthDirected ?: throw Exception("lengthDirected is null")) * 40f
        val distanceLabel = visLabel("${bundle.get("button.distance")} $distanceAction", font = game.extraLargeFont).hint(bundle.get("editor.hint.eyeDistance"))
        row()

        var distanceSlider: VisSlider? = null

        fun changeAngle(delta: Float) {
            distanceSlider?.let { it ->
                it.value = (it.value + delta).coerceIn(26f, 1399f)
            }
        }

        visTable ({ growX().expandX() }) {
            visTextButton("<", onClick = { changeAngle(-1f) })

            distanceSlider = visSlider(25f, 1400f, 1f, value = distanceAction,
                onValueChange = {
                    distanceLabel.setText("${bundle.get("button.distance")}: ${it.toInt()}")

                    actionSystem.action = actionSystem.action.copy(lengthDirected = it / 40f)
                }) { growX().expandX() }.hint(bundle.get("editor.hint.eyeDistance"))

            visTextButton(">", onClick = { changeAngle(1f) })
        }
        row()

        visTable {
            visLabel(bundle.get("button.colorRecognition"), font = game.extraLargeFont) { padRight(16.dp()) }.hint(bundle.get("editor.hint.eyeColor"))

            val color = getColorFromBits(actionSystem.action.colorRecognition ?: actionSystem.clickedCell.actual?.colorRecognition ?: throw Exception("colorRecognition is null"))

            lateinit var rButton: VisTextButton
            lateinit var gButton: VisTextButton
            lateinit var bButton: VisTextButton

            fun updateColor() {
                val r = if (rButton.isChecked) 1f else 0f
                val g = if (gButton.isChecked) 1f else 0f
                val b = if (bButton.isChecked) 1f else 0f
                val bits = encodeColorToBits(r, g, b)
                actionSystem.action = actionSystem.action.copy(colorRecognition = bits)
            }

            rButton = visToggleButton(
                text = "R",
                checked = color.r > 0f,
                onCheckedChange = { updateColor() }
            ).hint(bundle.get("editor.hint.eyeColor"))

            gButton = visToggleButton(
                text = "G",
                checked = color.g > 0f,
                onCheckedChange = { updateColor() }
            ).hint(bundle.get("editor.hint.eyeColor"))

            bButton = visToggleButton(
                text = "B",
                checked = color.b > 0f,
                onCheckedChange = { updateColor() }
            ).hint(bundle.get("editor.hint.eyeColor"))
        }
        row()
    }


    override fun describeMutation(actual: Action, mutate: Action, out: StringBuilder) = with(mutate) {
        if (colorRecognition != null && colorRecognition != actual.colorRecognition) {
            val colorFrom = if (actual.colorRecognition != null) getColorFromBits(actual.colorRecognition) else null
            val colorTo = getColorFromBits(colorRecognition)
            val fromText = if (colorFrom != null) "(r:${if (colorFrom.r > 0) 1 else 0}, g:${if (colorFrom.g > 0) 1 else 0}, b${if (colorFrom.b > 0) 1 else 0})" else null
            out.append("Eye color recognition: $fromText -> (r:${if (colorTo.r > 0) 1 else 0}, g:${if (colorTo.g > 0) 1 else 0}, b${if (colorTo.b > 0) 1 else 0})\n")
        }

        if (lengthDirected != null && lengthDirected != actual.lengthDirected) {
            out.append("Eye distance: ${actual.lengthDirected} -> $lengthDirected\n")
        }
    }
}

fun getColorFromBits(bits: Int): Color {
    if (bits == 0) return Color.BLACK.cpy()

    var r = 0f
    var g = 0f
    var b = 0f
    var count = 0

    if (bits and 1 != 0) {
        r += 1f
        count++
    }
    if (bits and 2 != 0) {
        g += 1f
        count++
    }
    if (bits and 4 != 0) {
        b += 1f
        count++
    }

    return Color(r / count, g / count, b / count, 1f)
}

fun encodeColorToBits(r: Float, g: Float, b: Float): Int {
    var bits = 0
    if (r == 1f) bits = bits or 1
    if (g == 1f) bits = bits or 2
    if (b == 1f) bits = bits or 4
    return bits
}
