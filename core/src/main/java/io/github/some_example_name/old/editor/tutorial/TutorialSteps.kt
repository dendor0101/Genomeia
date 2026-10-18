package io.github.some_example_name.old.editor.tutorial

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer
import io.github.some_example_name.old.editor.system.logic.CtrlY
import io.github.some_example_name.old.editor.system.logic.CtrlZ
import io.github.some_example_name.old.editor.system.logic.GoToEndOfTimeLine
import io.github.some_example_name.old.editor.system.logic.NextTickButtonTap
import io.github.some_example_name.old.editor.system.logic.PanScreen
import io.github.some_example_name.old.editor.system.logic.SaveGenome
import io.github.some_example_name.old.editor.system.logic.TapScreen
import io.github.some_example_name.old.editor.system.logic.TimeSlider
import io.github.some_example_name.old.editor.system.logic.ZoomCamera
import io.github.some_example_name.old.editor.tutorial.TutorialTask.Actions
import io.github.some_example_name.old.editor.tutorial.TutorialTask.Edits
import io.github.some_example_name.old.editor.tutorial.TutorialTask.Organism
import io.github.some_example_name.old.editor.undo_redo_commands.AddNeuralLinkCommand
import io.github.some_example_name.old.editor.undo_redo_commands.CancelMutationCommand
import io.github.some_example_name.old.editor.undo_redo_commands.ChangeDivideCommand
import io.github.some_example_name.old.editor.undo_redo_commands.DivideCellCommand
import io.github.some_example_name.old.editor.undo_redo_commands.MoveCellCommand
import io.github.some_example_name.old.editor.undo_redo_commands.MutateCellCommand
import io.github.some_example_name.old.editor.undo_redo_commands.RemoveCellCommand
import io.github.some_example_name.old.editor.undo_redo_commands.UndoRedoCommand
import io.github.some_example_name.old.systems.genomics.genome.Action

/**
 * Обучение редактору генома: от камеры и первого деления до «Франкенштейна» — организма,
 * который плывёт по клавише (Controller -> Tail), дрыгается мышцами и моргает хамелеоном
 * от часов (нейрон y = t -> нейрон с cos). Форма тела не проверяется, только состав.
 *
 * Как сделать каждое действие на ПК и телефоне, пишут тексты шагов в I18NBundle
 * (`tutorial.<id>.text`); задания проверяют команды (см. EditorTutorial).
 */
object TutorialSteps {

    const val LEAF = "leaf"
    const val FAT = "fat"
    const val CONTROLLER = "controller"
    const val TAIL = "tail"
    const val MUSCLE = "muscle"
    const val CHAMELEON = "chameleon"
    const val TIME = "time"
    const val COS = "cos"

    /** Цвет нейросвязи по умолчанию (DIGenomeEditorContainer.linkColor при запуске). */
    private val DEFAULT_LINK_COLOR: Color = Color.CYAN

    // ------------------------------------------------------------ проверки организма

    /**
     * Нейроны с косинусом, до которых доходит сигнал нейрона-часов (y = t) — напрямую или
     * через другие клетки.
     */
    private fun clockedCosines(s: OrganismSnapshot): List<Int> {
        val fromClock = s.reachableFrom(s.neurons(TIME))
        return s.neurons(COS).filter { fromClock[it] }
    }

    /** Куда доходит качание косинуса: клетки после нейронов с cos, у которых c != 0. */
    private fun swing(s: OrganismSnapshot): BooleanArray =
        s.reachableFrom(clockedCosines(s).filter { s.c[it] != 0f })

    private val hasController = Organism("has_controller") { it.count(CONTROLLER) >= 1 }
    private val hasTail = Organism("has_tail") { it.count(TAIL) >= 1 }
    private val controllerToTail = Organism("controller_to_tail") { it.reaches(CONTROLLER, TAIL) }
    private val clockNeuron = Organism("clock_neuron") { it.neurons(TIME).isNotEmpty() }
    private val cosNeuron = Organism("cos_neuron") { clockedCosines(it).isNotEmpty() }
    // Амплитуда проверяется у любого нейрона с cos, а не только подключённого к часам:
    // игрок ставит c в диалоге раньше, чем проводит связь, и должен сразу видеть, что попал.
    private val cosAmplitude = Organism("cos_amplitude") { s -> s.neurons(COS).any { s.c[it] != 0f } }
    private val muscles = Organism("muscles") { it.count(MUSCLE, swing(it)) >= 2 }
    private val chameleon = Organism("chameleon") { it.count(CHAMELEON, swing(it)) >= 1 }

    // ------------------------------------------------------------ проверки правок

    private fun isDivide(command: UndoRedoCommand, cellType: String? = null) =
        command is DivideCellCommand && (cellType == null || command.divide.cellType == cellType)

    /** Новая связь, а не снятие старой: снятие — тот же AddNeuralLinkCommand с живой связью. */
    private fun isNewLink(command: UndoRedoCommand) = command is AddNeuralLinkCommand && command.linkIndex == -1

    /** Правка, которая ставит клетке цвет не тот, что у её типа (или был до мутации). */
    private fun changesColor(command: UndoRedoCommand): Boolean = when (command) {
        is DivideCellCommand -> isOwnColor(command.divide)
        is ChangeDivideCommand -> isOwnColor(command.divide)
        is MutateCellCommand -> command.action.color.let { it != null && it != command.clickedCell.actual?.color }
        else -> false
    }

    private fun isOwnColor(action: Action): Boolean {
        val color = action.color ?: return false
        val type = action.cellType ?: return false
        val cells = DIGenomeEditorContainer.cellList
        return cells.containsKey(type) && cells.byKey(type).defaultColor != color
    }

    // ------------------------------------------------------------ шаги

    val all: List<TutorialStep> = listOf(
        TutorialStep("welcome"),

        TutorialStep(
            "camera", listOf(
                Actions("camera_pan", 1) { it is PanScreen && DIGenomeEditorContainer.isDruggingCamera },
                Actions("camera_zoom", 1) { it is ZoomCamera },
            )
        ),

        // Время — раньше деления: на стадии 0 зигота уже делится (getEmptyGenome), и
        // поделить там нечего, пока не перейдёшь на следующую.
        TutorialStep(
            "timeline", listOf(
                Actions("next_stage", 1) {
                    (it is NextTickButtonTap || it is GoToEndOfTimeLine || it is TimeSlider) &&
                        DIGenomeEditorContainer.currentTick > 0
                },
            )
        ),

        TutorialStep("first_divide", listOf(Edits("divide_any", 1) { c, _ -> isDivide(c) })),

        TutorialStep("divide_leaf", listOf(Edits("divide_leaf", 5) { c, _ -> isDivide(c, LEAF) })),

        TutorialStep(
            "repeat", listOf(
                // Правый клик (на телефоне — нажатие в режиме «повторить») повторяет прошлую правку.
                Edits("repeat_last", 3) { _, cause -> cause is TapScreen && !cause.isLeft && !cause.isCtrl }
            )
        ),

        TutorialStep("move", listOf(Edits("move_cell", 1) { c, _ -> c is MoveCellCommand })),

        TutorialStep(
            "undo", listOf(
                Actions("undo", 1) { it is CtrlZ },
                Actions("redo", 1) { it is CtrlY },
            )
        ),

        TutorialStep(
            "mutate", listOf(
                Edits("mutate_leaf_fat", 2) { c, _ ->
                    c is MutateCellCommand && c.action.cellType == FAT && c.clickedCell.actual?.cellType == LEAF
                }
            )
        ),

        TutorialStep("color", listOf(Edits("cell_color", 1) { c, _ -> changesColor(c) })),

        TutorialStep(
            "remove", listOf(
                Edits("remove_cell", 1) { c, _ -> c is RemoveCellCommand },
                Edits("cancel_mutation", 1) { c, _ -> c is CancelMutationCommand },
            )
        ),

        TutorialStep(
            "neural_links", listOf(
                Edits("neural_link", 3) { c, _ -> isNewLink(c) },
                // Цепочка, а не три отдельные связи: так видно, что сигнал идёт по клеткам дальше.
                Organism("link_chain") { it.hasChain(3) },
            )
        ),

        TutorialStep(
            "link_color", listOf(
                Edits("link_color", 1) { c, _ -> isNewLink(c) && (c as AddNeuralLinkCommand).color != DEFAULT_LINK_COLOR },
                Edits("remove_link", 1) { c, _ -> c is AddNeuralLinkCommand && c.linkIndex != -1 },
            )
        ),

        TutorialStep("signals"),

        TutorialStep("controller", listOf(hasController, hasTail, controllerToTail)),

        TutorialStep("clock", listOf(clockNeuron, cosNeuron, cosAmplitude)),

        TutorialStep("muscles", listOf(muscles)),

        TutorialStep("chameleon", listOf(chameleon)),

        // Всё вместе: пока игрок доделывал остальное, что-то могло отвалиться.
        TutorialStep(
            "frankenstein", listOf(
                controllerToTail, cosNeuron, cosAmplitude, muscles, chameleon,
                Actions("save", 1) { it is SaveGenome },
            )
        ),

        TutorialStep("finish"),
    )
}
