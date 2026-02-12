package io.github.some_example_name.old.genome_editor.commands

import io.github.some_example_name.old.genome.GenomeStage
import io.github.some_example_name.old.genome_editor.Command
import io.github.some_example_name.old.genome_editor.EditorCell

class RemoveCellCommand(
    val currentStage: Int,
    val clickedCell: EditorCell,
    val parentCell: EditorCell,
    val genomeStageInstruction: MutableList<GenomeStage>,
) : Command {
    override val stage = currentStage

    private val oldGenomeStageInstruction = genomeStageInstruction.map { it.deepCopy() }
    private var newGenomeStageInstruction: List<GenomeStage>? = null

    override fun execute() {

        if (newGenomeStageInstruction != null) {
            genomeStageInstruction.clear()
            genomeStageInstruction.addAll(newGenomeStageInstruction!!)
            return
        }

        val clickedCellDivideId = parentCell.divide?.id ?: return
        removeIfMutateNull(parentCell.id, currentStage)

        val deleteCellsIdList = mutableListOf<Int>()
        deleteCellsIdList.add(clickedCellDivideId)

        val deleteEmptyStageLists = mutableListOf<Int>()

        val addDeleteCellsIdList = mutableListOf<Int>()
        for (stage in currentStage + 1 until genomeStageInstruction.size ) {
            deleteCellsIdList.forEach {
                genomeStageInstruction[stage].cellActions.compute(it) { _, current ->
                    if (current == null) return@compute null
                    if (current.divide != null) {
                        addDeleteCellsIdList.add(current.divide!!.id)
                    }
                    null
                }
                genomeStageInstruction[stage - 1].cellActions.forEach { action ->
                    action.value.divide?.physicalLink?.remove(it)
                    action.value.mutate?.physicalLink?.remove(it)
                }
            }
            deleteCellsIdList.addAll(addDeleteCellsIdList)
            addDeleteCellsIdList.clear()

            if (genomeStageInstruction[stage].cellActions.isEmpty()) {
                deleteEmptyStageLists.add(stage)
            }
        }
        deleteCellsIdList.forEach {
            genomeStageInstruction.last().cellActions.forEach { action ->
                action.value.divide?.physicalLink?.remove(it)
                action.value.mutate?.physicalLink?.remove(it)
            }
        }
        deleteEmptyStageLists.sortedDescending().forEach {
            genomeStageInstruction.removeAt(it)
        }

        newGenomeStageInstruction = genomeStageInstruction.map { it.deepCopy() }
    }

    fun removeIfMutateNull(cellId: Int, currentStage: Int) {
        genomeStageInstruction[currentStage].cellActions.compute(cellId) { _, current ->
            if (current == null) return@compute null
            current.divide = null
            if (current.mutate == null) null else current
        }
    }

    override fun undo() {
        genomeStageInstruction.clear()
        genomeStageInstruction.addAll(oldGenomeStageInstruction)
    }
}
