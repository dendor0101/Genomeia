package io.github.some_example_name.old.features.editor

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.core.ui.makeStyledIconButton
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.core.ui.showDebugFilterDialog
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.features.simulation.HudIcons
import io.github.some_example_name.old.editor.system.logic.SetEditorDebugFilter
import io.github.some_example_name.render.RenderMode
import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.Align
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisSlider
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.currentTick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.isNeuralLinking
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.isRightClick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.lastTick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.linkColor
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.showPhysicalLink
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.symmetryManager
import io.github.some_example_name.old.editor.system.logic.CtrlY
import io.github.some_example_name.old.editor.system.logic.CtrlZ
import io.github.some_example_name.old.editor.system.logic.EditorLogicSystem
import io.github.some_example_name.old.editor.system.logic.GoToEndOfTimeLine
import io.github.some_example_name.old.editor.system.logic.GoToStartOfTimeLine
import io.github.some_example_name.old.editor.system.logic.NextTickButtonTap
import io.github.some_example_name.old.editor.system.logic.PrevTickButtonTap
import io.github.some_example_name.old.editor.system.logic.SetNeuralLinkColor
import io.github.some_example_name.old.editor.system.logic.SetNeuralLinking
import io.github.some_example_name.old.editor.system.logic.SetRenderMode
import io.github.some_example_name.old.editor.system.logic.SetRepeatLastAction
import io.github.some_example_name.old.editor.system.logic.SetShowPhysicalLinks
import io.github.some_example_name.old.editor.system.logic.SetSymmetry
import io.github.some_example_name.old.editor.system.logic.TimeSlider
import io.github.some_example_name.old.editor.system.render.EditorRenderSystem
import io.github.some_example_name.old.editor.system.simulation.EditorSimulationSystem
import io.github.some_example_name.old.features.editor.dialog.SymmetryDialog
import io.github.some_example_name.old.core.ui.FlowAlignment
import io.github.some_example_name.old.core.ui.STYLE_BLACK
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.renderModeSelectBox
import io.github.some_example_name.old.core.ui.globalVisTable
import io.github.some_example_name.old.core.ui.hint
import io.github.some_example_name.old.core.ui.visFlowRow
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visLeftArrowButton
import io.github.some_example_name.old.core.ui.visSelectBox
import io.github.some_example_name.old.core.ui.visSlider
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextButton
import io.github.some_example_name.old.core.ui.visToggleButton

/**
 * Вёрстка редактора генома. Состояние редактора здесь не меняется: каждая кнопка
 * отдаёт команду в EditorLogicSystem.putUiCommand.
 */
class ComposeGenomeEditor {

    private val icons = HudIcons()
    private val textures = mutableListOf<Texture>()
    fun dispose() {
        icons.dispose()
        textures.forEach { it.dispose() }
    }

    var isProgrammaticChange = false

    lateinit var tickLabel: VisLabel
    lateinit var timeSlider: VisSlider

    lateinit var undoButton: VisTextButton
    lateinit var redoButton: VisTextButton

    /** Собранная вёрстка: при пересборке (смена размера окна) старая снимается со сцены. */
    private var rootTable: Actor? = null

    fun composeGenomeEditor(
        stage: Stage,
        editorSimulationSystem: EditorSimulationSystem,
        editorLogicSystem: EditorLogicSystem,
        editorRenderSystem: EditorRenderSystem,
        /** Панель справа над полем (обучение); null — поле свободно целиком. */
        sidePanel: Actor? = null
    ) {
        // Ряд кнопок раскладывается по строкам один раз, по ширине окна в момент сборки
        // (visFlowRow), поэтому при смене размера окна вёрстка собирается заново.
        rootTable?.remove()
        textures.forEach { it.dispose() }
        textures.clear()
        val rootTable = globalVisTable {
            setFillParent(true)

            val compact = stage.width < 820.dp() || stage.height < 540.dp()
            val tools: VisTable.() -> Unit = {
                visLeftArrowButton(onClick = {
                    stage.saveDialog(isGoToMenu = true, genomeName = editorSimulationSystem.getGenome().name, editorLogicSystem)
                }).hint(bundle.get("editor.hint.back"))

                visTextButton(bundle.get("button.saveGenome"), onClick = {
                    stage.saveDialog(isGoToMenu = false, genomeName = editorSimulationSystem.getGenome().name, editorLogicSystem)
                }).hint(bundle.get("editor.hint.save"))

                visToggleButton(
                    text = bundle.get("button.showPhysicalLink"),
                    checked = showPhysicalLink,
                    onCheckedChange = {
                        editorLogicSystem.putUiCommand(SetShowPhysicalLinks(it))
                    }
                ).hint(bundle.get("editor.hint.physicalLinks"))

                visTextButton(bundle.get("button.neuralLinkColor"), onClick = {
                    stage.colorPickerDialog(
                        initColor = linkColor,
                        onFinished = { editorLogicSystem.putUiCommand(SetNeuralLinkColor(Color.rgba8888(it))) }
                    )
                }).hint(bundle.get("editor.hint.linkColor"))

                visTextButton(bundle.get("button.symmetry"), onClick = {
                    SymmetryDialog(
                        current = symmetryManager.symmetryMode,
                        onApply = { editorLogicSystem.putUiCommand(SetSymmetry(it)) }
                    ).show(stage)
                }).hint(bundle.get("editor.hint.symmetry"))

                // Режим — свой у редактора, с режимом симуляции не связан.
                add(
                    renderModeSelectBox(
                        selected = editorRenderSystem.mode,
                        onSelect = { editorLogicSystem.putUiCommand(SetRenderMode(it)) }
                    ).hint(bundle.get("editor.hint.renderMode"))
                )

                if (Gdx.app.type == Application.ApplicationType.Android) {
                    visToggleButton(
                        text = bundle.get("button.neural-linking"),
                        checked = isNeuralLinking,
                        onCheckedChange = {
                            editorLogicSystem.putUiCommand(SetNeuralLinking(it))
                        }
                    ).hint(bundle.get("editor.hint.neuralLinking"))

                    visToggleButton(
                        text = bundle.get("button.performLastAction"),
                        checked = isRightClick,
                        onCheckedChange = {
                            editorLogicSystem.putUiCommand(SetRepeatLastAction(it))
                        }
                    ).hint(bundle.get("editor.hint.repeatLast"))
                }

            }
            visTable({ growX().pad(4.dp(), 4.dp(), 4.dp(), 56.dp()) }) {
                if (compact) {
                    visTextButton(bundle.get("button.menu"), onClick = {
                        val column = VisTable()
                        val source = VisTable().apply(tools)
                        for (actor in source.children.toList()) {
                            column.add(actor).minWidth(0f).growX().pad(2.dp()).row()
                        }
                        val viewport = stage.viewport
                        val dialog = object : VisDialog(bundle.get("button.menu")) {
                            override fun getPrefWidth() = minOf(viewport.worldWidth - 8.dp(), 400.dp())
                            override fun getPrefHeight() = viewport.worldHeight * .85f
                        }
                        dialog.setupTitleSize(game)
                        val scroll = ScrollPane(column)
                        scroll.setScrollingDisabled(true, false)
                        dialog.contentTable.add(scroll).minSize(0f).grow()
                        dialog.closeOnEscape()
                        dialog.show(stage)
                        dialog.centerWindow()
                    }) { left() }.name = "editor-tools"
                } else {
                    visFlowRow(maxWidth = stage.width - 128.dp(), content = tools)
                }
                if (editorRenderSystem.mode == RenderMode.DEBUG) {
                    val filter = makeStyledIconButton(icons.optics, textures)
                    filter.name = "debug-filter-button"
                    filter.imageCell.grow()
                    filter.pad(6.dp())
                    filter.addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: Actor?) {
                            stage.showDebugFilterDialog(editorRenderSystem.debugFilter) {
                                editorLogicSystem.putUiCommand(SetEditorDebugFilter(it))
                            }
                        }
                    })
                    add(filter.hint(bundle.get("debugFilter.hint"))).size(48.dp())
                }
            }
            row()

            // Пустая ячейка ввод не перехватывает: нажатия идут в поле. Панель занимает
            // только свой размер и не налезает ни на кнопки, ни на шкалу времени.
            if (sidePanel != null) {
                add(sidePanel).minHeight(0f).fillY().expand().right().padRight(4.dp())
            }
            else add().expand().fill()
            row()

            visTable(
                cellInit = {
                    expandX().fillX().bottom()
                    pad(2.dp())
                }
            ) {
                defaults().pad(2.dp()).center()

                undoButton = visTextButton(bundle.get("button.undo"), onClick = {
                    editorLogicSystem.putUiCommand(CtrlZ)
                }).hint(bundle.get("editor.hint.undo"))

                tickLabel = visLabel(text = "${bundle.get("button.tick")}0", textColor = Color.WHITE, align = Align.center) {
                    expandX().fillX()
                    center()
                }.hint(bundle.get("editor.hint.tick"))

                redoButton = visTextButton(bundle.get("button.redo"), onClick = {
                    editorLogicSystem.putUiCommand(CtrlY)
                }).hint(bundle.get("editor.hint.redo"))
            }

            row()

            visTable(
                cellInit = {
                    expandX().fillX().bottom()
                    pad(2.dp())
                }
            ) {
                defaults().pad(2.dp()).center()

                visTextButton("<<", onClick = {
                    editorLogicSystem.putUiCommand(GoToStartOfTimeLine)
                }) { width(48.dp()) }.hint(bundle.get("editor.hint.timelineStart"))

                visTextButton("<", onClick = {
                    editorLogicSystem.putUiCommand(PrevTickButtonTap)
                }) { width(48.dp()) }.hint(bundle.get("editor.hint.prevStage"))

                timeSlider = visSlider(
                    min = 0f,
                    max = lastTick.toFloat(),
                    step = 1f,
                    value = currentTick.toFloat(),
                    onValueChange = { newValue ->
                        if (!isProgrammaticChange) {
                            editorLogicSystem.putUiCommand(
                                TimeSlider(value = newValue.toInt())
                            )
                        }
                    }
                ) {
                    minWidth(0f).growX()
                }.hint(bundle.get("editor.hint.timeline"))

                visTextButton(">", onClick = {
                    editorLogicSystem.putUiCommand(NextTickButtonTap)
                }) { width(48.dp()) }.hint(bundle.get("editor.hint.nextStage"))

                visTextButton(">>", onClick = {
                    editorLogicSystem.putUiCommand(GoToEndOfTimeLine)
                }) { width(48.dp()) }.hint(bundle.get("editor.hint.timelineEnd"))
            }
        }
        // Под окнами, а не поверх: при пересборке на открытый диалог вёрстка не ложится.
        stage.root.addActorAt(0, rootTable)
        this.rootTable = rootTable
    }
}
