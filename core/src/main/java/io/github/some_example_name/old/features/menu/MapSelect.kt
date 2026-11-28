package io.github.some_example_name.old.features.menu

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.VerticalGroup
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Align
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.ui.ConfirmDialog
import io.github.some_example_name.old.core.ui.makeStyledButton
import io.github.some_example_name.old.core.ui.makeStyledIconButton
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.game.MyGame
import io.github.some_example_name.old.systems.maps.MapSave
import io.github.some_example_name.old.systems.maps.SavedMap
import java.text.DateFormat
import java.util.Date

/**
 * Список сохранённых миров: превью, имя, когда сохранён, мусорка и кнопка нового мира.
 *
 * Только вёрстка: что делать с выбором, решает MenuViewModel через [onLoad], [onDelete]
 * и [onNew]. Удаление сначала переспрашивает — файл мира не вернуть.
 */
class MapSelect(
    private val game: MyGame,
    title: String,
    worlds: List<SavedMap>,
    newWorld: String,
    private val onLoad: (slot: String) -> Unit,
    private val onDelete: (slot: String) -> Unit,
    private val onNew: () -> Unit,
    private val onResize: (() -> Unit) -> Unit
) : VisDialog(title) {

    /** Превью и девятипатчи кнопок: освобождаются вместе с диалогом, см. [remove]. */
    private val textures = mutableListOf<Texture>()

    init {
        isModal = true
        isMovable = true
        setupTitleSize(game)

        val density = Gdx.graphics.density
        val buttonHeight = Gdx.graphics.height * 0.055f

        // VerticalGroup, а не таблица: удалённая строка уходит из неё вместе с отступами,
        // а у Table осталась бы пустая ячейка.
        val list = VerticalGroup().grow().space(8f * density)
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        worlds.forEach { world ->
            val label = "${displayName(world.slot)}   ${dateFormat.format(Date(world.lastModified))}"
            list.addActor(worldRow(world.slot, label, PREVIEW_DP * density))
        }

        val scrollPane = ScrollPane(list).apply {
            setFadeScrollBars(false)
            setScrollingDisabled(true, false)
            setFlickScroll(true)
            setOverscroll(false, true)
        }
        contentTable.add(scrollPane).grow().pad(10f * density).maxHeight(Gdx.graphics.height * 0.6f).row()

        makeStyledButton(newWorld, game, textures).also {
            it.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    remove()
                    onNew()
                }
            })
            contentTable.add(it).height(buttonHeight).padBottom(8f * density)
        }

        closeOnEscape()
        onResize { centerWindow() }
        pack()
        centerWindow()
    }

    /** Строка списка: кнопка мира и мусорка справа. */
    private fun worldRow(slot: String, text: String, previewSize: Float): VisTable {
        val row = VisTable()
        row.add(worldButton(slot, text, previewSize)).growX()
        val trash = makeStyledIconButton("icon-trash", textures)
        trash.imageCell.size(TRASH_ICON_DP * Gdx.graphics.density)
        trash.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) = confirmDelete(slot, row)
        })
        row.add(trash).width(TRASH_DP * Gdx.graphics.density).growY().padLeft(8f * Gdx.graphics.density)
        return row
    }

    private fun confirmDelete(slot: String, row: Actor) {
        ConfirmDialog(
            title = bundle.get("button.delete"),
            message = bundle.format("label.deleteMap", displayName(slot)),
            confirm = bundle.get("button.delete"),
            cancel = bundle.get("button.cancel"),
            onConfirm = {
                onDelete(slot)
                // Файл не удалился (занят другой программой) — строка остаётся как была.
                if (!MapSave.exists(slot)) removeRow(row)
            }
        ).show(stage)
    }

    /** Окно становится ниже на строку, а верхний край остаётся на месте. */
    private fun removeRow(row: Actor) {
        row.remove()
        val top = y + height
        pack()
        y = top - height
    }

    /** Кнопка в стиле игры, у которой слева от надписи стоит превью мира. */
    private fun worldButton(slot: String, text: String, previewSize: Float): VisTextButton {
        val button = makeStyledButton(text, game, textures)
        val label = button.label
        label.setAlignment(Align.left)

        button.clearChildren()
        val preview = MapSave.readPreview(slot)?.let { pixmap ->
            Texture(pixmap).also {
                pixmap.dispose()
                it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
                textures.add(it)
            }
        }
        // Мир без превью (сохранён без картинки или файл повреждён) — пустое место того же
        // размера, чтобы надписи в списке стояли ровно.
        if (preview != null) {
            button.add(Image(preview)).size(previewSize).padRight(12f * Gdx.graphics.density)
        } else {
            button.add().size(previewSize).padRight(12f * Gdx.graphics.density)
        }
        button.add(label).growX().left()

        button.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                remove()
                onLoad(slot)
            }
        })
        return button
    }

    /**
     * Сюда приходят все пути закрытия: выбор мира, крестик (fadeOut заканчивается
     * removeActor) и Escape. При выборе диалог снимается сразу, без анимации: экран
     * тут же сменится, и анимация, которую уже некому доиграть, оставила бы текстуры
     * неосвобождёнными.
     */
    override fun remove(): Boolean {
        onResize {}
        textures.forEach { it.dispose() }
        textures.clear()
        return super.remove()
    }

    private companion object {
        /** Превью в списке, в dp. Само превью 128 px — с запасом и на плотных экранах. */
        const val PREVIEW_DP = 64f

        /** Ширина мусорки и размер значка на ней, в dp. */
        const val TRASH_DP = 56f
        const val TRASH_ICON_DP = 28f

        /**
         * Имя мира — имя его файла. Старые сохранения назывались номерами, их показываем
         * как раньше: «#3».
         */
        fun displayName(slot: String): String = if (slot.all(Char::isDigit)) "#$slot" else slot
    }
}
