package io.github.some_example_name.old.editor.tutorial

import io.github.some_example_name.old.editor.system.logic.CtrlY
import io.github.some_example_name.old.editor.system.logic.CtrlZ
import io.github.some_example_name.old.editor.system.logic.TapScreen
import io.github.some_example_name.old.editor.system.logic.TutorialNextStep
import io.github.some_example_name.old.editor.system.logic.UiEditorCommands
import io.github.some_example_name.old.editor.undo_redo_commands.DivideCellCommand
import io.github.some_example_name.old.editor.undo_redo_commands.EditorHarness
import io.github.some_example_name.old.editor.undo_redo_commands.baseGenome
import io.github.some_example_name.old.editor.undo_redo_commands.cellAt
import io.github.some_example_name.old.systems.genomics.genome.Action
import it.unimi.dsi.fastutil.ints.IntArrayList
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Обучение засчитывает задания только по командам. Здесь команды идут так же, как в
 * EditorLogicSystem.putUiCommand: beforeCommand -> правка через настоящий стек истории ->
 * afterCommand. Без DIGenomeEditorContainer: его инициализация тянет OpenGL.
 */
class EditorTutorialTest {

    private val harness = EditorHarness(baseGenome())

    private fun tutorial(steps: List<TutorialStep>, organism: () -> OrganismSnapshot = { snapshot() }) =
        EditorTutorial(steps, organism).also { harness.stack.historyListener = it }

    /** Одна команда игрока: [edit] — правка генома внутри неё, как деление в TryToDivide. */
    private fun EditorTutorial.command(command: UiEditorCommands, edit: (() -> Unit)? = null) {
        beforeCommand(command)
        when (command) {
            CtrlZ -> harness.undo()
            CtrlY -> harness.redo()
            TutorialNextStep -> nextStep()
            else -> edit?.invoke()
        }
        afterCommand(command)
    }

    private fun divide(cellType: String) {
        val stages = harness.stages
        harness.execute(
            DivideCellCommand(
                clickedCell = cellAt(stages, 4, 5),
                neighboursCells = emptyList(),
                divide = Action(cellType = cellType),
                newId = 100 + harness.restarts,
                newPoint = 1f to 1f,
                stageInstruction = stages,
                currentTick = 4
            )
        )
    }

    private val tap = TapScreen(0f, 0f, isLeft = true, isCtrl = false)
    private val rightTap = TapScreen(0f, 0f, isLeft = false, isCtrl = false)

    @Test
    fun `правки считаются, отмена снимает, повтор возвращает`() {
        val t = tutorial(
            listOf(TutorialStep("s", listOf(TutorialTask.Edits("leaf", 2) { c, _ -> c is DivideCellCommand && c.divide.cellType == "leaf" }))),
        )
        t.command(tap) { divide("leaf") }
        t.command(tap) { divide("fat") }
        assertEquals(1, t.progress(0))

        t.command(CtrlZ) // отменили fat — leaf остаётся
        assertEquals(1, t.progress(0))
        t.command(CtrlZ) // отменили leaf
        assertEquals(0, t.progress(0))
        t.command(CtrlY)
        assertEquals(1, t.progress(0))

        t.command(tap) { divide("leaf") }
        assertTrue(t.isStepDone)
    }

    @Test
    fun `причина правки - отличает повтор правым кликом`() {
        val t = tutorial(
            listOf(TutorialStep("s", listOf(TutorialTask.Edits("repeat", 1) { _, cause -> cause is TapScreen && !cause.isLeft })))
        )
        t.command(tap) { divide("leaf") }
        assertEquals(0, t.progress(0))
        t.command(rightTap) { divide("leaf") }
        assertEquals(1, t.progress(0))
    }

    @Test
    fun `шаг не перелистывается, пока задания не выполнены, и счётчики нового шага с нуля`() {
        val t = tutorial(
            listOf(
                TutorialStep("a", listOf(TutorialTask.Actions("undo", 1) { it is CtrlZ })),
                TutorialStep("b", listOf(TutorialTask.Actions("undo", 1) { it is CtrlZ })),
            )
        )
        t.command(TutorialNextStep)
        assertEquals(0, t.stepIndex)

        t.command(CtrlZ)
        assertTrue(t.isStepDone)
        t.command(TutorialNextStep)
        assertEquals(1, t.stepIndex)
        assertFalse(t.isStepDone)

        // На последнем шаге «Далее» ничего не делает: выход решает экран.
        t.command(CtrlZ)
        t.command(TutorialNextStep)
        assertEquals(1, t.stepIndex)
    }

    @Test
    fun `организм проверяется после правок, и сломанное снова не засчитано`() {
        var organism = snapshot("controller", "tail", links = listOf(0 to 1))
        var reads = 0
        val t = tutorial(
            listOf(TutorialStep("s", listOf(TutorialTask.Organism("link") { it.hasSignal("controller", "tail") }))),
            organism = { reads++; organism }
        )
        assertTrue(t.isStepDone)

        // Команда без правки генома организм не перечитывает.
        t.command(tap)
        assertEquals(1, reads)

        organism = snapshot("controller", "tail", links = listOf(1 to 0))
        t.command(tap) { divide("leaf") }
        assertFalse(t.isStepDone, "сигнал идёт от Tail к Controller — это не то")
        assertEquals(2, reads)
    }

    @Test
    fun `снимок - сигнал доходит по цепочке`() {
        // t -> cos -> muscle -> muscle; cos -> chameleon
        val s = OrganismSnapshot(
            cellTypes = arrayOf("neuron", "neuron", "muscle", "muscle", "chameleon", "muscle"),
            formulas = arrayOf("time", "cos", "linear", "linear", "linear", "linear"),
            c = floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f),
            outgoing = edges(6, 0 to 1, 1 to 2, 2 to 3, 1 to 4)
        )
        val reached = s.reachableFrom(s.neurons("cos"))
        assertEquals(2, s.count("muscle", reached), "третья мышца ни с чем не соединена")
        assertEquals(1, s.count("chameleon", reached))
        assertTrue(s.hasSignal(0, 1))
        assertFalse(s.hasSignal(1, 0))
    }

    @Test
    fun `снимок - сигнал через третью клетку засчитан, цепочка ищется по разным клеткам`() {
        // controller -> neuron -> tail: напрямую не соединены, но сигнал доходит.
        val s = snapshot("controller", "neuron", "tail", "leaf", links = listOf(0 to 1, 1 to 2))
        assertFalse(s.hasSignal("controller", "tail"))
        assertTrue(s.reaches("controller", "tail"))
        assertFalse(s.reaches("tail", "controller"))

        assertTrue(s.hasChain(2))
        assertFalse(s.hasChain(3), "связей подряд только две")
        assertTrue(snapshot("a", "b", "c", "d", links = listOf(0 to 1, 1 to 2, 2 to 3)).hasChain(3))
        // Круг из двух клеток — не цепочка из трёх связей: клетки в ней повторяются.
        assertFalse(snapshot("a", "b", links = listOf(0 to 1, 1 to 0)).hasChain(3))
        // Три связи из одной клетки — не подряд.
        assertFalse(snapshot("a", "b", "c", "d", links = listOf(0 to 1, 0 to 2, 0 to 3)).hasChain(2))
    }

    @Test
    fun `у каждого шага и задания обучения есть тексты во всех переводах`() {
        val keys = TutorialSteps.all.flatMap { step ->
            listOf("tutorial.${step.id}.title", "tutorial.${step.id}.text") + step.tasks.map { "tutorial.task.${it.id}" }
        }.toSet() + "tutorial.tasks"
        val dir = File("../assets/ui/i18n")
        for (name in listOf("MyBundle", "MyBundle_ru", "MyBundle_es", "MyBundle_id")) {
            val props = Properties().apply { File(dir, "$name.properties").reader(Charsets.UTF_8).use(::load) }
            val missing = keys.filterNot(props::containsKey)
            assertTrue(missing.isEmpty(), "$name: нет ${missing.joinToString()}")
        }
    }

    private fun snapshot(vararg types: String, links: List<Pair<Int, Int>> = emptyList()) = OrganismSnapshot(
        cellTypes = arrayOf(*types),
        formulas = arrayOfNulls(types.size),
        c = FloatArray(types.size),
        outgoing = edges(types.size, *links.toTypedArray())
    )

    private fun edges(size: Int, vararg links: Pair<Int, Int>) =
        Array(size) { from -> IntArrayList(links.filter { it.first == from }.map { it.second }) }
}
