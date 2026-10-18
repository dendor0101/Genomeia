package io.github.some_example_name.old.features.simulation

import com.badlogic.gdx.scenes.scene2d.Stage
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.globalVisTable
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextButton
import io.github.some_example_name.old.core.ui.visTextField
import io.github.some_example_name.old.systems.maps.MapNames

/**
 * Имя мира перед сохранением. Только вёрстка: сохраняет команда SaveWorld, её строит
 * экран из имени, которое отдаёт [onSave]. Окно не закрывается само — экран может ещё
 * спросить про перезапись и вернуть игрока к имени ([editName]).
 */
class SaveWorldDialog(
    name: String,
    private val onSave: (name: String) -> Unit
) : VisDialog(bundle.get("dialog.saveWorld")) {

    private lateinit var nameField: VisTextField
    private lateinit var saveButton: VisTextButton
    private lateinit var error: VisLabel

    init {
        isModal = true
        isMovable = true
        setupTitleSize(game)

        contentTable.add(globalVisTable {
            visTable({ growX() }) {
                visLabel(bundle.get("button.name")) { pad(8.dp()) }
                nameField = visTextField(text = name, onTextChange = { checkName() }) {
                    growX().minWidth(MIN_FIELD_DP.dp())
                }
            }
            row()
            // Почему имя не подходит.
            // Переносится по ширине окна: длинная подсказка не должна раздвигать окно за экран.
            error = visLabel("", font = game.mediumFont) { growX().pad(0f, 8.dp(), 0f, 8.dp()) }
            error.wrap = true
            row()
            visTable {
                saveButton = visTextButton(bundle.get("button.save"), onClick = ::submit)
                visTextButton(bundle.get("button.cancel"), onClick = { fadeOut() })
            }
        })

        nameField.maxLength = MapNames.MAX_LENGTH
        // Enter в поле — то же, что кнопка «Сохранить».
        nameField.setTextFieldListener { _, c -> if (c == '\r' || c == '\n') submit() }
        checkName()

        closeOnEscape()
        pack()
        centerWindow()
    }

    override fun show(stage: Stage): VisDialog {
        super.show(stage)
        editName()
        return this
    }

    /** Фокус в поле с выделенным именем: можно сразу печатать новое. */
    fun editName() {
        nameField.focusField()
        nameField.selectAll()
    }

    private fun currentName(): String = nameField.text.trim()

    private fun checkName() {
        val valid = MapNames.isValid(currentName())
        saveButton.isDisabled = !valid
        // Пустое поле и так видно — подсказка нужна, когда непонятно, что не так.
        val hint = if (valid || nameField.text.isBlank()) "" else bundle.get("label.mapNameInvalid")
        if (error.text.toString() == hint) return
        error.setText(hint)
        if (stage == null) return
        // Окно меняет высоту под подсказку, а верхний край остаётся на месте.
        val top = y + height
        pack()
        y = top - height
    }

    private fun submit() {
        if (saveButton.isDisabled) return
        onSave(currentName())
    }

    private companion object {
        /** Поле не уже этого, даже если имя по умолчанию короткое. */
        const val MIN_FIELD_DP = 220
    }
}
