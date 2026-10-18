package io.github.some_example_name.old.features.editor.dialog

import io.github.some_example_name.old.core.ui.hint
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Action
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisRadioButton
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.editor.system.Axial
import io.github.some_example_name.old.editor.system.NoSymmetry
import io.github.some_example_name.old.editor.system.SquareGrid
import io.github.some_example_name.old.editor.system.SymmetryMode
import io.github.some_example_name.old.editor.system.TriangleGrid
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.game.applyCustomFont
import io.github.some_example_name.old.core.ui.makeStyledButton

/**
 * Выбор симметрии. Сам диалог её не меняет: выбранный режим уходит командой через
 * [onApply], а [current] нужен только чтобы отметить текущий.
 */
class SymmetryDialog(
    val current: SymmetryMode,
    val onApply: (SymmetryMode) -> Unit
) : VisDialog(bundle.get("dialog.select_symmetry") ?: "Select symmetry") {

    private val textures = mutableListOf<Texture>()
    private val buttonGroup = ButtonGroup<VisRadioButton>()
    private val noSymmetryButton   = VisRadioButton(bundle.get("symmetry.none")     ?: "No symmetry")
    private val axialButton        = VisRadioButton(bundle.get("symmetry.axial")    ?: "Axial symmetry")
    private val squareGridButton   = VisRadioButton(bundle.get("symmetry.square")   ?: "Square grid")
    private val triangleGridButton = VisRadioButton(bundle.get("symmetry.triangle") ?: "Triangle grid")

    init {
        val density = Gdx.graphics.density
        setupTitleSize(game)

        contentTable.pad(8f * density)
        contentTable.defaults().left().pad(4f * density)

        buttonGroup.add(noSymmetryButton, axialButton, squareGridButton, triangleGridButton)
        buttonGroup.setMaxCheckCount(1); buttonGroup.setMinCheckCount(1)
        listOf(noSymmetryButton, axialButton, squareGridButton, triangleGridButton)
            .forEach { game.applyCustomFont(it) }
        noSymmetryButton.hint(bundle.get("editor.hint.symmetryNone"))
        axialButton.hint(bundle.get("editor.hint.symmetryAxial"))
        squareGridButton.hint(bundle.get("editor.hint.symmetrySquare"))
        triangleGridButton.hint(bundle.get("editor.hint.symmetryTriangle"))

        // 2-column grid of radio buttons
        val radioGrid = VisTable()
        radioGrid.add(noSymmetryButton).padRight(16f * density)
        radioGrid.add(axialButton).row()
        radioGrid.add(squareGridButton).padRight(16f * density)
        radioGrid.add(triangleGridButton)
        contentTable.add(radioGrid).colspan(2).padBottom(12f * density).row()

        // Horizontal OK / Cancel
        val btnH = Gdx.graphics.height * 0.055f
        val okBtn = makeStyledButton(bundle.get("button.ok") ?: "OK", game, textures)
        val cancelBtn = makeStyledButton(bundle.get("button.cancel") ?: "Cancel", game, textures)

        okBtn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor?) {
                applySelectedSymmetry()
                fadeOut()
            }
        })
        cancelBtn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor?) {
                fadeOut()
            }
        })

        val btnRow = VisTable()
        btnRow.add(okBtn).height(btnH).padRight(8f * density)
        btnRow.add(cancelBtn).height(btnH)
        contentTable.add(btnRow).center().colspan(2)

        when (current) {
            is NoSymmetry  -> noSymmetryButton.isChecked   = true
            Axial          -> axialButton.isChecked         = true
            is SquareGrid  -> squareGridButton.isChecked    = true
            is TriangleGrid -> triangleGridButton.isChecked = true
        }

        closeOnEscape()
        pack()
        centerWindow()
    }

    private fun applySelectedSymmetry() {
        val selected = when {
            noSymmetryButton.isChecked   -> NoSymmetry
            axialButton.isChecked        -> Axial
            squareGridButton.isChecked   -> current as? SquareGrid ?: SquareGrid(0.6f)
            else                         -> current as? TriangleGrid ?: TriangleGrid(0.6f)
        }
        if (selected != current) onApply(selected)
    }

    override fun hide(action: Action?) {
        super.hide(action)
        addAction(Actions.sequence(
            Actions.delay(0.3f),
            Actions.run { textures.forEach { it.dispose() }; textures.clear() }
        ))
    }
}
