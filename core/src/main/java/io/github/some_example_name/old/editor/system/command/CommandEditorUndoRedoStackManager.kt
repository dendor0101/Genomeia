package io.github.some_example_name.old.editor.system.command

import io.github.some_example_name.old.editor.undo_redo_commands.UndoRedoCommand
import io.github.some_example_name.old.editor.system.logic.RestartSimulationCallBack
import java.util.Stack

/**
 * Кто следит за правками генома: обучение считает по ним выполненные задания.
 *
 * Вызывается ПОСЛЕ того, как геном изменён и тело заново выращено, — слушатель может
 * смотреть на получившийся организм.
 */
interface EditorHistoryListener {
    /** Правка выполнена впервые или повторена (redo). */
    fun onApplied(command: UndoRedoCommand)

    /** Правка отменена (undo). */
    fun onUndone(command: UndoRedoCommand)
}

class CommandEditorStackManager() {
    val undoStack = Stack<UndoRedoCommand>()
    val redoStack = Stack<UndoRedoCommand>()
    private val MAX_COMMANDS = 32
    lateinit var restartSimulationCallBack: RestartSimulationCallBack

    var historyListener: EditorHistoryListener? = null

    fun bind(restartSimulationCallBack: RestartSimulationCallBack) {
        this.restartSimulationCallBack = restartSimulationCallBack
    }

    fun executeCommand(command: UndoRedoCommand) {
        command.redo()
        undoStack.push(command)
        if (undoStack.size > MAX_COMMANDS) {
            undoStack.removeElementAt(0)
        }
        redoStack.clear()
        restartSimulationCallBack.restartSimulation()
        historyListener?.onApplied(command)
    }

    fun undo() {
        if (!undoStack.isEmpty()) {
            val command = undoStack.pop()
            // Геном заменили мимо истории (открыли другой) — эта история к нему не относится.
            if (!command.undo()) {
                clear()
                return
            }
            redoStack.push(command)
            if (redoStack.size > MAX_COMMANDS) {
                redoStack.removeElementAt(0)
            }
            restartSimulationCallBack.restartSimulation()
            historyListener?.onUndone(command)
        }
    }

    fun redo() {
        if (!redoStack.isEmpty()) {
            val command = redoStack.pop()
            if (!command.redo()) {
                clear()
                return
            }
            undoStack.push(command)
            if (undoStack.size > MAX_COMMANDS) {
                undoStack.removeElementAt(0)
            }
            restartSimulationCallBack.restartSimulation()
            historyListener?.onApplied(command)
        }
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
