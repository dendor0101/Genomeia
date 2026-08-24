package io.github.some_example_name.old.core.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Widget
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Align
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisSelectBox
import com.kotcrab.vis.ui.widget.VisSlider
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import com.badlogic.gdx.utils.Array as GdxArray

var IS_DEBUG = false

/**
 * Базовая библиотека DSL для VisUI (libGDX), стилизованная под Jetpack Compose.
 *
 * Главная цель — удобный декларативный синтаксис + мощное позиционирование через VisTable.
 * Это ЗАГОТОВКА под твою собственную библиотеку. Легко расширять:
 *  - добавляй новые vis* функции (VisCheckBox, VisTextField, VisProgressBar, VisWindow и т.д.)
 *  - добавляй state hoisting, modifiers, recomposition-like обновления
 *  - интегрируй с ktx если нужно, или делай полностью свою
 *
 * Использование:
 *   VisUI.load() // в create()
 *   val table = visTable { ... }
 *   stage.addActor(table)
 *
 * Требуемые зависимости (build.gradle):
 *   api "com.kotcrab.vis:vis-ui:1.5.x"   // или актуальная версия под твой libGDX
 *   // + стандартные libGDX core
 */

object VisCompose {
    /**
     * Создаёт VisTable (с дефолтными настройками VisUI при true).
     * Внутри блока используй visLabel / visTextButton / visSelectBox / visImage + row()
     */
    fun table(init: VisTable.() -> Unit): VisTable {
        val table = VisTable(true) // true включает удобные отступы и поведение для VisUI-компонентов
        table.debug = IS_DEBUG
        table.init()
        return table
    }
}

val w get() = Gdx.graphics.width.toFloat()
val h get() = Gdx.graphics.height.toFloat()
val density get() = Gdx.graphics.density

fun Float.dp() = this * density
fun Int.dp() = this.toFloat() * density

/**
 * Top-level функция для красивого Compose-like синтаксиса.
 * Используй именно её в своём коде: visTable { ... }
 *
 * Это решает "Unresolved reference 'visTable'"
 */
fun globalVisTable(init: VisTable.() -> Unit): VisTable = VisCompose.table(init)

/**
 * Вложенная таблица (гарантированно создаёт отдельный VisTable).
 * Можно указать цвет фона.
 *
 * Примеры использования:
 *
 * visTable { ... }                                           // обычная
 *
 * visTable(backgroundColor = Color(0.12f, 0.15f, 0.18f, 1f)) { ... }  // с фоном
 *
 * visTable(
 *     backgroundColor = Color(0.15f, 0.12f, 0.10f, 1f),
 *     cellInit = { expandX().fillX().pad(12f) }
 * ) { ... }
 */
fun VisTable.visTable(
    cellInit: (Cell<VisTable>.() -> Unit) = {},
    backgroundColor: Color? = null,
    init: VisTable.() -> Unit
): VisTable {
    val nested = VisTable(true)
    nested.debug = IS_DEBUG

    if (backgroundColor != null) {
        nested.background = VisUI.getSkin().newDrawable("white", backgroundColor)
    }

    nested.init()

    val cell = this.add(nested) as Cell<VisTable>
    cellInit(cell)

    return nested
}

// ==================== РАСШИРЕНИЯ ДЛЯ VisTable (удобное позиционирование) ====================

/**
 * VisLabel — текстовая метка.
 * @param cellInit — лямбда для Cell: expandX(), center(), pad(10f), fillX() и т.д. (точно как в Compose modifiers)
 */
fun VisTable.visLabel(
    text: String,
    textColor: Color = STYLE_BEIGE,
    align: Int = Align.left,
    // titleFont is display-only (menu logo); body UI must use a readable size
    font: BitmapFont = game.largeFont,
    cellInit: (Cell<VisLabel>.() -> Unit) = {}
): VisLabel {

    val label = VisLabel(text)
    label.style = Label.LabelStyle(font, textColor)
    label.setAlignment(align)                    // ← вот это главное

    val cell = this.add(label) as Cell<VisLabel>
    cellInit(cell)
    return label
}

/**
 * VisTextButton — кнопка с текстом.
 * @param onClick — чистая лямбда, как в Compose onClick = { }
 */
fun VisTable.visTextButton(
    text: String,
    onClick: (() -> Unit)? = null,
    cellInit: (Cell<VisTextButton>.() -> Unit) = {}
): VisTextButton {
    val button = makeStyledButton(text, game, mutableListOf())
    if (onClick != null) {
        button.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                onClick()
            }
        })
    }
    val cell = this.add(button) as Cell<VisTextButton>
    cellInit(cell)
    return button
}

/**
 * Toggle-кнопка в стиле Jetpack Compose.
 * Полностью использует твой makeStyledButton с toggle=true.
 */
fun VisTable.visToggleButton(
    text: String,
    checked: Boolean = false,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    cellInit: (Cell<VisTextButton>.() -> Unit) = {}
): VisTextButton {
    val button = makeStyledButton(text, game, mutableListOf(), toggle = true)

//    button.toggle = true          // ← вот так правильно (публичное поле Button)
    button.isChecked = checked

    if (onCheckedChange != null) {
        button.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                onCheckedChange(button.isChecked)
            }
        })
    }

    val cell = this.add(button) as Cell<VisTextButton>
    cellInit(cell)
    return button
}

private fun createArrowTextureRegion(): TextureRegion {
    val size = 32
    val p = Pixmap(size, size, Pixmap.Format.RGBA8888)
    p.setColor(0f, 0f, 0f, 0f); p.fill()

    val baseW = 14
    val h = 11
    val startX = (size - baseW) / 2
    val startY = (size - h) / 2

    p.setColor(STYLE_BEIGE)
    for (row in 0 until h) {
        val progress = row.toFloat() / h
        val w = (baseW * (1f - progress * 0.9f)).toInt().coerceAtLeast(1)
        val x = startX + (baseW - w) / 2
        p.fillRectangle(x, startY + row, w, 1)
    }

    val tex = Texture(p)
    tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
    p.dispose()
    return TextureRegion(tex)
}

/**
 * Кастомный SelectBox со стрелочкой, нарисованной в runtime.
 * Стрелочка теперь полностью независима от размера и масштаба NinePatch.
 */
class StyledSelectBox<T> : VisSelectBox<T>(makeCleanSelectBoxStyle()) {

    companion object {
        // Стрелочка создаётся один раз и переиспользуется
        private val arrowRegion: TextureRegion by lazy {
            createArrowTextureRegion()
        }

        private const val ARROW_WIDTH = 20f
        private const val ARROW_HEIGHT = 15f
        private const val ARROW_RIGHT_PADDING = 12f
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        super.draw(batch, parentAlpha)

        // Рисуем стрелочку поверх
        val arrowX = x + width - ARROW_WIDTH - ARROW_RIGHT_PADDING
        val arrowY = y + (height - ARROW_HEIGHT) / 2f

        batch.draw(
            arrowRegion,
            arrowX,
            arrowY,
            ARROW_WIDTH,
            ARROW_HEIGHT
        )
    }
}

fun <T> VisTable.visSelectBox(
    items: Array<T>,
    selectedIndex: Int = 0,
    onChange: ((T, Int) -> Unit)? = null,
    cellInit: (Cell<StyledSelectBox<T>>.() -> Unit) = {}
): StyledSelectBox<T> {
    val selectBox = StyledSelectBox<T>()

    val gdxArray = GdxArray(items)
    if (items.isNotEmpty()) {
        selectBox.items = gdxArray
        selectBox.selectedIndex = selectedIndex.coerceIn(0, items.size - 1)
    }

    if (onChange != null) {
        selectBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                @Suppress("UNCHECKED_CAST")
                onChange(selectBox.selected as T, selectBox.selectedIndex)
            }
        })
    }

    val cell = this.add(selectBox) as Cell<StyledSelectBox<T>>
    cellInit(cell)
    return selectBox
}

// ==================== visImage из ассетов (Compose-style) ====================

/**
 * visImage напрямую из assets/ (Gdx.files.internal).
 * Пример:
 *   visImage("ui/icons/heart.png") {
 *       size(32f.dp(), 32f.dp())
 *   }
 *
 * ВАЖНО: каждый вызов создаёт новый Texture.
 * Для продакшена используй AssetManager + передавай Texture / TextureRegion.
 */
fun VisTable.visImage(
    assetPath: String,
    cellInit: (Cell<Image>.() -> Unit) = {}
): Image {
    val texture = Texture(Gdx.files.internal(assetPath))
    val drawable = TextureRegionDrawable(TextureRegion(texture))
    return visImage(drawable, cellInit)
}

/**
 * visImage из уже загруженной Texture (рекомендуемый способ).
 */
fun VisTable.visImage(
    texture: Texture,
    cellInit: (Cell<Image>.() -> Unit) = {}
): Image {
    val drawable = TextureRegionDrawable(TextureRegion(texture))
    return visImage(drawable, cellInit)
}

/**
 * visImage из TextureRegion (лучше всего для TextureAtlas).
 */
fun VisTable.visImage(
    region: TextureRegion,
    cellInit: (Cell<Image>.() -> Unit) = {}
): Image {
    val drawable = TextureRegionDrawable(region)
    return visImage(drawable, cellInit)
}

/**
 * Image — для картинок / иконок.
 * @param drawable — обычно VisUI.skin.getDrawable("name") или TextureRegionDrawable
 */
fun VisTable.visImage(
    drawable: Drawable,
    cellInit: (Cell<Image>.() -> Unit) = {}
): Image {
    val image = Image(drawable)
    val cell = this.add(image) as Cell<Image>
    cellInit(cell)
    return image
}


/**
 * Нормальный ScrollPane в стиле Jetpack Compose.
 * Теперь content — последний параметр → можно писать красиво с trailing lambda.
 *
 * Пример использования (максимально близко к Compose):
 * visTable {
 *     visScrollPane({ expand().fill() }) {
 *         forEach(items) { item ->
 *             visLabel(item)
 *             row()
 *         }
 *     }
 * }
 */
fun VisTable.visScrollPane(
    cellInit: (Cell<VisScrollPane>.() -> Unit) = {},
    fadeScrollBars: Boolean = true,
    scrollingDisabledX: Boolean = false,
    scrollingDisabledY: Boolean = false,
    content: VisTable.() -> Unit
): VisScrollPane {
    val contentTable = VisTable(true)
    contentTable.content()

    val scrollPane = VisScrollPane(contentTable).apply {
        setFadeScrollBars(fadeScrollBars)           // ← теперь работает правильно
        // Отключённый скролл по оси означает ещё и то, что содержимое получает ширину
        // (или высоту) панели, а не свою желаемую — только так вертикальная колонка
        // настроек ужимается по ширине вместо того, чтобы уезжать за край.
        setScrollingDisabled(scrollingDisabledX, scrollingDisabledY)
        setForceScroll(false, true)
        setFlickScroll(true)
        setOverscroll(false, false)
        setSmoothScrolling(true)
        setTouchable(Touchable.enabled)

        addListener(object : InputListener() {
            override fun enter(event: InputEvent?, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                stage?.scrollFocus = this@apply
            }
        })
    }

    val cell = this.add(scrollPane) as Cell<VisScrollPane>
    cellInit(cell)
    return scrollPane
}

/**
 * Повторяет блок указанное количество раз.
 * Отлично подходит, когда нужно отрисовать много одинаковых элементов.
 *
 * Пример:
 * visTable {
 *     repeat(5) { index ->
 *         visTextButton("Кнопка ${index + 1}")
 *         row()
 *     }
 * }
 */
fun VisTable.repeat(count: Int, block: VisTable.(index: Int) -> Unit) {
    for (i in 0 until count) {
        block(i)
    }
}

/**
 * Итерирует по любой коллекции и выполняет блок для каждого элемента **внутри таблицы**.
 *
 * Пример:
 * val inventory = listOf("Меч", "Щит", "Зелье")
 * visTable {
 *     forEach(inventory) { item ->
 *         visLabel(item)
 *         row()
 *     }
 * }
 */
fun <T> VisTable.forEach(items: Iterable<T>, block: VisTable.(item: T) -> Unit) {
    items.forEach { item ->
        block(item)
    }
}


/**
 * VisSlider — стилизованный ползунок (аналог Slider из Jetpack Compose).
 *
 * Создаёт VisSlider с кастомным rounded стилем из makeStyledSlider.
 * Поддерживает начальное значение и onValueChange для реактивности (как value + onValueChange в Compose).
 *
 * @param min минимальное значение диапазона
 * @param max максимальное значение диапазона
 * @param step шаг изменения значения (дискретность)
 * @param vertical true = вертикальный слайдер
 * @param value начальное значение ползунка (controlled-like)
 * @param onValueChange вызывается при изменении пользователем (передаёт новое value)
 * @param cellInit лямбда для Cell (expandX().fillX(), pad(...) и т.д. — как modifiers в Compose)
 */
fun VisTable.visSlider(
    min: Float,
    max: Float,
    step: Float,
    vertical: Boolean = false,
    value: Float = min,
    onValueChange: ((Float) -> Unit)? = null,
    cellInit: (Cell<VisSlider>.() -> Unit) = {}
): VisSlider {
    val slider = makeStyledSlider(min, max, step, vertical, mutableListOf())
    slider.value = value.coerceIn(min, max)

    if (onValueChange != null) {
        slider.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                onValueChange(slider.value)
            }
        })
    }
    val cell = this.add(slider) as Cell<VisSlider>
    cellInit(cell)
    return slider
}


fun VisTable.visLeftArrowButton(
    onClick: (() -> Unit)? = null,
    cellInit: (Cell<StyledLeftArrowButton>.() -> Unit) = {}
): StyledLeftArrowButton {
    val button = StyledLeftArrowButton()

    if (onClick != null) {
        button.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                onClick()
            }
        })
    }

    val cell = this.add(button) as Cell<StyledLeftArrowButton>
    cellInit(cell)
    return button
}


// ==================== visFlowRow (Flow / Wrap Row) ====================

/**
 * Выравнивание строк в visFlowRow.
 */
enum class FlowAlignment {
    Start, Center, End
}

/**
 * visFlowRow — горизонтальный контейнер с переносом элементов на новую строку.
 * Полностью в стиле Jetpack Compose.
 *
 * Пример использования:
 * visFlowRow(alignment = FlowAlignment.Center, horizontalSpacing = 12f.dp()) {
 *     if (Gdx.app.type == Application.ApplicationType.Android) {
 *         add(ctrlZ)
 *         add(ctrlY)
 *         add(ctrl)
 *         add(rightClick)
 *     }
 * }
 */
fun VisTable.visFlowRow(
    alignment: FlowAlignment = FlowAlignment.Start,
    horizontalSpacing: Float = 8f.dp(),
    verticalSpacing: Float = 8f.dp(),
    maxWidth: Float = w,
    content: VisTable.() -> Unit
) {
    // Выполняем лямбду на временной таблице, чтобы все visTextButton / visLeftArrowButton { ... } работали как обычно
    val tempTable = VisTable(true)
    tempTable.content()

    // Забираем всех добавленных актёров
    val actors = mutableListOf<Actor>()
    tempTable.children.forEach { actors += it }
    tempTable.clearChildren()

    if (actors.isEmpty()) return

    layoutFlowRow(this, actors, alignment, horizontalSpacing, verticalSpacing, maxWidth)
}

private fun layoutFlowRow(
    container: VisTable,
    actors: List<Actor>,
    alignment: FlowAlignment,
    hSpacing: Float,
    vSpacing: Float,
    maxW: Float
) {
    var currentRow = VisTable(true)
    currentRow.defaults()
        .padLeft(hSpacing)
        .padRight(hSpacing)
        .padTop(vSpacing * 0.5f)
        .padBottom(vSpacing * 0.5f)

    var currentWidth = 0f

    for (actor in actors) {
        val baseW = if (actor is Widget) actor.prefWidth else actor.width
        val prefW = baseW + hSpacing * 2f

        if (currentWidth + prefW > maxW && currentWidth > 0f) {
            addFlowRowToContainer(container, currentRow, alignment)
            currentRow = VisTable(true)
            currentRow.defaults()
                .padLeft(hSpacing)
                .padRight(hSpacing)
                .padTop(vSpacing * 0.5f)
                .padBottom(vSpacing * 0.5f)
            currentWidth = 0f
        }
        currentRow.add(actor)
        currentWidth += prefW
    }

    if (currentRow.hasChildren()) {
        addFlowRowToContainer(container, currentRow, alignment)
    }
}

private fun addFlowRowToContainer(
    container: VisTable,
    rowTable: VisTable,
    alignment: FlowAlignment
) {
    val cell = container.add(rowTable) as Cell<VisTable>
    when (alignment) {
        FlowAlignment.Start  -> cell.left().growX()
        FlowAlignment.Center -> cell.center().growX()
        FlowAlignment.End    -> cell.right().growX()
    }
    container.row()
}


/**
 * VisTextField — поле ввода текста, стилизованное под Jetpack Compose.
 * Использует makeStyledTextField из UiStyle.kt (rounded corners, custom colors).
 *
 * Полностью в стиле Compose:
 *   visTextField(
 *       text = currentText,
 *       hint = "Введите имя...",
 *       onTextChange = { newText -> currentText = newText }  // state hoisting
 *   ) { expandX().fillX().pad(8f) }
 */
fun VisTable.visTextField(
    text: String = "",
    hint: String = "",
    textFieldFilter: VisTextField.TextFieldFilter? = null,   // ← вот это новое
    onTextChange: ((String) -> Unit)? = null,
    cellInit: (Cell<VisTextField>.() -> Unit) = {}
): VisTextField {
    val textField = makeStyledTextField(game, mutableListOf()).also {
        textFieldFilter?.let { filter ->
            it.textFieldFilter = filter
        }
    }
    textField.text = text
    if (hint.isNotBlank()) {
        textField.messageText = hint
    }

    if (onTextChange != null) {
        textField.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                onTextChange(textField.text)
            }
        })
    }

    val cell = this.add(textField) as Cell<VisTextField>
    cellInit(cell)
    return textField
}
