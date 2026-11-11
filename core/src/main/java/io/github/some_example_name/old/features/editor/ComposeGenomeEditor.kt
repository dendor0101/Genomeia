package io.github.some_example_name.old.features.editor

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
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
import io.github.some_example_name.old.features.levels.editor.LevelInfo
import io.github.some_example_name.old.core.ui.FlowAlignment
import io.github.some_example_name.old.core.ui.STYLE_BLACK
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.renderModeSelectBox
import io.github.some_example_name.old.core.ui.globalVisTable
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
class ComposeGenomeEditor(private val returnLevel: LevelInfo? = null) {

    var isProgrammaticChange = false

    lateinit var tickLabel: VisLabel
    lateinit var timeSlider: VisSlider

    lateinit var undoButton: VisTextButton
    lateinit var redoButton: VisTextButton

    fun composeGenomeEditor(
        stage: Stage,
        editorSimulationSystem: EditorSimulationSystem,
        editorLogicSystem: EditorLogicSystem,
        editorRenderSystem: EditorRenderSystem
    ) {
        val rootTable = globalVisTable {
            setFillParent(true)

            add().expandX().fillX().padTop(16.dp())
            row()

            visFlowRow(
                alignment = FlowAlignment.Center,
                horizontalSpacing = 12.dp(),
                verticalSpacing = 8.dp()
            ) {
                visLeftArrowButton(onClick = {
                    stage.saveDialog(isGoToMenu = true, genomeName = editorSimulationSystem.getGenome().name, editorLogicSystem, returnLevel)
                })

                visTextButton(bundle.get("button.saveGenome"), onClick = {
                    stage.saveDialog(isGoToMenu = false, genomeName = editorSimulationSystem.getGenome().name, editorLogicSystem, returnLevel)
                })

                visToggleButton(
                    text = bundle.get("button.showPhysicalLink"),
                    checked = showPhysicalLink,
                    onCheckedChange = {
                        editorLogicSystem.putUiCommand(SetShowPhysicalLinks(it))
                    }
                )

                visTextButton("Neural link color", onClick = {
                    stage.colorPickerDialog(
                        initColor = linkColor,
                        onFinished = { editorLogicSystem.putUiCommand(SetNeuralLinkColor(Color.rgba8888(it))) }
                    )
                })

                visTextButton("Symmetry", onClick = {
                    SymmetryDialog(
                        current = symmetryManager.symmetryMode,
                        onApply = { editorLogicSystem.putUiCommand(SetSymmetry(it)) }
                    ).show(stage)
                })

                // Режим — свой у редактора, с режимом симуляции не связан.
                add(
                    renderModeSelectBox(
                        selected = editorRenderSystem.mode,
                        onSelect = { editorLogicSystem.putUiCommand(SetRenderMode(it)) }
                    )
                )

                if (Gdx.app.type == Application.ApplicationType.Android) {
                    visToggleButton(
                        text = bundle.get("button.neural-linking"),
                        checked = isNeuralLinking,
                        onCheckedChange = {
                            editorLogicSystem.putUiCommand(SetNeuralLinking(it))
                        }
                    )

                    visToggleButton(
                        text = bundle.get("button.performLastAction"),
                        checked = isRightClick,
                        onCheckedChange = {
                            editorLogicSystem.putUiCommand(SetRepeatLastAction(it))
                        }
                    )
                }

//                visSelectBox(arrayOf("Main genome", "arm", "head", "tail", "neuron"))

            }
            row()

            add().expand().fill()
            row()

            visTable(
                cellInit = {
                    expandX().fillX().bottom()
                    pad(20.dp(), 16.dp(), 0.dp(), 16.dp())
                }
            ) {
                defaults().pad(8.dp()).center()

                undoButton = visTextButton("Undo", onClick = {
                    editorLogicSystem.putUiCommand(CtrlZ)
                })

                tickLabel = visLabel(text = "${bundle.get("button.tick")}0", textColor = STYLE_BLACK, align = Align.center) {
                    expandX().fillX()
                    center()
                }

                redoButton = visTextButton("Redo", onClick = {
                    editorLogicSystem.putUiCommand(CtrlY)
                })
            }

            row()

            visTable(
                cellInit = {
                    expandX().fillX().bottom()
                    pad(0.dp(), 16.dp(), 20.dp(), 16.dp())
                }
            ) {
                defaults().pad(8.dp()).center()

                visTextButton("<<", onClick = {
                    editorLogicSystem.putUiCommand(GoToStartOfTimeLine)
                })

                visTextButton("<", onClick = {
                    editorLogicSystem.putUiCommand(PrevTickButtonTap)
                })

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
                    expandX().fillX()
                    pad(0f, 24.dp(), 0f, 24.dp())
                }

                visTextButton(">", onClick = {
                    editorLogicSystem.putUiCommand(NextTickButtonTap)
                })

                visTextButton(">>", onClick = {
                    editorLogicSystem.putUiCommand(GoToEndOfTimeLine)
                })
            }
        }
        stage.addActor(rootTable)
    }
}
