package io.github.some_example_name.old.features.guide

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.bug.ReplayTarget
import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.base.CellPalette
import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.commands.GoBack
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.VisDslScreen
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.forEach
import io.github.some_example_name.old.core.ui.visImage
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visLeftArrowButton
import io.github.some_example_name.old.core.ui.visScrollPane
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextButton
import io.github.some_example_name.old.core.ui.visToggleButton
import io.github.some_example_name.old.core.ui.w
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer
import io.github.some_example_name.old.editor.tutorial.TutorialSteps
import io.github.some_example_name.old.features.editor.dialog.NeuralSection
import kotlin.math.min

/**
 * Гайд игрока. Три вкладки:
 *  - «Обучение» — что в нём будет и кнопка старта: само обучение идёт в редакторе генома
 *    (GoTutorial), на тех же экранах и диалогах, что и обычная игра;
 *  - «Клетки» — что делает каждая клетка, которую можно выбрать в редакторе;
 *  - «Формулы» — стенд одного нейрона с ползунками и графиком (FormulaLabView).
 */
class GuideScreen : VisDslScreen(isScrollable = false), ReplayTarget {

    private val viewModel = GuideViewModel()

    /** Текстуры клеток для вкладки «Клетки»: compose() зовётся на каждый resize. */
    private val textures = mutableMapOf<String, Texture>()

    /**
     * Стенд формул: свой нейрон (см. NeuronLab) и вёрстка вкладки. Создаётся при первом
     * открытии вкладки — до этого гайду не нужна даже CellEntity стенда.
     */
    private val formulaView by lazy {
        FormulaLabView(
            viewModel,
            NeuronLab(
                cellList = DIGenomeEditorContainer.cellList,
                particleEntity = DIGenomeEditorContainer.particleEntity,
                specialEntity = DIGenomeEditorContainer.specialEntity,
                organEntity = DIGenomeEditorContainer.organEntity,
                substrateSettings = DIGameGlobalContainer.substrateSettings
            )
        )
    }

    override fun replay(command: GameCommand): Boolean {
        if (command !is GuideIntent) return false
        viewModel.handle(command)
        recompose()
        return true
    }

    override fun VisTable.compose() {
        // Стенду формул нужен простор: ползунки и график стоят рядом.
        val maxWidth = if (viewModel.currentTab == GuideTab.FORMULAS) 1300.dp() else 900.dp()
        val width = min(w * 0.92f, maxWidth)

        visTable(cellInit = { expand().fill().width(width).padTop(16.dp()).padBottom(16.dp()) }) {
            visTable(cellInit = { left() }) {
                visLeftArrowButton(onClick = { navigation.performCommand(GoBack) }) { padRight(16.dp()) }
                visLabel(bundle.get("guide.title"), font = game.extraLargeFont)
            }
            row()

            visTable(cellInit = { expandX().fillX().padTop(12.dp()) }) {
                val tabs = LinkedHashMap<GuideTab, VisTextButton>()

                // Смена isChecked сама шлёт событие изменения — и в эту вкладку, и в соседние.
                // Без этого флага вкладки переключали друг друга бесконечно (StackOverflow).
                var syncing = false

                fun select(tab: GuideTab) {
                    if (syncing) return
                    syncing = true
                    tabs.forEach { (t, button) -> button.isChecked = t == tab }
                    syncing = false
                    selectTab(tab)
                }

                // Нажатие на уже выбранную вкладку снимает отметку — select её возвращает.
                for (tab in GuideTab.entries) {
                    tabs[tab] = visToggleButton(
                        text = bundle.get(TAB_TITLES.getValue(tab)),
                        checked = viewModel.currentTab == tab,
                        onCheckedChange = { select(if (it) tab else viewModel.currentTab) }
                    ) { expandX().fillX() }
                }
            }
            row()

            visScrollPane({ expand().fill().padTop(12.dp()) }, scrollingDisabledX = true) {
                when (viewModel.currentTab) {
                    GuideTab.TUTORIAL -> tutorialTab(width)
                    GuideTab.CELLS -> cellsTab(width)
                    GuideTab.FORMULAS -> formulaView.compose(this, width)
                }
            }
        }
    }

    private fun selectTab(tab: GuideTab) {
        if (viewModel.currentTab == tab) return
        viewModel.handle(GuideIntent.SelectTab(tab))
        recompose()
    }

    override fun dslRender(delta: Float) {
        if (viewModel.currentTab == GuideTab.FORMULAS) formulaView.update(delta)
    }

    private fun VisTable.tutorialTab(width: Float) {
        defaults().left().width(width - 32.dp())

        visLabel(bundle.get("guide.tutorial.text")).setWrap(true)
        row()

        visTextButton(
            bundle.get("guide.tutorial.start"),
            onClick = { viewModel.handle(GuideIntent.StartTutorial) }
        ) { padTop(16.dp()).width(min(width * 0.6f, 420.dp())).center() }
        row()

        visLabel(bundle.get("guide.tutorial.steps"), font = game.extraLargeFont) { padTop(16.dp()) }
        row()

        TutorialSteps.all.forEachIndexed { i, step ->
            visLabel("${i + 1}. ${bundle.get("tutorial.${step.id}.title")}") { padTop(4.dp()) }.setWrap(true)
            row()
        }
    }

    private fun VisTable.cellsTab(width: Float) {
        val iconSize = 56.dp()
        val textWidth = width - iconSize - 64.dp()

        // Только то, что можно выбрать в редакторе: выведенные из игры клетки остались ради
        // старых геномов, учить им незачем.
        val cells = CellPalette.FULL.cellChoices(DIGenomeEditorContainer.cellList, current = null)

        forEach(cells) { cell ->
            visTable(cellInit = { expandX().fillX().padBottom(12.dp()) }, backgroundColor = CARD) {
                pad(12.dp())
                visImage(texture(cell)) { size(iconSize, iconSize).top().padRight(16.dp()) }.color = cell.defaultColor

                visTable(cellInit = { expandX().fillX().left() }) {
                    defaults().left().width(textWidth)
                    visLabel(cell.name, font = game.extraLargeFont)
                    row()
                    visLabel(properties(cell), textColor = MUTED, font = game.mediumFont).setWrap(true)
                    row()
                    visLabel(cell.description.ifEmpty { bundle.get("guide.cell.noDescription") }) { padTop(6.dp()) }
                        .setWrap(true)
                    // У клетки с формулой — ссылка на стенд формул.
                    if (NeuralSection in cell.editorSections()) {
                        row()
                        visTextButton(
                            bundle.get("guide.cell.formulasLink"),
                            onClick = { selectTab(GuideTab.FORMULAS) }
                        ) { padTop(8.dp()).width(min(textWidth, 360.dp())) }
                    }
                }
            }
            row()
        }
    }

    /** Свойства клетки прямо из её класса, чтобы гайд не расходился с кодом. */
    private fun properties(cell: Cell): String = buildList {
        add(bundle.format("guide.cell.energy", cell.defaultCellSettings.maxEnergy))
        if (cell.isNeural) add(bundle.get("guide.cell.neural"))
        if (cell.isDirected) add(bundle.get("guide.cell.directed"))
        if (cell.effectOnContact) add(bundle.get("guide.cell.contact"))
        if (!cell.isCollidable) add(bundle.get("guide.cell.notCollidable"))
    }.joinToString(", ")

    private fun texture(cell: Cell): Texture = textures.getOrPut(cell.textureName) {
        Texture(Gdx.files.internal("cell_textures/${cell.textureName}"))
    }

    override fun dslDispose() {
        textures.values.forEach { it.dispose() }
        textures.clear()
    }

    private companion object {
        val TAB_TITLES = mapOf(
            GuideTab.TUTORIAL to "guide.tab.tutorial",
            GuideTab.CELLS to "guide.tab.cells",
            GuideTab.FORMULAS to "guide.tab.formulas",
        )

        val CARD = Color(0.12f, 0.15f, 0.18f, 1f)
        val MUTED = Color(0.65f, 0.62f, 0.56f, 1f)
    }
}
