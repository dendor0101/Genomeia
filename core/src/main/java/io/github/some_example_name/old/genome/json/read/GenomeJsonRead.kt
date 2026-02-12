package io.github.some_example_name.old.genome.json.read

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.genome.Action
import io.github.some_example_name.old.genome.CellAction
import io.github.some_example_name.old.genome.Genome
import io.github.some_example_name.old.genome.GenomeStage
import io.github.some_example_name.old.genome.LinkData
import kotlin.collections.component1
import kotlin.collections.component2


class CreatureJsonRead(
    val name: String = "",  // Made var for assignment in read
    val genomeStageInstruction: ArrayList<GenomeStageJsonRead> = ArrayList()
)

class GenomeStageJsonRead(
    val cellActions: HashMap<String, CellActionJsonRead> = hashMapOf()
)

class CellActionJsonRead(
    val divide: ActionJsonRead? = null,
    val mutate: ActionJsonRead? = null
)

class ActionJsonRead(
    val id: String? = null,
    val angle: Float? = null,
    val cellType: Int? = null,
    val indexOfNew: Int? = null,
    val physicalLink: HashMap<String, LinkDataJsonRead?> = hashMapOf(),
    val color: Color? = null,
    val colorHsv: String? = null,
    val angleDirected: Float? = null,
    val startDirectionId: Int? = null,
    val funActivation: Int? = null,
    val a: Float? = null,
    val b: Float? = null,
    val c: Float? = null,
    val isSum: Boolean? = null,
    val colorRecognition: Int? = null,
    val lengthDirected: Float? = null
)

class LinkDataJsonRead(
    val length: Float? = null,
    val isNeuronal: Boolean? = null,
    val weight: Float? = null,
    val directedNeuronLink: Int? = null
)


//Json to Domain
fun CreatureJsonRead.jsonToDomain(isEditor: Boolean = false): Genome {
    val genomeStageInstruction = this.toDomain(isEditor)
    val dividedTimes = IntArray(genomeStageInstruction.size)
    val mutatedTimes = IntArray(genomeStageInstruction.size)


    genomeStageInstruction.forEachIndexed { index, stage ->
        stage.cellActions.forEach { (_, action) ->
            if (action.divide != null) dividedTimes[index] ++
            if (action.mutate != null) mutatedTimes[index] ++
        }
    }
    return Genome(
        genomeStageInstruction = genomeStageInstruction.toMutableList(),
        dividedTimes = dividedTimes,
        mutatedTimes = mutatedTimes,
        name = this.name
    )
}

private fun CreatureJsonRead.toDomain(isEditor: Boolean): List<GenomeStage> {
    return genomeStageInstruction.map { stageJson ->
        val cellActions = stageJson.cellActions.mapKeys { (k, _) -> k.toInt() }
            .mapValues { (_, v) ->
                CellAction(
                    divide = v.divide?.toDomain(),
                    mutate = v.mutate?.toDomain()
                )
            } as HashMap<Int, CellAction>


        if (!isEditor) {
            //Эта манипуляция нужна для того чтобы если две клетки создаются в одной стадии но допустим, одна появилась раньше чем другая с которой она должна соединиться, то тогда та которая еще не создалась сама соедениться с  другой
            val invertedDivide = hashMapOf<Int, Int>()
            for ((key, action) in cellActions) {
                action.divide?.id?.let { divideId ->
                    invertedDivide[divideId] = key
                }
            }

            for ((key, action) in cellActions) {
                action.divide?.physicalLink?.forEach { linkKey, linkData ->
                    action.divide?.id?.let { id ->
                        invertedDivide[linkKey]?.let { divideKey ->
                            val cellAction = cellActions[divideKey]
                            cellAction?.divide?.physicalLink?.put(id, linkData)
                        }
                    }
                }
                action.mutate?.physicalLink?.forEach { linkKey, linkData ->
                    invertedDivide[linkKey]?.let { divideKey ->
                        val cellAction = cellActions[divideKey]
                        cellAction?.divide?.physicalLink?.put(key, linkData)
                    }
                }
            }
        }

        GenomeStage(
            cellActions = cellActions,
//            invertedDivide = invertedDivide
        )
    }
}

private fun ActionJsonRead.toDomain(): Action {
    return Action(
        id = id?.toIntOrNull() ?: -1,
        angle = angle,
        cellType = cellType,
        physicalLink = physicalLink.mapKeys { (k, _) -> k.toInt() }
            .mapValues { (_, v) -> v?.toDomain() } as HashMap<Int, LinkData?>,
        color = color ?: if (colorHsv != null) Color.valueOf(colorHsv) else null,
        angleDirected = angleDirected,
        funActivation = funActivation,
        a = a,
        b = b,
        c = c,
        isSum = isSum,
        colorRecognition = colorRecognition,
        lengthDirected = lengthDirected
    )
}

private fun LinkDataJsonRead.toDomain(): LinkData {
    return LinkData(
        length = length ?: 10f,
        isNeuronal = directedNeuronLink != null,
        weight = weight,
        directedNeuronLink = directedNeuronLink
    )
}
