package io.github.some_example_name.old.organisms

import io.github.some_example_name.old.genome.Action
import io.github.some_example_name.old.genome.GenomeStage
import io.github.some_example_name.old.good_one.utils.primitive_hash_map.UnorderedIntPairMap

class OrganismManager {
    val organisms = mutableListOf<Organism>()
}

class DecrementMutationCounter(
    val organismId: Int,
    val stage: Int
)

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
    val cellId: Int,
    val otherCellId: Int,
    val linksLength: Float,
    val degreeOfShortening: Float,
    val isStickyLink: Boolean,
    val isNeuronLink: Boolean,
    val directedNeuronLink: Int
)

class Organism(
    var genomeIndex: Int,
    var genomeSize: Int,
    var stage: Int = 0,
    var dividedTimes: IntArray,
    var mutatedTimes: IntArray,
    var justChangedStage: Boolean = false,
    var timerToGrowAfterStage: Int = 0,
    val linkIdMap: UnorderedIntPairMap
) {
    override fun toString(): String {
        return "Organism(genomeIndex=$genomeIndex, genomeSize=$genomeSize, stage=$stage, dividedTimes=${dividedTimes.contentToString()}, mutatedTimes=${mutatedTimes.contentToString()}, justChangedStage=$justChangedStage, timerToGrowAfterStage=$timerToGrowAfterStage)"
    }
}
