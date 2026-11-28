package io.github.some_example_name.old.features.guide

import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.kotcrab.vis.ui.widget.VisScrollPane
import io.github.some_example_name.old.features.guide.celllab.CellLabKeyboard
import com.badlogic.gdx.graphics.Color
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
import io.github.some_example_name.old.systems.render.DebugCellIcons
import io.github.some_example_name.old.editor.tutorial.TutorialSteps
import io.github.some_example_name.old.features.editor.dialog.NeuralSection
import io.github.some_example_name.old.features.guide.celllab.CellLabCatalog
import io.github.some_example_name.old.features.guide.celllab.CellLabView
import io.github.some_example_name.old.journal.CellLabRecord
import io.github.some_example_name.old.journal.CellLabInput
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
    private val cellLabView = CellLabView(viewModel.cellLab)
    private val cellLabKeyboard = CellLabKeyboard(viewModel.cellLab, stage)
    private var cellsScroll: VisScrollPane? = null
    private var cellsScrollY = 0f
    private var restoreCellsScroll = false

    override fun dslShow() {
        inputProcessor.addProcessor(0, cellLabKeyboard)
        inputProcessor.addProcessor(0, object : InputAdapter() {
            override fun keyDown(keycode: Int): Boolean {
                if (keycode != Input.Keys.BACK || viewModel.cellLab.model == null) return false
                handleGuide(GuideIntent.CloseCellLab)
                return true
            }
        })
    }

    private fun handleGuide(intent: GuideIntent) {
        if (intent is GuideIntent.OpenCellLab) cellsScrollY = cellsScroll?.scrollY ?: cellsScrollY
        viewModel.handle(intent)
        if (intent == GuideIntent.CloseCellLab) restoreCellsScroll = true
        if (viewModel.cellLab.model == null) cellLabView.dispose()
        recompose()
    }

    /** Текстуры клеток для вкладки «Клетки»: compose() зовётся на каждый resize. */
    private val previews by lazy { DebugCellIcons(DIGenomeEditorContainer.cellList) }

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
        if (command is CellLabRecord) {
            if (command is CellLabInput) viewModel.cellLab.input(command)
            else viewModel.cellLab.apply(command)
            if (command is CellLabInput && command.action in listOf("set", "reset", "pause")) recompose()
            return true
        }
        if (command !is GuideIntent) return false
        handleGuide(command)
        return true
    }

    override fun VisTable.compose() {
        if (viewModel.cellLab.model != null) {
            val width = min(w * 0.96f, 1300.dp())
            visScrollPane({ expand().fill() }, scrollingDisabledX = true) {
                visTable({ width(width).pad(16.dp()).top() }) {
                    cellLabView.compose(this, width, onClose = {
                        handleGuide(GuideIntent.CloseCellLab)
                    }, rebuild = { recompose() })
                }
            }
            return
        }
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

            val contentScroll = visScrollPane({ expand().fill().padTop(12.dp()) }, scrollingDisabledX = true) {
                when (viewModel.currentTab) {
                    GuideTab.TUTORIAL -> tutorialTab(width)
                    GuideTab.CELLS -> cellsTab(width)
                    GuideTab.FORMULAS -> formulaView.compose(this, width)
                }
            }
            if (viewModel.currentTab == GuideTab.CELLS) {
                cellsScroll = contentScroll
                contentScroll.name = "guide-cells-list"
            }
        }
    }

    private fun selectTab(tab: GuideTab) {
        if (viewModel.currentTab == tab) return
        handleGuide(GuideIntent.SelectTab(tab))
    }

    override fun dslRender(delta: Float) {
        if (restoreCellsScroll && viewModel.cellLab.model == null) {
            rootTable.validate()
            cellsScroll?.apply { validate(); scrollY = cellsScrollY; updateVisualScroll() }
            restoreCellsScroll = false
        }
        cellLabKeyboard.releaseWhileTyping()
        if (viewModel.currentTab == GuideTab.FORMULAS) formulaView.update(delta)
        if (viewModel.cellLab.model != null) cellLabView.update(delta)
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
        val iconSize = 168.dp()
        val stacked = width < 600.dp()
        val textWidth = if (stacked) width - 48.dp() else width - iconSize - 64.dp()

        // Только то, что можно выбрать в редакторе: выведенные из игры клетки остались ради
        // старых геномов, учить им незачем.
        val cells = CellPalette.FULL.cellChoices(DIGenomeEditorContainer.cellList, current = null)

        forEach(cells) { cell ->
            visTable(cellInit = { expandX().fillX().padBottom(12.dp()) }, backgroundColor = CARD) {
                pad(12.dp())
                visImage(previews.drawable(cell)) {
                    size(iconSize, iconSize).top().padRight(if (stacked) 0f else 16.dp())
                }
                if (stacked) row()

                visTable(cellInit = { expandX().fillX().left() }) {
                    defaults().left().width(textWidth)
                    visLabel(cell.name, font = game.extraLargeFont)
                    row()
                    visLabel(properties(cell), textColor = MUTED, font = game.mediumFont).setWrap(true)
                    row()
                    visLabel(cell.description.ifEmpty { bundle.get("guide.cell.noDescription") }) { padTop(6.dp()) }
                        .setWrap(true)
                    if (cell.key in CellLabCatalog.keys) {
                        row()
                        visTextButton(bundle.get("guide.lab.open"), onClick = {
                            handleGuide(GuideIntent.OpenCellLab(cell.key))
                        }) { padTop(8.dp()).width(min(textWidth, 360.dp())) }
                    }
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

    override fun dslDispose() {
        previews.dispose()
        cellLabView.dispose()
        viewModel.cellLab.dispose()
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
