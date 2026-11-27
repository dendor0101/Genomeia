package io.github.some_example_name.old.features.levels.dialogs

import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisSelectBox
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextField
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.makeStyledButton
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.features.levels.editor.ScriptedOrganism
import io.github.some_example_name.old.systems.genomics.library.GenomeCatalog

/**
 * Добавление или правка организма, который уровень ставит сам.
 *
 * В уровне хранится имя генома, координата и угол в градусах. Никакой случайности:
 * значения вводятся здесь и едут в LevelInfo, поэтому при каждом запуске получается
 * один и тот же организм в одной и той же точке.
 */
class ScriptedOrganismDialog(
    private val initial: ScriptedOrganism?,
    private val defaultX: Float,
    private val defaultY: Float,
    private val onSave: (ScriptedOrganism) -> Unit
) : VisDialog(if (initial == null) "Добавить организм" else "Изменить организм") {

    private val genomes: List<String> = GenomeCatalog.all()

    init {
        setModal(true)
        setupTitleSize(game)

        val table = VisTable()
        table.defaults().pad(4.dp())

        val genomeSelect = VisSelectBox<String>()
        genomeSelect.setItems(*genomes.map { GenomeCatalog.displayName(it) }.toTypedArray())
        if (initial != null) {
            val selected = genomes.indexOf(initial.genomeName)
            if (selected >= 0) genomeSelect.selectedIndex = selected
        }

        val xField = VisTextField((initial?.x ?: defaultX).toString())
        val yField = VisTextField((initial?.y ?: defaultY).toString())
        val angleField = VisTextField((initial?.angleDegrees ?: 0f).toString())
        val errorLabel = VisLabel("")
        errorLabel.setWrap(true)

        table.add(VisLabel("Геном:")).left()
        table.add(genomeSelect).growX().row()
        table.add(VisLabel("X:")).left()
        table.add(xField).growX().row()
        table.add(VisLabel("Y:")).left()
        table.add(yField).growX().row()
        table.add(VisLabel("Угол, градусы:")).left()
        table.add(angleField).growX().row()
        table.add(errorLabel).colspan(2).growX().row()

        val buttons = VisTable()
        buttons.add(makeStyledButton("OK", game, mutableListOf()).also { button ->
            button.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    super.clicked(event, x, y)
                    val index = genomeSelect.selectedIndex
                    if (index !in genomes.indices) {
                        errorLabel.setText("Нет доступных геномов.")
                        return
                    }
                    val xValue = xField.text.toFloatOrNull()
                    val yValue = yField.text.toFloatOrNull()
                    val angleValue = angleField.text.toFloatOrNull()
                    if (xValue == null || yValue == null || angleValue == null ||
                        !xValue.isFinite() || !yValue.isFinite() || !angleValue.isFinite()
                    ) {
                        errorLabel.setText("X, Y и угол должны быть числами.")
                        return
                    }
                    onSave(
                        ScriptedOrganism(
                            genomeName = genomes[index],
                            x = xValue,
                            y = yValue,
                            angleDegrees = angleValue
                        )
                    )
                    remove()
                }
            })
        }).padRight(8.dp())
        buttons.add(makeStyledButton("Cancel", game, mutableListOf()).also { button ->
            button.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    super.clicked(event, x, y)
                    remove()
                }
            })
        })

        table.add(buttons).colspan(2).center().row()
        contentTable.add(table).pad(10.dp())

        closeOnEscape()
        pack()
        centerWindow()
    }
}
