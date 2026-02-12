package io.github.some_example_name.old.genome_editor

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.I18NBundle
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTextField
import io.github.some_example_name.old.genome.json.GenomeJsonReader
import io.github.some_example_name.old.genome.json.write.CreatureJsonWrite
import io.github.some_example_name.old.genome_editor.dialog.actionButton
import io.github.some_example_name.old.screens.MyGame
import io.github.some_example_name.old.screens.applyCustomFont
import io.github.some_example_name.old.screens.applyCustomFontMedium
import io.github.some_example_name.old.screens.setupTitleSize

class SaveGenomeDialog(
    val genomeJsonReader: GenomeJsonReader,
    val genome: CreatureJsonWrite,
    val onSaveAndTest: (String) -> Unit,
    val onGoMenu: () -> Unit,
    val game: MyGame,
    val bundle: I18NBundle,
    isGoToMenu: Boolean
) : VisDialog(bundle.get("button.saveGenome")) {

    init {
        setupTitleSize(game)

        val density = Gdx.graphics.density

        val genomeNameTable = Table()
        val genomeText = VisLabel(bundle.get("button.name"))
        game.applyCustomFontMedium(genomeText)
        val genomeNameField = VisTextField()
        game.applyCustomFont(genomeNameField)
        genomeNameField.setText(genome.name)
        genomeNameTable.add(genomeText).padRight(15f * density)
        genomeNameTable.add(genomeNameField).width(220f * density)

        contentTable.add(genomeNameTable).padBottom(15f * density).row()

        val saveToFileAndTestButton = actionButton(bundle.get("button.saveAndTest"), game) {
            genome.name = genomeNameField.text
            genomeJsonReader.saveGenomeToFile(genome, "user_genomes/${genomeNameField.text}.json")
            onSaveAndTest.invoke("${genomeNameField.text}.json")
            fadeOut()
        }.also {
            it.labelCell.pad(8f * density)
            game.applyCustomFont(it)
            contentTable.add(it).padBottom(15f * density).fillX().row()
        }

        val saveToFileButton = actionButton(bundle.get("button.saveToFile"), game) {
            genome.name = genomeNameField.text
            genomeJsonReader.saveGenomeToFile(genome, "user_genomes/${genomeNameField.text}.json")
        }.also {
            it.labelCell.pad(8f * density)
            game.applyCustomFont(it)
            contentTable.add(it).padBottom(15f * density).fillX().row()
        }

        val exportButton =
            if (Gdx.app.type == Application.ApplicationType.Android) {
                actionButton(bundle.get("button.saveAndExport"), game) {
                    genome.name = genomeNameField.text
                    genomeJsonReader.saveGenomeToFile(
                        genome,
                        "user_genomes/${genomeNameField.text}.json"
                    )
                    game.multiPlatformFileProvider.exportGenome("user_genomes/${genomeNameField.text}.json")
                }.also {
                    it.labelCell.pad(8f * density)
                    game.applyCustomFont(it)
                    contentTable.add(it).padBottom(15f * density).fillX().row()
                }
            } else null

        if (isGoToMenu) {
            actionButton(bundle.get("button.menu"), game) {
                onGoMenu.invoke()
            }.also {
                it.labelCell.pad(8f * density)
                game.applyCustomFont(it)
                contentTable.add(it).padBottom(15f * density).fillX().row()
            }
        }

        val isDisabled = genomeNameField.text.isEmpty()
        saveToFileButton.isDisabled = isDisabled
        saveToFileAndTestButton.isDisabled = isDisabled
        exportButton?.isDisabled = isDisabled

        genomeNameField.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) {
                val isDisabled = genomeNameField.text.isEmpty()
                saveToFileButton.isDisabled = isDisabled
                saveToFileAndTestButton.isDisabled = isDisabled
                exportButton?.isDisabled = isDisabled
            }
        })
    }
}
