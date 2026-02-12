package io.github.some_example_name.old.genome_editor.commands

import io.github.some_example_name.old.genome.Action
import io.github.some_example_name.old.genome.CellAction
import io.github.some_example_name.old.genome.GenomeStage
import io.github.some_example_name.old.genome.LinkData
import io.github.some_example_name.old.genome_editor.Command
import io.github.some_example_name.old.genome_editor.EditorCell
import io.github.some_example_name.old.genome_editor.EditorLinks

class AddNeuralLinkCommand(
    val currentStage: Int,
    val cellFrom: EditorCell,
    val cellTo: EditorCell,
    val genomeStageInstruction: MutableList<GenomeStage>,
    val doesNeedAddNewStage: Boolean,
    val link: EditorLinks,
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

        val isNeural = link.isNeuralTo2 != null

        val linkData = if (isNeural) {
            LinkData(
                isNeuronal = false,
                directedNeuronLink = null
            )
        } else {
            LinkData(
                isNeuronal = true,
                directedNeuronLink = cellTo.id
            )
        }

        if (doesNeedAddNewStage) {
            genomeStageInstruction.add(GenomeStage())
        }

        when {
            cellFrom.isJustAdded && cellTo.isJustAdded -> {
                if (cellFrom.divide?.physicalLink[cellTo.id] != null) {
                    cellFrom.divide.physicalLink.compute(cellTo.id) { _, old ->
                        old?.copy(
                            isNeuronal = linkData.isNeuronal,
                            directedNeuronLink = linkData.directedNeuronLink
                        )
                    }
                } else if (cellTo.divide?.physicalLink[cellFrom.id] != null) {
                    cellTo.divide.physicalLink.compute(cellFrom.id) { _, old ->
                        old?.copy(
                            isNeuronal = linkData.isNeuronal,
                            directedNeuronLink = linkData.directedNeuronLink
                        )
                    }
                }
            }
            cellFrom.isJustAdded && !cellTo.isJustAdded -> {
                cellFrom.divide?.physicalLink?.compute(cellTo.id) { _, old ->
                    old?.copy(
                        isNeuronal = linkData.isNeuronal,
                        directedNeuronLink = linkData.directedNeuronLink
                    )
                }
            }
            !cellFrom.isJustAdded && cellTo.isJustAdded -> {
                cellTo.divide?.physicalLink?.compute(cellFrom.id) { _, old ->
                    old?.copy(
                        isNeuronal = linkData.isNeuronal,
                        directedNeuronLink = linkData.directedNeuronLink
                    )
                }
            }
            else -> {
                val otherCellId = if (cellTo.id != link.parentId) cellTo.id else cellFrom.id
                val parentCell = link.parentId

                val mutate = Action(physicalLink = hashMapOf(otherCellId to linkData))
                genomeStageInstruction[currentStage].cellActions.compute(parentCell) { _, current ->
                    return@compute when {
                        current == null -> CellAction(mutate = mutate)
                        current.mutate == null -> current.copy(mutate = mutate)
                        else -> {
                            current.also {
                                it.mutate?.physicalLink?.compute(otherCellId) { _, old ->
                                    if (old == null) return@compute linkData
                                    old.copy(
                                        isNeuronal = linkData.isNeuronal,
                                        directedNeuronLink = linkData.directedNeuronLink
                                    )
                                }
                                if (current.mutate!!.physicalLink.isEmpty()) {
                                    val default = Action()
                                    if (default == current.mutate) {
                                        current.mutate = null
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        newGenomeStageInstruction = genomeStageInstruction.map { it.deepCopy() }
    }

    override fun undo() {
        genomeStageInstruction.clear()
        genomeStageInstruction.addAll(oldGenomeStageInstruction)
    }
}
