package io.github.some_example_name.old.features.genomes

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.utils.Align
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
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
import io.github.some_example_name.old.core.ui.w
import io.github.some_example_name.old.game.applyCustomFontMedium

/*
 * Окна экрана «Геномы». Только вёрстка: что сделать, решает экран — колбэк превращается в
 * команду (GenomesIntent). Сами окна ничего не меняют и в журнал не идут.
 */

/** Общее для окон экрана: модальное, с крестиком, закрывается по Esc, по центру. */
private fun VisDialog.setup(content: VisTable.() -> Unit) {
    setupTitleSize(game)
    isModal = true
    isMovable = true
    contentTable.add(globalVisTable(content)).pad(8f.dp())
    closeOnEscape()
    pack()
    centerWindow()
}

/**
 * Окно генома: крупное превью и что с ним можно сделать. null у колбэка — кнопки нет
 * (встроенный геном только открывается в редакторе).
 */
class GenomeCardDialog(
    title: String,
    preview: Image,
    onEdit: () -> Unit,
    onShare: (() -> Unit)?,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?
) : VisDialog(title) {

    init {
        setup {
            add(preview).size(PREVIEW_DP.dp()).padBottom(10f.dp())
            row()
            visTable(cellInit = { growX() }) {
                defaults().growX().pad(4f.dp())
                visTextButton(bundle.get("button.edit"), onClick = { fadeOut(); onEdit() })
                row()
                if (onShare != null) {
                    visTextButton(bundle.get("button.share"), onClick = onShare)
                    row()
                }
                if (onRename != null) {
                    visTextButton(bundle.get("button.rename"), onClick = { fadeOut(); onRename() })
                    row()
                }
                if (onDelete != null) {
                    visTextButton(bundle.get("button.delete"), onClick = { fadeOut(); onDelete() })
                    row()
                }
            }
        }
    }

    companion object {
        /** Сторона крупного превью; на телефоне окно не должно вылезать за экран. */
        val PREVIEW_DP: Float get() = minOf(280f, w * 0.6f / Gdx.graphics.density)
    }
}

/**
 * Новое имя генома. Кнопка доступна, только когда имя годится ([problemOf] — проверка
 * GenomesViewModel.renameProblem), и под полем сказано, чем не годится.
 */
class RenameGenomeDialog(
    current: String,
    problemOf: (String) -> RenameProblem?,
    onRename: (String) -> Unit
) : VisDialog(bundle.get("button.rename")) {

    private lateinit var field: VisTextField

    init {
        setup {
            lateinit var ok: VisTextButton
            lateinit var problem: VisLabel

            fun check(text: String) {
                val reason = problemOf(text)
                ok.isDisabled = reason != null || text.trim() == current
                problem.setText(
                    when (reason) {
                        RenameProblem.EMPTY, null -> ""
                        RenameProblem.INVALID -> bundle.get("label.nameInvalid")
                        RenameProblem.TAKEN -> bundle.get("label.nameTaken")
                    }
                )
            }

            field = visTextField(text = current, onTextChange = { check(it) }) {
                width(320f.dp()).padBottom(4f.dp())
            }
            row()
            problem = visLabel("", font = game.mediumFont) { left().padBottom(8f.dp()) }
            row()
            visTable {
                ok = visTextButton(bundle.get("button.ok"), onClick = {
                    fadeOut()
                    onRename(field.text.trim())
                }) { padRight(8f.dp()) }
                visTextButton(bundle.get("button.cancel"), onClick = { fadeOut() })
            }
            check(current)
        }
    }

    override fun show(stage: Stage): VisDialog {
        val shown = super.show(stage)
        // Сразу печатать, не целясь мышью в поле.
        stage.keyboardFocus = field
        field.selectAll()
        return shown
    }
}

/** Подтверждение удаления: удалённый геном не вернуть. */
class ConfirmDeleteDialog(
    displayName: String,
    onDelete: () -> Unit
) : VisDialog(bundle.get("button.delete")) {

    init {
        setup {
            val message = VisLabel(bundle.format("label.deleteGenome", displayName), Align.center)
            game.applyCustomFontMedium(message)
            message.wrap = true
            add(message).width(320f.dp()).padBottom(12f.dp())
            row()
            visTable {
                visTextButton(bundle.get("button.delete"), onClick = { fadeOut(); onDelete() }) { padRight(8f.dp()) }
                visTextButton(bundle.get("button.cancel"), onClick = { fadeOut() })
            }
        }
    }
}

/** Просто сообщение — например, какие файлы не удалось импортировать. */
class MessageDialog(title: String, message: String) : VisDialog(title) {

    init {
        setup {
            val label = VisLabel(message)
            game.applyCustomFontMedium(label)
            add(label).pad(8f.dp())
        }
    }
}
