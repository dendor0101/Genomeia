package io.github.some_example_name.old.genome_editor.commands

import io.github.some_example_name.old.genome.Action
import io.github.some_example_name.old.genome.CellAction
import io.github.some_example_name.old.genome.GenomeStage
import io.github.some_example_name.old.genome_editor.Command
import io.github.some_example_name.old.genome_editor.EditorCell

class MutateCellCommand(
    override val stage: Int,
    val action: Action,
    val clickedCell: EditorCell,
    val genomeStageInstruction: MutableList<GenomeStage>,
    val doesNeedAddNewStage: Boolean
) : Command {
    private val oldGenomeStageInstruction = genomeStageInstruction.map { it.deepCopy() }
    private var newGenomeStageInstruction: List<GenomeStage>? = null

    override fun execute() {
        if (newGenomeStageInstruction != null) {
            genomeStageInstruction.clear()
            genomeStageInstruction.addAll(newGenomeStageInstruction!!)
            return
        }

        if (doesNeedAddNewStage) {
            genomeStageInstruction.add(GenomeStage())
        }

        genomeStageInstruction[stage].cellActions.compute(clickedCell.id) { _, oldValue ->
            if (oldValue == null) return@compute CellAction(
                mutate = action
            )
            if (oldValue.mutate == null) return@compute oldValue.copy(mutate = action)

            oldValue.copy(
                mutate = action.copy(
                    physicalLink = oldValue.mutate?.physicalLink ?: action.physicalLink
                )
            )
        }

        newGenomeStageInstruction = genomeStageInstruction.map { it.deepCopy() }
    }

    override fun undo() {
        genomeStageInstruction.clear()
        genomeStageInstruction.addAll(oldGenomeStageInstruction)
    }

}
