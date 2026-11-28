package io.github.some_example_name.old.editor.tutorial

import io.github.some_example_name.old.editor.system.command.EditorHistoryListener
import io.github.some_example_name.old.editor.system.logic.UiEditorCommands
import io.github.some_example_name.old.editor.undo_redo_commands.UndoRedoCommand

/**
 * Шаг обучения: экран текста и список заданий. Шаг пройден, когда выполнены все задания;
 * дальше игрок сам жмёт «Далее» (команда TutorialNextStep).
 *
 * Тексты — в I18NBundle по [id]: `tutorial.<id>.title`, `tutorial.<id>.text`.
 */
class TutorialStep(val id: String, val tasks: List<TutorialTask> = emptyList())

/**
 * Задание шага. Текст — `tutorial.task.<id>` (в нём {0} — сделано, {1} — нужно).
 *
 * Проверяются задания только по командам редактора, поэтому повтор .bug проходит
 * обучение ровно так же, как игрок: прогресс не хранится отдельно, а заново набирается
 * из тех же команд.
 */
sealed class TutorialTask(val id: String) {

    /** Сколько нужно сделать; у проверки организма — 1. */
    abstract val target: Int

    /**
     * Правки генома: команды истории редактора (деление, мутация, нейросвязь…). Отмена
     * правки (Ctrl+Z) снимает её и с задания, повтор (Ctrl+Y) — возвращает.
     *
     * [cause] — действие игрока, которым правка сделана: по нему отличается, например,
     * «повторить последнее действие» правым кликом от обычного деления через диалог.
     */
    class Edits(
        id: String,
        override val target: Int,
        val match: (command: UndoRedoCommand, cause: UiEditorCommands?) -> Boolean
    ) : TutorialTask(id)

    /** Действия игрока, которые геном не меняют: камера, время, Ctrl+Z, сохранение. */
    class Actions(
        id: String,
        override val target: Int,
        val match: (UiEditorCommands) -> Boolean
    ) : TutorialTask(id)

    /**
     * Состав выращенного организма. Проверяется после каждой правки генома: что
     * сломали — задание снова не выполнено.
     */
    class Organism(
        id: String,
        val check: (OrganismSnapshot) -> Boolean
    ) : TutorialTask(id) {
        override val target get() = 1
    }
}

/**
 * Прохождение обучения в редакторе генома.
 *
 * Слушает два канала: команды игрока (EditorLogicSystem.putUiCommand — до и после
 * выполнения) и правки генома (история редактора, [EditorHistoryListener]). Правка всегда
 * случается внутри команды игрока, поэтому у неё есть причина — [cause].
 *
 * Шаг меняется только командой TutorialNextStep, а не сам: иначе шаг перелистнулся бы
 * посреди текста, который игрок ещё читает.
 */
class EditorTutorial(
    val steps: List<TutorialStep>,
    /** Организм редактора как он выращен сейчас. Зовётся только на шагах с проверкой организма. */
    private val readOrganism: () -> OrganismSnapshot
) : EditorHistoryListener {

    init {
        require(steps.isNotEmpty()) { "обучение без шагов" }
    }

    var stepIndex = 0
        private set

    val step: TutorialStep get() = steps[stepIndex]

    val isLastStep: Boolean get() = stepIndex == steps.lastIndex

    /** Экран перерисовывает панель шага. */
    var onChanged: (() -> Unit)? = null

    private val maxTasks = steps.maxOf { it.tasks.size }

    /** Счётчики заданий [TutorialTask.Actions]. */
    private val actions = IntArray(maxTasks)

    /** Правки, засчитанные заданиям [TutorialTask.Edits] на этом шаге. По ссылке: отмена снимает именно её. */
    private val edits = Array(maxTasks) { ArrayList<UndoRedoCommand>() }

    /** Итог проверок [TutorialTask.Organism]. */
    private val organism = BooleanArray(maxTasks)

    /** Геном менялся с последней проверки организма. */
    private var organismChanged = true

    private var cause: UiEditorCommands? = null

    init {
        checkOrganism()
    }

    /** Сколько сделано по заданию [task] шага (не больше нужного). */
    fun progress(task: Int): Int {
        val t = step.tasks[task]
        val done = when (t) {
            is TutorialTask.Edits -> edits[task].size
            is TutorialTask.Actions -> actions[task]
            is TutorialTask.Organism -> if (organism[task]) 1 else 0
        }
        return done.coerceAtMost(t.target)
    }

    fun isDone(task: Int): Boolean = progress(task) >= step.tasks[task].target

    val isStepDone: Boolean get() = step.tasks.indices.all(::isDone)

    fun beforeCommand(command: UiEditorCommands) {
        cause = command
    }

    fun afterCommand(command: UiEditorCommands) {
        cause = null
        step.tasks.forEachIndexed { i, task ->
            if (task is TutorialTask.Actions && task.match(command)) actions[i]++
        }
        checkOrganism()
        onChanged?.invoke()
    }

    override fun onApplied(command: UndoRedoCommand) {
        organismChanged = true
        val cause = cause
        step.tasks.forEachIndexed { i, task ->
            if (task is TutorialTask.Edits && task.match(command, cause)) edits[i] += command
        }
    }

    override fun onUndone(command: UndoRedoCommand) {
        organismChanged = true
        for (list in edits) list.removeAll { it === command }
    }

    /**
     * Следующий шаг, если этот пройден. На последнем шаге ничего не делает: что делать
     * по его кнопке (выйти в меню), решает экран.
     */
    fun nextStep() {
        if (!isStepDone || isLastStep) return
        stepIndex++
        actions.fill(0)
        edits.forEach { it.clear() }
        organism.fill(false)
        organismChanged = true
        checkOrganism()
    }

    private fun checkOrganism() {
        if (!organismChanged) return
        val tasks = step.tasks
        if (tasks.none { it is TutorialTask.Organism }) return
        organismChanged = false
        val snapshot = readOrganism()
        tasks.forEachIndexed { i, task ->
            if (task is TutorialTask.Organism) organism[i] = task.check(snapshot)
        }
    }
}
