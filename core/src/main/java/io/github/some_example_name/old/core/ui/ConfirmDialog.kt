package io.github.some_example_name.old.core.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.Align
import com.kotcrab.vis.ui.widget.VisDialog
import io.github.some_example_name.old.core.DIGameGlobalContainer.game

/**
 * Вопрос «да или нет» перед необратимым действием (перезаписать мир, удалить мир).
 *
 * Только вёрстка: [onConfirm] и [onCancel] отдают выбор экрану, а тот уже строит команду.
 * Крестик и Escape — то же, что [onCancel].
 */
class ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    cancel: String,
    private val onConfirm: () -> Unit,
    private val onCancel: () -> Unit = {}
) : VisDialog(title) {

    /** Выбор сделан кнопкой: закрытие после него — не отмена. */
    private var answered = false

    init {
        isModal = true
        isMovable = true
        setupTitleSize(game)

        contentTable.add(globalVisTable {
            visLabel(message, align = Align.center) {
                // Перенос строк — только когда текст (с длинным именем мира) не влезает в
                // экран телефона: у переносимой метки нет своей ширины, её задаёт ячейка.
                val label = actor
                width(minOf(label.prefWidth, Gdx.graphics.width * 0.8f)).pad(8.dp())
                label.wrap = true
            }
            row()
            visTable {
                visTextButton(confirm, onClick = { answer(onConfirm) })
                visTextButton(cancel, onClick = { answer(onCancel) })
            }
        })

        closeOnEscape()
        pack()
        centerWindow()
    }

    private fun answer(action: () -> Unit) {
        answered = true
        fadeOut()
        action()
    }

    /** Крестик и Escape закрывают окно через close() — для экрана это отмена. */
    override fun close() {
        super.close()
        if (!answered) {
            answered = true
            onCancel()
        }
    }
}
