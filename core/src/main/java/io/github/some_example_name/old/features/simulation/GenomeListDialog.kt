package io.github.some_example_name.old.features.simulation

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Scaling
import com.badlogic.gdx.utils.TimeUtils
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.Separator
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.core.ui.STYLE_BEIGE
import io.github.some_example_name.old.core.ui.hoverPopup
import io.github.some_example_name.old.core.ui.makeStyledButton
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.features.genomes.GenomePreviewCache
import io.github.some_example_name.old.game.MyGame
import io.github.some_example_name.old.game.applyCustomFont
import io.github.some_example_name.old.game.applyCustomFontMedium
import kotlin.math.min

/**
 * Выбор генома в симуляции: список с превью, касание строки выбирает геном и закрывает
 * окно. Под списком, вне прокрутки, — «Создать новый геном»: она видна всегда, сколько бы
 * геномов ни было. Наведение (на телефоне — зажатие) на картинку показывает её крупно.
 *
 * Геномами здесь не управляют — переименование, удаление и прочее живут на экране
 * «Геномы» (GenomesScreen).
 */
class GenomeListDialog(
    private val genomesList: List<String>,
    /** Выбранный сейчас геном — строка подсвечена. */
    private val selectedGenome: String?,
    title: String,
    createLabel: String,
    private val onCreate: () -> Unit,
    private val onSelect: (String) -> Unit,
    private val game: MyGame,
    /** Что показать вместо имени из списка: у встроенных геномов — без приставки. */
    private val displayName: (String) -> String = { it },
    /**
     * С этого номера список идёт под чертой с подписью [sectionTitle] — так встроенные
     * геномы отделены от своих. -1 — одним списком.
     */
    private val sectionStart: Int = -1,
    private val sectionTitle: String? = null
) : VisDialog(title) {

    private val textures = mutableListOf<Texture>()

    /**
     * Превью геномов: мелкие для строк и крупные для всплывающей картинки. Рисуются в
     * [act] по нескольку за кадр (см. GenomePreviewCache), освобождаются, когда окно уходит
     * со сцены ([remove]). Крупные рисуются только по наведению — по одной.
     */
    private val previews = GenomePreviewCache(previewPixels(PREVIEW_DP * 1.5f))
    private val bigPreviews = GenomePreviewCache(previewPixels(BIG_PREVIEW_DP))
    private val previewBackground = VisUI.getSkin().newDrawable("white", PREVIEW_FILL)
    private val selectedBackground = VisUI.getSkin().newDrawable("white", SELECTED_FILL)

    /** Строка выбранного генома: к ней прокручивается список при открытии. */
    private var selectedRow: Actor? = null

    init {
        isModal = true
        isMovable = true
        setupTitleSize(game)

        val density = Gdx.graphics.density
        val list = VisTable()
        list.defaults().growX()
        genomesList.forEachIndexed { index, name ->
            if (index == sectionStart) addSection(list, density)
            list.add(genomeRow(name, density)).row()
        }

        val scrollPane = ScrollPane(list).apply {
            setFadeScrollBars(false)
            setScrollingDisabled(true, false)
            setFlickScroll(true)
            setOverscroll(false, true)
            addListener(object : InputListener() {
                override fun enter(event: InputEvent?, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                    stage?.scrollFocus = this@apply
                }
            })
        }

        // Список забирает всё, что останется от экрана под заголовком и кнопкой.
        contentTable.add(scrollPane).grow().minWidth(min(LIST_MIN_WIDTH_DP * density, Gdx.graphics.width * 0.9f))
            .maxHeight(Gdx.graphics.height * 0.65f).pad(8f * density).row()

        val create = makeStyledButton(createLabel, game, textures)
        create.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                fadeOut()
                onCreate()
            }
        })
        contentTable.add(create).height(BUTTON_DP * density).pad(8f * density)

        closeOnEscape()
        pack()
        centerWindow()

        // Прокрутить к выбранному геному: в длинном списке он может быть далеко внизу.
        addAction(Actions.run {
            stage?.scrollFocus = scrollPane
            val selected = selectedRow ?: return@run
            scrollPane.layout()
            scrollPane.scrollTo(selected.x, selected.y, selected.width, selected.height, false, true)
        })
    }

    /** Строка: картинка и имя. Касание строки — выбрать; зажатие картинки — крупное превью. */
    private fun genomeRow(name: String, density: Float): VisTable {
        val row = VisTable()
        row.touchable = Touchable.enabled
        row.pad(4f * density, 8f * density, 4f * density, 8f * density)
        if (name == selectedGenome) {
            row.background = selectedBackground
            selectedRow = row
        }

        val image = Image(previewBackground).apply { setScaling(Scaling.fit) }
        previews.request(name) { region -> if (region != null) image.drawable = TextureRegionDrawable(region) }
        image.hoverPopup { add(bigPreviewImage(name)).size(bigPreviewSize()) }

        val label = VisLabel(displayName(name))
        game.applyCustomFont(label)

        // Картинка не мельче строки: шрифт списка зависит от экрана, а dp — нет.
        row.add(image).size(maxOf(PREVIEW_DP * density, label.prefHeight * 1.2f)).padRight(12f * density)
        row.add(label).left().growX()

        row.addListener(object : ClickListener() {
            private var downAt = 0L

            override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                downAt = TimeUtils.millis()
                return super.touchDown(event, x, y, pointer, button)
            }

            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                // На телефоне зажатие показывает крупную картинку; отпустить палец после
                // просмотра — не значит выбрать. Время здесь — только жест интерфейса.
                if (isTouchScreen && TimeUtils.timeSinceMillis(downAt) >= LONG_PRESS_MS) return
                fadeOut()
                onSelect(name)
            }
        })
        return row
    }

    private fun bigPreviewImage(name: String): Image {
        val image = Image(previewBackground).apply { setScaling(Scaling.fit) }
        bigPreviews.request(name) { region -> if (region != null) image.drawable = TextureRegionDrawable(region) }
        return image
    }

    /** Крупная картинка не больше экрана: на телефоне в альбомной ориентации места мало. */
    private fun bigPreviewSize(): Float {
        val stageSide = min(stage?.width ?: Gdx.graphics.width.toFloat(), stage?.height ?: Gdx.graphics.height.toFloat())
        return min(BIG_PREVIEW_DP * Gdx.graphics.density, stageSide * 0.6f)
    }

    /** Черта и подпись раздела перед номером [sectionStart]. */
    private fun addSection(list: VisTable, density: Float) {
        list.add(Separator()).padTop(8f * density).row()
        if (sectionTitle != null) {
            val label = VisLabel(sectionTitle, Color.LIGHT_GRAY)
            game.applyCustomFontMedium(label)
            list.add(label).left().pad(4f * density, 8f * density, 4f * density, 8f * density).row()
        }
    }

    override fun act(delta: Float) {
        super.act(delta)
        // Между кадрами мира: превью рисуются в свои буферы, экран им не нужен.
        previews.update()
        bigPreviews.update()
    }

    /** Окно ушло со сцены (выбрали, закрыли, «Создать») — картинки и рамки больше не нужны. */
    override fun remove(): Boolean {
        previews.dispose()
        bigPreviews.dispose()
        textures.forEach { it.dispose() }
        textures.clear()
        return super.remove()
    }

    private companion object {
        /** Сторона превью в строке списка. */
        const val PREVIEW_DP = 56f

        /** Сторона крупного превью при наведении. */
        const val BIG_PREVIEW_DP = 400f

        const val LIST_MIN_WIDTH_DP = 360f
        const val BUTTON_DP = 48f

        /** Как у подсказок (core/ui/hoverPopup): столько держать палец, чтобы всплыла картинка. */
        const val LONG_PRESS_MS = 400L

        val PREVIEW_FILL = Color(0.03f, 0.03f, 0.04f, 1f)
        val SELECTED_FILL = Color(STYLE_BEIGE).also { it.a = 0.22f }

        val isTouchScreen: Boolean
            get() = Gdx.app.type == Application.ApplicationType.Android ||
                Gdx.app.type == Application.ApplicationType.iOS

        fun previewPixels(dp: Float) = (dp * Gdx.graphics.density).toInt().coerceIn(32, 512)
    }
}
