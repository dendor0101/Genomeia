package io.github.some_example_name.old.features.simulation

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Slider
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.I18NBundle
import com.badlogic.gdx.utils.Scaling
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.VisImageButton
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisSlider
import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.base.CellPalette
import io.github.some_example_name.old.core.ui.STYLE_BEIGE
import io.github.some_example_name.old.core.ui.disableScrollWhileDragging
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.hint
import io.github.some_example_name.old.core.ui.makeStyledButton
import io.github.some_example_name.old.core.ui.makeStyledNP
import io.github.some_example_name.old.core.ui.makeStyledSlider
import io.github.some_example_name.old.game.MyGame
import io.github.some_example_name.old.game.applyCustomFont
import io.github.some_example_name.old.game.applyCustomFontMedium
import io.github.some_example_name.old.game.applyCustomFontSmall
import io.github.some_example_name.old.systems.rules.CellRule
import io.github.some_example_name.old.systems.rules.RuleFormat
import io.github.some_example_name.old.systems.rules.RuleSection
import io.github.some_example_name.old.systems.rules.WorldRule
import io.github.some_example_name.old.systems.rules.WorldRules
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Что показывает панель настроек мира: значения и стандарт (WorldRules). Снимок берёт экран
 * между тиками; дальше панель правит его сама вместе с командами: на паузе команды ждут тика, а
 * ползунки должны стоять там, куда их поставил игрок. Держит его экран, поэтому вкладка и
 * выбранная клетка переживают закрытие панели и смену размера окна.
 */
class WorldRulesView(
    val values: FloatArray,
    val standard: FloatArray,
    val cellValues: FloatArray,
    val cellStandard: FloatArray,
    val typeCount: Int
) {
    var section = RuleSection.ENVIRONMENT
    var cellType = 0

    fun resetToStandard() {
        standard.copyInto(values)
        cellStandard.copyInto(cellValues)
    }

    /** Отличается ли что-то у типа клетки [type] от стандарта — значок типа подсвечивается. */
    fun isCellChanged(type: Int): Boolean =
        CellRule.entries.any { cellValues[it.ordinal * typeCount + type] != cellStandard[it.ordinal * typeCount + type] }

    companion object {
        /** Звать между тиками (SimulationSystem.betweenTicks): правила пишет поток симуляции. */
        fun of(rules: WorldRules) = WorldRulesView(
            rules.values.copyOf(), rules.standard.copyOf(),
            rules.cellValues.copyOf(), rules.cellStandard.copyOf(), rules.cellKeys.size
        )
    }
}

/**
 * ПАНЕЛЬ «НАСТРОЙКИ МИРА» — справа поверх идущего мира, не модальная: мир под ней живёт и
 * отвечает на касания, правка видна сразу. Вкладки — «Среда», «Организмы», «Клетки» (там своя
 * строка значков типов). Каждая настройка — название, значение, ползунок с границами под ним и
 * отметкой стандарта на дорожке; изменённую можно вернуть своей кнопкой, все — «Сбросить всё».
 * Подсказка о смысле — при наведении мыши на строку или зажатии пальцем на её названии
 * (hint: на телефоне зажатие на ползунке тянуло бы его, а не показывало подсказку).
 *
 * Как все окна экрана, сама мир не меняет: [onRule] и [onReset] экран превращает в команды мира
 * (PlayerCommand.SetWorldRule, ResetWorldRules). Числа уходят итоговые — проценты ползунка
 * переведены здесь, до команды.
 *
 * На узком экране панель закрывает почти весь мир, поэтому, пока тянут ползунок, она
 * становится полупрозрачной — как окно настроек микроскопа.
 */
class WorldSettingsPanel(
    private val game: MyGame,
    private val bundle: I18NBundle,
    private val icons: HudIcons,
    private val view: WorldRulesView,
    private val cells: List<Cell>,
    /** Шире панель не бывает (ширина экрана без полей); уже — тоже: ей нужно уместить заголовок и вкладки. */
    maxWidth: Float,
    panelHeight: Float,
    private val onRule: (key: String, cellType: String, value: Float) -> Unit,
    private val onReset: () -> Unit,
    private val onClose: () -> Unit
) : Table() {

    private val textures = mutableListOf<Texture>()
    private val cellTextures = HashMap<String, Texture>()
    private val refreshers = ArrayList<() -> Unit>()
    private val sliders = ArrayList<Slider>()
    private var refreshing = false
    private val content = Table()
    private val scroll = VisScrollPane(content)
    private val tabs = HashMap<RuleSection, Button>()

    /** Панель шире этой доли экрана — на время ползунка она прозрачнеет. Задаётся в init. */
    private var fadeWhileDragging = false
    private var dragProgress = 0f

    // Общие для всех кнопок и ползунков панели: иначе каждая строка заводила бы свои текстуры.
    private val sliderStyle = makeStyledSlider(0f, 1f, 1f, false, textures).style
    private val iconStyle = VisImageButton.VisImageButtonStyle().apply {
        up = makeStyledNP(BTN_FILL, Color(STYLE_BEIGE).also { it.a = 0.45f }, textures)
        over = makeStyledNP(BTN_OVER, Color(STYLE_BEIGE), textures)
        down = makeStyledNP(BTN_DOWN, Color.WHITE, textures)
        checked = makeStyledNP(BTN_CHECKED, Color.WHITE, textures)
    }
    private val line: Drawable = VisUI.getSkin().newDrawable("white", Color(STYLE_BEIGE).also { it.a = 0.14f })
    private val markDrawable: Drawable = VisUI.getSkin().newDrawable("white", Color.WHITE)
    /** Рамка значка типа клетки, у которого что-то изменено. До init: значки строит уже первая вкладка. */
    private val changedFrame = makeStyledNP(BTN_FILL, Color.WHITE, textures)

    init {
        touchable = Touchable.enabled
        background = makeStyledNP(PANEL_FILL, Color(STYLE_BEIGE).also { it.a = 0.8f }, textures)
        pad(PAD_DP.dp())

        // --- заголовок и закрытие ---
        // На узком экране заголовок обрезается многоточием, а не залезает под крестик.
        val title = VisLabel(bundle.get("worldRules.title")).also {
            game.applyCustomFont(it); it.color = STYLE_BEIGE; it.setEllipsis(true)
        }
        val close = iconButton(icons.close, BUTTON_DP)
        close.hint(bundle.get("worldRules.close"))
        close.onClick(onClose)
        val titleRow = Table().apply {
            add(title).growX().left().minWidth(0f)
            add(close).size(BUTTON_DP.dp()).padLeft(6f.dp())
        }
        add(titleRow).growX().row()
        val subtitle = VisLabel(bundle.get("worldRules.subtitle")).also {
            game.applyCustomFontSmall(it); it.color = DIM; it.setWrap(true)
        }
        add(subtitle).growX().padBottom(8f.dp()).row()

        // --- вкладки ---
        val tabRow = Table()
        for (section in RuleSection.entries) {
            val tab = makeStyledButton(bundle.get("worldRules.tab.${section.key}"), game, textures, toggle = true)
            tab.isChecked = section == view.section
            tab.hint(bundle.get("worldRules.tab.${section.key}.hint"))
            // Переключатель сам снимает отметку при повторном нажатии — возвращаем её.
            tab.onClick {
                view.section = section
                showSection()
            }
            tabs[section] = tab
            tabRow.add(tab).uniformX().growX().minWidth(0f).pad(0f, 2f.dp(), 0f, 2f.dp())
        }
        add(tabRow).growX().padBottom(8f.dp()).row()

        // Ширина — по заголовку и вкладкам: шрифты растут с экраном не так, как dp, и на ноутбуке
        // с плотностью меньше единицы 440 dp не вмещали ни заголовок, ни «Организмы».
        val needed = maxOf(titleRow.prefWidth, tabRow.prefWidth) + 2 * PAD_DP.dp()
        val panelWidth = maxOf(PANEL_DP.dp(), needed).coerceAtMost(maxWidth)
        setSize(panelWidth, panelHeight)
        fadeWhileDragging = panelWidth > Gdx.graphics.width * FADE_SCREEN_SHARE

        // --- содержимое вкладки ---
        scroll.setScrollingDisabled(true, false)
        scroll.setFadeScrollBars(false)
        scroll.setOverscroll(false, false)
        scroll.addCaptureListener(object : InputListener() {
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                // Начатая инерция прокрутки не должна двигать дорожку под пальцем.
                if (sliders.any { event.target.isDescendantOf(it) }) {
                    scroll.fling(0f, 0f, 0f); scroll.scrollY = scroll.visualScrollY; scroll.updateVisualScroll()
                }
                return false
            }
        })
        add(scroll).grow().row()

        // --- сброс всего ---
        val reset = makeStyledButton(bundle.get("worldRules.resetAll"), game, textures)
        reset.hint(bundle.get("worldRules.resetAll.hint"))
        reset.onClick {
            onReset()
            view.resetToStandard()
            syncControls()
        }
        add(reset).growX().padTop(8f.dp()).row()
        val footer = VisLabel(bundle.get("worldRules.footer")).also {
            game.applyCustomFontSmall(it); it.color = DIM; it.setWrap(true); it.setAlignment(Align.center)
        }
        add(footer).growX().padTop(4f.dp()).row()

        // Касание панели — панели, а не миру под ней. Колесо над панелью листает её, а не
        // приближает камеру: колесо сцена отдаёт тому, у кого фокус прокрутки.
        addListener(object : InputListener() {
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) = true

            override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                if (pointer == -1) stage?.scrollFocus = scroll
            }

            override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                if (pointer == -1 && (toActor == null || !toActor.isDescendantOf(this@WorldSettingsPanel))) {
                    if (stage?.scrollFocus === scroll) stage?.scrollFocus = null
                }
            }
        })

        showSection()
    }

    /** Справа, под верхней полосой кнопок экрана ([topReserve] — её высота с отступами). */
    fun place(stage: Stage, topReserve: Float) {
        setPosition(stage.width - width - EDGE_DP.dp(), (stage.height - topReserve - height).coerceAtLeast(EDGE_DP.dp()))
    }

    // --- вкладки --------------------------------------------------------------------------

    private fun showSection() {
        for ((section, tab) in tabs) tab.isChecked = section == view.section
        content.clear()
        refreshers.clear()
        sliders.clear()
        content.top().pad(2f.dp(), 0f, 2f.dp(), 4f.dp())
        content.defaults().growX()
        when (view.section) {
            RuleSection.CELLS -> showCells()
            else -> for (rule in WorldRule.entries) if (rule.section == view.section) worldRow(rule)
        }
        scroll.scrollY = 0f
        scroll.updateVisualScroll()
    }

    private fun worldRow(rule: WorldRule) {
        val i = rule.ordinal
        ruleRow(rule.key, rule.format, rule.min, rule.max, rule.step, view.standard[i], { view.values[i] }) { v ->
            view.values[i] = v
            onRule(rule.key, "", v)
        }
    }

    /**
     * «Клетки»: значки типов, затем выбранный тип — значок, имя, описание — и его настройки.
     * Выведенные из игры типы (CellPalette.RETIRED_CELLS) не показываются: их нет и в палитре
     * редактора. Значки — рядом с переносом по ширине панели (HorizontalGroup.wrap): число
     * столбцов, посчитанное заранее, расходилось с настоящей шириной прокрутки, и ряд вылезал за край.
     */
    private fun showCells() {
        val shown = cells.indices.filter { cells[it].key !in CellPalette.RETIRED_CELLS }
        if (shown.isEmpty()) return
        if (view.cellType !in shown) view.cellType = shown.first()

        val size = CELL_BUTTON_DP.dp()
        val gap = 6f.dp()
        val grid = HorizontalGroup().wrap().space(gap).wrapSpace(gap).rowLeft().left()
        for (type in shown) {
            val cell = cells[type]
            val button = VisImageButton(cellIconStyle(cell, changed = view.isCellChanged(type)))
            button.image.setScaling(Scaling.fit)
            button.pad(5f.dp())
            button.isChecked = type == view.cellType
            button.hint(cell.name)
            button.onClick {
                view.cellType = type
                showSection()
            }
            grid.addActor(Container(button).size(size))
        }
        content.add(grid).growX().padBottom(10f.dp()).row()

        val type = view.cellType
        val cell = cells[type]
        val name = VisLabel(cell.name).also { game.applyCustomFont(it); it.color = Color.WHITE }
        val description = VisLabel(cell.description).also {
            game.applyCustomFontSmall(it); it.color = DIM; it.setWrap(true)
        }
        content.add(Table().apply {
            add(Image(TextureRegionDrawable(TextureRegion(cellTexture(cell)))).apply { setScaling(Scaling.fit) })
                .size(48f.dp()).padRight(10f.dp())
            add(name).growX().left().minWidth(0f)
        }).padBottom(4f.dp()).row()
        if (cell.description.isNotEmpty()) content.add(description).growX().minWidth(0f).padBottom(10f.dp()).row()
        separator()

        val key = cell.key
        for (rule in CellRule.entries) {
            val k = rule.ordinal * view.typeCount + type
            ruleRow(rule.key, rule.format, rule.min, rule.max, rule.step, view.cellStandard[k], { view.cellValues[k] }) { v ->
                view.cellValues[k] = v
                onRule(rule.key, key, v)
            }
        }
    }

    // --- строка настройки -----------------------------------------------------------------

    /**
     * Название и значение, ползунок с отметкой стандарта, границы под ним. [value] — текущее
     * значение, [apply] — новое (уже итоговое) значение от игрока.
     */
    private fun ruleRow(
        key: String, format: RuleFormat, min: Float, max: Float, step: Float,
        standard: Float, value: () -> Float, apply: (Float) -> Unit
    ) {
        val standardSlider = format.toSlider(standard, standard)
        // Доля стандарта при стандарте 0 — всегда 0: ползунку нечего двигать.
        val usable = !(format == RuleFormat.PERCENT && standard == 0f)

        val name = VisLabel(bundle.get("worldRules.$key")).also {
            game.applyCustomFontMedium(it); it.color = STYLE_BEIGE; it.setWrap(true)
        }
        val number = VisLabel("").also { game.applyCustomFontMedium(it); it.setAlignment(Align.right) }
        val back = iconButton(icons.reset, RESET_DP)
        back.hint(bundle.format("worldRules.resetOne", text(format, step, standardSlider)))

        val slider = object : VisSlider(min, max, step, false, sliderStyle) {
            override fun getPrefHeight() = maxOf(super.getPrefHeight(), SLIDER_DP.dp())
        }
        slider.disableScrollWhileDragging()
        slider.isDisabled = !usable
        sliders += slider

        fun show() {
            val s = format.toSlider(value(), standard)
            val changed = usable && abs(s - standardSlider) > step * 0.5f
            // Обычный дефис: длинного тире нет в шрифте игры (только латиница-1 и кириллица).
            number.setText(if (usable) text(format, step, s) else "-")
            number.color = if (changed) Color.WHITE else STYLE_BEIGE
            back.isVisible = changed
        }
        fun moveSlider(to: Float) {
            refreshing = true
            try { slider.value = to } finally { refreshing = false }
        }

        moveSlider(format.toSlider(value(), standard))
        show()
        slider.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                if (refreshing) return
                val s = slider.value
                // На отметке стандарта — сам стандарт, а не его перевод туда и обратно через проценты.
                apply(if (abs(s - standardSlider) < step * 0.5f) standard else format.fromSlider(s, standard))
                show()
            }
        })
        back.onClick {
            moveSlider(standardSlider)
            apply(standard)
            show()
        }
        refreshers += { moveSlider(format.toSlider(value(), standard)); show() }

        val header = Table().apply {
            add(name).growX().left().minWidth(0f)
            add(number).right().minWidth(64f.dp()).padLeft(6f.dp())
            add(back).size(RESET_DP.dp()).padLeft(4f.dp())
        }
        val track = Stack()
        if (usable && standardSlider in min..max) track.add(StandardMark((standardSlider - min) / (max - min), markDrawable))
        track.add(slider)
        val range = Table().apply {
            add(caption(text(format, step, min))).left()
            add().growX()
            add(caption(text(format, step, max))).right()
        }

        val row = Table()
        row.add(header).growX().row()
        row.add(track).growX().padTop(2f.dp()).row()
        row.add(range).growX().padTop((-6f).dp()).row()

        val hint = bundle.get("worldRules.$key.hint") + "\n" + bundle.format("worldRules.standard", text(format, step, standardSlider))
        // Мышью — вся строка; пальцем — только название: зажатый ползунок должен тянуться.
        if (isTouchScreen) header.hint(hint) else row.hint(hint)

        content.add(row).padBottom(6f.dp()).row()
        separator()
    }

    private fun separator() {
        content.add(Image(line)).height(1f.dp().coerceAtLeast(1f)).padBottom(10f.dp()).row()
    }

    private fun caption(text: String) = VisLabel(text).also { game.applyCustomFontSmall(it); it.color = DIM }

    private fun text(format: RuleFormat, step: Float, slider: Float): String = when (format) {
        RuleFormat.PERCENT -> "${slider.roundToInt()}%"
        RuleFormat.TIMES -> "×" + trimmed(slider, 2)
        RuleFormat.NUMBER -> String.format(Locale.ROOT, "%.${decimals(step)}f", slider)
    }

    private fun decimals(step: Float) = when {
        step >= 1f -> 0
        step >= 0.1f -> 1
        else -> 2
    }

    private fun trimmed(v: Float, decimals: Int): String =
        String.format(Locale.ROOT, "%.${decimals}f", v).trimEnd('0').trimEnd('.')

    /** Поставить все ползунки по значениям (после «Сбросить всё»): одна команда, без десятков. */
    private fun syncControls() {
        refreshers.forEach { it() }
        // Подсветка изменённых типов клеток — заново строится вместе с вкладкой.
        if (view.section == RuleSection.CELLS) showSection()
    }

    // --- кнопки и значки ------------------------------------------------------------------

    private fun iconButton(icon: TextureRegion, sizeDp: Float): VisImageButton {
        val style = VisImageButton.VisImageButtonStyle(iconStyle).apply {
            // Не переключатель: кнопка libGDX иначе осталась бы «нажатой» после щелчка.
            checked = null
            val drawable = TextureRegionDrawable(icon)
            imageUp = drawable.tint(STYLE_BEIGE)
            imageOver = drawable.tint(Color.WHITE)
            imageDown = imageOver
        }
        return VisImageButton(style).also {
            it.image.setScaling(Scaling.fit)
            it.pad(sizeDp * 0.2f * Gdx.graphics.density)
        }
    }

    /** Значок типа клетки — картинкой как есть; изменённый тип — со светлой рамкой. */
    private fun cellIconStyle(cell: Cell, changed: Boolean) = VisImageButton.VisImageButtonStyle(iconStyle).apply {
        if (changed) up = changedFrame
        imageUp = TextureRegionDrawable(TextureRegion(cellTexture(cell)))
    }

    /**
     * Значок типа — картинка из ui/cell-icons. У типов без своей картинки там заглушка
     * not_cell.png (красный квадрат), поэтому им — кружок цвета клетки, как она выглядит в мире.
     */
    private fun cellTexture(cell: Cell): Texture = cellTextures.getOrPut(cell.key) {
        val file = Gdx.files.internal("ui/cell-icons/${cell.textureName}")
        if (cell.textureName != NO_ICON && file.exists()) {
            Texture(file, true).apply { setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear) }
        } else drawnCellIcon(cell.defaultColor)
    }

    private fun drawnCellIcon(color: Color): Texture {
        val size = 64
        val p = Pixmap(size, size, Pixmap.Format.RGBA8888)
        p.blending = Pixmap.Blending.None
        p.setColor(0f, 0f, 0f, 0f); p.fill()
        p.setColor(color.r * 0.55f, color.g * 0.55f, color.b * 0.55f, 1f); p.fillCircle(size / 2, size / 2, size / 2 - 3)
        p.setColor(color.r, color.g, color.b, 1f); p.fillCircle(size / 2, size / 2, size / 2 - 10)
        val texture = Texture(p, true).apply { setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear) }
        p.dispose()
        return texture
    }

    private fun <T : Button> T.onClick(action: () -> Unit): T = apply {
        addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) = action()
        })
    }

    // --- прозрачность на время ползунка -------------------------------------------------------

    override fun act(delta: Float) {
        super.act(delta)
        if (!fadeWhileDragging) return
        val step = delta / FADE_SECONDS
        dragProgress = (dragProgress + if (sliders.any { it.isDragging }) step else -step).coerceIn(0f, 1f)
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val opacity = 1f - FADE_DEPTH * Interpolation.smooth.apply(dragProgress)
        super.draw(batch, parentAlpha * opacity)
    }

    /** Панель ушла со сцены — её рамки, ползунки и значки клеток больше не рисуются. */
    override fun remove(): Boolean {
        val stage = stage
        if (stage != null && stage.scrollFocus === scroll) stage.scrollFocus = null
        val removed = super.remove()
        textures.forEach { it.dispose() }
        textures.clear()
        cellTextures.values.forEach { it.dispose() }
        cellTextures.clear()
        return removed
    }

    /**
     * Отметка стандарта на дорожке: тонкая черта там, где встанет центр ручки при стандарте.
     * Ручка ходит от половины своей ширины до ширины минус половина (ProgressBar), отсюда и [KNOB_DP].
     */
    private class StandardMark(private val fraction: Float, private val drawable: Drawable) : Actor() {
        init {
            touchable = Touchable.disabled
            color = Color(STYLE_BEIGE).also { it.a = 0.9f }
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val knob = KNOB_DP.dp()
            val w = 2f.dp().coerceAtLeast(1f)
            val h = 14f.dp()
            val c = batch.color.cpy()
            batch.setColor(color.r, color.g, color.b, color.a * parentAlpha)
            drawable.draw(batch, x + knob / 2 + (width - knob) * fraction - w / 2, y + (height - h) / 2, w, h)
            batch.color = c
        }
    }

    private companion object {
        /** Наименьшая ширина панели; больше — если заголовку и вкладкам нужно больше. */
        const val PANEL_DP = 440f
        const val PAD_DP = 12f
        /** Картинка-заглушка типа без своего значка (Cell.textureName по умолчанию). */
        const val NO_ICON = "not_cell.png"
        const val EDGE_DP = 8f
        const val BUTTON_DP = 40f
        const val RESET_DP = 28f
        const val CELL_BUTTON_DP = 44f
        const val SLIDER_DP = 40f
        /** Ручка ползунка — как у makeStyledSlider. */
        const val KNOB_DP = 20f
        const val FADE_SCREEN_SHARE = 0.6f
        const val FADE_SECONDS = 0.18f
        const val FADE_DEPTH = 0.7f

        val PANEL_FILL = Color(0.09f, 0.09f, 0.11f, 0.93f)
        val BTN_FILL = Color(0.16f, 0.16f, 0.18f, 0.82f)
        val BTN_OVER = Color(0.28f, 0.27f, 0.30f, 0.90f)
        val BTN_DOWN = Color(0.38f, 0.37f, 0.42f, 0.95f)
        val BTN_CHECKED = Color(0.40f, 0.36f, 0.26f, 0.95f)
        val DIM = Color(STYLE_BEIGE).also { it.a = 0.6f }

        val isTouchScreen: Boolean
            get() = Gdx.app.type == Application.ApplicationType.Android ||
                Gdx.app.type == Application.ApplicationType.iOS
    }
}
