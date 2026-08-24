package io.github.some_example_name.old.features.worldeditor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextField
import io.github.some_example_name.old.commands.GoBack
import io.github.some_example_name.old.commands.GoSimulation
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.ui.VisDslScreen
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.h
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visScrollPane
import io.github.some_example_name.old.core.ui.visSlider
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextButton
import io.github.some_example_name.old.core.ui.visTextField
import io.github.some_example_name.old.core.ui.visToggleButton
import io.github.some_example_name.old.core.ui.w
import io.github.some_example_name.old.core.utils.brownColors
import io.github.some_example_name.old.features.worldeditor.WorldEditorViewModel.Companion.BRUSH_SIZE_MAX
import io.github.some_example_name.old.features.worldeditor.WorldEditorViewModel.Companion.BRUSH_SIZE_MIN
import io.github.some_example_name.old.features.worldeditor.WorldEditorViewModel.Companion.DAY_NIGHT_MAX
import io.github.some_example_name.old.features.worldeditor.WorldEditorViewModel.Companion.DAY_NIGHT_MIN
import io.github.some_example_name.old.features.worldeditor.WorldEditorViewModel.Companion.SMOOTHING_MAX
import io.github.some_example_name.old.features.worldeditor.WorldEditorViewModel.Companion.SMOOTHING_MIN

/**
 * Редактор мира: рисование карты кистью плюс параметры генератора.
 *
 * Экран отвечает только за вёрстку и навигацию, вся логика — в [WorldEditorViewModel],
 * рисование карты — в [WorldCanvasActor].
 *
 * Панель настроек — не модальный диалог, а часть вёрстки: на широком экране она встаёт
 * колонкой справа от холста, на узком — строкой под ним. Так она не перекрывает карту,
 * которую в этот момент и настраивают, и не требует отдельного пересчёта размеров.
 */
class WorldEditorScreen : VisDslScreen(background = Color(0.1f, 0.1f, 0.1f, 1f)) {

    private val viewModel = WorldEditorViewModel()

    /**
     * Холст живёт дольше рекомпозиции: он владеет Pixmap и Texture, пересоздавать их
     * на каждый resize — это утечка и лишняя генерация карты.
     */
    private val canvas = WorldCanvasActor(
        viewModel = viewModel,
        wallColor = brownColors[2],
        emptyColor = Color.BLACK
    )

    private var isSettingsOpen = false

    /**
     * Панель встаёт колонкой справа только на заметно широком экране. На квадратном и на
     * вытянутом вверх колонка съедает ровно ту ширину, по которой квадратная карта и
     * вписывается, — там выгоднее положить панель под холст во всю ширину.
     */
    private val isSidePanel get() = w >= h * SIDE_PANEL_MIN_ASPECT

    override fun VisTable.compose() {
        pad(SCREEN_PAD.dp())

        topBar()
        row()

        content()
        row()

        bottomBar()
    }

    // ==================== Верхняя панель ====================

    private fun VisTable.topBar() {
        visTable({ growX() }) {
            visTextButton(
                text = bundle.get("button.back"),
                onClick = { navigation.performCommand(GoBack) }
            ) { left() }

            spacer()

            visToggleButton(
                text = bundle.get("button.settings"),
                checked = isSettingsOpen,
                onCheckedChange = { checked ->
                    isSettingsOpen = checked
                    // Перекладываем экран не изнутри обработчика события, а следующим кадром:
                    // иначе кнопка сносит саму себя прямо во время обхода слушателей.
                    Gdx.app.postRunnable { recompose() }
                }
            ) { right() }
        }
    }

    // ==================== Центр: холст и панель настроек ====================

    private fun VisTable.content() {
        if (!isSettingsOpen) {
            add(canvas).grow().pad(GAP.dp())
            return
        }

        visTable({ grow() }) {
            add(canvas).grow().pad(GAP.dp())

            // Размер панели не задаётся числом: холст забирает всё лишнее место (grow),
            // а панели достаётся её собственный желаемый размер — ровно столько, сколько
            // нужно её содержимому при текущем шрифте. Ограничение стоит только сверху,
            // чтобы на совсем маленьком экране панель не съела холст целиком.
            if (isSidePanel) {
                settingsPanel { growY().maxWidth(w * PANEL_MAX_FRACTION) }
            } else {
                row()
                settingsPanel { growX().maxHeight(h * PANEL_MAX_FRACTION) }
            }
        }
    }

    private fun VisTable.settingsPanel(cellInit: Cell<VisTable>.() -> Unit) {
        visTable(cellInit = cellInit, backgroundColor = PANEL_BACKGROUND) {
            visScrollPane({ grow().pad(GAP.dp()) }, scrollingDisabledX = true) {
                // Содержимое короче панели — прижимаем его к верху, иначе ScrollPane
                // растянет таблицу на всю высоту и настройки повиснут по центру.
                top()
                settingsContent()
            }.apply {
                // Полоса прокрутки рисуется поверх содержимого, а не отъедает у него ширину:
                // на узкой панели этих полутора десятков пикселей как раз и не хватает,
                // и подписи начинают обрезаться слева.
                setScrollbarsOnTop(true)
            }
        }
    }

    private fun VisTable.settingsContent() {
        seedRow()
        row()

        val dayNightLabel = visLabel(dayNightText()) { left().growX() }
        row()
        visSlider(
            min = DAY_NIGHT_MIN,
            max = DAY_NIGHT_MAX,
            step = 1f,
            value = viewModel.dayNight.toFloat(),
            onValueChange = { value ->
                viewModel.handle(WorldEditorIntent.SetDayNight(value.toInt()))
                dayNightLabel.setText(dayNightText())
            }
        ) { growX().padBottom(GAP.dp()) }
        row()

        val smoothingLabel = visLabel(smoothingText()) { left().growX() }
        row()
        visSlider(
            min = SMOOTHING_MIN,
            max = SMOOTHING_MAX,
            step = 1f,
            value = viewModel.smoothing.toFloat(),
            onValueChange = { value ->
                viewModel.handle(WorldEditorIntent.SetSmoothing(value.toInt()))
                smoothingLabel.setText(smoothingText())
            }
        ) { growX().padBottom(GAP.dp()) }
        row()

        val brushSizeLabel = visLabel(brushSizeText()) { left().growX() }
        row()
        visSlider(
            min = BRUSH_SIZE_MIN,
            max = BRUSH_SIZE_MAX,
            step = 1f,
            value = viewModel.brushSize.toFloat(),
            onValueChange = { value ->
                viewModel.handle(WorldEditorIntent.SetBrushSize(value.toInt()))
                brushSizeLabel.setText(brushSizeText())
            }
        ) { growX().padBottom(GAP.dp()) }
        row()

        visToggleButton(
            text = bundle.get("checkbox.eraseMode"),
            checked = viewModel.isErasing,
            onCheckedChange = { viewModel.handle(WorldEditorIntent.SetErasing(it)) }
        ) { growX() }
        row()

        visToggleButton(
            text = bundle.get("checkbox.circleBrush"),
            checked = viewModel.useCircleBrush,
            onCheckedChange = { viewModel.handle(WorldEditorIntent.SetCircleBrush(it)) }
        ) { growX() }
        row()
    }

    private fun VisTable.seedRow() {
        var seedField: VisTextField? = null

        visTable({ growX().padBottom(GAP.dp()) }) {
            visTextButton(
                text = bundle.get("button.newSeed"),
                onClick = {
                    viewModel.handle(WorldEditorIntent.NewSeed)
                    // Присвоение дёрнет onTextChange, но setSeed на тот же сид — это no-op.
                    seedField?.text = viewModel.seed
                }
            ) { padRight(GAP.dp()) }

            seedField = visTextField(
                text = viewModel.seed,
                hint = bundle.get("textfield.enterSeed"),
                onTextChange = { viewModel.handle(WorldEditorIntent.SetSeed(it)) }
            ) {
                // Своя ширина у поля маленькая, а растёт оно за счёт growX. Иначе желаемая
                // ширина VisTextField задирала бы желаемую ширину всей панели, и на узком
                // экране панель упиралась бы в потолок и обрезалась.
                growX()
                prefWidth(SEED_FIELD_PREF_WIDTH.dp())
            }
        }
    }

    // ==================== Нижняя панель ====================

    private fun VisTable.bottomBar() {
        visTable({ growX() }) {
            visTextButton(
                text = bundle.get("button.clearMap"),
                onClick = { viewModel.handle(WorldEditorIntent.ClearMap) }
            ) { left() }

            spacer()

            visTextButton(
                text = bundle.get("button.createNewWorld"),
                onClick = { navigation.performCommand(GoSimulation(viewModel.toSpec(), null)) }
            ) { right() }
        }
    }

    // ==================== Мелочи ====================

    /** Пустая растягивающаяся ячейка — разводит соседей по краям строки. */
    private fun VisTable.spacer() {
        visTable({ expandX().fillX() }) { }
    }

    private fun dayNightText() = bundle.get("label.dayNight") + viewModel.dayNight

    private fun smoothingText() = bundle.get("label.smoothing") + viewModel.smoothing

    private fun brushSizeText() = bundle.get("label.brushSize") + viewModel.brushSize

    override fun dslDispose() {
        canvas.dispose()
    }

    private companion object {
        const val SCREEN_PAD = 12f
        const val GAP = 8f
        const val SEED_FIELD_PREF_WIDTH = 90f

        /** Насколько экран должен быть шире, чем выше, чтобы панель ушла в колонку справа. */
        const val SIDE_PANEL_MIN_ASPECT = 1.1f

        /** Потолок для панели — доля экрана по её оси. */
        const val PANEL_MAX_FRACTION = 0.55f

        val PANEL_BACKGROUND = Color(0.16f, 0.16f, 0.19f, 1f)
    }
}
