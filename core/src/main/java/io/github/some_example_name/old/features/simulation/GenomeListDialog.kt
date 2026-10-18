package io.github.some_example_name.old.features.simulation

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.VisCheckBox
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.Separator
import com.kotcrab.vis.ui.widget.VisRadioButton
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.game.MyGame
import io.github.some_example_name.old.game.applyCustomFont
import io.github.some_example_name.old.game.applyCustomFontMedium
import io.github.some_example_name.old.core.ui.makeStyledButton
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.features.genomes.GenomePreviewCache
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Scaling

/**
 * Выбор генома в симуляции: список с превью, «Новый» и «Выбрать». Геномами здесь не
 * управляют — переименование, удаление и прочее живут на экране «Геномы» (GenomesScreen).
 */
class GenomeListDialog(
    val genomesList: List<String>,
    val selectedGenomeIndex: Int?,
    title: String,
    val new: String,
    val select: String,
    /** Кнопка «Новый». null — кнопки нет. */
    val onNew: (() -> Unit)?,
    val onNext: (String) -> Unit,
    val game: MyGame,
    val onResize: (() -> Unit) -> Unit,
    /** Что показать вместо имени из списка: у встроенных геномов — без приставки. */
    val displayName: (String) -> String = { it },
    /**
     * С этого номера список идёт под чертой с подписью [sectionTitle] — так в симуляции
     * встроенные геномы отделены от своих. -1 — одним списком.
     */
    val sectionStart: Int = -1,
    val sectionTitle: String? = null
) : VisDialog(title) {
    var selectedIndex = if (genomesList.isEmpty()) -1 else selectedGenomeIndex ?: 0
    val scrollPane: ScrollPane
    private val textures = mutableListOf<Texture>()

    /**
     * Превью геномов списка. Рисуются в [act] по нескольку за кадр (см. GenomePreviewCache),
     * освобождаются, когда окно уходит со сцены ([remove]).
     */
    private val previews = GenomePreviewCache((PREVIEW_DP * Gdx.graphics.density).toInt().coerceIn(32, 256))
    private val previewBackground = VisUI.getSkin().newDrawable("white", PREVIEW_FILL)

    init {
        isModal = true
        isMovable = true

        val scrollContentTable = VisTable()

        setupTitleSize(game)

        setupUI(scrollContentTable)
        scrollPane = ScrollPane(scrollContentTable).apply {
            setFadeScrollBars(false)
            setScrollingDisabled(false, false)
            setForceScroll(false, true)
            setFlickScroll(true)
            setOverscroll(false, true)
            addListener(object : InputListener() {
                override fun enter(event: InputEvent?, x: Float, y: Float, pointer: Int, fromActor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                    stage?.scrollFocus = this@apply
                }

                override fun mouseMoved(event: InputEvent?, x: Float, y: Float): Boolean {
                    stage?.scrollFocus = this@apply
                    return false
                }
            })
        }

        contentTable.add(scrollPane).grow().maxHeight(Gdx.graphics.height * 0.8f)
        contentTable.row()
        closeOnEscape()
        onResize.invoke { centerWindow() }
        pack()
        centerWindow()
        addAction(Actions.run { stage?.scrollFocus = scrollPane })
    }

    private fun setupUI(scrollContentTable: VisTable) {
        val density = Gdx.graphics.density
        val group = ButtonGroup<VisRadioButton>()
        group.setMinCheckCount(1) // можно ничего не выбирать
        group.setMaxCheckCount(1) // только один выбран одновременно

        // Используем стиль "radio" для круглых иконок (вместо "default", который для чекбоксов квадратных)
        val radioStyle = VisCheckBox.VisCheckBoxStyle(
            VisUI.getSkin().get("radio", VisCheckBox.VisCheckBoxStyle::class.java)
        )
        val iconSize = if (Gdx.app.type == Application.ApplicationType.Android) 10f else 15f  // Базовый размер иконки (подберите)

        // Устанавливаем размеры для круглой иконки: checkBackground - off (пустой круг), tick - on (точка внутри)
        // checkboxOff не существует; используйте checkBackground и tick из VisCheckBoxStyle
        radioStyle.checkBackground.minWidth = iconSize * density
        radioStyle.checkBackground.minHeight = iconSize * density

        radioStyle.tick.minWidth = iconSize * density  // Размер точки (on state); подберите, чтобы соответствовал background
        radioStyle.tick.minHeight = iconSize * density

        // Для состояний over/down/disabled, если они заданы
        if (radioStyle.checkBackgroundOver != null) {
            radioStyle.checkBackgroundOver.minWidth = iconSize * density
            radioStyle.checkBackgroundOver.minHeight = iconSize * density
        }
        if (radioStyle.checkBackgroundDown != null) {
            radioStyle.checkBackgroundDown.minWidth = iconSize * density
            radioStyle.checkBackgroundDown.minHeight = iconSize * density
        }
        if (radioStyle.tickDisabled != null) {
            radioStyle.tickDisabled.minWidth = iconSize * density
            radioStyle.tickDisabled.minHeight = iconSize * density
        }

        // Шрифт, как в оригинале
        radioStyle.font = if (Gdx.app.type == Application.ApplicationType.Android) game.largeFont else game.extraLargeFont

        val content = VisTable(true)

        genomesList.forEachIndexed { index, string ->
            if (index == sectionStart) addSection(content, density)
            val rb = VisRadioButton(displayName(string), radioStyle)
            game.applyCustomFont(rb)
            group.add(rb)
            content.add(previewImage(string)).size(PREVIEW_DP * density).left()
            content.add(rb).left().row()
            if (selectedIndex == index) rb.isChecked = true

            // Обрабатываем именно клик
            rb.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    if (rb.isChecked) {
                        selectedIndex = index
                        if (selectedIndex != -1) {
                            onNext.invoke(genomesList[selectedIndex])
                        }
                        fadeOut()
                    }
                }
            })
        }

        scrollContentTable.add(content).pad(10f * density).row()

        val btnH = Gdx.graphics.height * 0.055f
        val bottomButtonTable = VisTable()
        bottomButtonTable.defaults().padRight(8f * density)

        if (onNew != null) {
            makeStyledButton(new, game, textures).also {
                it.addListener(object : ClickListener() {
                    override fun clicked(event: InputEvent?, x: Float, y: Float) {
                        onNew.invoke(); fadeOut()
                    }
                })
                bottomButtonTable.add(it).height(btnH)
            }
        }

        // Пустой список — выбирать нечего.
        if (genomesList.isNotEmpty()) {
            makeStyledButton(select, game, textures).also {
                it.addListener(object : ClickListener() {
                    override fun clicked(event: InputEvent?, x: Float, y: Float) {
                        if (selectedIndex in genomesList.indices) onNext.invoke(genomesList[selectedIndex])
                        fadeOut()
                    }
                })
                bottomButtonTable.add(it).height(btnH)
            }
        }

        scrollContentTable.add(bottomButtonTable).center().padTop(8f * density)
    }

    /** Квадрат превью: тёмная заглушка, пока картинка не нарисована или снимка нет. */
    private fun previewImage(name: String): Image {
        val image = Image(previewBackground).apply { setScaling(Scaling.fit) }
        previews.request(name) { region -> if (region != null) image.drawable = TextureRegionDrawable(region) }
        return image
    }

    /** Черта и подпись раздела перед номером [sectionStart]. Строка списка — в два столбца. */
    private fun addSection(content: VisTable, density: Float) {
        content.add(Separator()).colspan(2).growX().padTop(8f * density).row()
        if (sectionTitle != null) {
            val label = VisLabel(sectionTitle, Color.LIGHT_GRAY)
            game.applyCustomFontMedium(label)
            content.add(label).colspan(2).left().row()
        }
    }

    override fun act(delta: Float) {
        super.act(delta)
        // Между кадрами мира: превью рисуются в свои буферы, экран им не нужен.
        previews.update()
    }

    /** Окно ушло со сцены (выбрали, закрыли, «Новый») — картинки больше не нужны. */
    override fun remove(): Boolean {
        previews.dispose()
        return super.remove()
    }

    override fun close() {
        super.close()
        onResize.invoke {}
        addAction(Actions.sequence(
            Actions.delay(0.3f),
            Actions.run { textures.forEach { it.dispose() }; textures.clear() }
        ))
    }

    private companion object {
        /** Сторона превью в строке списка. */
        const val PREVIEW_DP = 56f

        val PREVIEW_FILL = Color(0.03f, 0.03f, 0.04f, 1f)
    }
}
