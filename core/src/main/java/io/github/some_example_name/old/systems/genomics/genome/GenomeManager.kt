package io.github.some_example_name.old.systems.genomics.genome

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.core.prettyPrint
import io.github.some_example_name.old.systems.genomics.library.GenomeCatalog
import io.github.some_example_name.old.systems.simulation.SimulationData

class GenomeManager(
    val simulationData: SimulationData
) {

    var genome: Genome? = null

    val genomes = mutableListOf(getEmptyGenome())

    fun loadGenomes(genomeName: String? = null) {
        genomes.clear()
        simulationData.currentGenomeIndex = 0
        if (genomeName == null) {
            // Свои и встроенные, в порядке GenomeCatalog.all(): номер в этом списке — номер
            // генома в мире, и список выбора в симуляции показывает тот же порядок.
            GenomeCatalog.all().forEach {
                genomes.add(parseGenome(it))
            }
        } else {
            genomes.add(parseGenome(genomeName))
        }
    }

    /**
     * Добавить геном организма из сохранённого мира, вернуть его индекс.
     *
     * В конец, а не вместо загруженных из папки: индексы в genomes — это и номера в диалоге
     * выбора генома, и OrganEntity.genomeIndex. Сохранённый мир переназначает индексы
     * своих организмов на добавленные, а список из папки остаётся тем, что видит игрок.
     */
    fun addSavedGenome(genome: Genome): Int {
        genomes.add(genome.prepare())
        return genomes.size - 1
    }

    /**
     * Дочитать геном [name] из GenomeCatalog в конец списка, вернуть его индекс. В конец
     * по той же причине, что и [addSavedGenome]: индексы уже занятых геномов не двигаются.
     */
    fun addFromCatalog(name: String): Int {
        genomes.add(parseGenome(name))
        return genomes.size - 1
    }

    private fun parseGenome(name: String): Genome = GenomeCatalog.load(name).prepare()

    /** Досчитывает @Transient-поля генома: и после чтения файла, и после загрузки мира. */
    private fun Genome.prepare(): Genome = apply {
        genomeStageInstruction = addInvertedDivideLinks()
        dividedTimes = IntArray(genomeStageInstruction.size)
        mutatedTimes = IntArray(genomeStageInstruction.size)
        genomeStageInstruction.forEachIndexed { index, stage ->
            stage.cellActions.forEach { (_, action) ->
                if (action.divide != null) dividedTimes[index]++
                if (action.mutate != null) mutatedTimes[index]++
            }
        }
    }

    private fun Genome.addInvertedDivideLinks(): List<GenomeStage> {
        return genomeStageInstruction.map { stage ->
            val cellActions = stage.cellActions

            //Эта манипуляция нужна для того чтобы если две клетки создаются в одной стадии, но допустим, одна появилась раньше чем другая с которой она должна соединиться, то тогда та которая еще не создалась сама соедениться с другой
            //TODO в идеале от этого нужно изюавиться
            val invertedDivide = hashMapOf<Int, Int>()
            for ((key, action) in cellActions) {
                action.divide?.id?.let { divideId ->
                    invertedDivide[divideId] = key
                }
            }

            for ((key, action) in cellActions) {
                action.divide?.physicalLink?.forEach { linkKey, linkData ->
                    action.divide.id.let { id ->
                        invertedDivide[linkKey]?.let { divideKey ->
                            val cellAction = cellActions[divideKey]
                            cellAction?.divide?.physicalLinkMirroredForCell?.put(id, linkData)
                        }
                    }
                }
                action.mutate?.physicalLink?.forEach { linkKey, linkData ->
                    invertedDivide[linkKey]?.let { divideKey ->
                        val cellAction = cellActions[divideKey]
                        cellAction?.divide?.physicalLinkMirroredForCell?.put(key, linkData)
                    }
                }
            }

            GenomeStage(
                cellActions = cellActions
            )
        }
    }

}


fun getEmptyGenome() = Genome(
    name = "User genome",
    version = 24,
    stageInstruction = mutableListOf(
        GenomeStage(
            cellActions = hashMapOf(
                0 to CellAction(
                    divide = Action(
                        id = 1,
                        angle = 0f,
                        cellType = "leaf",
                        physicalLink = hashMapOf(0 to LinkData(length = 0.6f)),
                        color = Color(0.133f, 0.545f, 0.133f, 1f)
                    )
                )
            )
        )
    ),
    subGenomes = hashMapOf()
).apply {
    dividedTimes = IntArray(1) { 1 }
    mutatedTimes = IntArray(1)
}
