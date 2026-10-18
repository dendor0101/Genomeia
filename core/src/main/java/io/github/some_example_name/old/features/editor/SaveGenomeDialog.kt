package io.github.some_example_name.old.features.editor

import io.github.some_example_name.old.core.ui.hint
import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Action
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.globalVisTable
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextButton
import io.github.some_example_name.old.core.ui.visTextField

/**
 * Сохранение генома. Только вёрстка: сама запись в файл (с запеканием раскладки) — команда
 * редактора, её отдают колбэки. Имя в поле — черновик, в команду уходит то, что в поле
 * на момент нажатия.
 */
//TODO перейти на compose стиль
class SaveGenomeDialog(
    val genomeName: String,
    val onSave: (name: String) -> Unit,
    val onSaveAndTest: (name: String) -> Unit,
    /** Android: сохранить и отдать файл системе. */
    val onSaveAndExport: (name: String) -> Unit,
    val onGoMenu: () -> Unit,
    val isGoToMenu: Boolean
) : VisDialog(bundle.get("button.saveGenome")) {

    private val textures = mutableListOf<Texture>()

    fun VisTable.compose() {
        var genomeNameField: VisTextField? = null
        var saveToFileAndTestButton: VisTextButton? = null
        var saveToFileButton: VisTextButton? = null
        var exportButton: VisTextButton? = null

        visTable ( {growX().expandX()}) {
            visLabel(bundle.get("button.name")) {
                pad(8.dp())
            }.hint(bundle.get("editor.hint.genomeName"))

            genomeNameField = visTextField(text = genomeName, onTextChange = { text ->
                val isDisabled = text.isEmpty()
                saveToFileButton?.isDisabled = isDisabled
                saveToFileAndTestButton?.isDisabled = isDisabled
                exportButton?.isDisabled = isDisabled
            }) {
                growX().expandX()
            }.hint(bundle.get("editor.hint.genomeName"))
        }
        row()

        visTable ( {growX().expandX()}) {
            saveToFileAndTestButton = visTextButton(bundle.get("button.saveAndTest"), onClick = {
                val name = genomeNameField?.text ?: throw Exception("genomeNameField is null")
                onSaveAndTest.invoke(name)
                fadeOut()
            }).hint(bundle.get("editor.hint.saveAndTest"))
        }
        row()

        visTable {
            saveToFileButton = visTextButton(bundle.get("button.saveToFile"), onClick = {
                val name = genomeNameField?.text ?: throw Exception("genomeNameField is null")
                onSave.invoke(name)
            }).hint(bundle.get("editor.hint.saveToFile"))
            if (isGoToMenu) {
                visTextButton(bundle.get("button.menu"), onClick = {
                    onGoMenu.invoke()
                }) { center() }.hint(bundle.get("editor.hint.menuWithoutSaving"))
            }
        }
        row()

        visTable {
            if (Gdx.app.type == Application.ApplicationType.Android) {
                exportButton = visTextButton(bundle.get("button.saveAndExport"), onClick = {
                    val name = genomeNameField?.text ?: throw Exception("genomeNameField is null")
                    onSaveAndExport.invoke(name)
                }) { center() }.hint(bundle.get("editor.hint.saveAndExport"))
            }

            visTextButton("Share with everyone", onClick = {
                val name = genomeNameField?.text ?: throw Exception("genomeNameField is null")
                // Пока выгрузки на сервер нет, кнопка только сохраняет.
                onSave.invoke(name)

//                // Загружаем на сервер
//                val genomeFile = Gdx.files.local("genomes/$name.genome")
//
//                CoroutineScope(Dispatchers.IO).launch {
//                    try {
//                        ApiClient.uploadFile(genomeFile.file())   // .file() → java.io.File
//                        Gdx.app.postRunnable {
//                            // Здесь можно показать уведомление "Успешно отправлено"
//                            println("Genome \"$name\" успешно загружен на сервер")
//                        }
//                    } catch (e: Exception) {
//                        e.printStackTrace()
//                        Gdx.app.postRunnable {
//                            // Здесь можно показать диалог с ошибкой
//                            println("Ошибка загрузки: ${e.message}")
//                        }
//                    }
//                }
            }) { center() }
        }
        row()



        val isDisabled = genomeNameField?.text?.isEmpty() ?: false
        saveToFileButton?.isDisabled = isDisabled
        saveToFileAndTestButton?.isDisabled = isDisabled
        exportButton?.isDisabled = isDisabled
    }

    init {
        setupTitleSize(game)

        isModal = true
        isMovable = true

        val rootTable = globalVisTable { compose() }

        contentTable.add(rootTable)

        closeOnEscape()

        pack()
        centerWindow()
    }

    override fun hide(action: Action?) {
        super.hide(action)
        addAction(
            Actions.sequence(
            Actions.delay(0.3f),
            Actions.run { textures.forEach { it.dispose() }; textures.clear() }
        ))
    }
}
