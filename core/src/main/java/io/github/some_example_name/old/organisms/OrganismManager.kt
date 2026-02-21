package io.github.some_example_name.old.organisms

import io.github.some_example_name.old.genome.Action

//TODO It would probably be more logical to call this entity an organ, since one whole organism can consist of many such entities

class OrganismManager {
    val organisms = mutableListOf<Organism>()
}

class AddCell(
    val action: Action,
    val parentX: Float,
    val parentY: Float,
    val parentAngle: Float,
    val parentId: Int,
    val parentOrganismId: Int,
    val parentIndex: Int
)

class AddLink(
    val cellIndex: Int,
    val otherCellIndex: Int,
    val linksLength: Float,
    val degreeOfShortening: Float,
    val isStickyLink: Boolean,
    val isNeuronLink: Boolean,
    val isLink1NeuralDirected: Boolean,
//    val directedNeuronLink: Int
)

//TODO 100% Implement it using a structure of arrays
class Organism(
    var genomeIndex: Int,
    var genomeSize: Int,
    var stage: Int = 0,
    var dividedTimes: Int = 0,
    var mutatedTimes: Int = 0,
    var alreadyGrownUp: Boolean = false,
    var divideCounterThisStage: Int = 0,
    var mutateCounterThisStage: Int = 0,
    var divideAmountThisStage: Int = 0,
    var mutateAmountThisStage: Int = 0,
    var justChangedStage: Boolean = true
) {
    override fun toString(): String {
        return "Organism(genomeIndex=$genomeIndex, genomeSize=$genomeSize, stage=$stage, dividedTimes=${dividedTimes}, mutatedTimes=${mutatedTimes}, justChangedStage=$justChangedStage)"
    }
}
