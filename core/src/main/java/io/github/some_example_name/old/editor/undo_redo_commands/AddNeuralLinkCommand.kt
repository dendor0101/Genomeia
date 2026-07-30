package io.github.some_example_name.old.editor.undo_redo_commands

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.core.prettyPrint
import io.github.some_example_name.old.editor.entities.EditorCell
import io.github.some_example_name.old.systems.genomics.genome.Action
import io.github.some_example_name.old.systems.genomics.genome.CellAction
import io.github.some_example_name.old.systems.genomics.genome.GenomeStage
import io.github.some_example_name.old.systems.genomics.genome.LinkData

class AddNeuralLinkCommand(
    val cellFrom: EditorCell,
    val cellTo: EditorCell,
//    val isNeural: Boolean,
//    val isLongNeuralLink: Boolean,
    val color: Color,
    val linkIndex: Int,
    val isLink1NeuralDirected: Boolean,
    stageInstruction: MutableList<GenomeStage>,
    currentTick: Int
) : UndoRedoCommand(
    tick = currentTick,
    genomeStageInstruction = stageInstruction,
    doesNeedAddNewStage = stageInstruction.size <= currentTick
) {

    override fun execute(): StageResult {
        val stage = genomeStageInstruction[tick]

        // === 1. Определяем, что делать со связью (добавить / удалить) ===
        val linkData: LinkData? = when {
            linkIndex == -1 -> LinkData(
                isNeuronal = true,
                directedNeuronLink = cellTo.id,
                color = color
            )
            else -> null // удаляем нейро-линк
//            isNeural -> LinkData(isNeuronal = false, directedNeuronLink = null)
//            else -> LinkData(
//                isNeuronal = true,
//                directedNeuronLink = cellTo.id,
//                color = color
//            )
        }

        // === 2. Основная логика обновления ===
        val newCellActions: Map<Int, CellAction> = when {
            // === Случай: оба фантома (или один фантом) — работаем с divide ===
            cellFrom.isPhantom || cellTo.isPhantom -> {
                val targetCell = when {
                    cellFrom.isPhantom && cellTo.isPhantom ->
                        if (cellFrom.divide?.physicalLink?.containsKey(cellTo.id) == true) cellFrom
                        else cellTo
                    cellFrom.isPhantom -> cellFrom
                    else -> cellTo
                }
                val targetId = targetCell.id
                val parentTargetId = targetCell.parentId

                val oldAction = stage.cellActions[parentTargetId]
                val oldDivide = oldAction?.divide
                val otherCellId = if (targetId == cellFrom.id) cellTo.id else cellFrom.id

                val newPhysicalLink = if (linkData == null) {
                    //Удаление нейролинка
                    oldDivide?.physicalLink?.minus(otherCellId) ?: emptyMap()
                } else {
                    //Добавление нейролинка
                    val oldLinks = oldDivide?.physicalLink ?: emptyMap()
                    val oldLink = oldLinks[otherCellId] ?: linkData
                    oldLinks + (otherCellId to oldLink.copy(
                        isNeuronal = linkData.isNeuronal,
                        directedNeuronLink = linkData.directedNeuronLink,
                        color = linkData.color
                    ))
                }

                val newDivide = oldDivide?.copy(physicalLink = newPhysicalLink)
                val newAction = if (newDivide != null) {
                    oldAction.copy(divide = newDivide)
                } else {
                    oldAction?.copy(divide = null)
                }

                if (newAction != null && (newAction.divide != null || newAction.mutate != null)) {
                    stage.cellActions + (parentTargetId to newAction)
                } else {
                    stage.cellActions - parentTargetId
                }
            }

            // === Случай: работаем с mutate (клетки которые уже есть в этом тике) ===
            else -> {
                var targetParentId = cellFrom.id
                var otherCellId = cellTo.id

                if (linkIndex != -1) {
                    targetParentId = if (isLink1NeuralDirected) cellTo.id else cellFrom.id
                    otherCellId = if (isLink1NeuralDirected) cellFrom.id else cellTo.id
                }

                val oldAction = stage.cellActions[targetParentId]
                val oldMutate = oldAction?.mutate

                // Вычисляем новый physicalLink для mutate
                val currentLinks = oldMutate?.physicalLink ?: emptyMap()

                val newLinks: Map<Int, LinkData?> = when {
                    linkData == null -> {
                        if (currentLinks.containsKey(otherCellId)) {
                            currentLinks - otherCellId
                        } else {
                            currentLinks + (otherCellId to linkData)
                        }
                    }
                    else -> currentLinks + (otherCellId to linkData)
                }

                val newMutate = if (newLinks.isEmpty()) {
                    null
                } else {
                    (oldMutate ?: Action()).copy(physicalLink = newLinks)
                }

                val newAction = when (newMutate) {
                    null if oldAction?.divide == null -> null
                    null -> oldAction?.copy(mutate = null)
                    else -> (oldAction ?: CellAction()).copy(mutate = newMutate)
                }

                if (newAction != null) {
                    stage.cellActions + (targetParentId to newAction)
                } else {
                    stage.cellActions - targetParentId
                }
            }
        }

        val newStage = stage.copy(cellActions = newCellActions)

        return if (newStage.cellActions.isEmpty()) {
            StageResult.Remove
        } else {
            StageResult.Keep(newStage)
        }
    }
}
