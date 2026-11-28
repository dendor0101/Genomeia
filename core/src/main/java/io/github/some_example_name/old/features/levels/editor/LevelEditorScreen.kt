package io.github.some_example_name.old.features.levels.editor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener
import com.badlogic.gdx.utils.Align
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.bug.ReplayTarget
import io.github.some_example_name.old.cells.base.CellPalette
import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.ui.LineNumberTextArea
import io.github.some_example_name.old.core.ui.VisDslScreen
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.makeStyledNP
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visLineNumberTextArea
import io.github.some_example_name.old.core.ui.visScrollPane
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextButton
import io.github.some_example_name.old.core.ui.visTextField
import io.github.some_example_name.old.core.utils.brownColors
import io.github.some_example_name.old.features.levels.dialogs.AllowCellsDialog
import io.github.some_example_name.old.features.levels.dialogs.LevelSelectDialog
import io.github.some_example_name.old.features.levels.dialogs.ScriptedOrganismDialog
import io.github.some_example_name.old.features.worldeditor.LevelMapReceiver
import io.github.some_example_name.old.features.worldeditor.WorldCanvasActor
import io.github.some_example_name.old.features.worldeditor.WorldEditorViewModel
import io.github.some_example_name.old.features.worldeditor.WorldGenerator
import io.github.some_example_name.old.features.worldeditor.WorldSpec
import io.github.some_example_name.old.systems.genomics.library.GenomeCatalog

/**
 * Редактор уровней: скрипт, свойства, карта и кнопки в две колонки.
 *
 * Экран только собирает команды (LevelEditorIntent) и показывает состояние
 * [LevelEditorViewModel] — как остальные экраны игры, поэтому баг в редакторе уровней
 * повторяется из .bug.
 *
 * Не прокручивается: карта занимает остаток высоты и сжимается под окно (см.
 * WorldCanvasActor). Прокрутка здесь давала обратный эффект — содержимое получало
 * желаемую высоту и на 1600×900 уезжали вниз карта и кнопки.
 */
class LevelEditorScreen : VisDslScreen(), ReplayTarget, LevelMapReceiver {

    private val cells = DISimulationContainer.cellList.filterNot { it.key in CellPalette.RETIRED_CELLS }

    private val viewModel = LevelEditorViewModel(
        cellKeys = cells.map { it.key },
        genomeNames = GenomeCatalog::all,
        checkScript = DIGameGlobalContainer.luaEnv::check,
        writeLevel = { LevelFiles.write(it) },
        navigate = { navigation.performCommand(it) }
    )

    private val panel: Drawable by lazy { makeStyledNP(PANEL_BG, PANEL_BORDER, mutableListOf()) }
    private lateinit var textEditor: LineNumberTextArea
    private lateinit var logLabel: VisLabel

    /**
     * Черновик скрипта: что сейчас в поле, но ещё не ушло командой SetScript. Нужен, чтобы
     * пересборка вёрстки (resize) не теряла набранное, а журнал не получал скрипт целиком
     * на каждую клавишу. null — в поле ровно скрипт модели.
     */
    private var scriptDraft: String? = null

    /** Строка под скриптом: результат проверки, сохранения, ошибки свойств. */
    private var logText = bundle.get("level.editor.noErrors")

    /** Карта, которую сейчас показывает холст: пересобирается, только когда она другая. */
    private var shownMap: WorldSpec = viewModel.map

    private val canvas = WorldCanvasActor(
        viewModel = preview(viewModel.map),
        wallColor = brownColors[2],
        emptyColor = Color.BLACK,
        interactive = false
    )

    init {
        viewModel.onMessage = { setLog(describe(it)) }
        viewModel.onChanged = {
            if (viewModel.map != shownMap) {
                shownMap = viewModel.map
                canvas.viewModel = preview(shownMap)
                canvas.syncTexture(force = true)
            }
            scriptDraft = null
            Gdx.app.postRunnable { recompose() }
        }
    }

    override fun replay(command: GameCommand): Boolean {
        if (command !is LevelEditorIntent) return false
        viewModel.handle(command)
        recompose()
        return true
    }

    override fun onLevelMapEdited(map: WorldSpec) = viewModel.handle(LevelEditorIntent.SetMap(map))

    override fun VisTable.compose() {
        // Правая панель шире 0.32 экрана, когда есть место: шесть кнопок стоят в два столбца,
        // и в узкой панели подписи рвутся по слогам. На узком окне панель не расширяем сверх
        // половины ширины, иначе таблица перестаёт помещаться и всё уезжает за экран.
        val sideWidth = maxOf(Gdx.graphics.width * 0.32f, minOf(400.dp(), Gdx.graphics.width * 0.5f))
        // В две колонки кнопки встают только когда панель достаточно широка для слова.
        val buttonsPerRow = if (sideWidth >= 340.dp()) 2 else 1
        visTable(cellInit = { expand().fill() }, backgroundColor = BG) {
            visTable(cellInit = { grow().top().left().pad(16.dp()) }) { scriptPanel() }.background = panel
            visTable(cellInit = { growY().fill().prefWidth(sideWidth).top().right().pad(16.dp()) }) {
                propertiesPanel()
            }.background = panel
            row()
            visTable(cellInit = { grow().bottom().left().pad(16.dp()) }) {
                // Карта делит ряд с кнопками и заполняет его целиком: высоту ряда задают
                // кнопки, а не карта, поэтому панель не оставляет пустую полосу над собой.
                add(canvas).grow()
            }.background = panel
            visTable(cellInit = { growY().fill().prefWidth(sideWidth).bottom().right().pad(16.dp()) }) {
                actionButtons(buttonsPerRow)
            }.background = panel
        }
    }

    private fun VisTable.scriptPanel() {
        visLabel(bundle.get("level.editor.script"), textColor = ACCENT) { top().left().growX().padBottom(8.dp()) }
        row()
        scriptButton(bundle.get("level.editor.loadScript")) {
            // Файл с диска — внешний ввод: в журнал он едет текстом в команде SetScript.
            DIGameGlobalContainer.fileProvider.pickTextFile(listOf("lua")) { _, text ->
                if (text != null) Gdx.app.postRunnable {
                    viewModel.handle(LevelEditorIntent.SetScript(text))
                    scriptDraft = null
                    recompose()
                }
            }
        }
        // Выгрузка в файл игрока ничего в редакторе не меняет — это не команда.
        scriptButton(bundle.get("button.save")) {
            DIGameGlobalContainer.fileProvider.saveTextFile("level.lua", currentScript())
        }
        scriptButton(bundle.get("level.editor.check")) {
            setLog(describe(viewModel.check(currentScript())))
        }
        row()
        textEditor = visLineNumberTextArea(text = currentScript(), cellInit = { grow().colspan(4).padTop(8.dp()) })
        textEditor.setCodeColor(CODE)
        textEditor.gutterTextColor.set(ACCENT.r, ACCENT.g, ACCENT.b, 1f)
        textEditor.messageText = "-- " + bundle.get("level.editor.defaultScript")
        textEditor.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                scriptDraft = textEditor.text
            }
        })
        textEditor.addListener(object : FocusListener() {
            override fun keyboardFocusChanged(event: FocusEvent?, actor: Actor?, focused: Boolean) {
                if (!focused) commitScript()
            }
        })
        row()
        // Статус проверки и сохранения — под редактором, а не отдельным рядом:
        // отдельный ряд со своей подложкой съедал ~90 px высоты у самого скрипта.
        logLabel = visLabel(logText) { growX().colspan(4).left().padTop(6.dp()) }.apply { wrap = true }
    }

    private fun VisTable.scriptButton(text: String, onClick: () -> Unit) {
        visTextButton(text, onClick = onClick) { top().right().growX().padBottom(6.dp()) }
            .apply { pad(10.dp(), 8.dp(), 10.dp(), 8.dp()) }
    }

    private fun VisTable.propertiesPanel() {
        visLabel(bundle.get("level.editor.properties"), textColor = ACCENT) { top().left().growX().padBottom(8.dp()) }
        row()
        visScrollPane(cellInit = { grow() }, scrollingDisabledX = true) {
            propertyGroup(bundle.get("level.editor.name"), listOf(null to LevelField.NAME))
            propertyGroup(bundle.get("level.editor.cellsNeed"), listOf(null to LevelField.CELLS_NEED))
            propertyGroup(bundle.get("level.editor.destination"), listOf("X" to LevelField.DEST_X, "Y" to LevelField.DEST_Y))
            propertyGroup(
                bundle.get("level.editor.spawnZone"),
                listOf("X" to LevelField.SPAWN_X, "Y" to LevelField.SPAWN_Y, "R" to LevelField.SPAWN_RADIUS)
            )
            organismsGroup()
        }
    }

    /** Подпись и поля; у поля с меткой (X, Y, R) метка слева. */
    private fun VisTable.propertyGroup(title: String, inputs: List<Pair<String?, LevelField>>) {
        visTable(cellInit = { growX() }) {
            visLabel(title) { growX().padBottom(6.dp()); colspan(2) }.apply { setWrap(true) }
            row()
            for ((label, field) in inputs) {
                if (label != null) visLabel("$label: ")
                visTextField(
                    text = viewModel.field(field),
                    hint = label ?: "",
                    onTextChange = { viewModel.handle(LevelEditorIntent.SetField(field, it)) }
                ) { growX(); if (label == null) colspan(2) }
                row()
            }
        }.background = panel
        row()
    }

    /**
     * Организмы уровня: полноценный геном, а не скриптовая клетка. Их появление
     * повторяется от запуска к запуску, поэтому уровни Chameleon и Pheromones могут
     * проверять поведение, а не реакцию игрока.
     */
    private fun VisTable.organismsGroup() {
        visTable(cellInit = { growX() }) {
            visLabel(bundle.get("level.editor.organisms")) { growX().padBottom(6.dp()) }.apply { setWrap(true) }
            row()
            if (viewModel.organisms.isEmpty()) {
                visLabel(bundle.get("level.editor.none")) { growX() }
                row()
            }
            viewModel.organisms.forEachIndexed { index, organism ->
                visTable(cellInit = { growX() }) {
                    visLabel(
                        "${GenomeCatalog.displayName(organism.genomeName)}  " +
                            "(${organism.x}; ${organism.y})  ${organism.angleDegrees}°"
                    ) { growX() }.apply { setWrap(true) }
                    visTextButton(bundle.get("button.change"), onClick = { openOrganismDialog(index) }) { padLeft(4.dp()) }
                    visTextButton(bundle.get("button.remove"), onClick = {
                        viewModel.handle(LevelEditorIntent.RemoveOrganism(index))
                    }) { padLeft(4.dp()) }
                }
                row()
            }
            visTextButton(bundle.get("level.editor.add"), onClick = { openOrganismDialog(null) }) { growX() }
            row()
        }.background = panel
    }

    private fun VisTable.actionButtons(buttonsPerRow: Int) {
        val actions = listOf<Pair<String, () -> Unit>>(
            bundle.get("level.editor.loadLevel") to ::openLevel,
            bundle.get("level.editor.allowedCells") to ::openAllowedCells,
            bundle.get("level.editor.mapEditor") to { act(LevelEditorIntent.OpenMapEditor) },
            bundle.get("level.editor.saveLevel") to { act(LevelEditorIntent.SaveLevel) },
            bundle.get("level.editor.testLevel") to { act(LevelEditorIntent.TestLevel) },
            bundle.get("level.editor.exit") to { act(LevelEditorIntent.Exit) }
        )
        actions.forEachIndexed { index, (text, action) ->
            visTextButton(text) { growX().padBottom(6.dp()).fillY() }.apply {
                // Шрифт мельче обычного, иначе подписи в двух колонках не влезают в строку.
                style.font = DIGameGlobalContainer.game.mediumFont
                setStyle(style)
                label.setWrap(true)
                label.setAlignment(Align.center)
                addListener(object : ClickListener() {
                    override fun clicked(event: InputEvent?, x: Float, y: Float) {
                        super.clicked(event, x, y)
                        action()
                    }
                })
            }
            // row() закрывает ряд после добавления клетки, поэтому шаг — perRow.
            if ((index + 1) % buttonsPerRow == 0) row()
        }
    }

    /** Действие с уровнем целиком: сперва скрипт из поля, иначе оно ушло бы без последних правок. */
    private fun act(intent: LevelEditorIntent) {
        commitScript()
        viewModel.handle(intent)
    }

    private fun currentScript(): String = scriptDraft ?: viewModel.script

    private fun commitScript() {
        val draft = scriptDraft ?: return
        if (draft != viewModel.script) viewModel.handle(LevelEditorIntent.SetScript(draft))
        scriptDraft = null
    }

    private fun openAllowedCells() {
        AllowCellsDialog(
            cells = cells.map { it.key to it.name },
            allowed = viewModel.allowedCells,
            onToggle = { key, allowed -> viewModel.handle(LevelEditorIntent.SetCellAllowed(key, allowed)) }
        ).show(stage)
    }

    private fun openOrganismDialog(index: Int?) {
        ScriptedOrganismDialog(
            initial = index?.let { viewModel.organisms[it] },
            defaultX = viewModel.map.worldWidth / 2f,
            defaultY = viewModel.map.worldHeight / 2f,
            onSave = { viewModel.handle(LevelEditorIntent.PutOrganism(index ?: -1, it)) }
        ).show(stage)
    }

    /**
     * Встроенные уровни и свои: файлы лежат в ассетах и в личном хранилище, системное окно
     * выбора их не покажет — список собирается сам. Прочитанный уровень едет в команде.
     */
    private fun openLevel() {
        commitScript()
        LevelSelectDialog(LevelFiles.names() + LevelFiles.builtinNames, LevelFiles::title) { ref ->
            val level = runCatching { LevelFiles.read(ref) }
                .onFailure { Gdx.app.error(TAG, "не удалось открыть уровень $ref", it) }
                .getOrNull()
            if (level == null) setLog(bundle.get("level.editor.openFailed"))
            else viewModel.handle(LevelEditorIntent.LoadLevel(level))
        }.show(stage)
    }

    private fun setLog(text: String) {
        logText = text
        logLabel.setText(text)
    }

    /** Номера строк и размеры идут строками: MessageFormat разбил бы 1200 на «1,200». */
    private fun describe(message: LevelMessage): String =
        if (message.args.isEmpty()) bundle.get(message.key)
        else bundle.format(message.key, *message.args.toTypedArray())

    /**
     * Карта для холста. Параметры генератора — общие поля WorldGenerator, их выставляет
     * fromSpec; после сборки возвращаем как было, чтобы предпросмотр уровня не менял
     * открывшийся позже редактор мира.
     */
    private fun preview(spec: WorldSpec): WorldEditorViewModel {
        val dayNight = WorldGenerator.GENERATOR_DAY_NIGHT
        val smoothing = WorldGenerator.GENERATOR_INTERPOLATE
        try {
            return WorldEditorViewModel.fromSpec(spec)
        } finally {
            WorldGenerator.GENERATOR_DAY_NIGHT = dayNight
            WorldGenerator.GENERATOR_INTERPOLATE = smoothing
        }
    }

    companion object {
        private const val TAG = "LevelEditor"

        val BG = Color(0.07f, 0.05f, 0.04f, 1f)
        val PANEL_BG = Color(0.14f, 0.10f, 0.07f, 1f)
        val PANEL_BORDER = Color(0.85f, 0.50f, 0.20f, 0.55f)
        val ACCENT = Color(0.92f, 0.55f, 0.22f, 1f)
        val CODE = Color(0.95f, 0.84f, 0.70f, 1f)
    }
}
