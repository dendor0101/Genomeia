package io.github.some_example_name.old.genome

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.utils.Json
import com.badlogic.gdx.utils.JsonWriter
import io.github.some_example_name.old.genome.json.GenomeJsonReader
import io.github.some_example_name.old.genome.json.read.jsonToDomain
import io.github.some_example_name.old.world_logic.cells.currentGenomeIndex


class GenomeManager(
    val genomeJsonReader: GenomeJsonReader = GenomeJsonReader(),
    val isGenomeEditor: Boolean,
    val genomeName: String?
) {

    val genomes = mutableListOf<Genome>()
    val genomeForEditor: Genome

    init {
        val newGenome = Genome(
            name = "User genome",
            genomeStageInstruction = mutableListOf(
                GenomeStage(
                    cellActions = hashMapOf(
                        0 to CellAction(
                            divide = Action(
                                id = 1,
                                angle = 0.02165f,
                                cellType = 0,
                                physicalLink = hashMapOf(0 to LinkData(length = 30f)),
                                color = Color(0.133f, 0.545f, 0.133f, 1f)
                            )
                        )
                    )
                )
            ),
            dividedTimes = IntArray(1) { 1 },
            mutatedTimes = IntArray(1),
        )

        if (!isGenomeEditor) {
            if (genomeName == null) {
                val jsonGenomesAssets = genomeJsonReader.readAllGenomesFromAssetsFolder("genomes")
                val jsonGenomes =
                    jsonGenomesAssets + genomeJsonReader.readAllGenomesFromFolder("user_genomes")
                genomes.addAll(jsonGenomes.map { it.jsonToDomain() })
                genomeForEditor = jsonGenomes[currentGenomeIndex].jsonToDomain(true)
            } else {
                val genome = genomeJsonReader.readGenomeFromFolder("user_genomes", genomeName, false)
                println(genomeName)
                genomes.add(genome?.jsonToDomain() ?: newGenome)
                genomeForEditor = genome?.jsonToDomain(true) ?: newGenome
            }
        } else {
            if (genomeName != null) {
                val genome = genomeJsonReader.readGenomeFromFolder("user_genomes", genomeName, false)
                genomes.add(genome?.jsonToDomain() ?: newGenome)
                genomeForEditor = genome?.jsonToDomain(true) ?: newGenome
            } else {
                genomes.add(newGenome)
                genomeForEditor = newGenome
            }
        }

        println(genomes.size)
    }

}

class Genome(
    val name: String,
    val genomeStageInstruction: MutableList<GenomeStage>,
    val dividedTimes: IntArray,
    val mutatedTimes: IntArray
) {
    fun deepCopy(): Genome {
        return Genome(
            name = name,
            genomeStageInstruction = genomeStageInstruction.map { it.deepCopy() }.toMutableList(),
            dividedTimes = dividedTimes.copyOf(),
            mutatedTimes = mutatedTimes.copyOf()
        )
    }
}

class GenomeStage(
    val cellActions: HashMap<Int, CellAction> = hashMapOf()
) {
    fun deepCopy(): GenomeStage {
        return GenomeStage(
            cellActions = HashMap(cellActions.mapValues { it.value.deepCopy() })
        )
    }
}

data class CellAction(
    var divide: Action? = null,
    var mutate: Action? = null
) {
    fun deepCopy(): CellAction {
        return CellAction(
            divide = divide?.deepCopy(),
            mutate = mutate?.deepCopy()
        )
    }

    override fun toString(): String {
        val json = Json().apply { setOutputType(JsonWriter.OutputType.json) }
        return json.prettyPrint(this)
    }
}

data class Action(
    val id: Int = -1,
    var angle: Float? = null,
    var cellType: Int? = null,
    val physicalLink: HashMap<Int, LinkData?> = hashMapOf(),
    var color: Color? = null,
    val angleDirected: Float? = null,
    val funActivation: Int? = null,
    val a: Float? = null,
    val b: Float? = null,
    val c: Float? = null,
    val isSum: Boolean? = null,
    val colorRecognition: Int? = null,
    val lengthDirected: Float? = null
) {
    fun deepCopy(): Action {
        return Action(
            id = id,
            angle = angle,
            cellType = cellType,
            physicalLink = HashMap(physicalLink.mapValues { it.value?.deepCopy() }),
            color = color?.cpy(),
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

    override fun toString(): String {
        val json = Json().apply { setOutputType(JsonWriter.OutputType.json) }
        return json.prettyPrint(this)
    }
}

data class LinkData(
    val length: Float? = null,
    val isNeuronal: Boolean = false,
    val weight: Float? = null,
    val directedNeuronLink: Int? = null,
    val isExtra: Boolean = false
) {
    fun deepCopy(): LinkData {
        return LinkData(
            length = length,
            isNeuronal = isNeuronal,
            weight = weight,
            directedNeuronLink = directedNeuronLink,
            isExtra = isExtra
        )
    }

    override fun toString(): String {
        val json = Json().apply { setOutputType(JsonWriter.OutputType.json) }
        return json.prettyPrint(this)
    }
}
