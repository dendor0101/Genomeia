package io.github.some_example_name.old.genome_editor.commands

import io.github.some_example_name.old.genome.Action
import io.github.some_example_name.old.genome.GenomeStage
import io.github.some_example_name.old.genome_editor.Command
import io.github.some_example_name.old.genome_editor.EditorCell

class ChangeDivideCommand(
    override val stage: Int,
    val clickedCell: EditorCell,
    val divide: Action,
    val genomeStageInstruction: MutableList<GenomeStage>
) : Command {

    private val oldGenomeStageInstruction = genomeStageInstruction.map { it.deepCopy() }
    private var newGenomeStageInstruction: List<GenomeStage>? = null

    override fun execute() {
        if (newGenomeStageInstruction != null) {
            genomeStageInstruction.clear()
            genomeStageInstruction.addAll(newGenomeStageInstruction!!)
            return
        }

        genomeStageInstruction[stage].cellActions.compute(clickedCell.parentId) { _, oldValue ->
            oldValue?.copy(divide = oldValue.divide?.copy(
                cellType = divide.cellType,
                color = divide.color,
                angleDirected = divide.angleDirected,
                funActivation = divide.funActivation,
                a = divide.a,
                b = divide.b,
                c = divide.c,
                isSum = divide.isSum,
                colorRecognition = divide.colorRecognition,
                lengthDirected = divide.lengthDirected
            ))
        }

        newGenomeStageInstruction = genomeStageInstruction.map { it.deepCopy() }
    }

    override fun undo() {
        genomeStageInstruction.clear()
        genomeStageInstruction.addAll(oldGenomeStageInstruction)
    }
}
